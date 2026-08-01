"""Deterministic strategic decisions for runtime contract testing."""

from __future__ import annotations

import copy
from typing import Any

from ..protocol import (
    ENVELOPE_VERSION,
    INTENT_VERSION,
    ProtocolViolation,
    validate_envelope,
)


class DeterministicAgent:
    """Handles one connection without sharing decision state."""

    def __init__(self) -> None:
        self._next_message_seq = 0
        self.action_feedback: list[dict[str, Any]] = []
        self.world_events: list[dict[str, Any]] = []
        self.cancelled_decisions: list[str] = []

    def handle(
        self, envelope: dict[str, Any]
    ) -> list[dict[str, Any]]:
        """Processes one validated inbound envelope."""
        message = validate_envelope(envelope)
        message_type = message["type"]
        if message_type == "observation":
            return [self._submit_intent(message)]
        if message_type == "cancel_request":
            self.cancelled_decisions.append(
                message["data"]["decisionId"]
            )
            return [self._cancel_ack(message)]
        if message_type == "action_feedback":
            self.action_feedback.append(copy.deepcopy(message["data"]))
            return []
        if message_type == "world_event":
            self.world_events.append(copy.deepcopy(message["data"]))
            return []
        if message_type in {"heartbeat", "protocol_error"}:
            return []
        raise ProtocolViolation(
            "DIRECTION_MISMATCH",
            f"runtime cannot receive {message_type}",
        )

    def _submit_intent(
        self, request: dict[str, Any]
    ) -> dict[str, Any]:
        observation = request["data"]
        intent = self._decide(observation)
        data = {
            "decisionId": observation["decisionId"],
            "observationSeq": observation["observationSeq"],
            "requestGeneration": observation["requestGeneration"],
            "intent": intent,
        }
        return self._response(request, "submit_intent", data)

    def _cancel_ack(
        self, request: dict[str, Any]
    ) -> dict[str, Any]:
        cancelled = request["data"]
        data = {
            "decisionId": cancelled["decisionId"],
            "requestGeneration": cancelled["requestGeneration"],
        }
        return self._response(request, "cancel_ack", data)

    def _response(
        self,
        request: dict[str, Any],
        message_type: str,
        data: dict[str, Any],
    ) -> dict[str, Any]:
        message_seq = self._next_message_seq
        self._next_message_seq += 1
        return validate_envelope(
            {
                "schemaVersion": ENVELOPE_VERSION,
                "messageId": f"runtime-message-{message_seq}",
                "messageSeq": message_seq,
                "runId": request["runId"],
                "floorId": request["floorId"],
                "agentId": request["agentId"],
                "sessionEpoch": request["sessionEpoch"],
                "logicalTick": request["logicalTick"],
                "type": message_type,
                "data": data,
            }
        )

    @staticmethod
    def _decide(observation: dict[str, Any]) -> dict[str, Any]:
        self_position = observation["self"]["position"]
        players = sorted(
            (
                entity
                for entity in observation["visibleEntities"]
                if entity["type"] == "PLAYER"
            ),
            key=lambda entity: (
                abs(entity["position"]["x"] - self_position["x"])
                + abs(entity["position"]["y"] - self_position["y"]),
                entity["position"]["x"],
                entity["position"]["y"],
            ),
        )

        if players:
            target = players[0]["position"]
            distance = (
                abs(target["x"] - self_position["x"])
                + abs(target["y"] - self_position["y"])
            )
            skill = "ATTACK" if distance == 1 else "CHASE"
            parameters = {"targetPosition": dict(target)}
        else:
            candidates = sorted(
                (
                    tile
                    for tile in observation["visibleTiles"]
                    if tile["walkable"]
                    and (
                        tile["x"] != self_position["x"]
                        or tile["y"] != self_position["y"]
                    )
                ),
                key=lambda tile: (tile["x"], tile["y"], tile["type"]),
            )
            skill = "PATROL"
            parameters = (
                {
                    "targetPosition": {
                        "x": candidates[0]["x"],
                        "y": candidates[0]["y"],
                    }
                }
                if candidates
                else {}
            )

        confidence = {
            "ATTACK": 1.0,
            "CHASE": 0.9,
            "PATROL": 0.75,
        }[skill]
        valid_for_ticks = {
            "ATTACK": 2,
            "CHASE": 12,
            "PATROL": 20,
        }[skill]
        return {
            "intentVersion": INTENT_VERSION,
            "skill": skill,
            "parameters": parameters,
            "confidence": confidence,
            "validForTicks": valid_for_ticks,
            "interruptPolicy": {
                "engageVisiblePlayer": True,
                "respondToAdjacentThreat": True,
                "allowLocalReroute": skill != "ATTACK",
            },
        }
