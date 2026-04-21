"""
json_utils.py — Shared JSON repair helpers.

Extracted from chat.py so they can be imported by other services
(enrichment_service, image_search_titles) without pulling in the
heavy top-level imports of chat.py (strands_agent, etc.).
"""

from __future__ import annotations
import json
import re


def _remove_trailing_commas(text: str) -> str:
    """Remove trailing commas before ] or } — common LLM JSON error."""
    return re.sub(r",\s*([}\]])", r"\1", text)


def _fix_invalid_escapes(text: str) -> str:
    """
    Walk the raw LLM text and double any backslash that is not the start of a
    valid JSON escape sequence.
    Valid JSON escapes: \\\\ \\" \\/ \\b \\f \\n \\r \\t \\uXXXX
    """
    _VALID_AFTER = frozenset('"\\\/bfnrtu')

    def _replacer(m: re.Match) -> str:
        pair = m.group(0)
        return pair if pair[1] in _VALID_AFTER else ('\\\\' + pair[1:])

    return re.sub(r'\\(.)', _replacer, text, flags=re.DOTALL)


def extract_json_safe(text: str):
    """
    Robustly extract and parse the first top-level JSON object from text.
    Handles fenced blocks, trailing commas, invalid LaTeX backslash escapes.
    """
    stripped = text.strip()
    if stripped.startswith("```"):
        stripped = re.sub(r"^```(?:json)?\s*", "", stripped)
        stripped = re.sub(r"\s*```\s*$", "", stripped)

    for attempt in (
        lambda s: json.loads(s),
        lambda s: json.loads(_remove_trailing_commas(s)),
        lambda s: json.loads(_fix_invalid_escapes(_remove_trailing_commas(s))),
    ):
        try:
            return attempt(stripped)
        except json.JSONDecodeError:
            pass

    start = stripped.find('{')
    if start < 0:
        raise ValueError("No JSON object found in text")

    depth = 0
    in_string = False
    i = start
    while i < len(stripped):
        ch = stripped[i]
        if in_string:
            if ch == '\\':
                i += 2
                continue
            if ch == '"':
                in_string = False
        else:
            if ch == '"':
                in_string = True
            elif ch == '{':
                depth += 1
            elif ch == '}':
                depth -= 1
                if depth == 0:
                    candidate = stripped[start:i + 1]
                    for attempt in (
                        candidate,
                        _remove_trailing_commas(candidate),
                        _fix_invalid_escapes(_remove_trailing_commas(candidate)),
                    ):
                        try:
                            return json.loads(attempt)
                        except json.JSONDecodeError:
                            continue
                    raise json.JSONDecodeError(
                        "All repair strategies failed", candidate, 0
                    )
        i += 1

    raise ValueError("Unmatched braces — no complete JSON object found")
