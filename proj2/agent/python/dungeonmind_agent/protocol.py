"""Strict NDJSON codec shared by the Python runtime and its tests."""

from __future__ import annotations

import json
import math
from collections.abc import Iterable, Mapping
from typing import Any


# Compatibility value locked by the existing Java wire contract.
ENVELOPE_VERSION = "phase2.session.v1"
OBSERVATION_VERSION = "private-observation.v1"
INTENT_VERSION = "strategic-intent.v1"

DEFAULT_MAX_FRAME_BYTES = 65_536
DEFAULT_MAX_DEPTH = 16

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
WORLD_EVENT_TYPES = frozenset(
    {
        "PLAN_BLOCKED",
        "PLAN_EXHAUSTED",
        "PLAYER_SPOTTED",
        "REFLEX_OVERRIDE_STARTED",
        "REFLEX_OVERRIDE_ENDED",
    }
)
DECISION_SOURCES = frozenset({"REMOTE_AGENT", "LOCAL_FALLBACK"})

_ENVELOPE_FIELDS = (
    "schemaVersion",
    "messageId",
    "messageSeq",
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
    self_data = _object(data["self"], "observation.self")
    _exact_fields(self_data, ("position", "hp"), "observation.self")
    return {
        "observationVersion": observation_version,
        "decisionId": _string(data, "decisionId"),
        "observationSeq": _integer(data, "observationSeq", bits=64),
        "requestGeneration": _integer(
            data, "requestGeneration", bits=64
        ),
        "observedAtTurn": _integer(data, "observedAtTurn", bits=64),
        "self": {
            "position": _position(self_data["position"], "self.position"),
            "hp": _integer(self_data, "hp", bits=32),
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
    required = (
        "intentVersion",
        "skill",
        "parameters",
        "confidence",
        "validForTicks",
    )
    allowed = required + ("interruptPolicy",)
    _allowed_fields(intent, allowed, "intent")
    _require_fields(intent, required, "intent")
    intent_version = _string(intent, "intentVersion")
    if intent_version != INTENT_VERSION:
        raise ProtocolViolation(
            "UNKNOWN_PAYLOAD_VERSION", f"intentVersion: {intent_version}"
        )
    skill = _string(intent, "skill")
    if skill not in SKILLS:
        raise ProtocolViolation("UNKNOWN_SKILL", f"skill: {skill}")
    parameters = _object(intent["parameters"], "parameters")
    _allowed_fields(parameters, ("targetPosition",), "parameters")
    if skill != "PATROL" and "targetPosition" not in parameters:
        raise ProtocolViolation(
            "MISSING_REQUIRED", f"targetPosition for skill={skill}"
        )
    normalized_parameters: dict[str, Any] = {}
    if "targetPosition" in parameters:
        normalized_parameters["targetPosition"] = _position(
            parameters["targetPosition"], "parameters.targetPosition"
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
    policy_value = intent.get("interruptPolicy")
    policy = (
        None
        if policy_value is None
        else _interrupt_policy(policy_value)
    )
    return {
        "intentVersion": intent_version,
        "skill": skill,
        "parameters": normalized_parameters,
        "confidence": confidence,
        "validForTicks": valid_for_ticks,
        "interruptPolicy": policy,
    }


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
        "decisionId",
        "actionIndex",
        "actionType",
        "result",
        "beforePosition",
        "afterPosition",
        "selfHp",
        "decisionSource",
        "overrideReason",
    )
    _exact_fields(data, fields, "action_feedback")
    decision_source = _string(data, "decisionSource")
    if decision_source not in DECISION_SOURCES:
        raise ProtocolViolation(
            "SCHEMA_MISMATCH", f"decisionSource: {decision_source}"
        )
    return {
        "decisionId": _string(data, "decisionId"),
        "actionIndex": _integer(data, "actionIndex", bits=32),
        "actionType": _string(data, "actionType"),
        "result": _string(data, "result"),
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
        "eventType",
        "logicalTick",
        "relatedPosition",
        "relatedEntityId",
    )
    _exact_fields(data, fields, "world_event")
    event_type = _string(data, "eventType")
    if event_type not in WORLD_EVENT_TYPES:
        raise ProtocolViolation(
            "UNKNOWN_EVENT_TYPE", f"eventType: {event_type}"
        )
    position_value = data["relatedPosition"]
    return {
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
        result.append(
            {
                "x": _integer(tile, "x", bits=32),
                "y": _integer(tile, "y", bits=32),
                "type": _string(tile, "type"),
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
