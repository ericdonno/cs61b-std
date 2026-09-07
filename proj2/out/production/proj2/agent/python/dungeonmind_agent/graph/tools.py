"""Observation-only tools and the terminal intent submission tool."""

from __future__ import annotations

from typing import Any

from pydantic import ValidationError

from .state import StrategicIntentModel


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
        if not state.get("evidence_tool_used", False):
            return {"code": "EVIDENCE_REQUIRED"}
        skill = arguments.get("skill")
        if skill not in state["observation"]["capabilities"]["supportedSkills"]:
            return {"code": "UNSUPPORTED_SKILL"}
        payload = dict(arguments)
        payload["intentVersion"] = "strategic-intent.v2"
        payload["planMetadata"] = {
            "planId": f"{state['decision_id']}:plan",
            "stepId": "intent-0",
            "revision": 0,
        }
        try:
            intent = StrategicIntentModel.model_validate(payload)
        except ValidationError:
            return {"code": "INVALID_ARGUMENTS"}
        state["candidate_intent"] = intent.wire_dict()
        return {"code": "OK"}
