from app.core.logger import get_logger

logger = get_logger(__name__)


def get_context(page_id: str) -> str:
    """
    Return the text context for the given page.
    Replace this stub with a real DB / vector-store lookup when ready.
    """
    logger.info("Fetching context for page_id=%s", page_id)
    # TODO: fetch from database or vector store
    return f"Context for page {page_id}"
