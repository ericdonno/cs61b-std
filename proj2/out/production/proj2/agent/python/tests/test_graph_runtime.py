"""Bounded graph, checkpoint, scheduler, cancellation and trace tests."""

from __future__ import annotations

import io
import json
import tempfile
import threading
import time
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Any

from dungeonmind_agent.brain.base import EmitResult
from dungeonmind_agent.brain.graph_agent import GraphAgentBrain
from dungeonmind_agent.checkpoint import CheckpointManager
from dungeonmind_agent.config import RuntimeConfig
from dungeonmind_agent.graph.state import AgentKey
from dungeonmind_agent.graph.tools import ToolRuntime
from dungeonmind_agent.graph.workflow import AgentWorkflow
from dungeonmind_agent.model.adapter import (
    ModelResponse, ModelUsage, ScriptedModelAdapter,
)
from dungeonmind_agent.model.scheduler import (
    InferenceScheduler, ModelCallRequest,
)
from dungeonmind_agent.observability import TraceSink
from smoke_test import build_observation


class GraphRuntimeTest(unittest.TestCase):
    def test_model_mode_requires_builder_configuration_before_bind(self) -> None:
        with self.assertRaisesRegex(ValueError, "not configured"):
            RuntimeConfig(brain="model")

    def test_real_graph_uses_tools_and_isolates_checkpoint_keys(self) -> None:
        manager = CheckpointManager()
        scheduler = InferenceScheduler()
        workflow = AgentWorkflow(
            ScriptedModelAdapter(), scheduler, manager.saver,
            RuntimeConfig(brain="scripted"),
        )
        first = build_observation(agent_id="guard/a", decision_id="d-1")
        second = build_observation(agent_id="guard:a", decision_id="d-2")

        first_intent = workflow.decide(first, threading.Event())
        second_intent = workflow.decide(second, threading.Event())

        self.assertEqual("ATTACK", first_intent["skill"])
        self.assertEqual("strategic-intent.v2", first_intent["intentVersion"])
        self.assertNotEqual(
            AgentKey(world_id="world-test", floor_id=1,
                     agent_id="guard/a").thread_id(),
            AgentKey(world_id="world-test", floor_id=1,
                     agent_id="guard:a").thread_id(),
        )
        self.assertNotEqual(
            first_intent["planMetadata"]["planId"],
            second_intent["planMetadata"]["planId"],
        )
        scheduler.close()
        manager.close()

    def test_sqlite_checkpoint_survives_reopen(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = str(Path(directory) / "checkpoint.sqlite")
            observation = build_observation(decision_id="persisted")
            key = AgentKey(world_id="world-test", floor_id=1,
                           agent_id="guard-a")
            manager = CheckpointManager(path)
            scheduler = InferenceScheduler()
            workflow = AgentWorkflow(
                ScriptedModelAdapter(), scheduler, manager.saver,
                RuntimeConfig(brain="scripted"),
            )
            workflow.decide(observation, threading.Event())
            manager.close()
            scheduler.close()

            reopened = CheckpointManager(path)
            snapshot = reopened.saver.get_tuple(
                {"configurable": {"thread_id": key.thread_id()}}
            )
            self.assertIsNotNone(snapshot)
            self.assertEqual(
                "persisted", snapshot.checkpoint["channel_values"]["decision_id"]
            )
            reopened.close()

    def test_tools_cannot_inspect_hidden_tiles(self) -> None:
        state = {"observation": build_observation()["data"]}
        result = ToolRuntime().execute(
            "inspect_visible_tile", {"x": 99, "y": 99}, state
        )
        self.assertEqual("UNKNOWN_TO_AGENT", result["code"])

    def test_scheduler_bounds_concurrency_and_cancels_queued_call(self) -> None:
        scheduler = InferenceScheduler(max_concurrent=1, max_queued=3)
        entered = threading.Event()
        release = threading.Event()

        def blocking_call() -> ModelResponse:
            entered.set()
            release.wait(timeout=2)
            return ModelResponse((), ModelUsage(1, 1))

        def request(decision: str, invoke: Any,
                    cancellation: threading.Event) -> ModelCallRequest:
            return ModelCallRequest(
                ("run", 1), decision, time.monotonic_ns() + 2_000_000_000,
                1, 1, cancellation, invoke,
            )

        queued_started = threading.Event()
        queued_cancel = threading.Event()
        with ThreadPoolExecutor(max_workers=2) as executor:
            first = executor.submit(
                scheduler.call,
                request("first", blocking_call, threading.Event()),
            )
            self.assertTrue(entered.wait(timeout=1))
            second = executor.submit(
                scheduler.call,
                request("second", lambda: queued_started.set(), queued_cancel),
            )
            queued_cancel.set()
            release.set()
            first.result(timeout=2)
            with self.assertRaises(Exception):
                second.result(timeout=2)

        self.assertFalse(queued_started.is_set())
        self.assertEqual(1, scheduler.peak_active)
        scheduler.close()

    def test_cancel_ack_suppresses_late_intent(self) -> None:
        gate = threading.Event()

        class Workflow:
            def decide(self, envelope: dict[str, Any],
                       cancellation: threading.Event) -> dict[str, Any]:
                gate.wait(timeout=2)
                return {"intentVersion": "strategic-intent.v2"}

            def record_feedback(self, envelope: dict[str, Any]) -> None:
                pass

        class Emitter:
            def __init__(self) -> None:
                self.types: list[str] = []

            def emit_response(self, request: dict[str, Any],
                              message_type: str,
                              data: dict[str, Any]) -> EmitResult:
                self.types.append(message_type)
                return EmitResult.EMITTED

            def close(self) -> None:
                pass

        emitter = Emitter()
        with ThreadPoolExecutor(max_workers=1) as executor:
            brain = GraphAgentBrain(Workflow(), emitter, executor)
            observation = build_observation(decision_id="cancel-me")
            brain.on_message(observation)
            cancel = dict(observation)
            cancel["type"] = "cancel_request"
            cancel["data"] = {
                "decisionId": "cancel-me", "requestGeneration": 2,
                "reason": "HARD_TIMEOUT",
            }
            brain.on_message(cancel)
            gate.set()
            brain.close()

        self.assertEqual(["cancel_ack"], emitter.types)

    def test_trace_is_whitelisted_and_sequenced(self) -> None:
        stream = io.StringIO()
        sink = TraceSink(stream=stream)
        sink.record("MODEL_CALL_COMPLETED", decisionId="d-1",
                    inputTokens=3, rawPrompt="secret")
        sink.record("TOOL_CALL_COMPLETED", decisionId="d-1")
        events = [json.loads(line) for line in stream.getvalue().splitlines()]
        self.assertEqual([0, 1], [event["eventSeq"] for event in events])
        self.assertNotIn("rawPrompt", events[0])


if __name__ == "__main__":
    unittest.main()
