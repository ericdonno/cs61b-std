"""Contract tests for the strict cross-language protocol codec."""

from __future__ import annotations

import copy
import unittest

from dungeonmind_agent.brain.deterministic import DeterministicAgent
from dungeonmind_agent.protocol import (
    ENVELOPE_VERSION,
    ProtocolViolation,
    decode_frame,
    encode_frame,
)
from smoke_test import build_observation


class ProtocolCodecTest(unittest.TestCase):
    def test_observation_round_trip_is_deterministic(self) -> None:
        envelope = build_observation()

        encoded_once = encode_frame(envelope)
        encoded_twice = encode_frame(envelope)

        self.assertEqual(encoded_once, encoded_twice)
        self.assertEqual(envelope, decode_frame(encoded_once[:-1]))

    def test_all_message_payloads_round_trip(self) -> None:
        observation = build_observation()
        agent = DeterministicAgent()
        submit_intent = agent.handle(observation)[0]
        cancel_request = self._envelope(
            "cancel_request",
            {
                "decisionId": "decision-1",
                "requestGeneration": 2,
                "reason": "HARD_TIMEOUT",
            },
        )
        cancel_ack = agent.handle(cancel_request)[0]
        envelopes = [
            observation,
            submit_intent,
            cancel_request,
            cancel_ack,
            self._envelope(
                "action_feedback",
                {
                    "feedbackVersion": "action-feedback.v2",
                    "feedbackId": "feedback-1",
                    "decisionId": "decision-1",
                    "planId": "plan-1",
                    "stepId": "step-0",
                    "planRevision": 0,
                    "actionIndex": 1,
                    "actionType": "MoveAction",
                    "result": "SUCCESS",
                    "reasonCode": "ACTION_COMMITTED",
                    "stepStatus": "ACTIVE",
                    "planStatus": "ACTIVE",
                    "beforePosition": {"x": 2, "y": 2},
                    "afterPosition": {"x": 3, "y": 2},
                    "selfHp": 20,
                    "decisionSource": "REMOTE_AGENT",
                    "overrideReason": None,
                },
            ),
            self._envelope(
                "world_event",
                {
                    "eventVersion": "agent-event.v1",
                    "eventId": "event-1",
                    "eventType": "PLAYER_SPOTTED",
                    "logicalTick": 42,
                    "relatedPosition": {"x": 3, "y": 2},
                    "relatedEntityId": "player",
                    "decisionId": "decision-1",
                    "planId": "plan-1",
                    "stepId": "step-0",
                    "reasonCode": "NONE",
                },
            ),
            self._envelope("heartbeat", {"logicalTick": 42}),
            self._envelope(
                "protocol_error",
                {
                    "reason": "unsupported message",
                    "offendingType": "future_message",
                },
            ),
        ]

        for envelope in envelopes:
            with self.subTest(message_type=envelope["type"]):
                self.assertEqual(
                    envelope, decode_frame(encode_frame(envelope)[:-1])
                )

    def test_duplicate_key_is_rejected(self) -> None:
        with self.assertRaises(ProtocolViolation) as caught:
            decode_frame(b'{"schemaVersion":"a","schemaVersion":"b"}')

        self.assertEqual("DUPLICATE_KEY", caught.exception.reason)

    def test_non_finite_number_is_rejected(self) -> None:
        with self.assertRaises(ProtocolViolation) as caught:
            decode_frame(b'{"value":NaN}')

        self.assertEqual("JSON_SYNTAX", caught.exception.reason)

    def test_unknown_field_is_rejected(self) -> None:
        envelope = build_observation()
        envelope["unexpected"] = True

        with self.assertRaises(ProtocolViolation) as caught:
            encode_frame(envelope)

        self.assertEqual("UNKNOWN_FIELD", caught.exception.reason)

    def test_boolean_cannot_stand_in_for_integer(self) -> None:
        envelope = build_observation()
        envelope["messageSeq"] = True

        with self.assertRaises(ProtocolViolation) as caught:
            encode_frame(envelope)

        self.assertEqual("TYPE_MISMATCH", caught.exception.reason)

    def test_wrong_schema_version_is_rejected(self) -> None:
        envelope = build_observation()
        envelope["schemaVersion"] = "future.session.v1"

        with self.assertRaises(ProtocolViolation) as caught:
            encode_frame(envelope)

        self.assertEqual("SCHEMA_MISMATCH", caught.exception.reason)

    def test_excessive_depth_is_rejected(self) -> None:
        nested = b"[" * 17 + b"0" + b"]" * 17

        with self.assertRaises(ProtocolViolation) as caught:
            decode_frame(nested, max_depth=16)

        self.assertEqual("DEPTH_EXCEEDED", caught.exception.reason)

    def test_frame_limit_uses_utf8_byte_count(self) -> None:
        envelope = build_observation(agent_id="守卫")
        encoded = encode_frame(envelope)

        with self.assertRaises(ProtocolViolation) as caught:
            decode_frame(
                encoded[:-1], max_frame_bytes=len(encoded) - 2
            )

        self.assertEqual("FRAME_TOO_LARGE", caught.exception.reason)

    def test_input_is_not_mutated_during_validation(self) -> None:
        envelope = build_observation()
        original = copy.deepcopy(envelope)

        encode_frame(envelope)

        self.assertEqual(original, envelope)

    @staticmethod
    def _envelope(
        message_type: str, data: dict[str, object]
    ) -> dict[str, object]:
        return {
            "schemaVersion": ENVELOPE_VERSION,
            "messageId": f"java-{message_type}",
            "messageSeq": 0,
            "worldId": "world-test",
            "runId": "smoke-run",
            "floorId": 1,
            "agentId": "guard-a",
            "sessionEpoch": 1,
            "logicalTick": 42,
            "type": message_type,
            "data": data,
        }


if __name__ == "__main__":
    unittest.main()
