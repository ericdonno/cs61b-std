"""Stable brain/emitter interfaces shared by runtime modes."""

from __future__ import annotations

from enum import Enum
from typing import Any, Protocol


class EmitResult(str, Enum):
    EMITTED = "EMITTED"
    CLOSED = "CLOSED"
    INELIGIBLE = "INELIGIBLE"


class ResponseEmitter(Protocol):
    def emit_response(self, request: dict[str, Any], message_type: str,
                      data: dict[str, Any]) -> EmitResult: ...

    def close(self) -> None: ...


class RuntimeBrain(Protocol):
    def on_message(self, envelope: dict[str, Any]) -> None: ...
    def close(self) -> None: ...
