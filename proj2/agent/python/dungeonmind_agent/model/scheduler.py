"""Global concurrency, queue and encounter-budget enforcement."""

from __future__ import annotations

import threading
import time
from dataclasses import dataclass
from typing import Callable

from .adapter import ModelCallError, ModelErrorCode, ModelResponse


@dataclass(frozen=True)
class ModelCallRequest:
    encounter_key: tuple[str, int]
    decision_id: str
    deadline_ns: int
    estimated_input_tokens: int
    max_output_tokens: int
    cancellation: threading.Event
    invoke: Callable[[], ModelResponse]


@dataclass
class _Budget:
    calls: int = 0
    tokens: int = 0


class InferenceScheduler:
    """Schedules calls without storing prompts or graph state."""

    def __init__(self, *, max_concurrent: int = 2, max_queued: int = 8,
                 max_calls_per_encounter: int = 64,
                 max_tokens_per_encounter: int = 100_000) -> None:
        for value in (max_concurrent, max_queued, max_calls_per_encounter,
                      max_tokens_per_encounter):
            if value <= 0:
                raise ValueError("scheduler limits must be positive")
        self._semaphore = threading.BoundedSemaphore(max_concurrent)
        self._max_queued = max_queued
        self._max_calls = max_calls_per_encounter
        self._max_tokens = max_tokens_per_encounter
        self._lock = threading.Lock()
        self._budgets: dict[tuple[str, int], _Budget] = {}
        self._queued = 0
        self._active = 0
        self.peak_queued = 0
        self.peak_active = 0
        self._closed = False

    def call(self, request: ModelCallRequest) -> ModelResponse:
        reservation = request.estimated_input_tokens + request.max_output_tokens
        with self._lock:
            if self._closed:
                raise ModelCallError(ModelErrorCode.CANCELLED, "scheduler closed")
            if self._queued >= self._max_queued:
                raise ModelCallError(ModelErrorCode.PERMANENT, "QUEUE_FULL")
            budget = self._budgets.setdefault(request.encounter_key, _Budget())
            if budget.calls >= self._max_calls:
                raise ModelCallError(ModelErrorCode.PERMANENT, "CALL_BUDGET_EXHAUSTED")
            if budget.tokens + reservation > self._max_tokens:
                raise ModelCallError(ModelErrorCode.PERMANENT, "TOKEN_BUDGET_EXHAUSTED")
            budget.calls += 1
            budget.tokens += reservation
            self._queued += 1
            self.peak_queued = max(self.peak_queued, self._queued)

        acquired = False
        invoked = False
        try:
            while not acquired:
                if request.cancellation.is_set():
                    raise ModelCallError(ModelErrorCode.CANCELLED)
                remaining = (request.deadline_ns - time.monotonic_ns()) / 1e9
                if remaining <= 0:
                    raise ModelCallError(ModelErrorCode.TIMEOUT)
                acquired = self._semaphore.acquire(timeout=min(remaining, 0.05))
            with self._lock:
                self._queued -= 1
                self._active += 1
                self.peak_active = max(self.peak_active, self._active)
            if request.cancellation.is_set():
                raise ModelCallError(ModelErrorCode.CANCELLED)
            invoked = True
            response = request.invoke()
            usage = response.usage
            if usage is not None and not usage.estimated:
                actual = usage.input_tokens + usage.output_tokens
                with self._lock:
                    budget = self._budgets[request.encounter_key]
                    budget.tokens = max(0, budget.tokens - reservation + actual)
            return response
        finally:
            if not invoked:
                with self._lock:
                    budget = self._budgets[request.encounter_key]
                    budget.calls -= 1
                    budget.tokens -= reservation
            if acquired:
                with self._lock:
                    self._active -= 1
                self._semaphore.release()
            else:
                with self._lock:
                    self._queued -= 1

    def budget(self, encounter_key: tuple[str, int]) -> tuple[int, int]:
        with self._lock:
            value = self._budgets.get(encounter_key, _Budget())
            return value.calls, value.tokens

    def close(self) -> None:
        with self._lock:
            self._closed = True
