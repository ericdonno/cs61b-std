"""Provider-neutral model invocation contract and scripted test adapter."""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Protocol


class ModelErrorCode(str, Enum):
    TRANSIENT = "TRANSIENT"
    PERMANENT = "PERMANENT"
    TIMEOUT = "TIMEOUT"
    CANCELLED = "CANCELLED"
    NOT_CONFIGURED = "NOT_CONFIGURED"


class ModelCallError(RuntimeError):
    def __init__(self, code: ModelErrorCode, detail: str = "") -> None:
        super().__init__(code.value if not detail else f"{code.value}: {detail}")
        self.code = code


@dataclass(frozen=True)
class ToolCall:
    call_id: str
    name: str
    arguments: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class ModelUsage:
    input_tokens: int
    output_tokens: int
    estimated: bool = False


@dataclass(frozen=True)
class ModelInput:
    call_index: int
    observation: dict[str, Any]
    messages: tuple[dict[str, Any], ...]


@dataclass(frozen=True)
class ModelResponse:
    tool_calls: tuple[ToolCall, ...]
    usage: ModelUsage | None = None


class ModelAdapter(Protocol):
    def invoke(self, request: ModelInput) -> ModelResponse: ...


class ScriptedModelAdapter:
    """Deterministic model double that still uses the real graph and tools."""

    def invoke(self, request: ModelInput) -> ModelResponse:
        if request.call_index == 0:
            calls = (ToolCall("evidence-0", "read_self", {}),)
        else:
            observation = request.observation
            self_position = observation["self"]["position"]
            players = sorted(
                (entity for entity in observation["visibleEntities"]
                 if entity["type"] == "PLAYER"),
                key=lambda entity: (
                    abs(entity["position"]["x"] - self_position["x"])
                    + abs(entity["position"]["y"] - self_position["y"]),
                    entity["position"]["x"], entity["position"]["y"],
                ),
            )
            if players:
                target = dict(players[0]["position"])
                distance = abs(target["x"] - self_position["x"]) + abs(
                    target["y"] - self_position["y"]
                )
                skill = "ATTACK" if distance == 1 else "CHASE"
                parameters = {"targetPosition": target}
            else:
                candidates = sorted(
                    (tile for tile in observation["visibleTiles"]
                     if tile["walkable"] and (tile["x"], tile["y"])
                     != (self_position["x"], self_position["y"])),
                    key=lambda tile: (tile["x"], tile["y"], tile["type"]),
                )
                skill = "PATROL"
                parameters = ({"targetPosition": {
                    "x": candidates[0]["x"], "y": candidates[0]["y"]}}
                    if candidates else {})
            calls = (ToolCall("submit-0", "submit_strategic_intent", {
                "skill": skill,
                "parameters": parameters,
                "confidence": 0.9,
                "validForTicks": 12,
                "interruptPolicy": {
                    "engageVisiblePlayer": True,
                    "respondToAdjacentThreat": True,
                    "allowLocalReroute": skill != "ATTACK",
                },
            }),)
        return ModelResponse(calls, ModelUsage(32, 16, estimated=True))
