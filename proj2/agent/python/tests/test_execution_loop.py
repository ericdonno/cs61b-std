"""Execution inbox, bounded plans, local advance, and run reset tests."""

from __future__ import annotations

import threading
import unittest

from dungeonmind_agent.checkpoint import CheckpointManager
from dungeonmind_agent.config import RuntimeConfig
from dungeonmind_agent.graph.inbox import ExecutionInbox, InboxSnapshot
from dungeonmind_agent.graph.tools import ToolRuntime
from dungeonmind_agent.graph.workflow import AgentWorkflow
from dungeonmind_agent.model.adapter import ScriptedModelAdapter
from dungeonmind_agent.model.scheduler import InferenceScheduler
from smoke_test import build_observation


class CountingAdapter(ScriptedModelAdapter):
    def __init__(self) -> None:
        self.calls = 0

    def invoke(self, request):
        self.calls += 1
        return super().invoke(request)


class ExecutionLoopTest(unittest.TestCase):
    def setUp(self) -> None:
        self.manager = CheckpointManager()
        self.scheduler = InferenceScheduler()
        self.adapter = CountingAdapter()
        self.workflow = AgentWorkflow(
            self.adapter, self.scheduler, self.manager.saver,
            RuntimeConfig(brain="scripted"),
        )

    def tearDown(self) -> None:
        self.scheduler.close()
        self.manager.close()

    def test_step_success_advances_without_model_call(self) -> None:
        first_request = build_observation(
            decision_id="decision-1", player_position=None)
        first = self.workflow.decide(first_request, threading.Event())
        first_calls = self.adapter.calls
        metadata = first.intent["planMetadata"]
        self.assertEqual("PATROL", first.intent["skill"])

        feedback = self._feedback(metadata, "feedback-1", "SUCCEEDED",
                                  "ACTIVE", "COMMITMENT_COMPLETED")
        second_request = build_observation(
            decision_id="decision-2", player_position=None)
        second = self.workflow.decide(
            second_request, threading.Event(),
            InboxSnapshot(feedback=(feedback,)),
        )

        self.assertEqual("GUARD", second.intent["skill"])
        self.assertEqual(metadata["planId"],
                         second.intent["planMetadata"]["planId"])
        self.assertEqual(first_calls, self.adapter.calls)

    def test_new_run_discards_active_execution(self) -> None:
        first_request = build_observation(
            decision_id="decision-1", player_position=None)
        first = self.workflow.decide(first_request, threading.Event())
        calls = self.adapter.calls
        next_run = build_observation(
            decision_id="decision-2", player_position=None)
        next_run["runId"] = "new-run"

        second = self.workflow.decide(next_run, threading.Event())

        self.assertGreater(self.adapter.calls, calls)
        self.assertNotEqual(first.intent["planMetadata"]["planId"],
                            second.intent["planMetadata"]["planId"])

    def test_inbox_deduplicates_and_resets_at_run_boundary(self) -> None:
        inbox = ExecutionInbox(feedback_limit=2, event_limit=2,
                               consumed_limit=2)
        event = self._event("run-a", "event-1")
        inbox.stage(event)
        inbox.stage(event)
        snapshot = inbox.snapshot("run-a")
        self.assertEqual(("event-1",), snapshot.event_ids)
        inbox.commit(snapshot)
        inbox.stage(event)
        self.assertEqual((), inbox.snapshot("run-a").event_ids)
        self.assertEqual((), inbox.snapshot("run-b").event_ids)

    def test_plan_tool_rejects_out_of_bounds_and_unknown_skill(self) -> None:
        state = {
            "observation": build_observation()["data"],
            "decision_id": "decision-plan",
            "evidence_tool_used": True,
        }
        tool = ToolRuntime()
        self.assertEqual("INVALID_ARGUMENTS", tool.execute(
            "submit_plan", {"steps": []}, state)["code"])
        step = self._step("PATROL")
        self.assertEqual("INVALID_ARGUMENTS", tool.execute(
            "submit_plan", {"steps": [step] * 7}, state)["code"])
        self.assertEqual("UNSUPPORTED_SKILL", tool.execute(
            "submit_plan", {"steps": [self._step("FLANK")]}, state)["code"])

    @staticmethod
    def _step(skill: str) -> dict:
        return {
            "skill": skill,
            "parameters": {"targetPosition": {"x": 1, "y": 2}},
            "confidence": 0.9,
            "validForTicks": 12,
            "interruptPolicy": {
                "engageVisiblePlayer": True,
                "respondToAdjacentThreat": True,
                "allowLocalReroute": True,
            },
        }

    @staticmethod
    def _feedback(metadata: dict, feedback_id: str, step_status: str,
                  plan_status: str, reason: str) -> dict:
        return {
            "feedbackVersion": "action-feedback.v2",
            "feedbackId": feedback_id,
            "decisionId": "decision-1",
            "planId": metadata["planId"],
            "stepId": metadata["stepId"],
            "planRevision": metadata["revision"],
            "actionIndex": 1,
            "actionType": "MoveAction",
            "result": "SUCCESS",
            "reasonCode": reason,
            "stepStatus": step_status,
            "planStatus": plan_status,
            "beforePosition": {"x": 2, "y": 2},
            "afterPosition": {"x": 1, "y": 2},
            "selfHp": 20,
            "decisionSource": "REMOTE_AGENT",
            "overrideReason": None,
        }

    @staticmethod
    def _event(run_id: str, event_id: str) -> dict:
        envelope = build_observation()
        envelope["runId"] = run_id
        envelope["type"] = "world_event"
        envelope["data"] = {
            "eventVersion": "agent-event.v1",
            "eventId": event_id,
            "eventType": "PLAYER_SPOTTED",
            "logicalTick": 42,
            "relatedPosition": {"x": 3, "y": 2},
            "relatedEntityId": "player",
            "decisionId": None,
            "planId": None,
            "stepId": None,
            "reasonCode": "NONE",
        }
        return envelope


if __name__ == "__main__":
    unittest.main()
