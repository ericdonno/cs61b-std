"""Thread-safe bounded execution input staging for one Agent connection."""

from __future__ import annotations

import copy
import threading
from collections import deque
from dataclasses import dataclass
from typing import Any


@dataclass(frozen=True)
class InboxSnapshot:
    feedback: tuple[dict[str, Any], ...] = ()
    events: tuple[dict[str, Any], ...] = ()

    @property
    def feedback_ids(self) -> tuple[str, ...]:
        return tuple(item["feedbackId"] for item in self.feedback)

    @property
    def event_ids(self) -> tuple[str, ...]:
        return tuple(item["eventId"] for item in self.events)


class ExecutionInbox:
    def __init__(self, feedback_limit: int = 32, event_limit: int = 32,
                 consumed_limit: int = 64) -> None:
        self._feedback_limit = feedback_limit
        self._event_limit = event_limit
        self._consumed_limit = consumed_limit
        self._feedback: dict[str, dict[str, Any]] = {}
        self._events: dict[str, dict[str, Any]] = {}
        self._consumed_feedback: deque[str] = deque(maxlen=consumed_limit)
        self._consumed_events: deque[str] = deque(maxlen=consumed_limit)
        self._run_id: str | None = None
        self._lock = threading.Lock()

    def stage(self, envelope: dict[str, Any]) -> None:
        with self._lock:
            self._reset_if_changed(envelope["runId"])
            data = copy.deepcopy(envelope["data"])
            if envelope["type"] == "action_feedback":
                self._stage(self._feedback, data["feedbackId"], data,
                            self._feedback_limit, self._consumed_feedback,
                            critical=True)
            elif envelope["type"] == "world_event":
                self._stage(self._events, data["eventId"], data,
                            self._event_limit, self._consumed_events,
                            critical=False)

    def snapshot(self, run_id: str) -> InboxSnapshot:
        with self._lock:
            self._reset_if_changed(run_id)
            return InboxSnapshot(
                tuple(copy.deepcopy(tuple(self._feedback.values()))),
                tuple(copy.deepcopy(tuple(self._events.values()))),
            )

    def commit(self, snapshot: InboxSnapshot) -> None:
        self.commit_ids(snapshot.feedback_ids, snapshot.event_ids)

    def commit_ids(self, feedback_ids: tuple[str, ...],
                   event_ids: tuple[str, ...]) -> None:
        with self._lock:
            for item_id in feedback_ids:
                if self._feedback.pop(item_id, None) is not None:
                    self._consumed_feedback.append(item_id)
            for item_id in event_ids:
                if self._events.pop(item_id, None) is not None:
                    self._consumed_events.append(item_id)

    def _reset_if_changed(self, run_id: str) -> None:
        if self._run_id is None:
            self._run_id = run_id
        elif self._run_id != run_id:
            self._run_id = run_id
            self._feedback.clear()
            self._events.clear()
            self._consumed_feedback.clear()
            self._consumed_events.clear()

    @staticmethod
    def _stage(target: dict[str, dict[str, Any]], item_id: str,
               data: dict[str, Any], limit: int,
               consumed: deque[str], *, critical: bool) -> None:
        if item_id in consumed or item_id in target:
            return
        if len(target) >= limit:
            if critical:
                raise OverflowError("unconsumed feedback inbox is full")
            target.pop(next(iter(target)))
        target[item_id] = data
