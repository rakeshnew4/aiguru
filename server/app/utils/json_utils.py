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
    Walk the raw LLM text and fix any backslash that is not the start of a
    valid JSON escape sequence.
    Valid JSON escapes: \\\\ \\" \\/ \\b \\f \\n \\r \\t \\uXXXX

    Special case: \\' (backslash-apostrophe) — LLMs commonly emit this thinking
    it needs escaping, but JSON does not escape single quotes.  We strip the
    backslash so \\' → ' (not \\\\' which would leave a literal backslash in the value).
    All other invalid escapes get the backslash doubled so JSON sees a literal \\.
    """
    _VALID_AFTER = frozenset('"\\\/bfnrtu')

    def _replacer(m: re.Match) -> str:
        pair = m.group(0)
        ch = pair[1]
        if ch in _VALID_AFTER:
            return pair          # already valid — keep as-is
        if ch == "'":
            return ch            # \' → ' (remove meaningless escape)
        return '\\\\' + ch      # other invalid escape → double the backslash

    return re.sub(r'\\(.)', _replacer, text, flags=re.DOTALL)


def _fix_premature_frame_close(text: str) -> str:
    """
    Fix the LLM pattern where '}' prematurely closes a BB frame object right
    after the svg_elements array, leaving frame-level fields (image_show_confidencescore,
    speech, lang, etc.) dangling outside it.

    Bad:  "svg_elements": [...]}, "image_show_confidencescore": ...
    Good: "svg_elements": [...],  "image_show_confidencescore": ...
    """
    # These fields can only appear directly inside a frame object, not a frames array.
    _FRAME_FIELDS = (
        r'"image_show_confidencescore"'
        r'|"image_description"'
        r'|"tts_engine"'
        r'|"voice_role"'
        r'|"duration_ms"'
        r'|"quiz_answer"'
        r'|"quiz_options"'
        r'|"quiz_correct_index"'
        r'|"quiz_model_answer"'
        r'|"quiz_keywords"'
    )
    # Match ]}, "field" — the } is the premature frame-close; remove it.
    return re.sub(
        r'\]\},\s*(' + _FRAME_FIELDS + r')',
        r'], \1',
        text,
    )


def extract_json_safe(text: str):
    """
    Robustly extract and parse the first top-level JSON object from text.
    Handles fenced blocks, trailing commas, invalid LaTeX backslash escapes,
    and premature frame object closes.
    """
    stripped = text.strip()
    if stripped.startswith("```"):
        stripped = re.sub(r"^```(?:json)?\s*", "", stripped)
        stripped = re.sub(r"\s*```\s*$", "", stripped)

    def _full_repair(s: str) -> str:
        return _fix_invalid_escapes(_remove_trailing_commas(_fix_premature_frame_close(s)))

    for attempt in (
        lambda s: json.loads(s),
        lambda s: json.loads(_remove_trailing_commas(s)),
        lambda s: json.loads(_fix_invalid_escapes(_remove_trailing_commas(s))),
        lambda s: json.loads(_full_repair(s)),
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
                        _full_repair(candidate),
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
