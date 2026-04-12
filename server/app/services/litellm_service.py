"""
LiteLLM Proxy Service

Handles per-user API key management and usage tracking via LiteLLM proxy.
Allows each user to have isolated API keys for usage monitoring and cost allocation.

Architecture:
- Each user gets a unique LiteLLM API key on first login (via register endpoint)
- User's key is stored in PostgreSQL (managed by LiteLLM)
- All LLM calls are routed through LiteLLM proxy (localhost:8005)
- Usage is automatically tracked in PostgreSQL
- Admin can view per-user costs and revoke keys as needed
"""

import httpx
import json
import logging
from typing import Optional, Dict, List, Any
from app.core.config import settings

logger = logging.getLogger(__name__)

# ─── Constants ────────────────────────────────────────────────────────

LITELLM_PROXY_URL = settings.LITELLM_PROXY_URL  # http://localhost:8005 — all API ops here
LITELLM_MASTER_KEY = settings.LITELLM_MASTER_KEY
LITELLM_ADMIN_URL = settings.LITELLM_PROXY_URL   # key/generate, user/info are on proxy port too


# ─── Per-User API Key Management ─────────────────────────────────────

async def create_user_api_key(user_id: str, user_metadata: Optional[Dict[str, Any]] = None) -> Optional[str]:
    """
    Create a new LiteLLM API key for a user in PostgreSQL.
    
    Args:
        user_id: Unique user ID (e.g., Firebase UID)
        user_metadata: Optional metadata dict (name, tier, email, etc.)
    
    Returns:
        New LiteLLM API key (e.g., "sk-abc123def456...")
    
    Example:
        key = await create_user_api_key("google_user_12345", {"name": "John", "tier": "power"})
        # Returns: "sk-user-abc123..."
    """
    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            response = await client.post(
                f"{LITELLM_PROXY_URL}/key/generate",
                headers={"Authorization": f"Bearer {LITELLM_MASTER_KEY}"},
                json={
                    "user_id": user_id,
                    "models": ["cheaper", "faster", "gemini-2.5-flash"],
                    "duration": "365d",
                    "metadata": user_metadata or {}
                }
            )
            response.raise_for_status()
            data = response.json()
            key = data.get("key")
            logger.info(f"Created LiteLLM key for user {user_id}: {key[:20]}...")
            return key
    except Exception as e:
        logger.error(f"Failed to create LiteLLM key for user {user_id}: {e}")
        return None


async def get_user_api_keys(user_id: str) -> List[Dict[str, Any]]:
    """
    Fetch all API keys for a user.
    
    Args:
        user_id: User ID
    
    Returns:
        List of key objects: [{"key": "sk-...", "created_at": "...", "is_valid": true}, ...]
    
    Example:
        keys = await get_user_api_keys("google_user_12345")
    """
    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            response = await client.get(
                f"{LITELLM_PROXY_URL}/user/info",
                headers={"Authorization": f"Bearer {LITELLM_MASTER_KEY}"},
                params={"user_id": user_id}
            )
            response.raise_for_status()
            data = response.json()
            return data.get("keys", [])
    except Exception as e:
        logger.error(f"Failed to fetch keys for user {user_id}: {e}")
        return []


async def revoke_api_key(key: str) -> bool:
    """
    Revoke a LiteLLM API key (disable it).
    
    Args:
        key: LiteLLM API key to revoke
    
    Returns:
        True if successful, False otherwise
    
    Example:
        success = await revoke_api_key("sk-user-abc123...")
    """
    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            response = await client.post(
                f"{LITELLM_PROXY_URL}/key/delete",
                headers={"Authorization": f"Bearer {LITELLM_MASTER_KEY}"},
                json={"keys": [key]}
            )
            response.raise_for_status()
            logger.info(f"Revoked API key: {key[:20]}...")
            return True
    except Exception as e:
        logger.error(f"Failed to revoke API key {key[:20]}...: {e}")
        return False


# ─── Usage Monitoring ────────────────────────────────────────────────

async def get_user_usage_stats(user_id: str) -> Optional[Dict[str, Any]]:
    """
    Fetch usage statistics for a user from LiteLLM PostgreSQL.
    
    Args:
        user_id: User ID
    
    Returns:
        Dict with keys:
        - total_requests: Number of API calls
        - total_input_tokens: Sum of input tokens
        - total_output_tokens: Sum of output tokens
        - total_cost: Calculated cost (USD)
        - daily_usage: Dict of {date: {tokens, cost}}
        - models_used: List of model names used
        - last_request_at: ISO timestamp
    
    Example:
        stats = await get_user_usage_stats("google_user_12345")
        print(f"User spent ${stats['total_cost']:.2f}")
    """
    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            response = await client.get(
                f"{LITELLM_PROXY_URL}/user/info",
                headers={"Authorization": f"Bearer {LITELLM_MASTER_KEY}"},
                params={"user_id": user_id}
            )
            response.raise_for_status()
            return response.json()
    except Exception as e:
        logger.error(f"Failed to fetch usage stats for user {user_id}: {e}")
        return None


async def get_all_usage_stats() -> Optional[Dict[str, Any]]:
    """
    Fetch usage statistics for all users.
    
    Returns:
        Dict of {user_id: {total_requests, total_cost, ...}}
    
    Example:
        all_stats = await get_all_usage_stats()
        for user_id, stats in all_stats.items():
            print(f"{user_id}: ${stats['total_cost']:.2f}")
    """
    try:
        async with httpx.AsyncClient(timeout=30.0) as client:
            response = await client.get(
                f"{LITELLM_PROXY_URL}/user/list",
                headers={"Authorization": f"Bearer {LITELLM_MASTER_KEY}"}
            )
            response.raise_for_status()
            return response.json()
    except Exception as e:
        logger.error(f"Failed to fetch all usage stats: {e}")
        return None


# ─── LLM Proxy Calls ────────────────────────────────────────────────

async def call_litellm(
    messages: List[Dict[str, str]],
    model: str = "cheaper",
    user_litellm_key: Optional[str] = None,
    temperature: float = 0.7,
    max_tokens: int = 4096,
    stream: bool = False,
    **kwargs
) -> Dict[str, Any]:
    """
    Call LLM through LiteLLM proxy with per-user API key.
    
    Args:
        messages: OpenAI-format message list
        model: Model name (power, cheaper, faster) or direct model name
        user_litellm_key: User's LiteLLM API key (if None, uses master key)
        temperature: Sampling temperature
        max_tokens: Maximum tokens in response
        stream: Whether to stream response
        **kwargs: Additional OpenAI-compatible parameters
    
    Returns:
        OpenAI-format completion response
    
    Example:
        response = await call_litellm(
            messages=[{"role": "user", "content": "What is 2+2?"}],
            model="cheaper",
            user_litellm_key="sk-user-abc123..."
        )
        print(response["choices"][0]["message"]["content"])
    """
    auth_key = user_litellm_key or LITELLM_MASTER_KEY
    auth_key = "sk-O9b3-TMZdBMEipehw0csFA"
    
    try:
        async with httpx.AsyncClient(timeout=300.0) as client:
            response = await client.post(
                f"{LITELLM_PROXY_URL}/v1/chat/completions",
                headers={
                    "Authorization": f"Bearer {auth_key}",
                    "Content-Type": "application/json"
                },
                json={
                    "model": "gemini-2.5-flash",
                    "metadata": {"model_group": None},
                    "messages": messages,
                    "temperature": temperature,
                    "max_tokens": max_tokens,
                    "stream": stream,
                    # **kwargs
                }
            )
            response.raise_for_status()
            return response.json()
    except Exception as e:
        logger.error(f"LiteLLM call failed for model {model}: {e}")
        raise


async def stream_litellm(
    messages: List[Dict[str, str]],
    model: str = "cheaper",
    user_litellm_key: Optional[str] = None,
    temperature: float = 0.7,
    max_tokens: int = 4096,
    **kwargs
):
    """
    Stream LLM response through LiteLLM proxy.
    
    Yields:
        Streamed completion chunks (OpenAI format)
    
    Example:
        async for chunk in stream_litellm(messages, model="cheaper"):
            print(chunk["choices"][0]["delta"]["content"], end="")
    """
    auth_key = user_litellm_key or LITELLM_MASTER_KEY
    auth_key = "sk-O9b3-TMZdBMEipehw0csFA"
    try:
        async with httpx.AsyncClient(timeout=300.0) as client:
            async with client.stream(
                "POST",
                f"{LITELLM_PROXY_URL}/v1/chat/completions",
                headers={
                    "Authorization": f"Bearer {auth_key}",
                    "Content-Type": "application/json"
                },
                json={
                    "model": "gemini-2.5-flash",
                    "metadata": {"model_group": None},
                    "messages": messages,
                    "temperature": temperature,
                    "max_tokens": max_tokens,
                    "stream": True,
                    # **kwargs
                }
            ) as response:
                response.raise_for_status()
                async for line in response.aiter_lines():
                    if line.startswith("data: "):
                        chunk_str = line[6:]
                        if chunk_str != "[DONE]":
                            try:
                                chunk = json.loads(chunk_str)
                                yield chunk
                            except Exception:
                                continue
    except Exception as e:
        logger.error(f"LiteLLM stream failed for model {model}: {e}")
        raise


# ─── Model Discovery ────────────────────────────────────────────────

async def get_available_models() -> Optional[List[str]]:
    """
    Fetch list of available models from LiteLLM proxy.
    
    Returns:
        List of model names available in litellm_config.yaml
    
    Example:
        models = await get_available_models()
        # Returns: ["power", "cheaper", "faster", "gemini-2.0-flash", ...]
    """
    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            response = await client.get(
                f"{LITELLM_PROXY_URL}/models",
                headers={"Authorization": f"Bearer {LITELLM_MASTER_KEY}"}
            )
            response.raise_for_status()
            data = response.json()
            return data.get("data", [])
    except Exception as e:
        logger.error(f"Failed to fetch available models: {e}")
        return None


# ─── Health Check ────────────────────────────────────────────────

async def health_check() -> bool:
    """
    Check if LiteLLM proxy is running and healthy.
    
    Returns:
        True if proxy responds to health check, False otherwise
    
    Example:
        if await health_check():
            print("LiteLLM proxy is healthy")
        else:
            print("LiteLLM proxy is down")
    """
    try:
        async with httpx.AsyncClient(timeout=5.0) as client:
            response = await client.get(
                f"{LITELLM_PROXY_URL}/health",
                headers={"Authorization": f"Bearer {LITELLM_MASTER_KEY}"}
            )
            return response.status_code == 200
    except Exception:
        return False
