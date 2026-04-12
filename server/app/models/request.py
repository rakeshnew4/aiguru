from pydantic import BaseModel
from typing import Optional, List


class ChatRequest(BaseModel):
    question: str
    page_id: str
    student_level: Optional[int] = 5
    history: Optional[List[str]] = []
    # Each entry is either an HTTPS image URL or a base64 data URI
    # e.g. "https://..." or "data:image/jpeg;base64,<data>"
    images: Optional[List[str]] = []
    mode: Optional[str] = "blackboard"
    language: Optional[str] = "ta-IN"
    language_tag: Optional[str] = "ta-IN"