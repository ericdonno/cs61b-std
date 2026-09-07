"""Multi-connection TCP server for Python agent implementations."""

from __future__ import annotations

import argparse
import json
import logging
import socketserver
import sys
import threading
from concurrent.futures import ThreadPoolExecutor
from typing import BinaryIO

from .brain.base import EmitResult
from .brain.factory import BrainFactory
from .checkpoint import CheckpointManager
from .config import RuntimeConfig
from .graph.workflow import AgentWorkflow
from .model.adapter import ScriptedModelAdapter
from .model.openai_compatible import OpenAICompatibleModelAdapter
from .model.scheduler import InferenceScheduler
from .observability import TraceSink
from .protocol import (
    DEFAULT_MAX_FRAME_BYTES,
    ENVELOPE_VERSION,
    ProtocolViolation,
    decode_frame,
    encode_frame,
)


LOGGER = logging.getLogger("dungeonmind.agent_runtime")
RUNTIME_MODES = (
    "normal",
    "delay",
    "malformed",
    "disconnect",
    "no-read",
)


class AgentRuntimeServer(socketserver.ThreadingTCPServer):
    """Owns shared listener state while handlers own their agents."""

    allow_reuse_address = True
    daemon_threads = True
    block_on_close = False

    def __init__(
        self,
        server_address: tuple[str, int],
        mode: str,
        delay_seconds: float,
        max_frame_bytes: int,
        runtime_config: RuntimeConfig,
    ) -> None:
        if mode not in RUNTIME_MODES:
            raise ValueError(f"unsupported runtime mode: {mode}")
        if delay_seconds < 0:
            raise ValueError("delay_seconds must not be negative")
        self.mode = mode
        self.delay_seconds = delay_seconds
        self.max_frame_bytes = max_frame_bytes
        self.runtime_config = runtime_config
        self.stop_event = threading.Event()
        self._resources_closed = False
        self.checkpoints = None
        self.scheduler = None
        self.trace = None
        self.executor = None
        if runtime_config.brain in {"scripted", "model"}:
            self.checkpoints = CheckpointManager(runtime_config.checkpoint_db)
            self.scheduler = InferenceScheduler(
                max_concurrent=runtime_config.max_concurrent_model_calls,
                max_queued=runtime_config.max_queued_model_calls,
                max_calls_per_encounter=runtime_config.max_model_calls_per_encounter,
                max_tokens_per_encounter=runtime_config.max_tokens_per_encounter,
            )
            self.trace = TraceSink(path=runtime_config.runtime_trace)
            self.executor = ThreadPoolExecutor(
                max_workers=(runtime_config.max_concurrent_model_calls
                             + runtime_config.max_queued_model_calls),
                thread_name_prefix="agent-graph",
            )
            adapter = (ScriptedModelAdapter()
                       if runtime_config.brain == "scripted"
                       else OpenAICompatibleModelAdapter(
                           base_url=runtime_config.provider_base_url,
                           model=runtime_config.provider_model,
                           api_key=runtime_config.provider_api_key,
                           timeout_seconds=runtime_config.decision_timeout_seconds,
                           max_output_tokens=runtime_config.max_output_tokens,
                           token_limit_field=runtime_config.provider_token_limit_field,
                       ))
            workflow = AgentWorkflow(
                adapter, self.scheduler,
                self.checkpoints.saver, runtime_config, self.trace,
            )
            self.brain_factory = BrainFactory(
                runtime_config.brain, workflow, self.executor
            )
        else:
            self.brain_factory = BrainFactory(runtime_config.brain)
        super().__init__(server_address, AgentRequestHandler)

    def close_resources(self) -> None:
        if self._resources_closed:
            return
        self._resources_closed = True
        if self.scheduler is not None:
            self.scheduler.close()
        if self.executor is not None:
            self.executor.shutdown(wait=True, cancel_futures=True)
        if self.trace is not None:
            self.trace.close()
        if self.checkpoints is not None:
            self.checkpoints.close()


class ConnectionResponseEmitter:
    """Serializes response identity, sequence allocation and socket writes."""

    def __init__(self, writer: BinaryIO, max_frame_bytes: int) -> None:
        self._writer = writer
        self._max_frame_bytes = max_frame_bytes
        self._lock = threading.Lock()
        self._next_message_seq = 0
        self._closed = False

    def emit_response(self, request: dict, message_type: str,
                      data: dict) -> EmitResult:
        with self._lock:
            if self._closed:
                return EmitResult.CLOSED
            message_seq = self._next_message_seq
            envelope = {
                "schemaVersion": ENVELOPE_VERSION,
                "messageId": f"runtime-message-{message_seq}",
                "messageSeq": message_seq,
                "worldId": request["worldId"],
                "runId": request["runId"],
                "floorId": request["floorId"],
                "agentId": request["agentId"],
                "sessionEpoch": request["sessionEpoch"],
                "logicalTick": request["logicalTick"],
                "type": message_type,
                "data": data,
            }
            try:
                self._writer.write(encode_frame(
                    envelope, max_frame_bytes=self._max_frame_bytes
                ))
                self._writer.flush()
            except OSError:
                self._closed = True
                return EmitResult.CLOSED
            self._next_message_seq += 1
            return EmitResult.EMITTED

    def close(self) -> None:
        with self._lock:
            self._closed = True


class AgentRequestHandler(socketserver.StreamRequestHandler):
    """Services one connection with an isolated agent brain."""

    server: AgentRuntimeServer

    def handle(self) -> None:
        if self.server.mode == "no-read":
            self.server.stop_event.wait()
            return

        emitter = ConnectionResponseEmitter(
            self.wfile, self.server.max_frame_bytes
        )
        brain = self.server.brain_factory.create(emitter)
        try:
            self._read_messages(brain)
        finally:
            brain.close()
            emitter.close()

    def _read_messages(self, brain) -> None:
        while not self.server.stop_event.is_set():
            try:
                raw_frame = _read_bounded_frame(
                    self.rfile, self.server.max_frame_bytes
                )
                if raw_frame is None:
                    return
                envelope = decode_frame(
                    raw_frame,
                    max_frame_bytes=self.server.max_frame_bytes,
                )
            except ProtocolViolation as exception:
                LOGGER.warning(
                    "Rejected inbound frame: %s - %s",
                    exception.reason,
                    exception.detail,
                )
                return
            except OSError:
                return

            message_type = envelope["type"]
            if message_type == "observation":
                if self.server.mode == "disconnect":
                    return
                if self.server.mode == "malformed":
                    self.wfile.write(b'{"type": malformed]\n')
                    self.wfile.flush()
                    return
                if (
                    self.server.mode == "delay"
                    and self.server.stop_event.wait(
                        self.server.delay_seconds
                    )
                ):
                    return

            try:
                brain.on_message(envelope)
            except ProtocolViolation as exception:
                LOGGER.warning(
                    "Rejected message: %s - %s",
                    exception.reason,
                    exception.detail,
                )
                return
            except OSError:
                return

            if message_type in {"action_feedback", "world_event"}:
                LOGGER.info(
                    "Recorded %s for agentId=%s",
                    message_type,
                    envelope["agentId"],
                )


def create_server(
    host: str,
    port: int,
    *,
    mode: str = "normal",
    delay_seconds: float = 2.0,
    max_frame_bytes: int = DEFAULT_MAX_FRAME_BYTES,
    brain: str = "deterministic",
    checkpoint_db: str | None = None,
    runtime_trace: str | None = None,
) -> AgentRuntimeServer:
    """Creates a bound server without starting its serving loop."""
    if not host.strip():
        raise ValueError("host must not be blank")
    if not 0 <= port <= 65_535:
        raise ValueError("port must be between 0 and 65535")
    if max_frame_bytes < 1:
        raise ValueError("max_frame_bytes must be positive")
    runtime_config = RuntimeConfig.from_environment(
        brain=brain, checkpoint_db=checkpoint_db,
        runtime_trace=runtime_trace,
    )
    return AgentRuntimeServer((host, port), mode, delay_seconds,
                              max_frame_bytes, runtime_config)


def stop_server(server: AgentRuntimeServer) -> None:
    """Stops serving and releases handlers without an unbounded wait."""
    server.stop_event.set()
    server.shutdown()
    server.server_close()
    server.close_resources()


def _read_bounded_frame(
    reader: BinaryIO, max_frame_bytes: int
) -> bytes | None:
    """Reads one newline-delimited frame with bounded allocation."""
    frame = reader.readline(max_frame_bytes + 2)
    if frame == b"":
        return None
    if not frame.endswith(b"\n"):
        if len(frame) > max_frame_bytes:
            raise ProtocolViolation(
                "FRAME_TOO_LARGE",
                f"frame exceeds {max_frame_bytes} UTF-8 bytes",
            )
        raise ProtocolViolation(
            "JSON_SYNTAX", "frame ended before NDJSON delimiter"
        )
    payload = frame[:-1]
    if len(payload) > max_frame_bytes:
        raise ProtocolViolation(
            "FRAME_TOO_LARGE",
            f"frame exceeds {max_frame_bytes} UTF-8 bytes",
        )
    return payload


def _parse_args(arguments: list[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Run the DungeonMind agent server."
    )
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=9876)
    parser.add_argument("--mode", choices=RUNTIME_MODES, default="normal")
    parser.add_argument("--delay-seconds", type=float, default=2.0)
    parser.add_argument(
        "--brain", choices=("deterministic", "scripted", "model"),
        default="deterministic",
    )
    parser.add_argument("--checkpoint-db")
    parser.add_argument("--runtime-trace")
    parser.add_argument(
        "--max-frame-bytes",
        type=int,
        default=DEFAULT_MAX_FRAME_BYTES,
    )
    return parser.parse_args(arguments)


def main(arguments: list[str] | None = None) -> int:
    """Runs the CLI server until interrupted."""
    args = _parse_args(arguments)
    logging.basicConfig(
        level=logging.INFO,
        format="%(levelname)s %(name)s %(message)s",
    )
    server = create_server(
        args.host,
        args.port,
        mode=args.mode,
        delay_seconds=args.delay_seconds,
        max_frame_bytes=args.max_frame_bytes,
        brain=args.brain,
        checkpoint_db=args.checkpoint_db,
        runtime_trace=args.runtime_trace,
    )
    bound_host, bound_port = server.server_address
    ready = json.dumps(
        {
            "event": "ready",
            "host": bound_host,
            "port": bound_port,
            "mode": args.mode,
            "brain": args.brain,
            "checkpoint": args.checkpoint_db is not None,
        },
        ensure_ascii=False,
        separators=(",", ":"),
    )
    sys.stdout.write(ready + "\n")
    sys.stdout.flush()
    try:
        server.serve_forever(poll_interval=0.1)
    except KeyboardInterrupt:
        return 0
    finally:
        server.stop_event.set()
        server.server_close()
        server.close_resources()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
