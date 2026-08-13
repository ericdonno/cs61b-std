"""跨语言共享 wire fixture 的 Python 侧 runner。

合法 fixture 必须通过严格校验并得到相同 typed data；非法 fixture 必须给出
稳定 rejection code。
"""

from __future__ import annotations

import json
import unittest
from pathlib import Path

from dungeonmind_agent.protocol import (
    ProtocolViolation,
    validate_envelope,
)

FIXTURES = (
    Path(__file__).resolve().parent.parent.parent
    / "contract"
    / "fixtures"
)


class ContractFixtureTest(unittest.TestCase):
    def _load(self, name: str) -> dict:
        with (FIXTURES / name).open(encoding="utf-8") as f:
            return json.load(f)

    def test_valid_directional_fixture_accepted(self) -> None:
        envelope = validate_envelope(
            self._load("valid-observation-directional.json")
        )
        self.assertEqual("agent-session.v1", envelope["schemaVersion"])
        self.assertEqual("world-fixture", envelope["worldId"])
        self.assertEqual("run-fixture", envelope["runId"])
        data = envelope["data"]
        self.assertEqual("private-observation.v2",
                         data["observationVersion"])
        self.assertEqual("DIRECTIONAL", data["visionMode"])
        self.assertEqual(14, data["self"]["hp"])
        self.assertEqual(20, data["self"]["maxHp"])
        self.assertEqual("EAST", data["self"]["facing"])
        self.assertEqual(3, len(data["visibleTiles"]))

    def test_valid_omnidirectional_fixture_accepted(self) -> None:
        envelope = validate_envelope(
            self._load("valid-observation-omnidirectional.json")
        )
        self.assertEqual("OMNIDIRECTIONAL",
                         envelope["data"]["visionMode"])
        self.assertEqual("NORTH", envelope["data"]["self"]["facing"])

    def test_valid_intent_fixture_accepted(self) -> None:
        envelope = validate_envelope(
            self._load("valid-submit-intent-v2.json")
        )
        intent = envelope["data"]["intent"]
        self.assertEqual("strategic-intent.v2", intent["intentVersion"])
        self.assertEqual("CHASE", intent["skill"])
        self.assertEqual("plan-fixture-1", intent["planMetadata"]["planId"])
        self.assertEqual([True, None, "bounded"],
                         intent["parameters"]["futureHints"])

    def test_invalid_fixtures_rejected_with_stable_codes(self) -> None:
        cases = {
            "invalid-old-envelope-version.json": "SCHEMA_MISMATCH",
            "invalid-old-observation-version.json":
                "UNKNOWN_PAYLOAD_VERSION",
            "invalid-old-intent-version.json": "UNKNOWN_PAYLOAD_VERSION",
            "invalid-missing-world-id.json": "MISSING_REQUIRED",
            "invalid-facing.json": "UNKNOWN_FIELD",
            "invalid-vision-mode.json": "UNKNOWN_FIELD",
            "invalid-apple-tile-type.json": "UNKNOWN_FIELD",
        }
        for name, expected_code in cases.items():
            with self.subTest(name=name):
                with self.assertRaises(ProtocolViolation) as ctx:
                    validate_envelope(self._load(name))
                self.assertEqual(
                    expected_code, ctx.exception.reason, name
                )


if __name__ == "__main__":
    unittest.main()
