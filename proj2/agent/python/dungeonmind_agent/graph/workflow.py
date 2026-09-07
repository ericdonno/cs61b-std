"""Event-driven bounded LangGraph workflow for one strategic decision."""

from __future__ import annotations

import threading
import time
from dataclasses import dataclass
from typing import Any

from langgraph.graph import END, START, StateGraph

from ..config import RuntimeConfig
from ..model.adapter import ModelAdapter, ModelCallError, ModelInput
from ..model.scheduler import InferenceScheduler, ModelCallRequest
from ..observability import TraceSink
from .inbox import InboxSnapshot
from .state import AgentGraphState, AgentKey, ActivePlan, PlanStep, intent_for_plan
from .tools import EVIDENCE_TOOLS, ToolRuntime


@dataclass(frozen=True)
class WorkflowDecision:
    intent: dict[str, Any] | None
    feedback_ids: tuple[str, ...]
    event_ids: tuple[str, ...]


class AgentWorkflow:
    """Owns the compiled graph while the checkpointer owns stable state."""

    _EVENT_TRIGGERS = {
        "PLAYER_SPOTTED": "TARGET_SPOTTED",
        "SOUND_HEARD": "SOUND_HEARD",
        "MESSAGE_RECEIVED": "MESSAGE_RECEIVED",
        "STEP_FAILED": "STEP_FAILED",
        "PLAN_COMPLETED": "PLAN_COMPLETED",
        "PLAN_CANCELLED": "PLAN_CANCELLED",
    }

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
        builder.add_conditional_edges(
            "prepare_context", self._after_prepare,
            {"model": "invoke_model", "finish": "finalize_intent"},
        )
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

    @property
    def config(self) -> RuntimeConfig:
        return self._config

    def decide(self, envelope: dict[str, Any], cancellation: threading.Event,
               inputs: InboxSnapshot | None = None) -> WorkflowDecision:
        inputs = inputs or InboxSnapshot()
        observation = envelope["data"]
        key = self._key(envelope)
        state: AgentGraphState = {
            "agent_key": key.model_dump(),
            "run_id": envelope["runId"],
            "session_epoch": envelope["sessionEpoch"],
            "decision_id": observation["decisionId"],
            "observation_seq": observation["observationSeq"],
            "request_generation": observation["requestGeneration"],
            "observation": observation,
            "input_feedback": list(inputs.feedback),
            "input_events": list(inputs.events),
            "deadline_ns": time.monotonic_ns()
                + int(self._config.decision_timeout_seconds * 1e9),
        }
        with self._token_lock:
            self._tokens[observation["decisionId"]] = cancellation
        try:
            result = self.graph.invoke(
                state, {"configurable": {"thread_id": key.thread_id()}},
            )
            return WorkflowDecision(result.get("candidate_intent"),
                                    inputs.feedback_ids, inputs.event_ids)
        finally:
            with self._token_lock:
                self._tokens.pop(observation["decisionId"], None)

    def commit_consumption(self, envelope: dict[str, Any],
                           decision: WorkflowDecision) -> None:
        key = self._key(envelope)
        config = {"configurable": {"thread_id": key.thread_id()}}
        values = self.graph.get_state(config).values
        feedback = self._bounded_ids(
            values.get("consumed_feedback_ids", []), decision.feedback_ids)
        events = self._bounded_ids(
            values.get("consumed_event_ids", []), decision.event_ids)
        self.graph.update_state(config, {
            "consumed_feedback_ids": feedback,
            "consumed_event_ids": events,
        })

    def _prepare_context(self, state: AgentGraphState) -> dict[str, Any]:
        run_changed = state.get("last_run_id") != state["run_id"]
        plan = None if run_changed else self._plan(state.get("active_plan"))
        feedback = [] if run_changed else list(state.get("feedback_window", []))
        events = [] if run_changed else list(state.get("event_window", []))
        consumed_feedback = [] if run_changed else list(
            state.get("consumed_feedback_ids", []))
        consumed_events = [] if run_changed else list(
            state.get("consumed_event_ids", []))
        new_feedback = [item for item in state.get("input_feedback", [])
                        if item["feedbackId"] not in consumed_feedback]
        new_events = [item for item in state.get("input_events", [])
                      if item["eventId"] not in consumed_events]
        feedback = (feedback + new_feedback)[-self._config.max_feedback_window:]
        events = (events + new_events)[-self._config.max_event_window:]
        triggers: list[str] = ["COLD_START"] if run_changed else []

        if plan is not None:
            plan, feedback_trigger = self._reduce_feedback(plan, new_feedback)
            if feedback_trigger is not None:
                triggers.append(feedback_trigger)
            plan = self._reduce_reflex(plan, new_events)
        for event in new_events:
            trigger = self._EVENT_TRIGGERS.get(event["eventType"])
            if event["eventType"] == "REFLEX_OVERRIDE_ENDED" \
                    and event.get("reasonCode") == "LEASE_INVALIDATED":
                trigger = "PRECONDITION_CHANGED"
                if plan is not None:
                    plan = plan.model_copy(update={
                        "status": "REPLAN_REQUIRED",
                        "replan_reason": trigger,
                    })
            if trigger is not None and trigger not in triggers:
                triggers.append(trigger)

        candidate = None
        route = "model"
        if triggers:
            route = "model"
        elif plan is None:
            triggers = ["COLD_START"]
        elif plan.status == "ACTIVE":
            candidate = intent_for_plan(plan)
            route = "finish"
        else:
            route = "finish"

        return {
            "last_run_id": state["run_id"],
            "active_plan": None if plan is None else plan.model_dump(
                by_alias=True, mode="json"),
            "feedback_window": feedback,
            "event_window": events,
            "consumed_feedback_ids": consumed_feedback,
            "consumed_event_ids": consumed_events,
            "replan_triggers": triggers,
            "route_decision": route,
            "decision_count": state.get("decision_count", 0) + 1,
            "messages": [], "tool_batches": 0, "tool_calls": 0,
            "model_calls": 0, "evidence_tool_used": False,
            "pending_tool_calls": [], "candidate_intent": candidate,
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
            active_plan=state.get("active_plan"),
            feedback=tuple(state.get("input_feedback", [])),
            events=tuple(state.get("input_events", [])),
            replan_triggers=tuple(state.get("replan_triggers", [])),
        )
        request = ModelCallRequest(
            encounter_key=(state["run_id"], state["agent_key"]["floor_id"]),
            decision_id=state["decision_id"], deadline_ns=state["deadline_ns"],
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
        self._record("MODEL_CALL_COMPLETED", state, "invoke_model",
                     modelCallIndex=call_index,
                     inputTokens=None if usage is None else usage.input_tokens,
                     outputTokens=None if usage is None else usage.output_tokens,
                     usageEstimated=None if usage is None else usage.estimated,
                     resultCode="OK")
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
            "active_plan": working.get("active_plan"),
            "plan_sequence": working.get("plan_sequence", 0),
            "messages": (state.get("messages", []) + results)[-16:],
            "pending_tool_calls": [],
        }

    @staticmethod
    def _reduce_feedback(plan: ActivePlan,
                         feedback: list[dict[str, Any]]) -> tuple[ActivePlan, str | None]:
        matching = [item for item in feedback
                    if item.get("planId") == plan.plan_id
                    and item.get("stepId") == plan.current_step().step_id]
        if not matching:
            return plan, None
        item = matching[-1]
        status = item["stepStatus"]
        steps = list(plan.steps)
        index = plan.current_step_index
        if status == "SUCCEEDED":
            steps[index] = steps[index].model_copy(update={"status": "SUCCEEDED"})
            if index + 1 < len(steps):
                steps[index + 1] = steps[index + 1].model_copy(
                    update={"status": "ACTIVE"})
                return plan.model_copy(update={
                    "steps": tuple(steps), "current_step_index": index + 1,
                    "status": "ACTIVE", "replan_reason": None,
                }), None
            return plan.model_copy(update={
                "steps": tuple(steps), "status": "COMPLETED",
                "replan_reason": "PLAN_COMPLETED",
            }), "PLAN_COMPLETED"
        if status in {"FAILED", "CANCELLED"} or item["planStatus"] in {
            "REPLAN_REQUIRED", "CANCELLED"
        }:
            steps[index] = steps[index].model_copy(update={"status": status})
            reason = item["reasonCode"]
            return plan.model_copy(update={
                "steps": tuple(steps), "status": "REPLAN_REQUIRED",
                "replan_reason": reason,
            }), "STEP_FAILED"
        if status == "PAUSED":
            steps[index] = steps[index].model_copy(update={"status": "PAUSED"})
            return plan.model_copy(update={
                "steps": tuple(steps), "status": "PAUSED",
            }), None
        return plan, None

    @staticmethod
    def _reduce_reflex(plan: ActivePlan,
                       events: list[dict[str, Any]]) -> ActivePlan:
        types = {item["eventType"] for item in events}
        if "REFLEX_OVERRIDE_STARTED" in types:
            return plan.model_copy(update={"status": "PAUSED"})
        if "REFLEX_OVERRIDE_ENDED" in types and plan.status == "PAUSED":
            steps = list(plan.steps)
            index = plan.current_step_index
            steps[index] = steps[index].model_copy(update={"status": "ACTIVE"})
            return plan.model_copy(update={"status": "ACTIVE",
                                           "steps": tuple(steps)})
        return plan

    @staticmethod
    def _after_prepare(state: AgentGraphState) -> str:
        return "model" if state.get("route_decision") == "model" else "finish"

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

    def _bounded_ids(self, existing: list[str], additions: tuple[str, ...]) -> list[str]:
        result = list(existing)
        for item in additions:
            if item not in result:
                result.append(item)
        return result[-self._config.max_consumed_ids:]

    @staticmethod
    def _plan(value: dict[str, Any] | None) -> ActivePlan | None:
        return None if value is None else ActivePlan.model_validate(value)

    @staticmethod
    def _key(envelope: dict[str, Any]) -> AgentKey:
        return AgentKey(world_id=envelope["worldId"],
                        floor_id=envelope["floorId"],
                        agent_id=envelope["agentId"])

    def _token(self, decision_id: str) -> threading.Event:
        with self._token_lock:
            return self._tokens[decision_id]

    def _record(self, event_type: str, state: AgentGraphState,
                node: str, **fields: Any) -> None:
        plan = state.get("active_plan") or {}
        self._trace.record(
            event_type,
            worldId=state["agent_key"]["world_id"], runId=state["run_id"],
            floorId=state["agent_key"]["floor_id"],
            agentId=state["agent_key"]["agent_id"],
            sessionEpoch=state["session_epoch"],
            observationSeq=state["observation_seq"],
            decisionId=state["decision_id"],
            requestGeneration=state["request_generation"],
            graphNode=node, planId=plan.get("planId"),
            planRevision=plan.get("revision"),
            replanTrigger=(state.get("replan_triggers") or [None])[0],
            consumedInputCount=(len(state.get("input_feedback", []))
                                + len(state.get("input_events", []))),
            **fields,
        )
