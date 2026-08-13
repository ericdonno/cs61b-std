"""Per-connection brain composition with shared bounded services."""

from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor

from .base import ResponseEmitter, RuntimeBrain
from .deterministic import DeterministicBrain
from .graph_agent import GraphAgentBrain
from ..graph.workflow import AgentWorkflow


class BrainFactory:
    def __init__(self, brain: str, workflow: AgentWorkflow | None = None,
                 executor: ThreadPoolExecutor | None = None) -> None:
        self._brain = brain
        self._workflow = workflow
        self._executor = executor

    def create(self, emitter: ResponseEmitter) -> RuntimeBrain:
        if self._brain == "deterministic":
            return DeterministicBrain(emitter)
        if self._brain == "scripted" and self._workflow is not None \
                and self._executor is not None:
            return GraphAgentBrain(self._workflow, emitter, self._executor)
        raise ValueError("model provider is not configured")
