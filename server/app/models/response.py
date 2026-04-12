from pydantic import BaseModel
from typing import Optional, Dict, Any


class ChatResponse(BaseModel):
    text: str
    cached: bool = False
    tokens: Optional[Dict[str, Any]] = None
    done: bool = True
