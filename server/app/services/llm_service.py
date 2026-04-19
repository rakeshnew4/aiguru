import base64
import json
import http.client
from typing import List, Literal, Optional, Dict, Any

import httpx
import boto3
from groq import Groq
from google import genai
from google.genai import types as genai_types

from app.core.config import settings, ModelConfig
from app.core.logger import get_logger

logger = get_logger(__name__)

# ── Client Initialization (once at module load) ───────────────────────────────

def _init_gemini():
    if not settings.GEMINI_API_KEY:
        logger.warning("Gemini client not initialized: GEMINI_API_KEY not set")
        return None
    try:
        client = genai.Client(api_key=settings.GEMINI_API_KEY)
        logger.info("Gemini client initialized")
        return client
    except Exception as e:
        logger.error(f"Failed to initialize Gemini client: {e}")
        return None

def _init_groq():
    if not settings.GROQ_API_KEY:
        logger.warning("Groq client not initialized: GROQ_API_KEY not set")
        return None
    try:
        client = Groq(api_key=settings.GROQ_API_KEY)
        logger.info("Groq client initialized")
        return client
    except Exception as e:
        logger.error(f"Failed to initialize Groq client: {e}")
        return None

def _init_bedrock():
    try:
        if settings.AWS_ACCESS_KEY_ID and settings.AWS_SECRET_ACCESS_KEY:
            client = boto3.client(
                "bedrock-runtime",
                aws_access_key_id=settings.AWS_ACCESS_KEY_ID,
                aws_secret_access_key=settings.AWS_SECRET_ACCESS_KEY,
                region_name=settings.AWS_REGION,
            )
            logger.info(f"Bedrock client initialized with access keys (region: {settings.AWS_REGION})")
            return client
        elif settings.AWS_BEARER_TOKEN_BEDROCK:
            import os
            os.environ['AWS_BEARER_TOKEN_BEDROCK'] = settings.AWS_BEARER_TOKEN_BEDROCK
            client = boto3.client(
                "bedrock-runtime",
                region_name=settings.AWS_REGION,
            )
            logger.info(f"Bedrock client initialized with bearer token (region: {settings.AWS_REGION})")
            return client
        else:
            logger.warning("Bedrock client not initialized: provide AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY or AWS_BEARER_TOKEN_BEDROCK")
            return None
    except Exception as e:
        logger.error(f"Failed to initialize Bedrock client: {e}")
        return None

_genai_client = _init_gemini()
_groq_client = _init_groq()
_bedrock_client = _init_bedrock()


# ── Image Processing Utilities ────────────────────────────────────────────────
def _images_to_gemini_parts(images: List[str]) -> list:
    """
    Convert images to Gemini Part objects with validation.
    Supports base64 data URIs and HTTPS URLs.
    Invalid images are skipped with warning logs.
    """
    parts = []
    for idx, img in enumerate(images):
        try:
            if not img or not isinstance(img, str):
                logger.warning(f"Image {idx}: Invalid type (expected string), skipping")
                continue
            
            if img.startswith("data:"):
                # data:image/jpeg;base64,<data>
                try:
                    header, data = img.split(",", 1)
                    mime_type = header.split(":")[1].split(";")[0]
                    
                    # Validate MIME type is image/*
                    if not mime_type.startswith("image/"):
                        logger.warning(f"Image {idx}: Invalid MIME type '{mime_type}', skipping")
                        continue
                    
                    # Validate base64 is decodable
                    decoded = base64.b64decode(data)
                    if len(decoded) == 0:
                        logger.warning(f"Image {idx}: Empty base64 data, skipping")
                        continue
                    
                    parts.append(
                        genai_types.Part.from_bytes(
                            data=decoded, mime_type=mime_type
                        )
                    )
                except (ValueError, AssertionError) as e:
                    logger.warning(f"Image {idx}: Failed to decode base64: {e}, skipping")
                    continue
            else:
                # Remote URL — download and inline
                try:
                    resp = httpx.get(img, timeout=15, follow_redirects=True)
                    resp.raise_for_status()
                    mime_type = resp.headers.get("content-type", "image/jpeg").split(";")[0]
                    
                    # Validate MIME type is image/*
                    if not mime_type.startswith("image/"):
                        logger.warning(f"Image {idx}: URL returned non-image MIME type '{mime_type}', skipping")
                        continue
                    
                    parts.append(
                        genai_types.Part.from_bytes(data=resp.content, mime_type=mime_type)
                    )
                except Exception as e:
                    logger.warning(f"Image {idx}: Failed to download from URL: {e}, skipping")
                    continue
        except Exception as e:
            logger.warning(f"Image {idx}: Unexpected error: {e}, skipping")
            continue
    
    if not parts and images:
        logger.warning(f"No valid images found ({len(images)} provided, all skipped)")
    
    return parts


def _images_to_bedrock_content(images: List[str]) -> list:
    """
    Convert images to Bedrock converse() API content format with validation.
    Returns list of image content blocks for Claude.
    Invalid images are skipped with warning logs.
    """
    content_blocks = []
    
    for idx, img in enumerate(images):
        try:
            if not img or not isinstance(img, str):
                logger.warning(f"Image {idx}: Invalid type (expected string), skipping")
                continue
            
            if img.startswith("data:"):
                # Extract base64 data and mime type
                try:
                    header, data = img.split(",", 1)
                    mime_type = header.split(":")[1].split(";")[0]
                    
                    # Validate MIME type is image/*
                    if not mime_type.startswith("image/"):
                        logger.warning(f"Image {idx}: Invalid MIME type '{mime_type}', skipping")
                        continue
                    
                    # Validate base64 is decodable
                    try:
                        decoded = base64.b64decode(data)
                        if len(decoded) == 0:
                            logger.warning(f"Image {idx}: Empty base64 data, skipping")
                            continue
                    except Exception as e:
                        logger.warning(f"Image {idx}: Failed to decode base64: {e}, skipping")
                        continue
                    
                    # Map MIME types to Bedrock image formats
                    format_map = {
                        "image/jpeg": "jpeg",
                        "image/jpg": "jpeg",
                        "image/png": "png",
                        "image/gif": "gif",
                        "image/webp": "webp",
                    }
                    image_format = format_map.get(mime_type.lower())
                    
                    if not image_format:
                        logger.warning(f"Image {idx}: Unsupported format '{mime_type}', skipping")
                        continue
                    
                    # Bedrock converse() 'bytes' expects raw binary bytes, not base64 string
                    content_blocks.append({
                        "image": {
                            "format": image_format,
                            "source": {
                                "bytes": decoded,  # Raw binary bytes (already decoded above)
                            },
                        },
                    })
                    logger.info(f"Image {idx}: Valid base64 ({image_format}, {len(decoded)} bytes) added")
                except Exception as e:
                    logger.warning(f"Image {idx}: Failed to parse data URI: {e}, skipping")
                    continue
            else:
                # Download URL and convert to base64
                try:
                    resp = httpx.get(img, timeout=15, follow_redirects=True)
                    resp.raise_for_status()
                    mime_type = resp.headers.get("content-type", "image/jpeg").split(";")[0]
                    
                    # Validate MIME type is image/*
                    if not mime_type.startswith("image/"):
                        logger.warning(f"Image {idx}: URL returned non-image MIME type '{mime_type}', skipping")
                        continue
                    
                    # Map MIME types to Bedrock image formats
                    format_map = {
                        "image/jpeg": "jpeg",
                        "image/jpg": "jpeg",
                        "image/png": "png",
                        "image/gif": "gif",
                        "image/webp": "webp",
                    }
                    image_format = format_map.get(mime_type.lower())
                    
                    if not image_format:
                        logger.warning(f"Image {idx}: Unsupported format '{mime_type}', skipping")
                        continue
                    
                    # Bedrock converse() 'bytes' expects raw binary bytes
                    content_blocks.append({
                        "image": {
                            "format": image_format,
                            "source": {
                                "bytes": resp.content,  # Raw binary bytes directly
                            },
                        },
                    })
                    logger.info(f"Image {idx}: Valid URL ({image_format}, {len(resp.content)} bytes) added")
                except Exception as e:
                    logger.warning(f"Image {idx}: Failed to download from URL: {e}, skipping")
                    continue
        except Exception as e:
            logger.warning(f"Image {idx}: Unexpected error: {e}, skipping")
            continue
    
    if not content_blocks and images:
        logger.warning(f"No valid images found ({len(images)} provided, all skipped)")
    
    return content_blocks


# ── Provider-Specific LLM Calls ───────────────────────────────────────────────
def _call_gemini(
    prompt: str,
    model_config: ModelConfig,
    images: Optional[List[str]] = None
) -> Dict[str, Any]:
    """Call Google Gemini API with comprehensive error handling."""
    if not _genai_client:
        raise ValueError("GEMINI_API_KEY not configured")
    
    try:
        # Build contents: image parts first, then text prompt
        if images and model_config.supports_images:
            contents: list = _images_to_gemini_parts(images) + [
                genai_types.Part.from_text(text=prompt)
            ]
        else:
            if images:
                logger.warning("Images provided but Gemini model doesn't support them")
            contents = prompt
        
        response = _genai_client.models.generate_content(
            model=model_config.model_id,
            contents=contents,
            config=genai_types.GenerateContentConfig(
                temperature=model_config.temperature,
                max_output_tokens=model_config.max_tokens,
            ),
        )
        
        usage = response.usage_metadata
        logger.info(f"Gemini response: {len(response.text)} chars, {usage.total_token_count} tokens")
        
        return {
            "text": response.text,
            "tokens": {
                "inputTokens": usage.prompt_token_count,
                "outputTokens": usage.candidates_token_count,
                "totalTokens": usage.total_token_count,
            },
            "provider": "gemini",
            "model": model_config.model_id,
        }
    
    except Exception as e:
        logger.error(f"Gemini API error: {type(e).__name__}: {e}")
        raise


def _call_groq(
    prompt: str,
    model_config: ModelConfig,
    images: Optional[List[str]] = None
) -> Dict[str, Any]:
    """Call Groq API with comprehensive error handling."""
    if not _groq_client:
        raise ValueError("GROQ_API_KEY not configured")
    
    try:
        if images:
            logger.warning("Groq does not support images; images will be ignored")
        
        response = _groq_client.chat.completions.create(
            model=model_config.model_id,
            messages=[{"role": "user", "content": prompt}],
            temperature=model_config.temperature,
            max_tokens=model_config.max_tokens,
        )
        
        usage = response.usage
        logger.info(f"Groq response: {len(response.choices[0].message.content)} chars, {usage.total_tokens} tokens")
        
        return {
            "text": response.choices[0].message.content,
            "tokens": {
                "inputTokens": usage.prompt_tokens,
                "outputTokens": usage.completion_tokens,
                "totalTokens": usage.total_tokens,
            },
            "provider": "groq",
            "model": model_config.model_id,
        }
    
    except Exception as e:
        logger.error(f"Groq API error: {type(e).__name__}: {e}")
        raise


def _call_bedrock(
    prompt: str,
    model_config: ModelConfig,
    images: Optional[List[str]] = None
) -> Dict[str, Any]:
    """Call AWS Bedrock (Claude) API using converse method.
    
    Supports Claude 3 Haiku, Sonnet, and Opus models.
    """
    if not _bedrock_client:
        raise ValueError("AWS Bedrock not configured (missing AWS credentials or bearer token)")
    
    # Build content blocks for converse() API
    # Format: each block is one of {"text": ...}, {"image": ...}, etc.
    content_blocks = []
    if images and model_config.supports_images:
        content_blocks.extend(_images_to_bedrock_content(images))
    elif images:
        logger.warning("Images provided but model doesn't support them")
    
    # Add text prompt (must be a dict with "text" key, no "type" wrapper)
    content_blocks.append({"text": prompt})
    
    # Build messages for converse API
    messages = [{
        "role": "user",
        "content": content_blocks,
    }]
    
    logger.info(f"Calling Bedrock model: {model_config.model_id} with {len(content_blocks)} content blocks")
    
    try:
        # Use converse API (recommended for Claude 3)
        response = _bedrock_client.converse(
            modelId=model_config.model_id,
            messages=messages,
            inferenceConfig={
                "maxTokens": model_config.max_tokens,
                "temperature": model_config.temperature,
            },
        )
        
        # Extract response
        response_text = response["output"]["message"]["content"][0]["text"]
        usage = response.get("usage", {})
        
        logger.info(f"Bedrock response received: {len(response_text)} chars")
        
        return {
            "text": response_text,
            "tokens": {
                "inputTokens": usage.get("inputTokens", 0),
                "outputTokens": usage.get("outputTokens", 0),
                "totalTokens": usage.get("inputTokens", 0) + usage.get("outputTokens", 0),
            },
            "provider": "bedrock",
            "model": model_config.model_id,
        }
        
    except Exception as e:
        logger.error(f"Bedrock API error: {e}")
        raise


# ── Main LLM Interface ────────────────────────────────────────────────────────
def _call_litellm_proxy(
    prompt: str,
    model_config,
    images: Optional[List[str]] = None,
    system_prompt: Optional[str] = None,
) -> Dict[str, Any]:
    """Call LLM through the LiteLLM proxy using OpenAI-compatible vision format.

    When system_prompt is provided it is sent as role="system" (the first message).
    LiteLLM translates this to:
      - Gemini: systemInstruction  → qualifies for implicit caching (≥1024 tokens)
      - Anthropic: system block   → add cache_control in the call for explicit caching
      - OpenAI / Groq: standard system message
    """
    from urllib.parse import urlparse
    proxy_url = settings.LITELLM_PROXY_URL  # e.g. http://localhost:8005
    parsed = urlparse(proxy_url)
    host = parsed.hostname
    port = parsed.port or 80

    # Build messages list
    messages = []

    # System message (static, cache-eligible) — sent first so it forms the cached prefix
    if system_prompt:
        messages.append({"role": "system", "content": system_prompt})

    # User message: multimodal when images are present
    if images:
        content: Any = (
            [{"type": "image_url", "image_url": {"url": img}} for img in images]
            + [{"type": "text", "text": prompt}]
        )
        logger.info(f"LiteLLM multimodal request: {len(images)} image(s) + text")
    else:
        content = prompt

    messages.append({"role": "user", "content": content})

    # All tiers use gemini-2.5-flash-lite through LiteLLM during testing
    body = json.dumps({
        "model": "gemini-2.5-flash-lite",
        "messages": messages,
        "temperature": model_config.temperature,
        "max_tokens": model_config.max_tokens,
    }).encode()

    logger.debug(f"LiteLLM proxy request: model=gemini-2.5-flash-lite, has_system={'yes' if system_prompt else 'no'}, prompt_len={len(prompt)}, images={len(images) if images else 0}")

    try:
        conn = http.client.HTTPConnection(host, port, timeout=300)
        headers = {
            "Authorization": f"Bearer {settings.LITELLM_MASTER_KEY}",
            "Content-Type": "application/json",
        }
        conn.request("POST", "/v1/chat/completions", body=body, headers=headers)
        resp = conn.getresponse()
        raw = resp.read().decode()
        conn.close()

        if resp.status != 200:
            logger.error(f"LiteLLM proxy returned {resp.status}: {raw[:500]}")
            raise RuntimeError(f"LiteLLM proxy error {resp.status}: {raw[:300]}")

        data = json.loads(raw)
        if not data.get("choices") or not data["choices"][0].get("message"):
            logger.error(f"LiteLLM responded with invalid structure: {data}")
            raise RuntimeError("LiteLLM response missing choices[0].message")
        
        text = data["choices"][0]["message"]["content"]
        if not text:
            logger.warning("LiteLLM returned empty text content")
        
        usage = data.get("usage", {})
        result = {
            "text": text,
            "tokens": {
                "inputTokens": usage.get("prompt_tokens", 0),
                "outputTokens": usage.get("completion_tokens", 0),
                "totalTokens": usage.get("total_tokens", 0),
                "cachedTokens": usage.get("prompt_tokens_details", {}).get("cached_tokens", 0),
            },
            "provider": "litellm",
            "model": data.get("model", "gemini-2.5-flash-lite"),
        }
        logger.info(f"LiteLLM success: model={result['model']} | tokens={result['tokens']['totalTokens']} | cached={result['tokens']['cachedTokens']}")
        return result
        
    except json.JSONDecodeError as e:
        logger.error(f"LiteLLM response is not valid JSON: {e}")
        raise RuntimeError(f"LiteLLM returned invalid JSON: {str(e)[:100]}")
    except Exception as e:
        logger.error(f"LiteLLM proxy call failed: {type(e).__name__}: {e}")
        raise


def generate_response(
    prompt: str,
    images: Optional[List[str]] = None,
    tier: Literal["power", "cheaper", "faster"] = "cheaper",
    system_prompt: Optional[str] = None,
) -> Dict[str, Any]:
    """
    Generate LLM response using LiteLLM proxy.

    UNIFIED PATH: All requests go through LiteLLM proxy (http://localhost:8005).
    LiteLLM handles routing to the configured models in litellm_config.yaml.

    Args:
        prompt: The user message text
        images: Optional list of image URLs or base64 data URIs
        tier: Model tier - "power", "cheaper", or "faster"
        system_prompt: Optional static system instruction (role="system").
                       Identical content across requests enables provider caching:
                       Gemini implicit caching (≥1024 tokens), Anthropic cache_control.

    Returns:
        Dict with 'text', 'tokens', 'provider', 'model' keys

    Raises:
        RuntimeError: If LiteLLM is down or all models fail
    """
    logger.info(f"generate_response | tier={tier} | has_system={'yes' if system_prompt else 'no'} | images={len(images) if images else 0}")

    # LiteLLM proxy is the ONLY path (all tiers including faster go through LiteLLM)
    if not settings.USE_LITELLM_PROXY:
        logger.error("USE_LITELLM_PROXY is disabled. LiteLLM proxy is required.")
        raise RuntimeError("LiteLLM proxy is not enabled in configuration")
    
    try:
        model_config = settings.get_model_config(tier)
        model_config._tier_name = tier
        
        logger.info(f"Calling LiteLLM proxy with tier={tier}")
        result = _call_litellm_proxy(prompt, model_config, images, system_prompt=system_prompt)
        
        if result and result.get("text"):
            logger.info(f"LiteLLM success | tier={tier} | tokens={result.get('tokens', {})}")
            with open("response.json", "w") as f:
                json.dump(result, f, indent=2)
            return result
        else:
            logger.error(f"LiteLLM returned empty response: {result}")
            raise RuntimeError("LiteLLM returned empty or invalid response")
            
    except Exception as e:
        logger.error(f"LiteLLM call failed for tier={tier}: {type(e).__name__}: {e}")
        
        # Return a clear error response instead of None
        error_response = {
            "text": f"[LLM SERVICE ERROR] {str(e)[:200]}",
            "tokens": {"inputTokens": 0, "outputTokens": 0, "totalTokens": 0},
            "provider": "error",
            "model": f"error-{tier}",
            "error": str(e),
        }
        return error_response

