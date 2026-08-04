"""Behavior tests for deterministic strategic decisions."""

from __future__ import annotations

import unittest

from dungeonmind_agent.brain.deterministic import DeterministicAgent
from dungeonmind_agent.protocol import ENVELOPE_VERSION
from smoke_test import build_observation


class DeterministicAgentTest(unittest.TestCase):
    def test_adjacent_player_produces_attack(self) -> None:
        response = DeterministicAgent().handle(build_observation())[0]

        intent = response["data"]["intent"]
        self.assertEqual("ATTACK", intent["skill"])
        self.assertEqual(
            {"x": 3, "y": 2},
            intent["parameters"]["targetPosition"],
        )

    def test_visible_non_adjacent_player_produces_chase(self) -> None:
        response = DeterministicAgent().handle(
            build_observation(player_position=(8, 2))
        )[0]

        intent = response["data"]["intent"]
        self.assertEqual("CHASE", intent["skill"])
        self.assertEqual(
            {"x": 8, "y": 2},
            intent["parameters"]["targetPosition"],
        )

    def test_patrol_selects_first_sorted_walkable_tile(self) -> None:
        observation = build_observation(player_position=None)
        observation["data"]["visibleTiles"] = [
            {"x": 4, "y": 2, "type": "FLOOR", "walkable": True},
            {"x": 0, "y": 5, "type": "FLOOR", "walkable": True},
            {"x": 0, "y": 1, "type": "WALL", "walkable": False},
            {"x": 2, "y": 2, "type": "FLOOR", "walkable": True},
        ]

        response = DeterministicAgent().handle(observation)[0]

        intent = response["data"]["intent"]
        self.assertEqual("PATROL", intent["skill"])
        self.assertEqual(
            {"x": 0, "y": 5},
            intent["parameters"]["targetPosition"],
        )

    def test_same_observation_has_identical_fresh_response(self) -> None:
        observation = build_observation()

        first = DeterministicAgent().handle(observation)[0]
        second = DeterministicAgent().handle(observation)[0]

        self.assertEqual(first, second)

    def test_connection_state_is_not_shared(self) -> None:
        first_agent = DeterministicAgent()
        second_agent = DeterministicAgent()

        first_agent.handle(build_observation())
        first_followup = first_agent.handle(
            build_observation(message_seq=1, decision_id="decision-2")
        )[0]
        second_first = second_agent.handle(build_observation())[0]

        self.assertEqual(1, first_followup["messageSeq"])
        self.assertEqual(0, second_first["messageSeq"])

    def test_cancel_request_returns_correlated_ack(self) -> None:
        agent = DeterministicAgent()
        cancel = self._envelope(
            "cancel_request",
            {
                "decisionId": "decision-7",
                "requestGeneration": 4,
                "reason": "HARD_TIMEOUT",
            },
        )

        response = agent.handle(cancel)[0]

        self.assertEqual("cancel_ack", response["type"])
        self.assertEqual("decision-7", response["data"]["decisionId"])
        self.assertEqual(4, response["data"]["requestGeneration"])
        self.assertEqual(["decision-7"], agent.cancelled_decisions)

    def test_feedback_and_world_events_are_recorded(self) -> None:
        agent = DeterministicAgent()
        feedback = self._envelope(
            "action_feedback",
            {
                "decisionId": "decision-1",
                "actionIndex": 0,
                "actionType": "MoveAction",
                "result": "BLOCKED",
                "beforePosition": {"x": 2, "y": 2},
                "afterPosition": {"x": 2, "y": 2},
                "selfHp": 20,
                "decisionSource": "LOCAL_FALLBACK",
                "overrideReason": "PLAN_BLOCKED",
            },
        )
        event = self._envelope(
            "world_event",
            {
                "eventType": "PLAN_BLOCKED",
                "logicalTick": 42,
                "relatedPosition": {"x": 3, "y": 2},
                "relatedEntityId": None,
            },
        )

        self.assertEqual([], agent.handle(feedback))
        self.assertEqual([], agent.handle(event))

        self.assertEqual(1, len(agent.action_feedback))
        self.assertEqual(1, len(agent.world_events))

    @staticmethod
    def _envelope(
        message_type: str, data: dict[str, object]
    ) -> dict[str, object]:
        return {
            "schemaVersion": ENVELOPE_VERSION,
            "messageId": f"java-{message_type}",
            "messageSeq": 0,
            "worldId": "world-test",
            "runId": "agent-test",
            "floorId": 1,
            "agentId": "guard-a",
            "sessionEpoch": 1,
            "logicalTick": 42,
            "type": message_type,
            "data": data,
        }


if __name__ == "__main__":
    unittest.main()
