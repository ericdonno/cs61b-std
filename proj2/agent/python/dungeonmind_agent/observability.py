"""Thread-safe, field-whitelisted model trace sinks."""

from __future__ import annotations

import json
import threading
from pathlib import Path
from typing import Any, TextIO


ALLOWED_FIELDS = frozenset({
    "eventType", "worldId", "runId", "floorId", "agentId",
    "sessionEpoch", "observationSeq", "decisionId", "requestGeneration",
    "graphNode", "modelCallIndex", "toolCallId", "toolName", "queueDepth",
    "activeModelCalls", "inputTokens", "outputTokens", "usageEstimated",
    "elapsedMs", "resultCode", "skillId", "planId", "stepId",
})


class TraceSink:
    def __init__(self, stream: TextIO | None = None, path: str | None = None) -> None:
        if stream is not None and path is not None:
            raise ValueError("choose stream or path")
        self._owned = None
        if path is not None:
            target = Path(path)
            target.parent.mkdir(parents=True, exist_ok=True)
            self._owned = target.open("a", encoding="utf-8")
            stream = self._owned
        self._stream = stream
        self._lock = threading.Lock()
        self._sequence = 0

    def record(self, event_type: str, **fields: Any) -> dict[str, Any]:
        event = {"schemaVersion": "agent-model.trace.v1",
                 "eventType": event_type}
        event.update({key: value for key, value in fields.items()
                      if key in ALLOWED_FIELDS})
        with self._lock:
            event["eventSeq"] = self._sequence
            self._sequence += 1
            if self._stream is not None:
                self._stream.write(json.dumps(
                    event, ensure_ascii=False, separators=(",", ":")
                ) + "\n")
                self._stream.flush()
        return event

    def close(self) -> None:
        if self._owned is not None:
            self._owned.close()
            self._owned = None
