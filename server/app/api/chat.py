import re
import asyncio
import json
from typing import Optional, List, Dict, Any

from fastapi import APIRouter, Depends
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field
from app.services.context_service import get_context
from app.services.prompt_service import (
    build_prompt,
    build_intent_classifier_prompt,
    build_bb_planner_prompt,
    build_bb_main_prompt,
    get_normal_mode_system_prompt,
    get_blackboard_mode_system_prompt,
    build_normal_mode_user_content,
    build_blackboard_mode_user_content,
)
from app.services.llm_service import generate_response
from app.services.strands_agent import run_agent
from app.services import user_service
from app.core.config import settings
from app.core.logger import get_logger
from app.core.auth import require_auth, AuthUser
from app.api.image_search_titles import search_wikimedia_images, get_titles
_YT_IMPORT_ERROR = ""
try:
    from youtube_extractor import enrich_steps_with_videos as _yt_enrich
    _YT_ENABLED = True
except ImportError as exc:
    _YT_ENABLED = False
    _YT_IMPORT_ERROR = str(exc)

logger = get_logger(__name__)
if _YT_ENABLED:
    logger.info("YouTube enrichment enabled")
else:
    logger.warning("YouTube enrichment disabled: %s", _YT_IMPORT_ERROR or "import failed")

router = APIRouter(prefix="", tags=["chat"])

_BB_INTENT_SYSTEM_PROMPT = (
    "You are a lesson planner. "
    "Return ONLY a JSON object (no markdown, no fences) with these fields:\n"
    '  "lesson_title": short engaging title,\n'
    '  "steps": array of step titles (match the requested count),\n'
    '  "use_svg": true if the topic needs diagrams (math/science/charts), else false,\n'
    '  "category": one of "math","science","history","language","general"\n'
    "Example: {\"lesson_title\":\"Photosynthesis\",\"steps\":[\"What is it?\",\"Light reactions\"],\"use_svg\":false,\"category\":\"science\"}"
)


class ChatRequest(BaseModel):
    question: str
    page_id: str
    student_level: Optional[int] = 5
    history: List[str] = Field(default_factory=list)

    # old contract (still supported)
    images: List[str] = Field(default_factory=list)

    # new app contract
    image_base64: Optional[str] = None
    image_data: Optional[Dict[str, Any]] = None

    mode: Optional[str] = "normal"
    language: Optional[str] = "en-US"
    language_tag: Optional[str] = "en-US"
    
    # User subscription plan (determines model tier)
    user_plan: Optional[str] = "premium"  # free, premium, pro
    user_id: Optional[str] = None   # Firebase Auth UID for token tracking

    # BB session feature flags (default True = enabled)
    bb_quiz_enabled: Optional[bool] = True
    bb_videos_enabled: Optional[bool] = True
    bb_animations_enabled: Optional[bool] = True
    bb_images_enabled: Optional[bool] = True


def _normalize_images(req: ChatRequest) -> List[str]:
    if req.images:
        return req.images
    if req.image_base64:
        raw = req.image_base64.strip()
        if raw.startswith("data:image/"):
            return [raw]
        return [f"data:image/jpeg;base64,{raw}"]
    return []


def _get_model_tier(user_plan: str) -> str:
    """
    Map user subscription plan to model tier.
    
    Args:
        user_plan: User's subscription level (free, premium, pro)
    
    Returns:
        Model tier: "power", "cheaper", or "faster"
    """
    plan_to_tier = {
        "free": "power",      # Free users get balanced models
        "premium": "power",     # Premium users get best models
        "pro": "power",         # Pro users also get best models
    }
    return plan_to_tier.get(user_plan.lower(), "cheaper")


def _merge_context_with_image_data(base_context: Any, image_data: Optional[Dict[str, Any]]) -> str:
    context_str = "" if base_context is None else str(base_context)
    if not image_data:
        return context_str

    transcript = (image_data.get("transcript") or "").strip()
    key_terms = image_data.get("key_terms") or []
    diagrams = image_data.get("diagrams") or []

    extra_parts = []
    if transcript:
        extra_parts.append(f"IMAGE_TRANSCRIPT:\n{transcript}")
    if key_terms:
        extra_parts.append(f"IMAGE_KEY_TERMS: {', '.join([str(k) for k in key_terms])}")
    if diagrams:
        diag_lines = []
        for d in diagrams[:5]:
            heading = d.get("heading", "")
            depiction = d.get("depiction", "")
            diag_lines.append(f"- {heading} :: {depiction}".strip(" :"))
        if diag_lines:
            extra_parts.append("IMAGE_DIAGRAMS:\n" + "\n".join(diag_lines))

    if not extra_parts:
        return context_str
    return f"{context_str}\n\n" + "\n\n".join(extra_parts)


_GREET_PATTERNS = re.compile(
    r"^\s*(hi|hello|hey|hii|helo|good\s*(morning|afternoon|evening|night)|"
    r"thanks|thank\s*you|ok|okay|great|nice|cool|bye|goodbye|see\s*you|"
    r"howdy|sup|what'?s\s*up|yo)\W*\s*$",
    re.IGNORECASE,
)


def _rule_based_intent(question: str) -> dict | None:
    """Returns intent dict for obvious cases, or None to fall through to LLM."""
    q = question.strip()
    if len(q) <= 60 and _GREET_PATTERNS.match(q):
        return {"intent": "greet", "complexity": "low"}
    return None


def _classify_intent(question: str, has_image: bool, last_reply: str) -> dict:
    """
    Fast intent classification using the 'faster' model tier (gemini-2.0-flash).
    Returns {"intent": str, "complexity": str}.
    Falls back to {"intent": "explain", "complexity": "medium"} on any error.
    """
    fast = _rule_based_intent(question)
    if fast:
        logger.info("Intent=%s complexity=%s (rule-based, no LLM call)", fast["intent"], fast["complexity"])
        return fast
    try:
        classifier_prompt = build_intent_classifier_prompt(
            question=question,
            has_image=has_image,
            last_reply=last_reply,
        )
        raw = generate_response(classifier_prompt, [], tier="faster")
        text = (raw.get("text") or "").strip()
        parsed = extract_json_safe(text)
        intent = parsed.get("intent", "explain")
        complexity = parsed.get("complexity", "medium")
        valid_intents = {"greet", "explain", "calculate", "image_explain",
                         "followup", "definition", "practice", "other"}
        if intent not in valid_intents:
            intent = "explain"
        if complexity not in {"low", "medium", "high"}:
            complexity = "medium"
        return {"intent": intent, "complexity": complexity}
    except Exception as exc:
        logger.warning("Intent classification failed (%s) — defaulting to explain/medium", exc)
        return {"intent": "explain", "complexity": "medium"}


async def _bb_plan(question: str, context: str, history: list, level: int) -> dict:
    """
    Fast BB lesson planner (~150ms, 'faster' model).
    Returns plan dict with topic_type, scope, steps_count, key_concepts,
    image_search_terms, question_focus, question_type, prior_knowledge.
    Falls back to safe defaults on any error.
    """
    defaults = {
        "topic_type": "other",
        "scope": "medium",
        "key_concepts": [],
        "steps_count": 5,
        "image_search_terms": [],
        "question_focus": "",
        "question_type": "conceptual",
        "prior_knowledge": "",
    }
    try:
        planner_prompt = build_bb_planner_prompt(question, context, history, level)
        loop = asyncio.get_event_loop()
        raw = await loop.run_in_executor(
            None, lambda: generate_response(planner_prompt, [], tier="faster")
        )
        text = (raw.get("text") or "").strip()
        plan = extract_json_safe(text)
        if not isinstance(plan, dict):
            return defaults
        plan["steps_count"] = max(4, min(6, int(plan.get("steps_count") or 5)))
        if not isinstance(plan.get("key_concepts"), list):
            plan["key_concepts"] = []
        if not isinstance(plan.get("image_search_terms"), list):
            plan["image_search_terms"] = []
        # Ensure new fields are strings, falling back gracefully
        plan.setdefault("question_focus", "")
        plan.setdefault("question_type", "conceptual")
        plan.setdefault("prior_knowledge", "")
        logger.info(
            "BB planner extras | focus=%r type=%s prior=%r",
            plan["question_focus"][:80], plan["question_type"],
            plan["prior_knowledge"][:60],
        )
        return plan
    except Exception as exc:
        logger.warning("BB planner failed (%s) — using defaults", exc)
        return defaults


async def _prefetch_wikimedia(search_terms: list) -> list:
    """
    Fetch Wikimedia image titles for the planner's search terms in parallel.
    Runs in the background while the main BB LLM call is pending — effectively free.
    """
    if not search_terms:
        return []
    try:
        tasks = [search_wikimedia_images(term, limit=8) for term in search_terms[:4]]
        results = await asyncio.gather(*tasks, return_exceptions=True)
        candidates = []
        for r in results:
            if isinstance(r, list):
                candidates.extend(r)  # each item is now {"title": ..., "url": ...}
        return candidates
    except Exception as exc:
        logger.warning("Wikimedia prefetch failed: %s", exc)
        return []


def _extract_page_transcript(
    result: Dict[str, Any],
    images: List[str],
    image_data: Optional[Dict[str, Any]],
) -> Optional[str]:
    """
    Returns a transcript string to send back to the Android app so it can
    persist the image analysis into the Firestore chapter system context.

    Priority:
      1. LLM JSON "user_attachment_transcription" — parsed from the LLM response
      2. result["page_transcript"] — if generate_response() extracts it from vision
      3. result["transcript"]      — alternate key
      4. image_data["transcript"]  — client already analysed; echo it back
      5. None                      — no image attached, skip the frame
    """
    # Try to extract transcription from the LLM's own JSON response
    text = result.get("text", "")
    try:
        parsed = extract_json_safe(text)
        transcription = parsed.get("user_attachment_transcription", "").strip()
        if transcription:
            return transcription
    except Exception:
        pass

    if not images and not image_data:
        return None

    transcript = (
        result.get("page_transcript")
        or result.get("transcript")
        or (image_data or {}).get("transcript")
    )
    return str(transcript).strip() or None if transcript else None

def _remove_trailing_commas(text: str) -> str:
    """Remove trailing commas before ] or } — common LLM JSON error."""
    import re
    return re.sub(r",\s*([}\]])", r"\1", text)


def _fix_invalid_escapes(text: str) -> str:
    """
    Walk the raw LLM text and double any backslash that is not the start of a
    valid JSON escape sequence.

    Valid JSON escapes: \\\\ \\" \\/ \\b \\f \\n \\r \\t \\uXXXX
    Invalid examples from LaTeX/math in LLM output: \\p (pi), \\e (epsilon),
    \\S (Sigma), \\a (alpha) — these cause json.JSONDecodeError on large BBs.

    Uses re.sub with a 2-char consuming pattern so that the second backslash
    in a valid \\\\ escape is never re-processed.
    """
    import re
    _VALID_AFTER = frozenset('"\\\/bfnrtu')

    def _replacer(m: re.Match) -> str:
        pair = m.group(0)           # always 2 chars: backslash + next char
        return pair if pair[1] in _VALID_AFTER else ('\\\\' + pair[1:])

    # re.DOTALL lets . match newline so trailing backslash edge-cases are caught
    return re.sub(r'\\(.)', _replacer, text, flags=re.DOTALL)


def _fix_unbalanced_braces(text: str) -> str:
    """
    Walk JSON text (string-aware) and drop closing braces / brackets that do
    not match the innermost open delimiter on the stack.

    Fixes two LLM structural bugs at once:
      1. Extra } in the middle: "data": {}}}, {"frame_type"
                                          ^^ second } is dropped
      2. Trailing garbage after root closes: ...}]}]}\n]}
                                                      ^^^ all dropped

    Applied AFTER _fix_invalid_escapes so the string-scanner's backslash
    handling sees only valid \\X sequences (not raw \X that would confuse it).
    """
    result: list[str] = []
    stack: list[str] = []   # '{' or '['
    in_string = False
    i = 0
    n = len(text)
    while i < n:
        ch = text[i]
        if in_string:
            if ch == '\\':
                result.append(ch)
                i += 1
                if i < n:
                    result.append(text[i])
                i += 1
                continue
            elif ch == '"':
                in_string = False
            result.append(ch)
        else:
            if ch == '"':
                in_string = True
                result.append(ch)
            elif ch == '{':
                stack.append('{')
                result.append(ch)
            elif ch == '[':
                stack.append('[')
                result.append(ch)
            elif ch == '}':
                if stack and stack[-1] == '{':
                    stack.pop()
                    result.append(ch)
                # else: mismatched / extra } — drop it silently
            elif ch == ']':
                if stack and stack[-1] == '[':
                    stack.pop()
                    result.append(ch)
                # else: mismatched / extra ] — drop it silently
            else:
                result.append(ch)
        i += 1
    return ''.join(result)


def _fix_literal_newlines(text: str) -> str:
    """
    Walk JSON text and replace literal newline/carriage-return/tab/control
    characters that appear INSIDE string values with their JSON escape
    equivalents (\\n, \\r, \\t, \\uXXXX).

    LLMs sometimes emit multi-line speech strings without escaping the newlines,
    which makes the text syntactically invalid JSON even though the brace
    structure is correct — causing 'All repair strategies failed' at char 0.
    This is applied AFTER _fix_invalid_escapes so backslash sequences are clean.
    """
    result: list[str] = []
    in_string = False
    i = 0
    n = len(text)
    while i < n:
        ch = text[i]
        if in_string:
            if ch == '\\':
                # Already-escaped sequence — copy both chars verbatim.
                result.append(ch)
                i += 1
                if i < n:
                    result.append(text[i])
                i += 1
                continue
            elif ch == '"':
                in_string = False
                result.append(ch)
            elif ch == '\n':
                result.append('\\n')
            elif ch == '\r':
                result.append('\\r')
            elif ch == '\t':
                result.append('\\t')
            elif ord(ch) < 0x20:        # other ASCII control chars
                result.append(f'\\u{ord(ch):04x}')
            else:
                result.append(ch)
        else:
            if ch == '"':
                in_string = True
                result.append(ch)
            else:
                result.append(ch)
        i += 1
    return ''.join(result)


def extract_json_safe(text):
    """
    Robustly extract and parse the first top-level JSON object from text.
    Handles:
      - Bare JSON
      - ```json ... ``` fenced JSON
      - Prefix/suffix prose around the JSON
      - Nested objects / arrays / LaTeX braces inside string values
      - Trailing commas before } or ]
      - Invalid backslash escapes from LaTeX/math in string values (e.g. \\p, \\e)
    """
    import json
    import re

    # Strip outer whitespace and optional markdown fences
    stripped = text.strip()
    if stripped.startswith("```"):
        stripped = re.sub(r"^```(?:json)?\s*", "", stripped)
        stripped = re.sub(r"\s*```\s*$", "", stripped)

    # Fast path: entire (stripped) text is valid JSON
    try:
        return json.loads(stripped)
    except json.JSONDecodeError:
        pass

    # Retry with trailing comma removal
    try:
        return json.loads(_remove_trailing_commas(stripped))
    except json.JSONDecodeError:
        pass

    # Retry with invalid escape fix + trailing comma removal
    # (handles large BBs with LaTeX like \pi, \alpha inside string values)
    try:
        return json.loads(_fix_invalid_escapes(_remove_trailing_commas(stripped)))
    except json.JSONDecodeError:
        pass

    # Retry with literal newline fix (LLM emits multi-line speech strings)
    try:
        return json.loads(_fix_literal_newlines(_fix_invalid_escapes(_remove_trailing_commas(stripped))))
    except json.JSONDecodeError:
        pass

    # Retry with unbalanced-brace fix — handles LLM structural bugs:
    #   "data": {}}}, {"frame_type"   →  extra } dropped
    #   trailing \n]}  after root     →  dropped
    # This MUST come after _fix_invalid_escapes so the string-scanner
    # sees valid \\X sequences, not raw \X that would mis-track quote depth.
    try:
        return json.loads(_fix_unbalanced_braces(_fix_literal_newlines(_fix_invalid_escapes(_remove_trailing_commas(stripped)))))
    except json.JSONDecodeError:
        pass

    # Scan for the first '{' then walk with balanced-brace counting,
    # correctly skipping string contents (including escaped chars).
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
                i += 2          # skip escaped character
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
                    # Try each strategy in order:
                    #  1. direct parse
                    #  2. trailing commas removed
                    #  3. invalid escape sequences fixed
                    #  4. literal newlines/control chars fixed (LLM multi-line strings)
                    #  5. all three combined
                    #  6. unbalanced braces removed (extra } from LLM)
                    _tc  = _remove_trailing_commas(candidate)
                    _esc = _fix_invalid_escapes(_tc)
                    _nl  = _fix_literal_newlines(_esc)
                    _ub  = _fix_unbalanced_braces(_nl)
                    for attempt in (
                        candidate,
                        _tc,
                        _esc,
                        _nl,
                        _fix_literal_newlines(_fix_invalid_escapes(candidate)),
                        _ub,
                        _fix_unbalanced_braces(_fix_literal_newlines(_fix_invalid_escapes(candidate))),
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


# ── Normal-mode JSON fields expected by Android ──────────────────────────────
_NORMAL_FIELDS = ("user_question", "answer", "user_attachment_transcription", "extra_details_or_summary", "suggest_blackboard")

# Valid values for BB frame fields
_VALID_TTS_ENGINES = {"android", "gemini", "google"}
_VALID_VOICE_ROLES = {"teacher", "assistant", "quiz", "feedback"}

# ── Speech opener patterns to strip (classroom filler that annoys students) ───
# Applied to every frame's speech field in _sanitize_bb_response.
_OPENER_RE = [
    # "Hi/Hello/Hey everyone/students/class/all/folks[!,. ]"
    re.compile(r'^(hi|hello|hey)[,!]?\s+(everyone|students|class|there|all|folks)[!,.]?\s+', re.I),
    # "Today we are going to learn/explore/discuss/cover/study [about] " — prefix only
    re.compile(r'^today[,]?\s+we(?:\s+are|\s*\'re)?\s+going\s+to\s+(?:learn(?:\s+about)?|explore|discuss|cover|study|look\s+at|talk\s+about)\s+', re.I),
    # "Today we will learn/explore/study/cover [about] " — prefix only
    re.compile(r'^today[,]?\s+we\s+will\s+(?:learn(?:\s+about)?|explore|discuss|cover|study|look\s+at|talk\s+about)\s+', re.I),
    # "In this lesson/session/step/video, we [are going to / will] cover/explore/study/learn about "
    re.compile(r'^in\s+this\s+(lesson|session|step|video)[,.]?\s+we(?:\s+are\s+going\s+to|\s+will)?\s+(?:learn(?:\s+about)?|explore|discuss|cover|study|look\s+at)\s+', re.I),
    # "Let's begin/start our lesson/session on "
    re.compile(r'^let\s*\'?s\s+(?:begin|start)\s+(?:our\s+)?(?:lesson|session|class|exploration)\s+on\s+', re.I),
    # "Let's learn about / explore [how] / dive into "
    re.compile(r'^let\s*\'?s\s+(?:learn\s+about|explore|discuss|dive\s+into)\s+(?:how\s+)?', re.I),
    # "Welcome to today's/our lesson/session on " — single-sentence specific form
    re.compile(r'^welcome\s+to\s+(?:today\'s\s+)?(?:lesson|session|class|our\s+\w+)\s+on\s+', re.I),
    # "Welcome to [phrase ending !.] " — multi-sentence form, keeps content after punctuation
    re.compile(r'^welcome\s+to\s+[^.!?]{0,80}[.!]\s+', re.I),
    # "Great/Alright/Okay/So, let's explore/learn/study [how] "
    re.compile(r'^(great|alright|okay|so)[!,]?\s+let\s*\'?s\s+(?:explore|learn\s+about|discuss|cover|study)\s+(?:how\s+)?', re.I),
]

def _strip_speech_opener(speech: str) -> str:
    """Remove generic classroom-lecture filler from the start of a speech string.
    Applies up to 3 passes so chained openers (e.g. 'Hi everyone! Today we will...')
    are fully removed. Stops when no pattern matches or remaining text is too short.
    """
    if not speech or len(speech) < 20:
        return speech
    for _ in range(3):
        changed = False
        for pat in _OPENER_RE:
            cleaned = pat.sub('', speech).lstrip()
            if cleaned != speech and len(cleaned) >= 10:
                speech = cleaned
                changed = True
                break
        if not changed:
            break
    return speech

def _try_extract_answer_from_raw(text: str) -> str:
    """
    Last-resort attempt to pull just the answer value out of a partially-broken
    JSON string without fully parsing it.  Returns empty string on failure.
    """
    import re
    # Try to find "answer": "<value>" with a simple regex on the raw text.
    # This handles cases where the outer JSON is broken but the answer key exists.
    m = re.search(r'"answer"\s*:\s*"((?:[^"\\]|\\.)*)"\s*[,}]', text, re.DOTALL)
    if m:
        try:
            return json.loads('"' + m.group(1) + '"')
        except Exception:
            return m.group(1)[:2000]
    return ""


def _sanitize_normal_response(text: str) -> str:
    """
    Parse and re-serialize a normal-mode LLM JSON response.
    Fills missing required fields with empty strings.
    Returns a clean JSON string safe for Android to parse.
    Falls back safely without ever exposing raw JSON structure to the user.
    """
    try:
        parsed = extract_json_safe(text)
        for field in _NORMAL_FIELDS:
            if field not in parsed:
                parsed[field] = False if field == "suggest_blackboard" else ""
        return json.dumps(parsed, ensure_ascii=False)
    except Exception as e:
        logger.warning("Normal JSON repair failed (%s) — attempting answer extraction", e)
        # Try to rescue just the answer value from the broken JSON
        rescued_answer = _try_extract_answer_from_raw(text or "")
        if not rescued_answer:
            # If text is plain (not JSON), use it directly; otherwise show a safe message
            stripped = (text or "").strip()
            if stripped and not stripped.startswith("{") and not stripped.startswith("["):
                rescued_answer = stripped[:3000]
            else:
                rescued_answer = "I had trouble formatting my response. Please try asking again."
        return json.dumps({
            "user_question": "",
            "answer": rescued_answer,
            "user_attachment_transcription": "",
            "extra_details_or_summary": "",
        }, ensure_ascii=False)


_VALID_FRAME_TYPES = frozenset({
    "concept", "memory", "diagram", "quiz_mcq", "quiz_typed",
    "quiz_voice", "quiz_order", "summary",
})
# Diagram sub-types the LLM sometimes uses as frame_type by mistake
_DIAGRAM_SUB_TYPES = frozenset({
    "atom", "solar_system", "waveform_signal", "wave", "triangle",
    "circle_radius", "rectangle_area", "geometry_angles", "line_graph",
    "graph_function", "number_line", "fraction_bar",
    "flow", "cycle", "comparison", "labeled_diagram", "anatomy", "cell",
    "cell_diagram", "flow_arrow", "custom",
})

def _coerce_frame(frame: dict) -> None:
    """In-place coercion of a single BB frame's field types."""
    dm = frame.get("duration_ms")
    if dm is not None and not isinstance(dm, int):
        try:
            frame["duration_ms"] = int(dm)
        except (ValueError, TypeError):
            frame["duration_ms"] = 2500
    qci = frame.get("quiz_correct_index")
    if qci is not None and not isinstance(qci, int):
        try:
            frame["quiz_correct_index"] = int(qci)
        except (ValueError, TypeError):
            frame["quiz_correct_index"] = -1
    if frame.get("tts_engine") not in _VALID_TTS_ENGINES:
        frame["tts_engine"] = "gemini"
    if frame.get("voice_role") not in _VALID_VOICE_ROLES:
        frame["voice_role"] = "teacher"
    # Ensure every frame has a 'text' field — diagram frames often omit it,
    # which causes a JSONException in Android's BlackboardGenerator.parseStepsArray
    # (it uses getString("text") which throws if the key is absent).
    if "text" not in frame or frame["text"] is None:
        frame["text"] = ""
    # Normalize frame_type: LLM sometimes uses diagram sub-type as frame_type
    # e.g. frame_type="flow" instead of frame_type="diagram" + diagram_type="flow"
    ftype = frame.get("frame_type", "")
    if ftype not in _VALID_FRAME_TYPES:
        if ftype in _DIAGRAM_SUB_TYPES:
            # Move it: set frame_type="diagram", preserve the value as diagram_type
            if not frame.get("diagram_type"):
                frame["diagram_type"] = ftype
            frame["frame_type"] = "diagram"
        else:
            frame["frame_type"] = "concept"


def _repair_ndjson_bb_response(text: str) -> str:
    """
    Repair NDJSON (newline-delimited JSON) BB responses where the LLM outputs
    multiple separate JSON objects instead of wrapping them in {"steps": [...]}.

    Pattern (broken):
        {"steps": [...initial_step...]}
        {"lang": "...", "frames": [...]}
        {"lang": "...", "frames": [...]}

    Pattern (fixed):
        {"steps": [...initial_step..., step2_obj, step3_obj, ...]}
    """
    try:
        # Find all top-level JSON objects in the text
        objects = []
        i = 0
        while i < len(text):
            # Skip whitespace and delimiters
            while i < len(text) and text[i] in ' \t\n\r,':
                i += 1
            if i >= len(text) or text[i] != '{':
                break
            # Extract one object
            depth = 0
            in_str = False
            start = i
            while i < len(text):
                ch = text[i]
                if in_str:
                    if ch == '\\':
                        i += 2
                        continue
                    if ch == '"':
                        in_str = False
                else:
                    if ch == '"':
                        in_str = True
                    elif ch == '{':
                        depth += 1
                    elif ch == '}':
                        depth -= 1
                        if depth == 0:
                            try:
                                obj = json.loads(text[start:i+1])
                                objects.append(obj)
                            except (json.JSONDecodeError, ValueError):
                                pass
                            i += 1
                            break
                i += 1

        if len(objects) <= 1:
            return text  # Not NDJSON, return as-is

        # Check if first object has "steps" array
        first_obj = objects[0]
        if not isinstance(first_obj, dict) or "steps" not in first_obj:
            return text

        steps = first_obj["steps"]
        if not isinstance(steps, list):
            return text

        # Collect all remaining objects as additional steps
        # (they typically have "lang", "frames", etc. — the structure of a step)
        for obj in objects[1:]:
            if isinstance(obj, dict) and "frames" in obj:
                steps.append(obj)

        logger.info(
            "BB NDJSON repair: combined %d objects into 1 with %d steps",
            len(objects), len(steps),
        )
        return json.dumps(first_obj, ensure_ascii=False)
    except Exception as e:
        logger.debug("NDJSON repair failed (%s) — returning original", e)
        return text


def _sanitize_bb_response(text: str) -> str:
    """
    Parse and re-serialize a blackboard-mode LLM JSON response.
    - Repairs NDJSON output (multiple top-level JSON objects).
    - Coerces field types (duration_ms → int, quiz_correct_index → int,
      tts_engine / voice_role → valid enum values) to prevent Android crashes.
    - Flattens nested frames: the LLM sometimes wraps actual frames inside
      another frame object (frame.frames[]). We detect and unwrap this.
    - Sanity-checks that the output is not dramatically smaller than the input
      (which would indicate the parser found a wrong inner object).
    Falls back to the original text on catastrophic failure.
    """
    try:
        # Repair NDJSON if present (must be done before extract_json_safe)
        text = _repair_ndjson_bb_response(text)

        parsed = extract_json_safe(text)
        steps = parsed.get("steps")
        if not isinstance(steps, list):
            logger.warning("BB response missing 'steps' list — returning original")
            return text

        structural_changes = 0  # count unwraps / renames — skip size check if > 0

        for step in steps:
            if not isinstance(step, dict):
                continue

            # ── Normalize step-level field names ──────────────────────────
            # LLM sometimes outputs "step_title" instead of "title"
            if "title" not in step and "step_title" in step:
                step["title"] = step.pop("step_title")
                structural_changes += 1
            # Ensure lang is present at step level
            if "lang" not in step:
                step["lang"] = "en-US"

            raw_frames = step.get("frames", [])
            if not isinstance(raw_frames, list):
                continue

            flattened = []
            for frame in raw_frames:
                if not isinstance(frame, dict):
                    continue
                # ── Flatten nested frames ──────────────────────────────────
                # LLM sometimes outputs wrapper objects where the actual frames
                # are inside frame["frames"]. Unwrap and process those instead.
                nested = frame.get("frames")
                if isinstance(nested, list) and nested:
                    logger.warning(
                        "BB sanitizer: unwrapping nested frames in step '%s'",
                        step.get("title", "?"),
                    )
                    structural_changes += len(nested)
                    for inner in nested:
                        if isinstance(inner, dict):
                            _coerce_frame(inner)
                            inner["speech"] = _strip_speech_opener(inner.get("speech") or "")
                            flattened.append(inner)
                else:
                    _coerce_frame(frame)
                    frame["speech"] = _strip_speech_opener(frame.get("speech") or "")
                    flattened.append(frame)

            step["frames"] = flattened

        sanitized = json.dumps(parsed, ensure_ascii=False)

        # Sanity check: if the output shrank by more than 60% AND we made no
        # structural changes, the parser likely found a small inner object —
        # return original to be safe.
        # Skip this check when structural changes were made (nested unwrapping,
        # step_title → title renames): those changes legitimately reduce size.
        if structural_changes == 0 and len(sanitized) < len(text) * 0.4:
            logger.warning(
                "BB sanitizer: output (%d) is much smaller than input (%d) "
                "— possible wrong-object parse; returning sanitized to avoid raw JSON exposure",
                len(sanitized), len(text),
            )
            # Return the (small but valid) sanitized output rather than the raw LLM text.

        if structural_changes > 0:
            logger.info(
                "BB sanitizer: applied %d structural fix(es) | %d→%d chars",
                structural_changes, len(text), len(sanitized),
            )

        return sanitized
    except Exception as e:
        logger.warning("BB JSON repair failed (%s) — returning safe empty lesson", e)
        # Never return raw LLM text; return a minimal valid BB structure instead.
        return json.dumps({"steps": []}, ensure_ascii=False)


def _status_frame(message: str, progress: int) -> str:
    """Return a progress-status SSE data frame so the UI can show activity."""
    return f"data: {json.dumps({'status': message, 'progress': progress})}\n\n"


def _attach_video_clips(text_content: str, yt_clips: list) -> str:
    """Inject youtube_clip into concept/diagram frames across BB steps.

    yt_clips is a flat list (max 2) returned by enrich_steps_with_videos.
    Clips are injected into the first concept/diagram frame found in each step,
    spreading across steps so clip 0 lands in an earlier step, clip 1 in a later one.
    Returns text_content unchanged on any parse error.
    """
    if not yt_clips:
        return text_content
    try:
        data = json.loads(text_content)
        steps = data.get("steps", [])
        clip_idx = 0
        attached = 0
        for step in steps:
            if clip_idx >= len(yt_clips):
                break
            for frame in step.get("frames", []):
                if frame.get("frame_type") in ("concept", "diagram"):
                    frame["youtube_clip"] = {k: v for k, v in yt_clips[clip_idx].items() if k != "score"}
                    attached += 1
                    clip_idx += 1
                    break  # one clip per step, move to next step
        logger.info("Attached %d/%d YouTube clip(s) to BB steps", attached, len(yt_clips))
        return json.dumps(data, ensure_ascii=False)
    except Exception as exc:
        logger.warning("YouTube clip attachment parse failed: %s", exc)
        return text_content


@router.post("/chat-stream")
async def chat_stream(req: ChatRequest, auth: AuthUser = Depends(require_auth)):

    # ── Server-side quota gate ────────────────────────────────────────────────
    # Runs BEFORE the stream starts so the client gets a clean HTTP 429 (not an
    # error buried inside an SSE payload) when the daily limit is reached.
    # The Android client removed its own Firestore writes; the server is now the
    # single source of truth for usage counters.
    _uid = req.user_id or auth.uid
    if _uid and _uid != "guest_user":
        _mode_type = "blackboard" if req.mode == "blackboard" else "chat"
        _allowed, _quota_reason = await asyncio.get_event_loop().run_in_executor(
            None, user_service.check_and_record_quota, _uid, _mode_type
        )
        if not _allowed:
            from fastapi import HTTPException
            raise HTTPException(status_code=429, detail=_quota_reason)
    # ─────────────────────────────────────────────────────────────────────────

    async def generator():
        try:
            # Log chat event (fire-and-forget)
            asyncio.get_event_loop().run_in_executor(
                None,
                user_service.log_activity,
                "chat",
                req.user_id or auth.uid,
                "",
                auth.email,
                {
                    "page_id": req.page_id or "",
                    "mode": req.mode or "normal",
                    "user_plan": req.user_plan or "free",
                    "has_image": bool(req.image_base64 or req.images),
                    "prompt_preview": (req.question or "")[:80],
                },
            )

            # 1) context fetch + image normalisation (both modes)
            context = get_context(req.page_id)
            normalized_images = _normalize_images(req)
            has_image = bool(normalized_images)
            loop = asyncio.get_event_loop()
            lang = req.language_tag or req.language or "en-US"

            # ── First status: let the user know we received their request ──────
            if req.mode == "blackboard":
                yield _status_frame("📖 Reading your question...", 12)
            else:
                yield _status_frame("🤔 Understanding your question...", 20)

            if req.mode == "blackboard":
                # ── BLACKBOARD PIPELINE ────────────────────────────────────────
                # B1) Fast planner (150ms, faster model) — decides scope + key concepts
                plan = await _bb_plan(
                    req.question,
                    str(context) if context else "",
                    req.history or [],
                    req.student_level or 5,
                )
                logger.info(
                    "BB plan: type=%s scope=%s steps=%d concepts=%s",
                    plan.get("topic_type"), plan.get("scope"),
                    plan.get("steps_count", 5), plan.get("key_concepts"),
                )
                yield _status_frame("🔍 Collecting topic details and context...", 38)

                # B2) Start Wikimedia pre-fetch — gated on bb_images_enabled flag
                wiki_task = asyncio.ensure_future(
                    _prefetch_wikimedia(plan.get("image_search_terms", []))
                ) if req.bb_images_enabled is not False else None

                # B2b) Start YouTube enrichment — gated on bb_videos_enabled flag
                yt_task = asyncio.ensure_future(
                    _yt_enrich(req.question, plan)
                ) if (_YT_ENABLED and req.bb_videos_enabled is not False) else None
                if yt_task is not None:
                    logger.info("BB YouTube enrichment task started | steps=%d", len(plan.get("steps", [])))
                else:
                    logger.info("BB YouTube enrichment skipped | enabled=%s", _YT_ENABLED)

                # B3) Build context-enriched BB prompt
                prompt = build_bb_main_prompt(
                    context=str(context) if context else "",
                    question=req.question,
                    level=req.student_level or 5,
                    history=req.history or [],
                    plan=plan,
                    lang=lang,
                )
                model_tier = _get_model_tier(req.user_plan or "free")

            elif req.mode == "blackboard_intent":
                # Fast lesson-planner path — no intent classification, no wiki prefetch.
                # Returns JSON: {lesson_title, steps[], use_svg, category}
                model_tier = "faster"
                prompt = req.question  # fallback only; overridden in content section below
                logger.info("blackboard_intent | model_tier=faster | question_len=%d", len(req.question))

            else:
                # ── NORMAL CHAT PIPELINE ──────────────────────────────────────
                # N1) Classify intent — skip when image is attached (always image_explain)
                if has_image:
                    intent = "image_explain"
                    complexity = "high"
                    logger.info("Intent=image_explain (image attached — skipped classifier)")
                else:
                    last_reply = ""
                    for h in reversed(req.history or []):
                        if h.startswith("assistant:"):
                            last_reply = h[10:130]
                            break
                    intent_result = await loop.run_in_executor(
                        None,
                        lambda: _classify_intent(req.question, has_image, last_reply),
                    )
                    intent = intent_result["intent"]
                    complexity = intent_result["complexity"]
                    logger.info("Intent=%s complexity=%s has_image=%s", intent, complexity, has_image)

                # N2) Merge context (skip stale transcript injection when fresh image present)
                if has_image:
                    merged_context = str(context) if context else ""
                else:
                    merged_context = _merge_context_with_image_data(context, req.image_data)

                # N3) Build intent-routed prompt
                prompt = build_prompt(
                    context=merged_context,
                    question=req.question,
                    student_level=req.student_level,
                    history=req.history,
                    language=lang,
                    mode=req.mode,
                    intent=intent,
                    complexity=complexity,
                )

                # N4) Model tier
                model_tier = "faster" if intent == "greet" else _get_model_tier(req.user_plan or "free")
                logger.info("User plan: %s → model_tier: %s", req.user_plan, model_tier)

            # Debug dump
            try:
                with open("prompt.txt", "w") as f:
                    f.write(prompt)
            except Exception:
                pass

            # ── Status before the main (slowest) LLM call ────────────────────
            if req.mode == "blackboard":
                yield _status_frame("🎨 Preparing your blackboard...", 58)
            else:
                yield _status_frame("💡 Writing your answer...", 55)

            # 4) LLM call — use Strands agent or direct LLM based on config
            loop = asyncio.get_event_loop()
            try:
                if settings.USE_AGENT and not normalized_images:
                    # Strands agent path (text-only; images not supported)
                    logger.info("Using Strands agent for this request")
                    agent_result = await run_agent(prompt)
                    result: Dict[str, Any] = {
                        "text": agent_result["text"],
                        "tokens": {
                            "inputTokens": agent_result["inputTokens"],
                            "outputTokens": agent_result["outputTokens"],
                            "totalTokens": agent_result["totalTokens"],
                        },
                    }
                else:
                    # ── Separate system prompt from user content (enables caching) ──
                    system_prompt = None
                    user_content = prompt

                    if req.mode == "blackboard_intent":
                        system_prompt = _BB_INTENT_SYSTEM_PROMPT
                        user_content = req.question  # "Topic: ...\nRequested number of steps: N"
                    elif req.mode == "normal":
                        system_prompt = get_normal_mode_system_prompt()
                        user_content = build_normal_mode_user_content(
                            context=merged_context,
                            history="\n".join(req.history or []),
                            question=req.question,
                            intent=intent,
                            complexity=complexity,
                            student_level=req.student_level or 5,
                        )
                    elif req.mode == "blackboard":
                        system_prompt = get_blackboard_mode_system_prompt()
                        user_content = build_blackboard_mode_user_content(
                            context=str(context) if context else "",
                            question=req.question,
                            level=req.student_level or 5,
                            history=req.history or [],
                            plan=plan,
                            lang=lang,
                            image_data=req.image_data,
                        )
                    
                    # Run the LLM in the background and emit SSE keepalive pings
                    # every 3 s so Android's read-timeout doesn't fire while we wait.
                    # (BB model typically takes 8-15 s; normal is 2-5 s.)
                    _llm_future = loop.run_in_executor(
                        None,
                        lambda: generate_response(
                            user_content,
                            normalized_images,
                            tier=model_tier,
                            system_prompt=system_prompt,
                        ),
                    )
                    while not _llm_future.done():
                        await asyncio.sleep(3)
                        if not _llm_future.done():
                            yield ": ping\n\n"  # SSE comment = keepalive, invisible to app
                    result: Dict[str, Any] = await _llm_future
                
                # Check for valid response structure
                if not result or not isinstance(result, dict):
                    logger.error(f"Invalid LLM response: {type(result)}")
                    yield f"data: {json.dumps({'error': 'Invalid LLM response'})}\n\n"
                    return
                
                # Check if this is an error response from LiteLLM
                if result.get("provider") == "error":
                    logger.error(f"LLM error response: {result.get('error', 'Unknown error')}")
                    error_msg = result.get("error", "LLM service error")
                    yield f"data: {json.dumps({'error': error_msg})}\n\n"
                    return
                
                logger.info(f"LLM response received | provider={result.get('provider')} | model={result.get('model')}")

            except Exception as e:
                logger.error(f"LLM call failed: {e}")
                # Fallback: try with no images to avoid image validation errors
                if normalized_images:
                    logger.info("Retrying without images...")
                    try:
                        result = await loop.run_in_executor(
                            None, lambda: generate_response(user_content, [], tier=model_tier, system_prompt=system_prompt)
                        )
                        if not result or not isinstance(result, dict):
                            logger.error(f"Invalid LLM response on image-less retry: {type(result)}")
                            yield f"data: {json.dumps({'error': 'LLM returned empty response'})}\n\n"
                            return
                    except Exception as e2:
                        logger.error(f"LLM call failed even without images: {e2}")
                        yield f"data: {json.dumps({'error': str(e2)})}\n\n"
                        return
                else:
                    yield f"data: {json.dumps({'error': str(e)})}\n\n"
                    return
            
            # BB post-processing: image title matching (LLM-powered + pre-fetched Wikimedia)
            if req.mode == "blackboard":
                yield _status_frame("🖼️ Matching visuals to your lesson...", 87)
                try:
                    # wiki_task is None when bb_images_enabled=False
                    if wiki_task is not None:
                        while not wiki_task.done():
                            await asyncio.sleep(2)
                            if not wiki_task.done():
                                yield ": ping\n\n"
                        extra_wiki = wiki_task.result()
                    else:
                        extra_wiki = []

                    # get_titles does per-step Wikimedia searches + LLM image picker;
                    # run it as a task so we can interleave keepalive pings.
                    _titles_task = asyncio.ensure_future(
                        get_titles(result["text"], extra_candidates=extra_wiki)
                    )
                    while not _titles_task.done():
                        await asyncio.sleep(2)
                        if not _titles_task.done():
                            yield ": ping\n\n"
                    result["text"] = await _titles_task
                    logger.info("BB image matching complete")
                except Exception as e:
                    logger.warning("BB image matching failed: %s — using raw LLM output", e)
                # Emit 99% progress so Android knows data is coming
                yield _status_frame("✅ Building your lesson...", 99)

            # 5) Emit page_transcript BEFORE the answer so Android can persist
            #    it to Firestore system-context as early as possible.
            try:
                with open("response.json", "w") as f:
                    json.dump(result, f, indent=2)
            except Exception as e:
                logger.warning(f"Failed to write response.json: {e}")

            page_transcript = _extract_page_transcript(result, normalized_images, req.image_data)
            if page_transcript:
                logger.info(
                    "Emitting page_transcript (%d chars) for page_id=%s",
                    len(page_transcript), req.page_id,
                )
                yield f"data: {json.dumps({'page_transcript': page_transcript})}\n\n"

            # 6) Answer text
            text_content = result.get('text', '')
            if not text_content:
                logger.warning("No text content in response")
                text_content = "[No response generated]"

            # Sanitize LLM output: strip fences, fix trailing commas, coerce field types.
            # This prevents JSON parsing errors on Android without fabricating content.
            cached_tokens = result.get("tokens", {}).get("cachedTokens", 0)
            logger.info(
                "Sanitizing response | mode=%s | text_len=%d | cached_tokens=%d",
                req.mode, len(text_content), cached_tokens,
            )
            # Save pre-sanitize BB text for post-mortem debugging
            if req.mode == "blackboard":
                try:
                    with open("bb_presanitize.json", "w") as _f:
                        _f.write(text_content)
                except Exception:
                    pass
            if req.mode == "blackboard":
                text_content = _sanitize_bb_response(text_content)
                # Attach YouTube clips gathered concurrently during LLM call
                if yt_task is not None:
                    try:
                        yt_wait_timeout = max(3.0, float(getattr(settings, "YT_ENRICHMENT_TIMEOUT", 2.5)))
                        if yt_task.done():
                            yt_clips = yt_task.result()
                            logger.info("BB YouTube enrichment task already done")
                        else:
                            yt_clips = await asyncio.wait_for(yt_task, timeout=yt_wait_timeout)
                            logger.info("BB YouTube enrichment awaited | timeout=%.2fs", yt_wait_timeout)
                        text_content = _attach_video_clips(text_content, yt_clips)
                        logger.info(
                            "BB YouTube clips ready | attached_candidates=%d",
                            sum(1 for c in (yt_clips or []) if c),
                        )
                    except asyncio.TimeoutError:
                        logger.info("BB YouTube enrichment still running; response sent without waiting")
                    except Exception as exc:
                        logger.warning("BB YouTube enrichment attach failed: %s", exc)
            elif req.mode == "normal":
                text_content = _sanitize_normal_response(text_content)

            logger.info("Emitting main data frame | text_len=%d", len(text_content))

            # Send the full JSON envelope as-is so the client can store every field
            # (user_question, answer, user_attachment_transcription,
            #  extra_details_or_summary) in Firestore.
            # Blackboard mode already returns its own JSON for BlackboardGenerator.
            # Android extracts only the 'answer' field for display.
            yield f"data: {json.dumps({'text': text_content, 'cached': cached_tokens > 0})}\n\n"

            logger.info("Main data frame sent")

            # 6b) Determine whether the LLM thinks this answer is worth showing
            #     on the blackboard (only for normal chat mode, not blackboard itself).
            suggest_bb = False
            if req.mode != "blackboard":
                try:
                    parsed_resp = extract_json_safe(text_content)
                    suggest_bb = bool(parsed_resp.get("suggest_blackboard", False))
                except Exception:
                    pass

            # 7) Done frame
            tokens = result.get("tokens", {})
            if tokens:
                in_t  = tokens.get("inputTokens", 0)
                out_t = tokens.get("outputTokens", 0)
                tot_t = tokens.get("totalTokens", 0)
                yield (
                    "data: "
                    + json.dumps(
                        {
                            "done": True,
                            "suggest_blackboard": suggest_bb,
                            "inputTokens": in_t,
                            "outputTokens": out_t,
                            "totalTokens": tot_t,
                        }
                    )
                    + "\n\n"
                )
                # 8) Fire-and-forget: update token counters in Firestore
                if req.user_id:
                    asyncio.get_event_loop().run_in_executor(
                        None,
                        user_service.record_tokens,
                        req.user_id, in_t, out_t, tot_t,
                    )
            else:
                yield f"data: {json.dumps({'done': True, 'suggest_blackboard': suggest_bb})}\n\n"

            logger.info("Done frame sent | mode=%s", req.mode)

        except GeneratorExit:
            logger.warning("Client disconnected (GeneratorExit) — stream was closed early")
        except BrokenPipeError as exc:
            logger.warning("Client disconnected (BrokenPipeError): %s", exc)
        except GeneratorExit:
            # Android client disconnected (read-timeout or user navigated away).
            # GeneratorExit is a BaseException, not Exception — log it and exit cleanly.
            logger.info("chat_stream: client disconnected (GeneratorExit)")
            return
        except Exception as exc:
            logger.exception("Error in chat_stream: %s", exc)
            try:
                yield f"data: {json.dumps({'error': str(exc)})}\n\n"
            except Exception:
                pass  # client already gone

    return StreamingResponse(
        generator(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",
        },
    )

    