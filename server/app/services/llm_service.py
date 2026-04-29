import base64
import json
from typing import List, Literal, Optional, Dict, Any

import httpx
from google import genai
from google.genai import types as genai_types

from app.core.config import settings, ModelConfig
from app.core.logger import get_logger

logger = get_logger(__name__)

# ── Per-user LiteLLM key cache (uid -> key) ───────────────────────────────────
_user_key_cache: Dict[str, str] = {}


def get_or_create_litellm_key(uid: str) -> Optional[str]:
    """Return the user's LiteLLM proxy key, creating and storing it if missing."""
    if uid in _user_key_cache:
        return _user_key_cache[uid]

    try:
        from app.core.firebase_auth import get_firestore_db
        db = get_firestore_db()
        if db is None:
            return None

        doc = db.collection("users_table").document(uid).get()
        key = (doc.to_dict() or {}).get("litellm_key") if doc.exists else None

        if not key:
            with httpx.Client(timeout=10.0) as client:
                resp = client.post(
                    f"{settings.LITELLM_PROXY_URL}/key/generate",
                    headers={"Authorization": f"Bearer {settings.LITELLM_MASTER_KEY}"},
                    json={
                        "user_id": uid,
                        "key_alias": f"user-{uid[:8]}",
                        "duration": "365d",
                    },
                )
                resp.raise_for_status()
                key = resp.json().get("key")

            if not key:
                logger.warning("get_or_create_litellm_key: empty key returned for uid=%s", uid)
                return None

            db.collection("users_table").document(uid).set({"litellm_key": key}, merge=True)
            logger.info("get_or_create_litellm_key: created and stored key for uid=%s", uid)

        _user_key_cache[uid] = key
        return key

    except Exception as exc:
        logger.warning("get_or_create_litellm_key uid=%s: %s", uid, exc)
        return None


# ── Google GenAI client (native SDK — used as LiteLLM fallback) ───────────────
_genai_client: Optional[genai.Client] = None


def _init_gemini() -> Optional[genai.Client]:
    if not settings.GEMINI_API_KEY:
        logger.warning("Google native client not initialized: GEMINI_API_KEY not set")
        return None
    try:
        client = genai.Client(api_key=settings.GEMINI_API_KEY)
        logger.info("Google native GenAI client initialized")
        return client
    except Exception as exc:
        logger.error("Failed to initialize Google GenAI client: %s", exc)
        return None


_genai_client = _init_gemini()


# ── Image helpers ─────────────────────────────────────────────────────────────
def _images_to_gemini_parts(images: List[str]) -> list:
    """Convert base64 data URIs or HTTPS URLs to Gemini Part objects."""
    parts = []
    for idx, img in enumerate(images):
        try:
            if not img or not isinstance(img, str):
                continue
            if img.startswith("data:"):
                header, data = img.split(",", 1)
                mime_type = header.split(":")[1].split(";")[0]
                if not mime_type.startswith("image/"):
                    logger.warning("Image %d: invalid MIME type '%s', skipping", idx, mime_type)
                    continue
                decoded = base64.b64decode(data)
                if not decoded:
                    continue
                parts.append(genai_types.Part.from_bytes(data=decoded, mime_type=mime_type))
            else:
                resp = httpx.get(img, timeout=15, follow_redirects=True)
                resp.raise_for_status()
                mime_type = resp.headers.get("content-type", "image/jpeg").split(";")[0]
                if not mime_type.startswith("image/"):
                    continue
                parts.append(genai_types.Part.from_bytes(data=resp.content, mime_type=mime_type))
        except Exception as exc:
            logger.warning("Image %d: skipped — %s", idx, exc)
    return parts


# ── Google native call (fallback when LiteLLM is unavailable) ─────────────────
def _call_gemini(
    prompt: str,
    model_config: ModelConfig,
    images: Optional[List[str]] = None,
    system_prompt: Optional[str] = None,
) -> Dict[str, Any]:
    """Call Gemini directly via google-genai SDK. Used as LiteLLM fallback."""
    if not _genai_client:
        raise RuntimeError("Google native client unavailable (GEMINI_API_KEY not set)")

    if images and model_config.supports_images:
        contents: Any = _images_to_gemini_parts(images) + [genai_types.Part.from_text(text=prompt)]
    else:
        if images:
            logger.warning("_call_gemini: images provided but model does not support them")
        contents = prompt

    cfg = genai_types.GenerateContentConfig(
        temperature=model_config.temperature,
        max_output_tokens=model_config.max_tokens,
    )
    if system_prompt:
        cfg.system_instruction = system_prompt

    response = _genai_client.models.generate_content(
        model=model_config.model_id,
        contents=contents,
        config=cfg,
    )

    usage = response.usage_metadata
    logger.info("Google native response: %d chars, %d tokens, model=%s",
                len(response.text), usage.total_token_count, model_config.model_id)
    return {
        "text": response.text,
        "tokens": {
            "inputTokens":  usage.prompt_token_count,
            "outputTokens": usage.candidates_token_count,
            "totalTokens":  usage.total_token_count,
            "cachedTokens": 0,
        },
        "provider": "google_native",
        "model": model_config.model_id,
    }


# ── LLM call logging ──────────────────────────────────────────────────────────
_LLM_LOG_DIR = "llm_logs"


def _log_llm_call(call_name: str, prompt: str, system_prompt: Optional[str],
                  response_text: Optional[str], session_id: Optional[str] = None) -> None:
    try:
        import os
        from datetime import datetime
        os.makedirs(_LLM_LOG_DIR, exist_ok=True)
        path = os.path.join(_LLM_LOG_DIR, f"{call_name}.txt")
        with open(path, "w", encoding="utf-8") as f:
            f.write(f"=== {call_name.upper()} | {datetime.now().strftime('%Y-%m-%d %H:%M:%S')} | session={session_id or 'none'} ===\n\n")
            if system_prompt:
                f.write("=== SYSTEM ===\n")
                f.write(system_prompt)
                f.write("\n\n")
            f.write("=== PROMPT ===\n")
            f.write(prompt)
        with open(path, "a", encoding="utf-8") as f:
            f.write("\n\n=== RESPONSE ===\n")
            f.write(response_text or "[empty]")
    except Exception as exc:
        logger.warning("_log_llm_call failed for %s: %s", call_name, exc)


# ── LiteLLM proxy call (primary path) ────────────────────────────────────────
def _call_litellm_proxy(
    prompt: str,
    model_config: ModelConfig,
    images: Optional[List[str]] = None,
    system_prompt: Optional[str] = None,
    uid: Optional[str] = None,
    call_name: str = "llm_call",
    session_id: Optional[str] = None,
) -> Dict[str, Any]:
    """Call Gemini via LiteLLM proxy using the user's per-user key."""
    messages = []
    if system_prompt:
        messages.append({
            "role": "system",
            "content": system_prompt,
            "cache_control": {"type": "ephemeral"},
        })

    if images:
        content: Any = (
            [{"type": "image_url", "image_url": {"url": img}} for img in images]
            + [{"type": "text", "text": prompt}]
        )
        logger.info("LiteLLM multimodal request: %d image(s) + text", len(images))
    else:
        content = prompt

    messages.append({"role": "user", "content": content})

    auth_key = settings.LITELLM_MASTER_KEY
    if uid and uid != "guest_user":
        user_key = get_or_create_litellm_key(uid)
        if user_key:
            auth_key = user_key

    model = model_config.model_id
    logger.debug("LiteLLM proxy request: model=%s uid=%s images=%d",
                 model, uid, len(images) if images else 0)

    with httpx.Client(timeout=300.0) as client:
        resp = client.post(
            f"{settings.LITELLM_PROXY_URL}/v1/chat/completions",
            headers={
                "Authorization": f"Bearer {auth_key}",
                "Content-Type": "application/json",
            },
            json={
                "model": model,
                "messages": messages,
                "temperature": model_config.temperature,
                "max_tokens": model_config.max_tokens,
                "cache": {"no-cache": False},
                "metadata": {
                    "call_name": call_name,
                    "uid": uid or "guest",
                    "session_id": session_id or "",
                },
            },
        )

    if resp.status_code != 200:
        raise RuntimeError(f"LiteLLM proxy error {resp.status_code}: {resp.text[:300]}")

    data = resp.json()
    if not data.get("choices") or not data["choices"][0].get("message"):
        raise RuntimeError("LiteLLM response missing choices[0].message")

    text = data["choices"][0]["message"]["content"]
    if not text:
        logger.warning("LiteLLM returned empty text content")

    _log_llm_call(call_name, prompt, system_prompt, text, session_id=session_id)

    usage = data.get("usage", {})
    result = {
        "text": text,
        "tokens": {
            "inputTokens":  usage.get("prompt_tokens", 0),
            "outputTokens": usage.get("completion_tokens", 0),
            "totalTokens":  usage.get("total_tokens", 0),
            "cachedTokens": usage.get("prompt_tokens_details", {}).get("cached_tokens", 0),
        },
        "provider": "litellm",
        "model": model,
    }
    logger.info("LiteLLM success: model=%s uid=%s tokens=%d",
                model, uid, result["tokens"]["totalTokens"])
    return result


# ── Main interface ────────────────────────────────────────────────────────────
def generate_response(
    prompt: str,
    images: Optional[List[str]] = None,
    tier: Literal["power", "cheaper", "faster"] = "cheaper",
    system_prompt: Optional[str] = None,
    uid: Optional[str] = None,
    call_name: str = "llm_call",
    session_id: Optional[str] = None,
    charge_credits: bool = True,
) -> Dict[str, Any]:
    """
    Generate LLM response.

    Primary path  : LiteLLM proxy (localhost:8005) — handles per-user keys,
                    caching, and usage tracking.
    Fallback path : Google native SDK (google-genai) — used when LiteLLM is
                    unavailable or returns an error.

    Both paths use the same model IDs (gemini-2.5-flash / gemini-2.5-flash-lite)
    so behaviour is identical regardless of which path is taken.
    """
    logger.info("generate_response | tier=%s | images=%d | system=%s",
                tier, len(images) if images else 0, "yes" if system_prompt else "no")

    model_config = settings.get_model_config(tier)

    result: Optional[Dict[str, Any]] = None
    last_error: Optional[Exception] = None

    # ── 1. Try LiteLLM proxy ──────────────────────────────────────────────────
    if settings.USE_LITELLM_PROXY:
        try:
            result = _call_litellm_proxy(
                prompt, model_config, images,
                system_prompt=system_prompt, uid=uid,
                call_name=call_name, session_id=session_id,
            )
        except Exception as exc:
            last_error = exc
            logger.error("LiteLLM FAILED (tier=%s uid=%s call=%s): %s",
                         tier, uid, call_name, exc)
            logger.warning("Falling back to Google native SDK")

    # ── 2. Google native fallback ─────────────────────────────────────────────
    if result is None:
        try:
            result = _call_gemini(prompt, model_config, images, system_prompt)
            _log_llm_call(call_name, prompt, system_prompt, result.get("text"), session_id=session_id)
        except Exception as exc:
            last_error = exc
            logger.error("Google native fallback also failed (tier=%s): %s", tier, exc)

    if result is None or not result.get("text"):
        logger.error("All LLM paths failed for tier=%s: %s", tier, last_error)
        return {
            "text": f"[LLM SERVICE ERROR] {str(last_error)[:200]}",
            "tokens": {"inputTokens": 0, "outputTokens": 0, "totalTokens": 0, "cachedTokens": 0},
            "provider": "error",
            "model": f"error-{tier}",
            "error": str(last_error),
        }

    # ── Record token usage ────────────────────────────────────────────────────
    try:
        with open("response.json", "w") as f:
            json.dump(result, f, indent=2)
    except Exception:
        pass

    if uid and uid != "guest_user" and charge_credits:
        tok = result.get("tokens", {})
        tot_t = tok.get("totalTokens", 0)
        if tot_t > 0:
            try:
                from app.services.user_service import record_tokens as _record_tokens
                import threading
                threading.Thread(
                    target=_record_tokens,
                    args=(uid, tok.get("inputTokens", 0), tok.get("outputTokens", 0), tot_t),
                    daemon=True,
                ).start()
            except Exception as exc:
                logger.warning("generate_response: token record failed uid=%s: %s", uid, exc)

    return result
