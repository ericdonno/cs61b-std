"""Shortest real-socket observation-to-intent verification."""

from __future__ import annotations

import argparse
import json
import socket
import sys
import threading
from typing import Any

from dungeonmind_agent.protocol import (
    DEFAULT_MAX_FRAME_BYTES,
    ENVELOPE_VERSION,
    OBSERVATION_VERSION,
    decode_frame,
    encode_frame,
)
from dungeonmind_agent.server import create_server, stop_server


def build_observation(
    *,
    agent_id: str = "guard-a",
    message_seq: int = 0,
    decision_id: str = "decision-1",
    player_position: tuple[int, int] | None = (3, 2),
) -> dict[str, Any]:
    """Builds a representative private observation envelope."""
    visible_entities = []
    if player_position is not None:
        visible_entities.append(
            {
                "type": "PLAYER",
                "position": {
                    "x": player_position[0],
                    "y": player_position[1],
                },
                "visibleHp": 100,
                "agentId": None,
            }
        )
    return {
        "schemaVersion": ENVELOPE_VERSION,
        "messageId": f"java-message-{message_seq}",
        "messageSeq": message_seq,
        "worldId": "world-test",
        "runId": "smoke-run",
        "floorId": 1,
        "agentId": agent_id,
        "sessionEpoch": 1,
        "logicalTick": 42,
        "type": "observation",
        "data": {
            "observationVersion": OBSERVATION_VERSION,
            "decisionId": decision_id,
            "observationSeq": 5,
            "requestGeneration": 2,
            "observedAtTurn": 42,
            "visionMode": "DIRECTIONAL",
            "self": {
                "position": {"x": 2, "y": 2},
                "hp": 20,
                "maxHp": 20,
                "facing": "EAST",
            },
            "visibleTiles": [
                {"x": 2, "y": 2, "type": "FLOOR", "walkable": True},
                {"x": 1, "y": 2, "type": "FLOOR", "walkable": True},
                {"x": 3, "y": 2, "type": "FLOOR", "walkable": True},
            ],
            "visibleEntities": visible_entities,
            "heardEvents": [],
            "pendingEvents": [],
            "capabilities": {
                "supportedSkills": [
                    "PATROL",
                    "CHASE",
                    "ATTACK",
                    "GUARD",
                ],
                "sightRange": 7,
                "attackDamage": 10,
                "moveInterval": 5,
            },
        },
    }


def exchange_observation(
    host: str,
    port: int,
    observation: dict[str, Any],
    *,
    timeout_seconds: float = 3.0,
) -> dict[str, Any]:
    """Sends one observation and validates the correlated response."""
    with socket.create_connection(
        (host, port), timeout=timeout_seconds
    ) as connection:
        connection.settimeout(timeout_seconds)
        connection.sendall(encode_frame(observation))
        with connection.makefile("rb") as reader:
            raw_response = reader.readline(DEFAULT_MAX_FRAME_BYTES + 2)
    if not raw_response.endswith(b"\n"):
        raise AssertionError("runtime returned no complete NDJSON frame")
    response = decode_frame(raw_response[:-1])
    if response["type"] != "submit_intent":
        raise AssertionError(
            f"expected submit_intent, got {response['type']}"
        )
    for field in ("runId", "floorId", "agentId", "sessionEpoch"):
        if response[field] != observation[field]:
            raise AssertionError(f"identity mismatch for {field}")
    response_data = response["data"]
    observation_data = observation["data"]
    for field in (
        "decisionId",
        "observationSeq",
        "requestGeneration",
    ):
        if response_data[field] != observation_data[field]:
            raise AssertionError(f"correlation mismatch for {field}")
    return response


def run_embedded_smoke(*, brain: str = "deterministic",
                       timeout_seconds: float = 12.0) -> dict[str, Any]:
    """Starts a real local server and executes one bounded exchange."""
    server = create_server("127.0.0.1", 0, mode="normal", brain=brain)
    worker = threading.Thread(
        target=server.serve_forever,
        kwargs={"poll_interval": 0.05},
        name="agent-smoke-server",
        daemon=True,
    )
    worker.start()
    try:
        host, port = server.server_address
        return exchange_observation(
            host, port, build_observation(), timeout_seconds=timeout_seconds
        )
    finally:
        stop_server(server)
        worker.join(timeout=2.0)
        if worker.is_alive():
            raise AssertionError("smoke server did not stop")


def _parse_args(arguments: list[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Verify one observation-to-intent socket exchange."
    )
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=9876)
    parser.add_argument("--spawn-server", action="store_true")
    parser.add_argument(
        "--brain", choices=("deterministic", "scripted", "model"),
        default="deterministic",
    )
    parser.add_argument("--timeout-seconds", type=float, default=12.0)
    return parser.parse_args(arguments)


def main(arguments: list[str] | None = None) -> int:
    """Runs the smoke exchange against a server or an embedded runtime."""
    args = _parse_args(arguments)
    response = (
        run_embedded_smoke(
            brain=args.brain, timeout_seconds=args.timeout_seconds
        )
        if args.spawn_server
        else exchange_observation(
            args.host, args.port, build_observation(),
            timeout_seconds=args.timeout_seconds,
        )
    )
    summary = {
        "result": "ok",
        "agentId": response["agentId"],
        "decisionId": response["data"]["decisionId"],
        "skill": response["data"]["intent"]["skill"],
    }
    sys.stdout.write(
        json.dumps(
            summary,
            ensure_ascii=False,
            separators=(",", ":"),
        )
        + "\n"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
