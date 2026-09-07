"""Strict NDJSON codec shared by the Python runtime and its tests."""

from __future__ import annotations

import json
import math
from collections.abc import Iterable, Mapping
from typing import Any


# Compatibility value locked by the existing Java wire contract.
ENVELOPE_VERSION = "agent-session.v1"
OBSERVATION_VERSION = "private-observation.v3"
INTENT_VERSION = "strategic-intent.v2"
FEEDBACK_VERSION = "action-feedback.v2"
EVENT_VERSION = "agent-event.v1"

DEFAULT_MAX_FRAME_BYTES = 65_536
DEFAULT_MAX_DEPTH = 16
MAX_PARAMETER_DEPTH = 8
MAX_OBJECT_KEYS = 64
MAX_ARRAY_ITEMS = 64
MAX_STRING_CHARS = 1_024

MESSAGE_TYPES = frozenset(
    {
        "observation",
        "action_feedback",
        "world_event",
        "heartbeat",
        "cancel_request",
        "submit_intent",
        "cancel_ack",
        "protocol_error",
    }
)
SKILLS = frozenset({"PATROL", "CHASE", "ATTACK", "GUARD"})
FACINGS = frozenset({"NORTH", "EAST", "SOUTH", "WEST"})
VISION_MODES = frozenset({"DIRECTIONAL", "OMNIDIRECTIONAL"})
WORLD_EVENT_TYPES = frozenset(
    {
        "PLAYER_SPOTTED",
        "SOUND_HEARD",
        "MESSAGE_RECEIVED",
        "STEP_SUCCEEDED",
        "STEP_FAILED",
        "PLAN_COMPLETED",
        "PLAN_CANCELLED",
        "REFLEX_OVERRIDE_STARTED",
        "REFLEX_OVERRIDE_ENDED",
    }
)
DECISION_SOURCES = frozenset({"REMOTE_AGENT", "LOCAL_FALLBACK"})
STEP_STATUSES = frozenset({
    "UNTRACKED", "ACTIVE", "SUCCEEDED", "FAILED", "PAUSED", "CANCELLED",
})
PLAN_STATUSES = frozenset({
    "UNTRACKED", "ACTIVE", "PAUSED", "COMPLETED", "REPLAN_REQUIRED",
    "CANCELLED",
})
OUTCOME_REASONS = frozenset({
    "NONE", "ACTION_COMMITTED", "DAMAGE_COMMITTED",
    "OCCUPIED_OR_TERRAIN_BLOCKED", "LOCAL_REROUTE", "REPEATED_BLOCKED",
    "TARGET_LOST", "PRECONDITION_CHANGED", "COMMITMENT_COMPLETED",
    "REFLEX_OVERRIDE_STARTED", "REFLEX_OVERRIDE_ENDED", "LEASE_INVALIDATED",
    "PLAN_COMPLETED", "CANCELLED",
})
TILE_TYPES = frozenset(
    {
        "FLOOR", "WALL", "STAIRS", "NOTHING", "GRASS", "WATER",
        "FLOWER", "LOCKED_DOOR", "UNLOCKED_DOOR", "SAND", "MOUNTAIN",
        "TREE", "UNKNOWN",
    }
)

_ENVELOPE_FIELDS = (
    "schemaVersion",
    "messageId",
    "messageSeq",
    "worldId",
    "runId",
    "floorId",
    "agentId",
    "sessionEpoch",
    "logicalTick",
    "type",
    "data",
)
_INT32_MIN = -(2**31)
_INT32_MAX = 2**31 - 1
_INT64_MIN = -(2**63)
_INT64_MAX = 2**63 - 1


class ProtocolViolation(ValueError):
    """Describes one syntax, framing, or schema rejection."""

    def __init__(self, reason: str, detail: str):
        super().__init__(f"{reason}: {detail}")
        self.reason = reason
        self.detail = detail


def decode_frame(
    frame: bytes | str,
    *,
    max_frame_bytes: int = DEFAULT_MAX_FRAME_BYTES,
    max_depth: int = DEFAULT_MAX_DEPTH,
) -> dict[str, Any]:
    """Decodes and validates one JSON frame without its NDJSON delimiter."""
    if max_frame_bytes < 1:
        raise ValueError("max_frame_bytes must be positive")
    if max_depth < 1:
        raise ValueError("max_depth must be positive")
    if isinstance(frame, bytes):
        if len(frame) > max_frame_bytes:
            raise ProtocolViolation(
                "FRAME_TOO_LARGE",
                f"frame exceeds {max_frame_bytes} UTF-8 bytes",
            )
        try:
            text = frame.decode("utf-8", errors="strict")
        except UnicodeDecodeError as exception:
            raise ProtocolViolation(
                "JSON_SYNTAX", "frame is not valid UTF-8"
            ) from exception
    elif isinstance(frame, str):
        if len(frame.encode("utf-8")) > max_frame_bytes:
            raise ProtocolViolation(
                "FRAME_TOO_LARGE",
                f"frame exceeds {max_frame_bytes} UTF-8 bytes",
            )
        text = frame
    else:
        raise TypeError("frame must be bytes or str")

    try:
        parsed = json.loads(
            text,
            object_pairs_hook=_reject_duplicate_keys,
            parse_constant=_reject_non_finite_literal,
        )
    except ProtocolViolation:
        raise
    except (json.JSONDecodeError, UnicodeError) as exception:
        raise ProtocolViolation("JSON_SYNTAX", str(exception)) from exception

    if _nesting_depth(parsed) > max_depth:
        raise ProtocolViolation(
            "DEPTH_EXCEEDED", f"JSON nesting exceeds {max_depth}"
        )
    return validate_envelope(parsed)


def encode_frame(
    envelope: Mapping[str, Any],
    *,
    max_frame_bytes: int = DEFAULT_MAX_FRAME_BYTES,
) -> bytes:
    """Validates and encodes one complete UTF-8 NDJSON frame."""
    normalized = validate_envelope(envelope)
    try:
        text = json.dumps(
            normalized,
            ensure_ascii=False,
            allow_nan=False,
            separators=(",", ":"),
        )
    except (TypeError, ValueError) as exception:
        raise ProtocolViolation("SCHEMA_MISMATCH", str(exception)) from exception
    encoded = text.encode("utf-8")
    if len(encoded) > max_frame_bytes:
        raise ProtocolViolation(
            "FRAME_TOO_LARGE",
            f"frame exceeds {max_frame_bytes} UTF-8 bytes",
        )
    return encoded + b"\n"


def validate_envelope(value: Any) -> dict[str, Any]:
    """Returns a field-ordered envelope after strict schema validation."""
    envelope = _object(value, "envelope")
    _exact_fields(envelope, _ENVELOPE_FIELDS, "envelope")
    schema_version = _string(envelope, "schemaVersion")
    if schema_version != ENVELOPE_VERSION:
        raise ProtocolViolation(
            "SCHEMA_MISMATCH", f"schemaVersion: {schema_version}"
        )
    message_type = _string(envelope, "type")
    if message_type not in MESSAGE_TYPES:
        raise ProtocolViolation(
            "UNKNOWN_MESSAGE_TYPE", f"type: {message_type}"
        )

    normalized = {
        "schemaVersion": schema_version,
        "messageId": _string(envelope, "messageId"),
        "messageSeq": _integer(envelope, "messageSeq", bits=64),
        "worldId": _string(envelope, "worldId"),
        "runId": _string(envelope, "runId"),
        "floorId": _integer(envelope, "floorId", bits=32),
        "agentId": _string(envelope, "agentId"),
        "sessionEpoch": _integer(envelope, "sessionEpoch", bits=64),
        "logicalTick": _integer(envelope, "logicalTick", bits=64),
        "type": message_type,
        "data": _validate_data(message_type, envelope["data"]),
    }
    return normalized


def _validate_data(message_type: str, value: Any) -> dict[str, Any]:
    validators = {
        "observation": _observation,
        "submit_intent": _submit_intent,
        "action_feedback": _action_feedback,
        "cancel_request": _cancel_request,
        "cancel_ack": _cancel_ack,
        "world_event": _world_event,
        "heartbeat": _heartbeat,
        "protocol_error": _protocol_error,
    }
    return validators[message_type](value)


def _observation(value: Any) -> dict[str, Any]:
    data = _object(value, "observation")
    fields = (
        "observationVersion",
        "decisionId",
        "observationSeq",
        "requestGeneration",
        "observedAtTurn",
        "visionMode",
        "self",
        "visibleTiles",
        "visibleEntities",
        "heardEvents",
        "pendingEvents",
        "capabilities",
    )
    _exact_fields(data, fields, "observation")
    observation_version = _string(data, "observationVersion")
    if observation_version != OBSERVATION_VERSION:
        raise ProtocolViolation(
            "UNKNOWN_PAYLOAD_VERSION",
            f"observationVersion: {observation_version}",
        )
    vision_mode = _string(data, "visionMode")
    if vision_mode not in VISION_MODES:
        raise ProtocolViolation(
            "UNKNOWN_FIELD", f"visionMode: {vision_mode}"
        )
    self_data = _object(data["self"], "observation.self")
    _exact_fields(
        self_data, ("position", "hp", "maxHp", "facing"),
        "observation.self",
    )
    facing = _string(self_data, "facing")
    if facing not in FACINGS:
        raise ProtocolViolation(
            "UNKNOWN_FIELD", f"self.facing: {facing}"
        )
    return {
        "observationVersion": observation_version,
        "decisionId": _string(data, "decisionId"),
        "observationSeq": _integer(data, "observationSeq", bits=64),
        "requestGeneration": _integer(
            data, "requestGeneration", bits=64
        ),
        "observedAtTurn": _integer(data, "observedAtTurn", bits=64),
        "visionMode": vision_mode,
        "self": {
            "position": _position(self_data["position"], "self.position"),
            "hp": _integer(self_data, "hp", bits=32),
            "maxHp": _integer(self_data, "maxHp", bits=32),
            "facing": facing,
        },
        "visibleTiles": _visible_tiles(data["visibleTiles"]),
        "visibleEntities": _visible_entities(data["visibleEntities"]),
        "heardEvents": _heard_events(data["heardEvents"]),
        "pendingEvents": _world_events(data["pendingEvents"]),
        "capabilities": _capabilities(data["capabilities"]),
    }


def _submit_intent(value: Any) -> dict[str, Any]:
    data = _object(value, "submit_intent")
    fields = (
        "decisionId",
        "observationSeq",
        "requestGeneration",
        "intent",
    )
    _exact_fields(data, fields, "submit_intent")
    return {
        "decisionId": _string(data, "decisionId"),
        "observationSeq": _integer(data, "observationSeq", bits=64),
        "requestGeneration": _integer(
            data, "requestGeneration", bits=64
        ),
        "intent": _intent(data["intent"]),
    }


def _intent(value: Any) -> dict[str, Any]:
    intent = _object(value, "intent")
    fields = (
        "intentVersion",
        "skill",
        "parameters",
        "confidence",
        "validForTicks",
        "interruptPolicy",
        "planMetadata",
    )
    _exact_fields(intent, fields, "intent")
    intent_version = _string(intent, "intentVersion")
    if intent_version != INTENT_VERSION:
        raise ProtocolViolation(
            "UNKNOWN_PAYLOAD_VERSION", f"intentVersion: {intent_version}"
        )
    skill = _string(intent, "skill")
    if not _valid_skill_id(skill):
        raise ProtocolViolation("UNKNOWN_SKILL", f"skill: {skill}")
    parameters = _bounded_json(
        _object(intent["parameters"], "parameters"),
        "parameters",
        depth=0,
    )

    confidence = _number(intent, "confidence")
    if not 0.0 <= confidence <= 1.0:
        raise ProtocolViolation(
            "OUT_OF_RANGE", f"confidence: {confidence}"
        )
    valid_for_ticks = _integer(intent, "validForTicks", bits=32)
    if not 1 <= valid_for_ticks <= 60:
        raise ProtocolViolation(
            "OUT_OF_RANGE", f"validForTicks: {valid_for_ticks}"
        )
    policy = _interrupt_policy(intent["interruptPolicy"])
    if not policy["respondToAdjacentThreat"]:
        raise ProtocolViolation(
            "OUT_OF_RANGE", "respondToAdjacentThreat must be true"
        )
    return {
        "intentVersion": intent_version,
        "skill": skill,
        "parameters": parameters,
        "confidence": confidence,
        "validForTicks": valid_for_ticks,
        "interruptPolicy": policy,
        "planMetadata": _plan_metadata(intent["planMetadata"]),
    }


def _plan_metadata(value: Any) -> dict[str, Any]:
    metadata = _object(value, "planMetadata")
    _exact_fields(metadata, ("planId", "stepId", "revision"), "planMetadata")
    plan_id = _string(metadata, "planId")
    step_id = _string(metadata, "stepId")
    if not plan_id or len(plan_id) > MAX_STRING_CHARS:
        raise ProtocolViolation("OUT_OF_RANGE", "planMetadata.planId")
    if not step_id or len(step_id) > MAX_STRING_CHARS:
        raise ProtocolViolation("OUT_OF_RANGE", "planMetadata.stepId")
    revision = _integer(metadata, "revision", bits=32)
    if revision < 0:
        raise ProtocolViolation("OUT_OF_RANGE", "planMetadata.revision")
    return {"planId": plan_id, "stepId": step_id, "revision": revision}


def _valid_skill_id(skill: str) -> bool:
    return (
        1 <= len(skill) <= 64
        and skill.isascii()
        and "A" <= skill[0] <= "Z"
        and all(character == "_" or character.isdigit()
                or "A" <= character <= "Z" for character in skill)
    )


def _bounded_json(value: Any, context: str, *, depth: int) -> Any:
    if depth > MAX_PARAMETER_DEPTH:
        raise ProtocolViolation("DEPTH_EXCEEDED", context)
    if value is None or isinstance(value, bool):
        return value
    if isinstance(value, int):
        if not _INT64_MIN <= value <= _INT64_MAX:
            raise ProtocolViolation("OUT_OF_RANGE", context)
        return value
    if isinstance(value, float):
        if not math.isfinite(value):
            raise ProtocolViolation("JSON_SYNTAX", context)
        return value
    if isinstance(value, str):
        if len(value) > MAX_STRING_CHARS:
            raise ProtocolViolation("OUT_OF_RANGE", context)
        return value
    if isinstance(value, list):
        if len(value) > MAX_ARRAY_ITEMS:
            raise ProtocolViolation("OUT_OF_RANGE", context)
        return [
            _bounded_json(item, f"{context}[{index}]", depth=depth + 1)
            for index, item in enumerate(value)
        ]
    if isinstance(value, dict):
        if len(value) > MAX_OBJECT_KEYS:
            raise ProtocolViolation("OUT_OF_RANGE", context)
        normalized: dict[str, Any] = {}
        for key, item in value.items():
            if not isinstance(key, str) or not key or len(key) > MAX_STRING_CHARS:
                raise ProtocolViolation("TYPE_MISMATCH", f"{context} key")
            normalized[key] = _bounded_json(
                item, f"{context}.{key}", depth=depth + 1
            )
        return normalized
    raise ProtocolViolation("TYPE_MISMATCH", context)


def _interrupt_policy(value: Any) -> dict[str, bool]:
    policy = _object(value, "interruptPolicy")
    fields = (
        "engageVisiblePlayer",
        "respondToAdjacentThreat",
        "allowLocalReroute",
    )
    _exact_fields(policy, fields, "interruptPolicy")
    return {
        "engageVisiblePlayer": _boolean(
            policy, "engageVisiblePlayer"
        ),
        "respondToAdjacentThreat": _boolean(
            policy, "respondToAdjacentThreat"
        ),
        "allowLocalReroute": _boolean(policy, "allowLocalReroute"),
    }


def _action_feedback(value: Any) -> dict[str, Any]:
    data = _object(value, "action_feedback")
    fields = (
        "feedbackVersion", "feedbackId", "decisionId",
        "planId", "stepId", "planRevision",
        "actionIndex",
        "actionType",
        "result",
        "reasonCode", "stepStatus", "planStatus",
        "beforePosition",
        "afterPosition",
        "selfHp",
        "decisionSource",
        "overrideReason",
    )
    _exact_fields(data, fields, "action_feedback")
    feedback_version = _string(data, "feedbackVersion")
    if feedback_version != FEEDBACK_VERSION:
        raise ProtocolViolation(
            "UNKNOWN_PAYLOAD_VERSION", f"feedbackVersion: {feedback_version}"
        )
    decision_source = _string(data, "decisionSource")
    if decision_source not in DECISION_SOURCES:
        raise ProtocolViolation(
            "SCHEMA_MISMATCH", f"decisionSource: {decision_source}"
        )
    reason_code = _string(data, "reasonCode")
    step_status = _string(data, "stepStatus")
    plan_status = _string(data, "planStatus")
    if reason_code not in OUTCOME_REASONS:
        raise ProtocolViolation("SCHEMA_MISMATCH", f"reasonCode: {reason_code}")
    if step_status not in STEP_STATUSES:
        raise ProtocolViolation("SCHEMA_MISMATCH", f"stepStatus: {step_status}")
    if plan_status not in PLAN_STATUSES:
        raise ProtocolViolation("SCHEMA_MISMATCH", f"planStatus: {plan_status}")
    plan_id = _nullable_string(data, "planId")
    step_id = _nullable_string(data, "stepId")
    revision = _nullable_integer(data, "planRevision", bits=32)
    if any(item is not None for item in (plan_id, step_id, revision)) and any(
        item is None for item in (plan_id, step_id, revision)
    ):
        raise ProtocolViolation("SCHEMA_MISMATCH", "partial plan metadata")
    has_plan = plan_id is not None
    if decision_source == "REMOTE_AGENT" and not has_plan:
        raise ProtocolViolation(
            "SCHEMA_MISMATCH", "remote feedback requires plan metadata"
        )
    if decision_source == "LOCAL_FALLBACK" and has_plan:
        raise ProtocolViolation(
            "SCHEMA_MISMATCH", "local feedback cannot carry plan metadata"
        )
    return {
        "feedbackVersion": feedback_version,
        "feedbackId": _string(data, "feedbackId"),
        "decisionId": _string(data, "decisionId"),
        "planId": plan_id,
        "stepId": step_id,
        "planRevision": revision,
        "actionIndex": _integer(data, "actionIndex", bits=32),
        "actionType": _string(data, "actionType"),
        "result": _string(data, "result"),
        "reasonCode": reason_code,
        "stepStatus": step_status,
        "planStatus": plan_status,
        "beforePosition": _position(
            data["beforePosition"], "beforePosition"
        ),
        "afterPosition": _position(
            data["afterPosition"], "afterPosition"
        ),
        "selfHp": _integer(data, "selfHp", bits=32),
        "decisionSource": decision_source,
        "overrideReason": _nullable_string(data, "overrideReason"),
    }


def _cancel_request(value: Any) -> dict[str, Any]:
    data = _object(value, "cancel_request")
    fields = ("decisionId", "requestGeneration", "reason")
    _exact_fields(data, fields, "cancel_request")
    return {
        "decisionId": _string(data, "decisionId"),
        "requestGeneration": _integer(
            data, "requestGeneration", bits=64
        ),
        "reason": _string(data, "reason"),
    }


def _cancel_ack(value: Any) -> dict[str, Any]:
    data = _object(value, "cancel_ack")
    fields = ("decisionId", "requestGeneration")
    _exact_fields(data, fields, "cancel_ack")
    return {
        "decisionId": _string(data, "decisionId"),
        "requestGeneration": _integer(
            data, "requestGeneration", bits=64
        ),
    }


def _world_event(value: Any) -> dict[str, Any]:
    data = _object(value, "world_event")
    fields = (
        "eventVersion", "eventId", "eventType",
        "logicalTick",
        "relatedPosition",
        "relatedEntityId",
        "decisionId", "planId", "stepId", "reasonCode",
    )
    _exact_fields(data, fields, "world_event")
    event_version = _string(data, "eventVersion")
    if event_version != EVENT_VERSION:
        raise ProtocolViolation(
            "UNKNOWN_PAYLOAD_VERSION", f"eventVersion: {event_version}"
        )
    event_type = _string(data, "eventType")
    if event_type not in WORLD_EVENT_TYPES:
        raise ProtocolViolation(
            "UNKNOWN_EVENT_TYPE", f"eventType: {event_type}"
        )
    position_value = data["relatedPosition"]
    reason_code = _nullable_string(data, "reasonCode")
    if reason_code is not None and reason_code not in OUTCOME_REASONS:
        raise ProtocolViolation("SCHEMA_MISMATCH", f"reasonCode: {reason_code}")
    return {
        "eventVersion": event_version,
        "eventId": _string(data, "eventId"),
        "eventType": event_type,
        "logicalTick": _integer(data, "logicalTick", bits=64),
        "relatedPosition": (
            None
            if position_value is None
            else _position(position_value, "relatedPosition")
        ),
        "relatedEntityId": _nullable_string(
            data, "relatedEntityId"
        ),
        "decisionId": _nullable_string(data, "decisionId"),
        "planId": _nullable_string(data, "planId"),
        "stepId": _nullable_string(data, "stepId"),
        "reasonCode": reason_code,
    }


def _heartbeat(value: Any) -> dict[str, Any]:
    data = _object(value, "heartbeat")
    _exact_fields(data, ("logicalTick",), "heartbeat")
    return {"logicalTick": _integer(data, "logicalTick", bits=64)}


def _protocol_error(value: Any) -> dict[str, Any]:
    data = _object(value, "protocol_error")
    fields = ("reason", "offendingType")
    _exact_fields(data, fields, "protocol_error")
    return {
        "reason": _string(data, "reason"),
        "offendingType": _string(data, "offendingType"),
    }


def _visible_tiles(value: Any) -> list[dict[str, Any]]:
    result = []
    for index, item in enumerate(_array(value, "visibleTiles")):
        tile = _object(item, f"visibleTiles[{index}]")
        fields = ("x", "y", "type", "walkable")
        _exact_fields(tile, fields, f"visibleTiles[{index}]")
        tile_type = _string(tile, "type")
        if tile_type not in TILE_TYPES:
            raise ProtocolViolation(
                "UNKNOWN_FIELD", f"visibleTiles[{index}].type: {tile_type}"
            )
        result.append(
            {
                "x": _integer(tile, "x", bits=32),
                "y": _integer(tile, "y", bits=32),
                "type": tile_type,
                "walkable": _boolean(tile, "walkable"),
            }
        )
    return result


def _visible_entities(value: Any) -> list[dict[str, Any]]:
    result = []
    for index, item in enumerate(_array(value, "visibleEntities")):
        entity = _object(item, f"visibleEntities[{index}]")
        fields = ("type", "position", "visibleHp", "agentId")
        _exact_fields(entity, fields, f"visibleEntities[{index}]")
        result.append(
            {
                "type": _string(entity, "type"),
                "position": _position(
                    entity["position"],
                    f"visibleEntities[{index}].position",
                ),
                "visibleHp": _integer(entity, "visibleHp", bits=32),
                "agentId": _nullable_string(entity, "agentId"),
            }
        )
    return result


def _heard_events(value: Any) -> list[dict[str, Any]]:
    result = []
    for index, item in enumerate(_array(value, "heardEvents")):
        event = _object(item, f"heardEvents[{index}]")
        fields = ("soundType", "sourcePosition", "turn")
        _exact_fields(event, fields, f"heardEvents[{index}]")
        result.append(
            {
                "soundType": _string(event, "soundType"),
                "sourcePosition": _position(
                    event["sourcePosition"],
                    f"heardEvents[{index}].sourcePosition",
                ),
                "turn": _integer(event, "turn", bits=64),
            }
        )
    return result


def _world_events(value: Any) -> list[dict[str, Any]]:
    return [_world_event(item) for item in _array(value, "pendingEvents")]


def _capabilities(value: Any) -> dict[str, Any]:
    capabilities = _object(value, "capabilities")
    fields = (
        "supportedSkills",
        "sightRange",
        "attackDamage",
        "moveInterval",
    )
    _exact_fields(capabilities, fields, "capabilities")
    supported_skills = []
    for index, skill in enumerate(
        _array(capabilities["supportedSkills"], "supportedSkills")
    ):
        if not isinstance(skill, str):
            raise ProtocolViolation(
                "TYPE_MISMATCH",
                f"supportedSkills[{index}] must be string",
            )
        if skill not in SKILLS:
            raise ProtocolViolation(
                "UNKNOWN_SKILL", f"supportedSkills[{index}]: {skill}"
            )
        supported_skills.append(skill)
    return {
        "supportedSkills": supported_skills,
        "sightRange": _integer(capabilities, "sightRange", bits=32),
        "attackDamage": _integer(
            capabilities, "attackDamage", bits=32
        ),
        "moveInterval": _integer(
            capabilities, "moveInterval", bits=32
        ),
    }


def _position(value: Any, context: str) -> dict[str, int]:
    position = _object(value, context)
    _exact_fields(position, ("x", "y"), context)
    return {
        "x": _integer(position, "x", bits=32),
        "y": _integer(position, "y", bits=32),
    }


def _object(value: Any, context: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ProtocolViolation(
            "TYPE_MISMATCH", f"{context} must be object"
        )
    return value


def _array(value: Any, context: str) -> list[Any]:
    if not isinstance(value, list):
        raise ProtocolViolation(
            "TYPE_MISMATCH", f"{context} must be array"
        )
    return value


def _exact_fields(
    value: Mapping[str, Any],
    fields: Iterable[str],
    context: str,
) -> None:
    field_tuple = tuple(fields)
    _allowed_fields(value, field_tuple, context)
    _require_fields(value, field_tuple, context)


def _allowed_fields(
    value: Mapping[str, Any],
    fields: Iterable[str],
    context: str,
) -> None:
    allowed = frozenset(fields)
    unknown = [key for key in value if key not in allowed]
    if unknown:
        raise ProtocolViolation(
            "UNKNOWN_FIELD", f"{context}.{unknown[0]}"
        )


def _require_fields(
    value: Mapping[str, Any],
    fields: Iterable[str],
    context: str,
) -> None:
    for field in fields:
        if field not in value:
            raise ProtocolViolation(
                "MISSING_REQUIRED", f"{context}.{field}"
            )


def _string(value: Mapping[str, Any], key: str) -> str:
    item = value.get(key)
    if key not in value:
        raise ProtocolViolation("MISSING_REQUIRED", key)
    if not isinstance(item, str):
        raise ProtocolViolation("TYPE_MISMATCH", f"{key} must be string")
    return item


def _nullable_string(
    value: Mapping[str, Any], key: str
) -> str | None:
    if key not in value:
        raise ProtocolViolation("MISSING_REQUIRED", key)
    item = value[key]
    if item is not None and not isinstance(item, str):
        raise ProtocolViolation(
            "TYPE_MISMATCH", f"{key} must be string or null"
        )
    return item


def _integer(
    value: Mapping[str, Any], key: str, *, bits: int
) -> int:
    if key not in value:
        raise ProtocolViolation("MISSING_REQUIRED", key)
    item = value[key]
    if isinstance(item, bool) or not isinstance(item, int):
        raise ProtocolViolation("TYPE_MISMATCH", f"{key} must be integer")
    lower, upper = (
        (_INT32_MIN, _INT32_MAX)
        if bits == 32
        else (_INT64_MIN, _INT64_MAX)
    )
    if not lower <= item <= upper:
        raise ProtocolViolation(
            "OUT_OF_RANGE", f"{key} is outside signed {bits}-bit range"
        )
    return item


def _nullable_integer(
    value: Mapping[str, Any], key: str, *, bits: int
) -> int | None:
    if key not in value:
        raise ProtocolViolation("MISSING_REQUIRED", key)
    if value[key] is None:
        return None
    return _integer(value, key, bits=bits)


def _number(value: Mapping[str, Any], key: str) -> float:
    if key not in value:
        raise ProtocolViolation("MISSING_REQUIRED", key)
    item = value[key]
    if isinstance(item, bool) or not isinstance(item, (int, float)):
        raise ProtocolViolation("TYPE_MISMATCH", f"{key} must be number")
    number = float(item)
    if not math.isfinite(number):
        raise ProtocolViolation(
            "JSON_SYNTAX", f"{key} must be finite"
        )
    return number


def _boolean(value: Mapping[str, Any], key: str) -> bool:
    if key not in value:
        raise ProtocolViolation("MISSING_REQUIRED", key)
    item = value[key]
    if not isinstance(item, bool):
        raise ProtocolViolation("TYPE_MISMATCH", f"{key} must be boolean")
    return item


def _reject_duplicate_keys(
    pairs: list[tuple[str, Any]],
) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ProtocolViolation("DUPLICATE_KEY", key)
        result[key] = value
    return result


def _reject_non_finite_literal(literal: str) -> None:
    raise ProtocolViolation("JSON_SYNTAX", f"non-finite number: {literal}")


def _nesting_depth(value: Any) -> int:
    if isinstance(value, dict):
        return 1 + max(
            (_nesting_depth(child) for child in value.values()),
            default=0,
        )
    if isinstance(value, list):
        return 1 + max(
            (_nesting_depth(child) for child in value),
            default=0,
        )
    return 0
