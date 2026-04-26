from app.core.logger import get_logger

logger = get_logger(__name__)


def get_context(page_id: str) -> str:
    """
    Return the text context for the given page.
    Currently returns empty string — wire a real DB / vector-store lookup here.
    """
    logger.info("get_context: no store wired for page_id=%s — returning empty", page_id)
    return ""
