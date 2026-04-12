import re


def clean_text(text: str) -> str:
    """Strip leading/trailing whitespace and collapse multiple blank lines."""
    text = text.strip()
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text


def truncate(text: str, max_chars: int = 500) -> str:
    """Return at most *max_chars* characters, appending '…' when truncated."""
    if len(text) <= max_chars:
        return text
    return text[:max_chars].rstrip() + "…"


def word_count(text: str) -> int:
    return len(text.split())
