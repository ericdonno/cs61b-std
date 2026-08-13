"""Validated runtime configuration without provider credentials."""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class RuntimeConfig:
    brain: str = "deterministic"
    checkpoint_db: str | None = None
    runtime_trace: str | None = None
    decision_timeout_seconds: float = 8.0
    max_tool_batches: int = 4
    max_tool_calls: int = 8
    max_model_calls_per_decision: int = 5
    max_concurrent_model_calls: int = 2
    max_queued_model_calls: int = 8
    max_model_calls_per_encounter: int = 64
    max_tokens_per_encounter: int = 100_000
    max_output_tokens: int = 512

    def __post_init__(self) -> None:
        if self.brain not in {"deterministic", "scripted", "model"}:
            raise ValueError("unsupported brain")
        for value in (
            self.decision_timeout_seconds, self.max_tool_batches,
            self.max_tool_calls, self.max_model_calls_per_decision,
            self.max_concurrent_model_calls, self.max_queued_model_calls,
            self.max_model_calls_per_encounter, self.max_tokens_per_encounter,
            self.max_output_tokens,
        ):
            if value <= 0:
                raise ValueError("runtime limits must be positive")
        if self.brain == "model":
            raise ValueError(
                "model provider is not configured; add the Builder's adapter first"
            )
