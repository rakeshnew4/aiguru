import asyncio
from typing import Dict, Any

from app.core.config import settings
from app.core.logger import get_logger
from strands import Agent
from strands.models.litellm import LiteLLMModel
from strands_tools import calculator

logger = get_logger(__name__)

# ── Lazy singleton ────────────────────────────────────────────────────────────
_agent = None
def litellm_agent(prompt: str) -> Dict[str, Any]:
    

    model = LiteLLMModel(
    client_args={
        "api_key": settings.LITELLM_MASTER_KEY,
        "api_base": settings.LITELLM_PROXY_URL if settings.USE_LITELLM_PROXY else None,
        "use_litellm_proxy": settings.USE_LITELLM_PROXY
    },
    model_id="gemini-3.1-flash-lite-preview"
    )

    agent = Agent(model=model)
    response = agent(prompt)
    return response

def _get_agent():
    """Build (once) and return the Strands Agent backed by Gemini."""
    global _agent
    if _agent is not None:
        return _agent

    if not settings.GEMINI_API_KEY:
        raise RuntimeError("GEMINI_API_KEY is not set — cannot initialise Strands agent")


    try:
        from strands import Agent
        from strands.models.gemini import GeminiModel

        model_cfg = settings.get_model_config("cheaper")  # gemini-3.1-flash-lite-preview by default
        gemini_model = GeminiModel(
            client_args={"api_key": settings.GEMINI_API_KEY},
            model_id=model_cfg.model_id,
            params={"temperature": model_cfg.temperature},
        )
        _agent = Agent(model=gemini_model)
        logger.info(f"Strands agent initialised (model: {model_cfg.model_id})")
    except Exception as e:
        logger.error(f"Failed to initialise Strands agent: {e}")
        raise

    return _agent


def _run_agent_sync(prompt: str) -> Dict[str, Any]:
    """Blocking call — must be run in a thread executor."""
    # agent = _get_agent()
    # output = agent(prompt)
    output = litellm_agent(prompt)
    text = str(output)
    usage = output.metrics.accumulated_usage
    return {
        "text": text,
        "inputTokens": usage.get("inputTokens", 0),
        "outputTokens": usage.get("outputTokens", 0),
        "totalTokens": usage.get("totalTokens", 0),
    }


async def run_agent(prompt: str) -> Dict[str, Any]:
    """Async wrapper — runs the blocking Strands agent in a thread pool."""
    loop = asyncio.get_event_loop()
    return await loop.run_in_executor(None, lambda: _run_agent_sync(prompt))
