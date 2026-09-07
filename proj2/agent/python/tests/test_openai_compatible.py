"""Local HTTP verification for the OpenAI-compatible model path."""

from __future__ import annotations

import json
import os
import subprocess
import sys
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch

from dungeonmind_agent.config import RuntimeConfig


class _ProviderHandler(BaseHTTPRequestHandler):
    requests: list[dict] = []
    authorization: list[str | None] = []

    def do_POST(self) -> None:
        length = int(self.headers["Content-Length"])
        self.__class__.requests.append(json.loads(self.rfile.read(length)))
        self.__class__.authorization.append(self.headers.get("Authorization"))
        if len(self.__class__.requests) == 1:
            name, arguments = "read_self", {}
        else:
            name, arguments = "submit_plan", {"steps": [{
                "skill": "ATTACK",
                "parameters": {"targetPosition": {"x": 3, "y": 2}},
                "confidence": 0.9,
                "validForTicks": 12,
                "interruptPolicy": {
                    "engageVisiblePlayer": True,
                    "respondToAdjacentThreat": True,
                    "allowLocalReroute": False,
                },
            }]}
        payload = {
            "choices": [{"message": {"tool_calls": [{
                "id": f"call-{len(self.__class__.requests)}",
                "type": "function",
                "function": {
                    "name": name,
                    "arguments": json.dumps(arguments),
                },
            }]}}],
            "usage": {"prompt_tokens": 20, "completion_tokens": 10},
        }
        body = json.dumps(payload).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format: str, *args: object) -> None:
        pass


class OpenAICompatibleTest(unittest.TestCase):
    def test_model_server_completes_tool_round_without_leaking_key(self) -> None:
        _ProviderHandler.requests = []
        _ProviderHandler.authorization = []
        provider = ThreadingHTTPServer(("127.0.0.1", 0), _ProviderHandler)
        provider_worker = threading.Thread(
            target=provider.serve_forever, daemon=True
        )
        provider_worker.start()
        host, port = provider.server_address
        secret = "local-test-secret"
        environment = {
            "DUNGEONMIND_API_BASE": f"http://{host}:{port}/v1",
            "DUNGEONMIND_MODEL": "fake-tool-model",
            "DUNGEONMIND_API_KEY": secret,
        }
        try:
            with patch.dict(os.environ, environment, clear=False):
                config = RuntimeConfig.from_environment(brain="model")
                self.assertNotIn(secret, repr(config))
                completed = subprocess.run(
                    [
                        sys.executable,
                        str(Path(__file__).parents[1] / "smoke_test.py"),
                        "--spawn-server", "--brain", "model",
                        "--timeout-seconds", "5",
                    ],
                    check=False, capture_output=True, text=True,
                    encoding="utf-8", timeout=10,
                )
        finally:
            provider.shutdown()
            provider.server_close()
            provider_worker.join(timeout=2.0)

        self.assertEqual(0, completed.returncode, completed.stderr)
        self.assertEqual("ATTACK", json.loads(completed.stdout)["skill"])
        self.assertEqual(2, len(_ProviderHandler.requests))
        self.assertEqual(
            [f"Bearer {secret}", f"Bearer {secret}"],
            _ProviderHandler.authorization,
        )
        self.assertTrue(all(
            secret not in json.dumps(request)
            for request in _ProviderHandler.requests
        ))


if __name__ == "__main__":
    unittest.main()
