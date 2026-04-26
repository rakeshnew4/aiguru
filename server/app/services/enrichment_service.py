"""
enrichment_service.py — Post-generation accuracy passes using small LLM calls.

Two passes (run in parallel for all frames inside get_titles()):

  1. Diagram data enricher
     Given diagram_type + step teaching content → optimal, complete data dict.
     The main LLM (power/cheaper) often emits incomplete or generic data.
     A tiny focused call on the faster model fills every key perfectly.

  2. Quiz answer validator
     For every quiz_mcq frame, verifies the correct_index is factually right.
     Wrong quiz answers destroy student trust — this pass catches them.

Both use tier="faster" (Gemini Flash Lite, temperature 0.3).
Both are fail-safe: return the original values on ANY error.
Both run as sync functions, intended to be wrapped in run_in_executor()
so they can be gathered in parallel with async Wikimedia searches.
"""

from __future__ import annotations

import json
import logging
from functools import partial
from typing import Any

logger = logging.getLogger(__name__)


# ── Diagram type schemas (concise — fits in < 200 tokens) ──────────────────

_SCHEMAS: dict[str, dict[str, str]] = {
    "atom": {
        "schema": (
            '{"nucleus_label":"1-3 char element symbol","nucleus_color":"color_key",'
            '"nucleus_radius":25,"orbits":[{"electrons":N,"color":"color_key",'
            '"label":"shell name","tilt_deg":0}],"duration":12}'
        ),
        "hint": (
            "nucleus_color: use 'highlight' (red) for heavy nuclei, 'label' (yellow) for light ones. "
            "One orbit entry per electron shell in order K, L, M, N. "
            "K shell max 2, L max 8, M max 18 electrons. duration 8-15."
        ),
    },
    "solar_system": {
        "schema": (
            '{"sun_label":"Sun","sun_color":"orange",'
            '"planets":[{"label":"name","color":"color_key","orbit_radius":N,"duration":N}]}'
        ),
        "hint": (
            "Orbit radii 40-160 (inner to outer). "
            "Inner planets: duration 8-20s. Gas giants: 30-60s. "
            "Color hints: Earth=blue, Mars=highlight, Venus=orange, "
            "Jupiter=gold, Saturn=coral, Neptune=teal."
        ),
    },
    "triangle": {
        "schema": (
            '{"labels":["A","B","C"],"show_height":false,'
            '"show_incircle":false,"show_circumcircle":false}'
        ),
        "hint": (
            "show_height: true when Pythagoras or altitude is being taught. "
            "Labels can be angle names or side lengths. "
            "show_incircle/circumcircle: true only when explicitly relevant."
        ),
    },
    "circle_radius": {
        "schema": '{"radius":70,"label":"r"}',
        "hint": "radius 40-100. label: show the actual value e.g. 'r = 7 cm'.",
    },
    "rectangle_area": {
        "schema": '{"width":140,"height":80}',
        "hint": "width 60-220, height 40-120. Reflect actual dimensions from the problem.",
    },
    "geometry_angles": {
        "schema": (
            '{"angle_deg":90,"angle_type":"right","labels":["A","O","B"],'
            '"title":"Right Angle","show_second":false}'
        ),
        "hint": (
            "angle_type: acute(<90) obtuse(90-180) right(=90) reflex(>180) supplementary. "
            "show_second: true when teaching supplementary/complementary pairs."
        ),
    },
    "flow": {
        "schema": '{"title":"short title","steps":["Step 1","Step 2","Step 3","Step 4"]}',
        "hint": (
            "3-6 steps. Each step MAXIMUM 4 words — must be ultra-concise. "
            "Order must be scientifically correct. title ≤ 3 words."
        ),
    },
    "cycle": {
        "schema": '{"title":"Cycle Name","steps":["Stage 1","Stage 2","Stage 3","Stage 4"]}',
        "hint": (
            "3-6 stages arranged cyclically. Each stage 1-4 words. "
            "Last stage naturally leads back to first."
        ),
    },
    "comparison": {
        "schema": (
            '{"left":"Name A","right":"Name B",'
            '"left_points":["trait1","trait2","trait3"],'
            '"right_points":["trait1","trait2","trait3"]}'
        ),
        "hint": (
            "2-4 bullet points per side. Each point ≤ 6 words. "
            "Points must be parallel in structure (same attributes compared)."
        ),
    },
    "labeled_diagram": {
        "schema": (
            '{"center":"Central Structure","center_shape":"circle",'
            '"parts":["Part1","Part2","Part3","Part4","Part5"]}'
        ),
        "hint": (
            "center: the main structure name. parts: 3-7 component labels, "
            "clockwise from top. center_shape: circle (biology cells), "
            "rect (mechanical parts)."
        ),
    },
    "line_graph": {
        "schema": (
            '{"x_label":"X axis","y_label":"Y axis",'
            '"points":[[0,0],[1,4],[2,7],[3,9]]}'
        ),
        "hint": (
            "3-8 (x,y) data points. x values monotonically increasing. "
            "y values 0-100 scale. Labels reflect the actual quantities."
        ),
    },
    "graph_function": {
        "schema": (
            '{"function":"quadratic","a":1,"b":0,"c":0,'
            '"x_range":[-4,4],"label":"y = x\u00b2","color":"secondary"}'
        ),
        "hint": (
            "function: quadratic|linear|cubic|sine|cosine|abs. "
            "a,b,c: real coefficients. x_range: [-N, N] centered at 0. "
            "label: LaTeX-free equation string."
        ),
    },
    "waveform_signal": {
        "schema": (
            '{"title":"Wave Name","wave_type":"sine","cycles":2.5,'
            '"amplitude":50,"x_label":"time (s)","y_label":"amplitude","color":"secondary"}'
        ),
        "hint": (
            "wave_type: sine|cosine. cycles 1-5. amplitude 20-80. "
            "color: secondary=blue for sound, highlight=red for EM, teal for light."
        ),
    },
    "number_line": {
        "schema": (
            '{"start":-5,"end":5,"marked_points":[0],'
            '"highlight_range":[],"label":"Number Line"}'
        ),
        "hint": (
            "start/end: integers. marked_points: numbers to highlight with dots. "
            "highlight_range: [lo, hi] to shade; omit if not needed."
        ),
    },
    "fraction_bar": {
        "schema": '{"fractions":[{"num":1,"den":2},{"num":3,"den":4}],"title":"Fractions"}',
        "hint": "2-4 fractions. Each {num, den} where 0 < num ≤ den (proper fractions for comparison).",
    },
}

_COLOR_KEYS = (
    "primary (chalk white), label (yellow), secondary (sky blue), "
    "highlight (red), teal, orange, green, pink, purple, gold, coral, mint"
)

_ENRICH_SYSTEM = (
    "You are a diagram data generator for an educational app. "
    "Output ONLY a valid JSON object — no text, no markdown, no explanation."
)

_QUIZ_SYSTEM = (
    "You are an educational quiz fact-checker. "
    "Output ONLY a valid JSON object — no text, no markdown."
)


# ── Diagram data enricher ──────────────────────────────────────────────────

def enrich_diagram_data(
    diagram_type: str,
    step_title: str,
    frame_text: str,
    frame_speech: str,
    existing_data: dict,
) -> dict:
    """
    Generate the optimal `data` dict for a diagram frame.

    Uses tier="faster" (Gemini Flash Lite, temp=0.3).
    Returns enriched dict on success, original existing_data on any failure.

    Intentionally sync — wrap in run_in_executor() for async use.
    """
    from app.services.llm_service import generate_response
    from app.utils.json_utils import extract_json_safe

    schema_info = _SCHEMAS.get(diagram_type)
    if not schema_info:
        return existing_data   # unknown type — no enrichment possible

    teaching_content = (frame_speech or frame_text or "")[:150].strip()
    existing_str = json.dumps(existing_data) if existing_data else "{}"

    prompt = (
        f"Topic: \"{step_title[:80]}\"\n"
        f"Teaching content: \"{teaching_content}\"\n"
        f"Diagram type: {diagram_type}\n"
        f"Color keys: {_COLOR_KEYS}\n"
        f"Schema: {schema_info['schema']}\n"
        f"Hint: {schema_info['hint']}\n"
        f"Existing data (improve/complete this): {existing_str}\n\n"
        f"Generate the BEST data JSON for this diagram. "
        f"Fill ALL keys. Be accurate to the topic. "
        f"Reply with ONLY the JSON object."
    )

    try:
        raw = generate_response(
            prompt,
            images=[],
            tier="faster",
            system_prompt=_ENRICH_SYSTEM,
        )
        text = (raw.get("text") or "").strip()
        if not text or raw.get("provider") == "error":
            return existing_data

        enriched = extract_json_safe(text)
        if not isinstance(enriched, dict) or not enriched:
            return existing_data

        # Merge: enriched values take precedence; fall back to existing for missing keys
        merged = {**existing_data, **enriched}
        logger.info(
            "diagram_enricher: type=%s step='%s' keys=%s",
            diagram_type, step_title[:40], list(enriched.keys()),
        )
        return merged

    except Exception as exc:
        logger.warning(
            "diagram_enricher failed (type=%s step='%s'): %s — using original data",
            diagram_type, step_title[:40], exc,
        )
        return existing_data


# ── Quiz answer validator ──────────────────────────────────────────────────

def validate_quiz_mcq(
    step_title: str,
    question_text: str,
    options: list,
    current_index: int,
) -> int:
    """
    Verify (and correct if wrong) the quiz_correct_index for a quiz_mcq frame.

    Uses tier="faster" (Gemini Flash Lite, temp=0.1 for deterministic output).
    Returns verified (or corrected) index on success, original index on failure.

    Intentionally sync — wrap in run_in_executor() for async use.
    """
    from app.services.llm_service import generate_response
    from app.utils.json_utils import extract_json_safe

    if not options or len(options) < 2:
        return current_index
    if not (0 <= current_index < len(options)):
        current_index = 0

    options_str = "\n".join(f"  {i}: {opt}" for i, opt in enumerate(options))
    question_clean = (question_text or step_title or "")[:200].strip()

    prompt = (
        f"Fact-check this educational quiz question.\n"
        f"Topic: \"{step_title[:60]}\"\n"
        f"Question: \"{question_clean}\"\n"
        f"Options:\n{options_str}\n\n"
        f"Current answer index: {current_index} → \"{options[current_index]}\"\n\n"
        f"Which option index (0-{len(options)-1}) is FACTUALLY CORRECT?\n"
        f"Reply ONLY with JSON: {{\"correct_index\": N}}"
    )

    try:
        raw = generate_response(
            prompt,
            images=[],
            tier="faster",
            system_prompt=_QUIZ_SYSTEM,
        )
        text = (raw.get("text") or "").strip()
        if not text or raw.get("provider") == "error":
            return current_index

        parsed = extract_json_safe(text)
        verified = int(parsed.get("correct_index", current_index))
        if not (0 <= verified < len(options)):
            return current_index

        if verified != current_index:
            logger.info(
                "quiz_validator: CORRECTED index %d→%d for '%s' | was='%s' now='%s'",
                current_index, verified,
                step_title[:40],
                options[current_index],
                options[verified],
            )
        return verified

    except Exception as exc:
        logger.warning(
            "quiz_validator failed (step='%s'): %s — keeping index %d",
            step_title[:40], exc, current_index,
        )
        return current_index


# ── Batch helper — returns coroutines for all enrichments in a lesson ───────

_MAX_DIAGRAM_ENRICHMENTS = 2   # cap LLM enrichment calls per session to save tokens


def build_enrichment_tasks(
    steps: list,
    loop,
) -> tuple[list, list, list]:
    """
    Walk parsed BB steps and build diagram-enrichment futures only.

    Quiz MCQ validation is intentionally skipped — the main BB LLM already
    sets quiz_correct_index correctly and the extra LLM round-trip per quiz
    frame added 2-4 unnecessary calls per session.

    Diagram enrichment is capped at _MAX_DIAGRAM_ENRICHMENTS per session to
    bound LLM call count regardless of lesson length.

    Returns:
      (futures_list, diagram_frame_refs, [])   ← quiz_refs always empty now
    """
    diagram_refs: list[tuple[dict, dict]] = []
    diagram_futs: list = []

    for step in steps:
        if not isinstance(step, dict):
            continue
        if len(diagram_futs) >= _MAX_DIAGRAM_ENRICHMENTS:
            break
        step_title = step.get("title", "")
        for frame in step.get("frames", []):
            if not isinstance(frame, dict):
                continue
            if len(diagram_futs) >= _MAX_DIAGRAM_ENRICHMENTS:
                break

            ft = frame.get("frame_type", "")
            d_type = (frame.get("diagram_type") or "").strip()

            if ft == "diagram" and d_type and d_type in _SCHEMAS:
                diagram_refs.append((step, frame))
                fut = loop.run_in_executor(
                    None,
                    partial(
                        enrich_diagram_data,
                        d_type,
                        step_title,
                        frame.get("text", ""),
                        frame.get("speech", ""),
                        dict(frame.get("data") or {}),
                    ),
                )
                diagram_futs.append(fut)

    return diagram_futs, diagram_refs, []
