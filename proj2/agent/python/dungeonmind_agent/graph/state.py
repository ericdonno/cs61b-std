"""Typed, bounded state shared by graph nodes."""

from __future__ import annotations

import base64
import json
from typing import Any, TypedDict

from pydantic import BaseModel, ConfigDict, Field, field_validator


class AgentKey(BaseModel):
    """Stable world identity used only for checkpoint isolation."""

    model_config = ConfigDict(frozen=True)

    world_id: str
    floor_id: int
    agent_id: str

    def thread_id(self) -> str:
        payload = json.dumps(
            [self.world_id, self.floor_id, self.agent_id],
            ensure_ascii=False,
            separators=(",", ":"),
        ).encode("utf-8")
        return base64.urlsafe_b64encode(payload).decode("ascii").rstrip("=")


class InterruptPolicyModel(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="forbid")

    engage_visible_player: bool = Field(alias="engageVisiblePlayer")
    respond_to_adjacent_threat: bool = Field(alias="respondToAdjacentThreat")
    allow_local_reroute: bool = Field(alias="allowLocalReroute")

    @field_validator("respond_to_adjacent_threat")
    @classmethod
    def adjacent_threat_must_remain_enabled(cls, value: bool) -> bool:
        if not value:
            raise ValueError("respondToAdjacentThreat must be true")
        return value


class PlanMetadataModel(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="forbid")

    plan_id: str = Field(alias="planId", min_length=1, max_length=1_024)
    step_id: str = Field(alias="stepId", min_length=1, max_length=1_024)
    revision: int = Field(ge=0)


class StrategicIntentModel(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="forbid")

    intent_version: str = Field(alias="intentVersion", pattern=r"^strategic-intent\.v2$")
    skill: str = Field(pattern=r"^[A-Z][A-Z0-9_]{0,63}$")
    parameters: dict[str, Any]
    confidence: float = Field(ge=0.0, le=1.0, allow_inf_nan=False)
    valid_for_ticks: int = Field(alias="validForTicks", ge=1, le=60)
    interrupt_policy: InterruptPolicyModel = Field(alias="interruptPolicy")
    plan_metadata: PlanMetadataModel = Field(alias="planMetadata")

    def wire_dict(self) -> dict[str, Any]:
        return self.model_dump(by_alias=True, mode="json")


class AgentGraphState(TypedDict, total=False):
    agent_key: dict[str, Any]
    run_id: str
    session_epoch: int
    decision_id: str
    observation_seq: int
    request_generation: int
    observation: dict[str, Any]
    previous_intent: dict[str, Any] | None
    recent_feedback: dict[str, Any] | None
    decision_count: int
    messages: list[dict[str, Any]]
    tool_batches: int
    tool_calls: int
    model_calls: int
    evidence_tool_used: bool
    pending_tool_calls: list[dict[str, Any]]
    candidate_intent: dict[str, Any] | None
    deadline_ns: int
    rejection_code: str | None
