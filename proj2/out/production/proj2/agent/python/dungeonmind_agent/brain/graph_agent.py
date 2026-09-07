"""Asynchronous graph brain with correlated cancellation and late suppression."""

from __future__ import annotations

import threading
from concurrent.futures import Future, ThreadPoolExecutor
from typing import Any

from .base import ResponseEmitter
from ..graph.workflow import AgentWorkflow


class GraphAgentBrain:
    def __init__(self, workflow: AgentWorkflow, emitter: ResponseEmitter,
                 executor: ThreadPoolExecutor) -> None:
        self._workflow = workflow
        self._emitter = emitter
        self._executor = executor
        self._lock = threading.Lock()
        self._active: dict[str, tuple[threading.Event, Future[Any],
                                      dict[str, Any]]] = {}
        self._cancelled: set[str] = set()
        self._closed = False

    def on_message(self, envelope: dict[str, Any]) -> None:
        message_type = envelope["type"]
        if message_type == "observation":
            self._start_decision(envelope)
        elif message_type == "cancel_request":
            self._cancel(envelope)
        elif message_type == "action_feedback":
            self._workflow.record_feedback(envelope)

    def _start_decision(self, request: dict[str, Any]) -> None:
        decision_id = request["data"]["decisionId"]
        token = threading.Event()
        with self._lock:
            if self._closed:
                return
            for old_token, old_future, _ in self._active.values():
                old_token.set()
                old_future.cancel()
            self._active.clear()
            future = self._executor.submit(self._workflow.decide, request, token)
            self._active[decision_id] = (token, future, request)
        future.add_done_callback(
            lambda completed: self._complete(decision_id, completed)
        )

    def _complete(self, decision_id: str, future: Future[Any]) -> None:
        with self._lock:
            active = self._active.pop(decision_id, None)
            eligible = (active is not None and not self._closed
                        and decision_id not in self._cancelled)
        if not eligible or future.cancelled():
            return
        try:
            intent = future.result()
        except Exception:
            return
        if intent is None:
            return
        request = active[2]
        observation = request["data"]
        self._emitter.emit_response(request, "submit_intent", {
            "decisionId": decision_id,
            "observationSeq": observation["observationSeq"],
            "requestGeneration": observation["requestGeneration"],
            "intent": intent,
        })

    def _cancel(self, request: dict[str, Any]) -> None:
        decision_id = request["data"]["decisionId"]
        with self._lock:
            self._cancelled.add(decision_id)
            active = self._active.get(decision_id)
            if active is not None:
                active[0].set()
                active[1].cancel()
        self._emitter.emit_response(request, "cancel_ack", {
            "decisionId": decision_id,
            "requestGeneration": request["data"]["requestGeneration"],
        })

    def close(self) -> None:
        with self._lock:
            if self._closed:
                return
            self._closed = True
            for token, future, _ in self._active.values():
                token.set()
                future.cancel()
            self._active.clear()
