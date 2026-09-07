"""Validated runtime configuration."""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from urllib.parse import urlsplit


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
    max_plan_steps: int = 6
    max_feedback_window: int = 32
    max_event_window: int = 32
    max_consumed_ids: int = 64
    max_plan_revisions: int = 32
    max_local_reroutes_per_step: int = 1
    provider_base_url: str | None = None
    provider_model: str | None = None
    provider_api_key: str | None = field(default=None, repr=False)
    provider_token_limit_field: str = "max_tokens"

    @classmethod
    def from_environment(cls, *, brain: str, checkpoint_db: str | None = None,
                         runtime_trace: str | None = None) -> "RuntimeConfig":
        return cls(
            brain=brain,
            checkpoint_db=checkpoint_db,
            runtime_trace=runtime_trace,
            provider_base_url=os.environ.get("DUNGEONMIND_API_BASE"),
            provider_model=os.environ.get("DUNGEONMIND_MODEL"),
            provider_api_key=os.environ.get("DUNGEONMIND_API_KEY"),
            provider_token_limit_field=os.environ.get(
                "DUNGEONMIND_TOKEN_LIMIT_FIELD", "max_tokens"
            ),
        )

    def __post_init__(self) -> None:
        if self.brain not in {"deterministic", "scripted", "model"}:
            raise ValueError("unsupported brain")
        for value in (
            self.decision_timeout_seconds, self.max_tool_batches,
            self.max_tool_calls, self.max_model_calls_per_decision,
            self.max_concurrent_model_calls, self.max_queued_model_calls,
            self.max_model_calls_per_encounter, self.max_tokens_per_encounter,
            self.max_output_tokens,
            self.max_plan_steps, self.max_feedback_window,
            self.max_event_window, self.max_consumed_ids,
            self.max_plan_revisions, self.max_local_reroutes_per_step,
        ):
            if value <= 0:
                raise ValueError("runtime limits must be positive")
        if self.brain == "model":
            required = {
                "DUNGEONMIND_API_BASE": self.provider_base_url,
                "DUNGEONMIND_MODEL": self.provider_model,
                "DUNGEONMIND_API_KEY": self.provider_api_key,
            }
            missing = [name for name, value in required.items()
                       if value is None or not value.strip()]
            if missing:
                raise ValueError(
                    "model provider configuration is incomplete: "
                    + ", ".join(missing)
                )
            parsed = urlsplit(self.provider_base_url)
            if parsed.scheme not in {"http", "https"} or not parsed.netloc:
                raise ValueError("DUNGEONMIND_API_BASE must be an HTTP(S) URL")
            if parsed.username or parsed.password or parsed.query or parsed.fragment:
                raise ValueError(
                    "DUNGEONMIND_API_BASE must not contain credentials, query, or fragment"
                )
            if self.provider_token_limit_field not in {
                "max_tokens", "max_completion_tokens"
            }:
                raise ValueError(
                    "DUNGEONMIND_TOKEN_LIMIT_FIELD must be max_tokens "
                    "or max_completion_tokens"
                )
