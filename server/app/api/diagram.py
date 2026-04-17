"""
diagram.py — AI Visual Learning endpoint.

POST /diagram/generate
    Accepts a student question and returns:
    - explanation (plain text, 2–3 lines)
    - visual_intent (one sentence)
    - diagram_type (what was chosen)
    - diagram_html (full animated SVG HTML for WebView)
    - source ("auto" | "llm")
"""

import logging
from fastapi import APIRouter, Depends
from pydantic import BaseModel, Field

from app.core.auth import require_auth, AuthUser
from app.services.diagram_service import generate_diagram

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/diagram", tags=["diagram"])


class DiagramRequest(BaseModel):
    question: str = Field(..., min_length=1, max_length=1000,
                          description="The student's question or topic to visualise.")


class DiagramResponse(BaseModel):
    diagram_type:  str
    explanation:   str
    visual_intent: str
    diagram_html:  str
    source:        str   # "auto" | "llm"


@router.post("/generate", response_model=DiagramResponse)
async def generate_diagram_endpoint(
    req: DiagramRequest,
    auth: AuthUser = Depends(require_auth),
):
    """
    Convert a student question into an animated SVG diagram.

    - Tries keyword auto-detection first (fast, free).
    - Falls back to LLM for complex or ambiguous questions.
    - Always returns a valid HTML string in `diagram_html`.
    """
    result = generate_diagram(req.question.strip())
    return DiagramResponse(
        diagram_type  = result["diagram_type"],
        explanation   = result["explanation"],
        visual_intent = result["visual_intent"],
        diagram_html  = result["diagram_html"],
        source        = result["source"],
    )
