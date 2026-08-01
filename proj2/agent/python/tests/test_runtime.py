"""Real-socket tests for runtime modes and connection isolation."""

from __future__ import annotations

import contextlib
import json
import queue
import socket
import subprocess
import sys
import threading
import time
import unittest
from collections.abc import Iterator
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from dungeonmind_agent.protocol import (
    DEFAULT_MAX_FRAME_BYTES,
    ProtocolViolation,
    decode_frame,
    encode_frame,
)
from dungeonmind_agent.server import (
    AgentRuntimeServer,
    create_server,
    stop_server,
)
from smoke_test import build_observation, exchange_observation


class RuntimeModeTest(unittest.TestCase):
    def test_cli_can_be_started_by_process_harness(self) -> None:
        command = [
            sys.executable,
            str(Path(__file__).parents[1] / "run.py"),
            "--host",
            "127.0.0.1",
            "--port",
            "0",
            "--mode",
            "normal",
        ]
        process = subprocess.Popen(
            command,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
        )
        self.assertIsNotNone(process.stdout)
        ready_lines: queue.Queue[str] = queue.Queue(maxsize=1)
        reader = threading.Thread(
            target=lambda: ready_lines.put(process.stdout.readline()),
            name="runtime-ready-reader",
            daemon=True,
        )
        reader.start()
        try:
            ready_line = ready_lines.get(timeout=20.0)
            self.assertNotEqual("", ready_line)
            ready = json.loads(ready_line)
            self.assertEqual("ready", ready["event"])
            response = exchange_observation(
                ready["host"], ready["port"], build_observation()
            )
            self.assertEqual("submit_intent", response["type"])
        finally:
            process.terminate()
            try:
                process.wait(timeout=3.0)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=3.0)
            reader.join(timeout=1.0)
            if process.stdout is not None:
                process.stdout.close()
            if process.stderr is not None:
                process.stderr.close()

    def test_concurrent_connections_have_isolated_sequence_state(self) -> None:
        with self._running_server("normal") as server:
            host, port = server.server_address
            barrier = threading.Barrier(2)

            def exchange(agent_id: str) -> dict[str, object]:
                barrier.wait(timeout=1.0)
                return exchange_observation(
                    host, port, build_observation(agent_id=agent_id)
                )

            with ThreadPoolExecutor(max_workers=2) as executor:
                first_future = executor.submit(exchange, "guard-a")
                second_future = executor.submit(exchange, "guard-b")
                first = first_future.result(timeout=2.0)
                second = second_future.result(timeout=2.0)

        self.assertEqual("guard-a", first["agentId"])
        self.assertEqual("guard-b", second["agentId"])
        self.assertEqual(0, first["messageSeq"])
        self.assertEqual(0, second["messageSeq"])
        self.assertEqual(
            first["data"]["intent"], second["data"]["intent"]
        )

    def test_delay_mode_defers_response(self) -> None:
        with self._running_server(
            "delay", delay_seconds=0.05
        ) as server:
            host, port = server.server_address
            started = time.monotonic()
            response = exchange_observation(host, port, build_observation())
            elapsed = time.monotonic() - started

        self.assertEqual("submit_intent", response["type"])
        self.assertGreaterEqual(elapsed, 0.04)

    def test_malformed_mode_returns_invalid_json(self) -> None:
        with self._running_server("malformed") as server:
            raw_response = self._raw_exchange(server)

        with self.assertRaises(ProtocolViolation) as caught:
            decode_frame(raw_response[:-1])
        self.assertEqual("JSON_SYNTAX", caught.exception.reason)

    def test_disconnect_mode_closes_after_observation(self) -> None:
        with self._running_server("disconnect") as server:
            raw_response = self._raw_exchange(server)

        self.assertEqual(b"", raw_response)

    def test_no_read_mode_accepts_without_consuming(self) -> None:
        with self._running_server("no-read") as server:
            host, port = server.server_address
            with socket.create_connection((host, port), timeout=1.0) as client:
                client.settimeout(0.08)
                client.sendall(encode_frame(build_observation()))
                with self.assertRaises(socket.timeout):
                    client.recv(1)

    @staticmethod
    def _raw_exchange(server: AgentRuntimeServer) -> bytes:
        host, port = server.server_address
        with socket.create_connection((host, port), timeout=1.0) as client:
            client.settimeout(1.0)
            client.sendall(encode_frame(build_observation()))
            with client.makefile("rb") as reader:
                return reader.readline(DEFAULT_MAX_FRAME_BYTES + 2)

    @staticmethod
    @contextlib.contextmanager
    def _running_server(
        mode: str, *, delay_seconds: float = 2.0
    ) -> Iterator[AgentRuntimeServer]:
        server = create_server(
            "127.0.0.1",
            0,
            mode=mode,
            delay_seconds=delay_seconds,
        )
        worker = threading.Thread(
            target=server.serve_forever,
            kwargs={"poll_interval": 0.02},
            name=f"runtime-test-{mode}",
            daemon=True,
        )
        worker.start()
        try:
            yield server
        finally:
            stop_server(server)
            worker.join(timeout=2.0)
            if worker.is_alive():
                raise AssertionError(
                    f"runtime worker did not stop for mode={mode}"
                )


if __name__ == "__main__":
    unittest.main()
