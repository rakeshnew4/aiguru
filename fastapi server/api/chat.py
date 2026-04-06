import asyncio
import json
from typing import Optional, List, Dict, Any

from fastapi import APIRouter
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field
from app.services.context_service import get_context
from app.services.prompt_service import build_prompt
from app.services.llm_service import generate_response
from app.core.logger import get_logger
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
        "free": "cheaper",      # Free users get balanced models
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


def _extract_page_transcript(
    result: Dict[str, Any],
    images: List[str],
    image_data: Optional[Dict[str, Any]],
) -> Optional[str]:
    """
    Returns a transcript string to send back to the Android app so it can
    persist the image analysis into the Firestore chapter system context.

    Priority:
      1. result["page_transcript"] — if generate_response() extracts it from vision
      2. result["transcript"]      — alternate key
      3. image_data["transcript"]  — client already analysed; echo it back
      4. None                      — no image attached, skip the frame
    """
    if not images and not image_data:
        return None

    transcript = (
        result.get("page_transcript")
        or result.get("transcript")
        or (image_data or {}).get("transcript")
    )
    return str(transcript).strip() or None if transcript else None

def extract_json_safe(text):
    import re
    import json

    # Case 1: ```json block
    match = re.search(r"```json\s*(\{.*?\})\s*```", text, re.DOTALL)
    if match:
        return json.loads(match.group(1))

    # Case 2: plain JSON
    match = re.search(r"(\{.*\})", text, re.DOTALL)
    if match:
        return json.loads(match.group(1))

    raise text
@router.post("/chat-stream")
async def chat_stream(req: ChatRequest):
    async def generator():
        try:
            # 1) context fetch
            context = get_context(req.page_id)
            merged_context = _merge_context_with_image_data(context, req.image_data)

            # 2) prompt build
            prompt = build_prompt(
                context=merged_context,
                question=req.question,
                student_level=req.student_level,
                history=req.history,
                language=req.language_tag or req.language,
                mode=req.mode,
            )

            # 3) image normalisation
            normalized_images = _normalize_images(req)

            # 3b) determine model tier based on user plan
            model_tier = _get_model_tier(req.user_plan or "free")
            logger.info(f"User plan: {req.user_plan} → Model tier: {model_tier}")

            # 4) LLM call in thread
            loop = asyncio.get_event_loop()
            try:
                result: Dict[str, Any] = await loop.run_in_executor(
                    None, lambda: generate_response(prompt, normalized_images, tier=model_tier)
                )
                
                if not result or not isinstance(result, dict):
                    logger.error(f"Invalid LLM response: {type(result)}")
                    yield f"data: {json.dumps({'error': 'Invalid LLM response'})}\n\n"
                    return
                    
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
            
            # 4b) Async image title matching (runs in parallel for speed)
            try:
                result['text'] = await get_titles(result['text'])
            except Exception as e:
                logger.warning(f"Image title matching failed: {e}. Continuing with original text.")
                # Continue with original text if image title matching fails
            # 5) Emit page_transcript BEFORE the answer so Android can persist
            #    it to Firestore system-context as early as possible.
            try:
                with open("response.txt", "w") as f:
                    f.write(str(result.get('text', '')))
            except Exception as e:
                logger.warning(f"Failed to write response.txt: {e}")
            
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
            
            yield f"data: {json.dumps({'text': text_content, 'cached': False})}\n\n"

            # 7) Done frame
            tokens = result.get("tokens", {})
            if tokens:
                yield (
                    "data: "
                    + json.dumps(
                        {
                            "done": True,
                            "inputTokens": tokens.get("inputTokens", 0),
                            "outputTokens": tokens.get("outputTokens", 0),
                            "totalTokens": tokens.get("totalTokens", 0),
                        }
                    )
                    + "\n\n"
                )
            else:
                yield f"data: {json.dumps({'done': True})}\n\n"

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