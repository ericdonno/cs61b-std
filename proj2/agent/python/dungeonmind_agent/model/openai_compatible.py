"""OpenAI-compatible Chat Completions adapter using the standard library."""

from __future__ import annotations

import json
import socket
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit, urlunsplit
from urllib.request import Request, urlopen

from .adapter import (
    ModelAdapter, ModelCallError, ModelErrorCode, ModelInput, ModelResponse,
    ModelUsage, ToolCall,
)


_MAX_RESPONSE_BYTES = 2_000_000
_SYSTEM_PROMPT = """You control one DungeonMind enemy.
Use only the private observation and tool results in the user message. Never
invent hidden entities or tiles. Respond with function tool calls only.
First call at least one evidence tool. Then call submit_plan with 1 to 6 legal
steps. Prefer ATTACK for an adjacent visible player, CHASE for a farther visible
player, and PATROL/GUARD when no player is visible. targetPosition must come from
the visible observation. Keep validForTicks between 1 and 60 and always set
respondToAdjacentThreat to true."""

_POSITION_SCHEMA = {
    "type": "object",
    "properties": {"x": {"type": "integer"}, "y": {"type": "integer"}},
    "required": ["x", "y"],
    "additionalProperties": False,
}
_INTERRUPT_SCHEMA = {
    "type": "object",
    "properties": {
        "engageVisiblePlayer": {"type": "boolean"},
        "respondToAdjacentThreat": {"type": "boolean", "const": True},
        "allowLocalReroute": {"type": "boolean"},
    },
    "required": [
        "engageVisiblePlayer", "respondToAdjacentThreat", "allowLocalReroute"
    ],
    "additionalProperties": False,
}
_TOOLS = [
    {
        "type": "function",
        "function": {
            "name": "read_self",
            "description": "Read this enemy's visible position, health, and facing.",
            "parameters": {"type": "object", "properties": {},
                           "additionalProperties": False},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "list_visible_entities",
            "description": "List entities visible to this enemy.",
            "parameters": {
                "type": "object",
                "properties": {"type": {"type": "string"}},
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "inspect_visible_tile",
            "description": "Inspect one tile already present in the observation.",
            "parameters": {
                "type": "object",
                "properties": _POSITION_SCHEMA["properties"],
                "required": ["x", "y"],
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "check_skill_candidate",
            "description": "Check whether a proposed skill is supported.",
            "parameters": {
                "type": "object",
                "properties": {"skill": {
                    "type": "string",
                    "enum": ["PATROL", "CHASE", "ATTACK", "GUARD"],
                }},
                "required": ["skill"],
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "submit_plan",
            "description": "Submit the enemy's bounded strategic plan.",
            "parameters": {
                "type": "object",
                "properties": {"steps": {
                    "type": "array", "minItems": 1, "maxItems": 6,
                    "items": {
                        "type": "object",
                        "properties": {
                            "skill": {
                                "type": "string",
                                "enum": ["PATROL", "CHASE", "ATTACK", "GUARD"],
                            },
                            "parameters": {
                                "type": "object",
                                "properties": {"targetPosition": _POSITION_SCHEMA},
                                "additionalProperties": False,
                            },
                            "confidence": {"type": "number", "minimum": 0,
                                           "maximum": 1},
                            "validForTicks": {"type": "integer", "minimum": 1,
                                              "maximum": 60},
                            "interruptPolicy": _INTERRUPT_SCHEMA,
                        },
                        "required": [
                            "skill", "parameters", "confidence", "validForTicks",
                            "interruptPolicy",
                        ],
                        "additionalProperties": False,
                    },
                }},
                "required": ["steps"],
                "additionalProperties": False,
            },
        },
    },
]


class OpenAICompatibleModelAdapter(ModelAdapter):
    """Converts the stable model contract to Chat Completions tool calls."""

    def __init__(self, *, base_url: str, model: str, api_key: str,
                 timeout_seconds: float, max_output_tokens: int,
                 token_limit_field: str = "max_tokens") -> None:
        self._endpoint = _chat_endpoint(base_url)
        self._model = model
        self._api_key = api_key
        self._timeout_seconds = timeout_seconds
        self._max_output_tokens = max_output_tokens
        self._token_limit_field = token_limit_field

    def invoke(self, request: ModelInput) -> ModelResponse:
        context = {
            "observation": request.observation,
            "activePlan": request.active_plan,
            "feedback": request.feedback,
            "events": request.events,
            "replanTriggers": request.replan_triggers,
            "toolResults": request.messages,
        }
        body = {
            "model": self._model,
            "messages": [
                {"role": "system", "content": _SYSTEM_PROMPT},
                {"role": "user", "content": json.dumps(
                    context, ensure_ascii=False, separators=(",", ":")
                )},
            ],
            "tools": _TOOLS,
            "tool_choice": "required",
            self._token_limit_field: self._max_output_tokens,
        }
        wire_request = Request(
            self._endpoint,
            data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
            headers={
                "Authorization": f"Bearer {self._api_key}",
                "Content-Type": "application/json",
                "Accept": "application/json",
            },
            method="POST",
        )
        try:
            with urlopen(wire_request, timeout=self._timeout_seconds) as response:
                payload_bytes = response.read(_MAX_RESPONSE_BYTES + 1)
        except HTTPError as exception:
            raise ModelCallError(_http_error_code(exception.code),
                                 f"HTTP {exception.code}") from None
        except (TimeoutError, socket.timeout):
            raise ModelCallError(ModelErrorCode.TIMEOUT) from None
        except URLError:
            raise ModelCallError(ModelErrorCode.TRANSIENT,
                                 "network request failed") from None
        if len(payload_bytes) > _MAX_RESPONSE_BYTES:
            raise ModelCallError(ModelErrorCode.PERMANENT,
                                 "provider response exceeded size limit")
        try:
            payload = json.loads(payload_bytes)
            message = payload["choices"][0]["message"]
            raw_calls = message.get("tool_calls") or []
        except (KeyError, IndexError, TypeError, json.JSONDecodeError):
            raise ModelCallError(ModelErrorCode.PERMANENT,
                                 "invalid provider response") from None
        calls = tuple(self._parse_tool_call(item, request.call_index, index)
                      for index, item in enumerate(raw_calls))
        return ModelResponse(calls, _usage(payload.get("usage")))

    @staticmethod
    def _parse_tool_call(item: Any, call_index: int, index: int) -> ToolCall:
        try:
            function = item["function"]
            name = function["name"]
            arguments = function.get("arguments", "{}")
            if isinstance(arguments, str):
                arguments = json.loads(arguments)
            if not isinstance(name, str) or not name or not isinstance(arguments, dict):
                raise ValueError
            call_id = item.get("id") or f"provider-{call_index}-{index}"
            if not isinstance(call_id, str):
                raise ValueError
            return ToolCall(call_id, name, arguments)
        except (KeyError, TypeError, ValueError, json.JSONDecodeError):
            raise ModelCallError(ModelErrorCode.PERMANENT,
                                 "invalid provider tool call") from None


def _chat_endpoint(base_url: str) -> str:
    parsed = urlsplit(base_url.rstrip("/"))
    path = parsed.path.rstrip("/")
    if not path.endswith("/chat/completions"):
        path += "/chat/completions"
    return urlunsplit((parsed.scheme, parsed.netloc, path, "", ""))


def _http_error_code(status: int) -> ModelErrorCode:
    if status in {408, 504}:
        return ModelErrorCode.TIMEOUT
    if status in {409, 425, 429} or status >= 500:
        return ModelErrorCode.TRANSIENT
    return ModelErrorCode.PERMANENT


def _usage(value: Any) -> ModelUsage | None:
    if not isinstance(value, dict):
        return None
    input_tokens = value.get("prompt_tokens", value.get("input_tokens"))
    output_tokens = value.get("completion_tokens", value.get("output_tokens"))
    if not _token_count(input_tokens) or not _token_count(output_tokens):
        return None
    return ModelUsage(input_tokens, output_tokens)


def _token_count(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool) and value >= 0
