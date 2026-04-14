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
)
from app.services.llm_service import generate_response
from app.services.strands_agent import run_agent
from app.services import user_service
from app.core.config import settings
from app.core.logger import get_logger
from app.core.auth import require_auth, AuthUser
from app.api.image_search_titles import search_wikimedia_images, get_titles

logger = get_logger(__name__)
router = APIRouter(prefix="", tags=["chat"])


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


def _classify_intent(question: str, has_image: bool, last_reply: str) -> dict:
    """
    Fast intent classification using the 'faster' model tier (gemini-2.0-flash).
    Returns {"intent": str, "complexity": str}.
    Falls back to {"intent": "explain", "complexity": "medium"} on any error.
    """
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
    Returns plan dict with topic_type, scope, steps_count, key_concepts, image_search_terms.
    Falls back to safe defaults on any error.
    """
    defaults = {
        "topic_type": "other",
        "scope": "medium",
        "key_concepts": [],
        "steps_count": 5,
        "image_search_terms": [],
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
        titles = []
        for r in results:
            if isinstance(r, list):
                titles.extend(r)
        return titles
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

def extract_json_safe(text):
    """
    Robustly extract and parse the first top-level JSON object from text.
    Handles:
      - Bare JSON
      - ```json ... ``` fenced JSON
      - Prefix/suffix prose around the JSON
      - Nested objects / arrays / LaTeX braces inside string values
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
                    return json.loads(candidate)
        i += 1

    raise ValueError("Unmatched braces — no complete JSON object found")


def _status_frame(message: str, progress: int) -> str:
    """Return a progress-status SSE data frame so the UI can show activity."""
    return f"data: {json.dumps({'status': message, 'progress': progress})}\n\n"


@router.post("/chat-stream")
async def chat_stream(req: ChatRequest, auth: AuthUser = Depends(require_auth)):
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

                # B2) Start Wikimedia pre-fetch NOW — runs while BB LLM is running (free)
                wiki_task = asyncio.ensure_future(
                    _prefetch_wikimedia(plan.get("image_search_terms", []))
                )

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
                    result: Dict[str, Any] = await loop.run_in_executor(
                        None, lambda: generate_response(prompt, normalized_images, tier=model_tier)
                    )
                
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
                            None, lambda: generate_response(prompt, [], tier=model_tier)
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
                    extra_wiki = await wiki_task  # pre-fetched while BB LLM was running
                    result["text"] = await get_titles(result["text"], extra_candidates=extra_wiki)
                    logger.info("BB image matching complete")
                except Exception as e:
                    logger.warning("BB image matching failed: %s — using raw LLM output", e)
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

            # Send the full JSON envelope as-is so the client can store every field
            # (user_question, answer, user_attachment_transcription,
            #  extra_details_or_summary) in Firestore.
            # Blackboard mode already returns its own JSON for BlackboardGenerator.
            # Android extracts only the 'answer' field for display.
            yield f"data: {json.dumps({'text': text_content, 'cached': False})}\n\n"

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

        except Exception as exc:
            logger.exception("Error in chat_stream: %s", exc)
            yield f"data: {json.dumps({'error': str(exc)})}\n\n"

    return StreamingResponse(
        generator(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",
        },
    )

    