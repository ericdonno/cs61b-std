"""Observation-only tools and the terminal intent submission tool."""

from __future__ import annotations

from typing import Any

from pydantic import ValidationError

from .state import (
    ActivePlan, PlanStep, PlanStepSubmission, intent_for_plan,
)


EVIDENCE_TOOLS = frozenset({
    "list_visible_entities", "inspect_visible_tile", "read_self",
    "check_skill_candidate",
})


class ToolRuntime:
    def execute(self, name: str, arguments: dict[str, Any],
                state: dict[str, Any]) -> dict[str, Any]:
        handlers = {
            "list_visible_entities": self._list_visible_entities,
            "inspect_visible_tile": self._inspect_visible_tile,
            "read_self": self._read_self,
            "check_skill_candidate": self._check_skill_candidate,
            "submit_strategic_intent": self._submit_strategic_intent,
            "submit_plan": self._submit_plan,
        }
        handler = handlers.get(name)
        if handler is None:
            return {"code": "UNKNOWN_TOOL"}
        try:
            return handler(arguments, state)
        except (KeyError, TypeError, ValueError):
            return {"code": "INVALID_ARGUMENTS"}

    @staticmethod
    def _list_visible_entities(arguments: dict[str, Any],
                               state: dict[str, Any]) -> dict[str, Any]:
        entity_type = arguments.get("type")
        entities = state["observation"]["visibleEntities"]
        if entity_type is not None:
            entities = [item for item in entities if item["type"] == entity_type]
        return {"code": "OK", "entities": entities}

    @staticmethod
    def _inspect_visible_tile(arguments: dict[str, Any],
                              state: dict[str, Any]) -> dict[str, Any]:
        x, y = arguments["x"], arguments["y"]
        if isinstance(x, bool) or isinstance(y, bool):
            return {"code": "INVALID_ARGUMENTS"}
        for tile in state["observation"]["visibleTiles"]:
            if tile["x"] == x and tile["y"] == y:
                return {"code": "OK", "tile": tile}
        return {"code": "UNKNOWN_TO_AGENT"}

    @staticmethod
    def _read_self(arguments: dict[str, Any],
                   state: dict[str, Any]) -> dict[str, Any]:
        if arguments:
            return {"code": "INVALID_ARGUMENTS"}
        observation = state["observation"]
        return {"code": "OK", "self": observation["self"],
                "visionMode": observation["visionMode"]}

    @staticmethod
    def _check_skill_candidate(arguments: dict[str, Any],
                               state: dict[str, Any]) -> dict[str, Any]:
        skill = arguments.get("skill")
        supported = state["observation"]["capabilities"]["supportedSkills"]
        if skill not in supported:
            return {"code": "UNSUPPORTED_SKILL"}
        return {"code": "OK", "authority": "JAVA_VALIDATES_AGAIN"}

    @staticmethod
    def _submit_strategic_intent(arguments: dict[str, Any],
                                 state: dict[str, Any]) -> dict[str, Any]:
        return ToolRuntime._create_plan([arguments], state)

    @staticmethod
    def _submit_plan(arguments: dict[str, Any],
                     state: dict[str, Any]) -> dict[str, Any]:
        if set(arguments) != {"steps"} or not isinstance(arguments["steps"], list):
            return {"code": "INVALID_ARGUMENTS"}
        return ToolRuntime._create_plan(arguments["steps"], state)

    @staticmethod
    def _create_plan(raw_steps: list[dict[str, Any]],
                     state: dict[str, Any]) -> dict[str, Any]:
        if not state.get("evidence_tool_used", False):
            return {"code": "EVIDENCE_REQUIRED"}
        if not 1 <= len(raw_steps) <= 6:
            return {"code": "INVALID_ARGUMENTS"}
        try:
            submissions = [PlanStepSubmission.model_validate(item)
                           for item in raw_steps]
        except (ValidationError, TypeError):
            return {"code": "INVALID_ARGUMENTS"}
        supported = state["observation"]["capabilities"]["supportedSkills"]
        if any(step.skill not in supported for step in submissions):
            return {"code": "UNSUPPORTED_SKILL"}
        if any(not ToolRuntime._valid_parameters(step)
               for step in submissions):
            return {"code": "INVALID_ARGUMENTS"}
        sequence = state.get("plan_sequence", 0) + 1
        previous = state.get("active_plan")
        previous_revision = -1 if previous is None else previous["revision"]
        revision = previous_revision + 1
        if revision > 32:
            return {"code": "PLAN_REVISION_LIMIT"}
        plan_id = f"{state['decision_id']}:plan:{sequence}"
        steps = tuple(PlanStep(
            **submission.model_dump(by_alias=True),
            stepId=f"step-{index}",
            status="ACTIVE" if index == 0 else "PENDING",
        ) for index, submission in enumerate(submissions))
        plan = ActivePlan(planId=plan_id, revision=revision, steps=steps,
                          currentStepIndex=0, status="ACTIVE")
        state["active_plan"] = plan.model_dump(by_alias=True, mode="json")
        state["plan_sequence"] = sequence
        state["candidate_intent"] = intent_for_plan(plan)
        return {"code": "OK"}

    @staticmethod
    def _valid_parameters(step: PlanStepSubmission) -> bool:
        parameters = step.parameters
        if step.skill == "PATROL" and not parameters:
            return True
        if set(parameters) != {"targetPosition"}:
            return False
        target = parameters["targetPosition"]
        if not isinstance(target, dict) or set(target) != {"x", "y"}:
            return False
        return all(isinstance(target[key], int)
                   and not isinstance(target[key], bool)
                   for key in ("x", "y"))
