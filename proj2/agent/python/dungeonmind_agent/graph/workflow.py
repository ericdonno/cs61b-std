"""Explicit bounded LangGraph workflow for one strategic decision."""

from __future__ import annotations

import threading
import time
from typing import Any

from langgraph.graph import END, START, StateGraph

from ..config import RuntimeConfig
from ..model.adapter import ModelAdapter, ModelCallError, ModelInput
from ..model.scheduler import InferenceScheduler, ModelCallRequest
from ..observability import TraceSink
from .state import AgentGraphState, AgentKey
from .tools import EVIDENCE_TOOLS, ToolRuntime


class AgentWorkflow:
    """Owns the compiled graph while the checkpointer owns stable state."""

    def __init__(self, adapter: ModelAdapter, scheduler: InferenceScheduler,
                 checkpointer: Any, config: RuntimeConfig,
                 trace: TraceSink | None = None) -> None:
        self._adapter = adapter
        self._scheduler = scheduler
        self._config = config
        self._trace = trace or TraceSink()
        self._tools = ToolRuntime()
        self._tokens: dict[str, threading.Event] = {}
        self._token_lock = threading.Lock()

        builder = StateGraph(AgentGraphState)
        builder.add_node("prepare_context", self._prepare_context)
        builder.add_node("invoke_model", self._invoke_model)
        builder.add_node("execute_tools", self._execute_tools)
        builder.add_node("finalize_intent", self._finalize_intent)
        builder.add_node("reject_decision", self._reject_decision)
        builder.add_edge(START, "prepare_context")
        builder.add_edge("prepare_context", "invoke_model")
        builder.add_conditional_edges(
            "invoke_model", self._after_model,
            {"tools": "execute_tools", "reject": "reject_decision"},
        )
        builder.add_conditional_edges(
            "execute_tools", self._after_tools,
            {"model": "invoke_model", "finalize": "finalize_intent",
             "reject": "reject_decision"},
        )
        builder.add_edge("finalize_intent", END)
        builder.add_edge("reject_decision", END)
        self.graph = builder.compile(checkpointer=checkpointer)

    def decide(self, envelope: dict[str, Any],
               cancellation: threading.Event) -> dict[str, Any] | None:
        observation = envelope["data"]
        key = AgentKey(world_id=envelope["worldId"],
                       floor_id=envelope["floorId"],
                       agent_id=envelope["agentId"])
        state: AgentGraphState = {
            "agent_key": key.model_dump(),
            "run_id": envelope["runId"],
            "session_epoch": envelope["sessionEpoch"],
            "decision_id": observation["decisionId"],
            "observation_seq": observation["observationSeq"],
            "request_generation": observation["requestGeneration"],
            "observation": observation,
            "deadline_ns": time.monotonic_ns()
                + int(self._config.decision_timeout_seconds * 1e9),
        }
        with self._token_lock:
            self._tokens[observation["decisionId"]] = cancellation
        try:
            result = self.graph.invoke(
                state, {"configurable": {"thread_id": key.thread_id()}},
            )
            return result.get("candidate_intent")
        finally:
            with self._token_lock:
                self._tokens.pop(observation["decisionId"], None)

    def record_feedback(self, envelope: dict[str, Any]) -> None:
        key = AgentKey(world_id=envelope["worldId"],
                       floor_id=envelope["floorId"],
                       agent_id=envelope["agentId"])
        self.graph.update_state(
            {"configurable": {"thread_id": key.thread_id()}},
            {"recent_feedback": envelope["data"]},
        )

    def _prepare_context(self, state: AgentGraphState) -> dict[str, Any]:
        return {
            "decision_count": state.get("decision_count", 0) + 1,
            "messages": [], "tool_batches": 0, "tool_calls": 0,
            "model_calls": 0, "evidence_tool_used": False,
            "pending_tool_calls": [], "candidate_intent": None,
            "rejection_code": None,
        }

    def _invoke_model(self, state: AgentGraphState) -> dict[str, Any]:
        token = self._token(state["decision_id"])
        if token.is_set():
            return {"rejection_code": "CANCELLED", "pending_tool_calls": []}
        if time.monotonic_ns() >= state["deadline_ns"]:
            return {"rejection_code": "DECISION_DEADLINE", "pending_tool_calls": []}
        call_index = state.get("model_calls", 0)
        if call_index >= self._config.max_model_calls_per_decision:
            return {"rejection_code": "MODEL_CALL_LIMIT", "pending_tool_calls": []}
        model_input = ModelInput(
            call_index=call_index,
            observation=state["observation"],
            messages=tuple(state.get("messages", [])),
        )
        request = ModelCallRequest(
            encounter_key=(state["run_id"], state["agent_key"]["floor_id"]),
            decision_id=state["decision_id"],
            deadline_ns=state["deadline_ns"],
            estimated_input_tokens=64,
            max_output_tokens=self._config.max_output_tokens,
            cancellation=token,
            invoke=lambda: self._adapter.invoke(model_input),
        )
        self._record("MODEL_CALL_STARTED", state, "invoke_model",
                     modelCallIndex=call_index)
        try:
            response = self._scheduler.call(request)
        except ModelCallError as exception:
            self._record("MODEL_CALL_FAILED", state, "invoke_model",
                         modelCallIndex=call_index,
                         resultCode=exception.code.value)
            return {"model_calls": call_index + 1,
                    "rejection_code": str(exception),
                    "pending_tool_calls": []}
        usage = response.usage
        self._record(
            "MODEL_CALL_COMPLETED", state, "invoke_model",
            modelCallIndex=call_index,
            inputTokens=None if usage is None else usage.input_tokens,
            outputTokens=None if usage is None else usage.output_tokens,
            usageEstimated=None if usage is None else usage.estimated,
            resultCode="OK",
        )
        return {
            "model_calls": call_index + 1,
            "pending_tool_calls": [
                {"call_id": call.call_id, "name": call.name,
                 "arguments": call.arguments}
                for call in response.tool_calls
            ],
        }

    def _execute_tools(self, state: AgentGraphState) -> dict[str, Any]:
        calls = state.get("pending_tool_calls", [])
        batch = state.get("tool_batches", 0)
        total = state.get("tool_calls", 0)
        if batch >= self._config.max_tool_batches:
            return {"rejection_code": "TOOL_BATCH_LIMIT"}
        if total + len(calls) > self._config.max_tool_calls:
            return {"rejection_code": "TOOL_CALL_LIMIT"}
        working = dict(state)
        results = []
        evidence = state.get("evidence_tool_used", False)
        for call in calls:
            if call["name"] in EVIDENCE_TOOLS:
                evidence = True
                working["evidence_tool_used"] = True
            result = self._tools.execute(call["name"], call["arguments"], working)
            self._record("TOOL_CALL_COMPLETED", state, "execute_tools",
                         toolCallId=call["call_id"], toolName=call["name"],
                         resultCode=result["code"])
            results.append({"tool": call["name"], "result": result})
        return {
            "tool_batches": batch + 1,
            "tool_calls": total + len(calls),
            "evidence_tool_used": evidence,
            "candidate_intent": working.get("candidate_intent"),
            "messages": (state.get("messages", []) + results)[-16:],
            "pending_tool_calls": [],
        }

    @staticmethod
    def _after_model(state: AgentGraphState) -> str:
        return "tools" if state.get("pending_tool_calls") else "reject"

    @staticmethod
    def _after_tools(state: AgentGraphState) -> str:
        if state.get("rejection_code"):
            return "reject"
        return "finalize" if state.get("candidate_intent") else "model"

    @staticmethod
    def _finalize_intent(state: AgentGraphState) -> dict[str, Any]:
        return {"previous_intent": state.get("candidate_intent")}

    @staticmethod
    def _reject_decision(state: AgentGraphState) -> dict[str, Any]:
        return {"candidate_intent": None,
                "rejection_code": state.get("rejection_code") or "NO_PROPOSAL"}

    def _token(self, decision_id: str) -> threading.Event:
        with self._token_lock:
            return self._tokens[decision_id]

    def _record(self, event_type: str, state: AgentGraphState,
                node: str, **fields: Any) -> None:
        self._trace.record(
            event_type,
            worldId=state["agent_key"]["world_id"], runId=state["run_id"],
            floorId=state["agent_key"]["floor_id"],
            agentId=state["agent_key"]["agent_id"],
            sessionEpoch=state["session_epoch"],
            observationSeq=state["observation_seq"],
            decisionId=state["decision_id"],
            requestGeneration=state["request_generation"],
            graphNode=node, **fields,
        )
