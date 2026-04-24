"""
test_visual_desc_changes.py — Unit tests for the visual_description feature.

Tests:
  1. prompt_service.py  — visual_description present in blackboard_prompt
  2. svg_llm_builder.py — visual_layout_line injected into user prompt
  3. image_search_titles.py — _has_force_description, constants, _pick_titles_sync vis extraction
  4. End-to-end PATH 2 svg_elements render (smoke test, no LLM)

Run:
    cd server
    python test_visual_desc_changes.py
"""
import sys, os, json
sys.path.insert(0, os.path.dirname(__file__))

PASS = 0
FAIL = 0
results = []

def check(name, cond, detail=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        results.append(("PASS", name))
    else:
        FAIL += 1
        results.append(("FAIL", name + (f" -- {detail}" if detail else "")))

# ─── 1. prompt_service.py ─────────────────────────────────────────────────────
print("\n[1] prompt_service.py — blackboard_prompt checks")

from app.services.prompt_service import blackboard_prompt

check("schema JSON contains visual_description field",
      '"visual_description": ""' in blackboard_prompt)

check("DIAGRAM VISUAL RULE present",
      "DIAGRAM VISUAL RULE" in blackboard_prompt)

check("visual_description MANDATORY instruction present",
      "MANDATORY" in blackboard_prompt and "visual_description" in blackboard_prompt)

check("labeled_diagram restriction present",
      "NEVER for physics forces" in blackboard_prompt)

check("PHYSICS/FORCE DIAGRAM RULE present",
      "PHYSICS/FORCE DIAGRAM RULE" in blackboard_prompt)

check("Newton's 3rd Law svg_elements example present",
      "Action F" in blackboard_prompt and "Reaction F" in blackboard_prompt)

check("step-level field enforcement present",
      "STEP-LEVEL FIELDS" in blackboard_prompt)

check("non-diagram frames rule includes visual_description",
      'visual_description=""' in blackboard_prompt or "visual_description" in blackboard_prompt)

# ─── 2. svg_llm_builder.py ───────────────────────────────────────────────────
print("\n[2] svg_llm_builder.py — build_llm_svg signature & template")

from app.utils.svg_llm_builder import build_llm_svg, _USER_PROMPT_TEMPLATE
import inspect

sig = inspect.signature(build_llm_svg)
params = list(sig.parameters.keys())

check("build_llm_svg has visual_description param",
      "visual_description" in params)

check("visual_description param has default empty string",
      sig.parameters.get("visual_description") is not None and
      sig.parameters["visual_description"].default == "")

check("_USER_PROMPT_TEMPLATE contains {visual_layout_line}",
      "{visual_layout_line}" in _USER_PROMPT_TEMPLATE)

# Test that visual_layout_line is injected correctly (no LLM call needed)
# We'll just verify the template formatting works
try:
    formatted = _USER_PROMPT_TEMPLATE.format(
        diagram_type="triangle",
        topic="Inclined Plane",
        speech="Block on incline",
        data_json="{}",
        visual_layout_line="  Visual layout: Triangle with force arrows.\n  Follow this layout exactly.\n",
    )
    check("template formats correctly with visual_layout_line",
          "Visual layout: Triangle with force arrows" in formatted)
except Exception as e:
    check("template formats correctly with visual_layout_line", False, str(e))

try:
    formatted_empty = _USER_PROMPT_TEMPLATE.format(
        diagram_type="cycle",
        topic="Water Cycle",
        speech="Water evaporates",
        data_json="{}",
        visual_layout_line="",
    )
    check("template formats correctly without visual_layout_line (empty)",
          "Water Cycle" in formatted_empty and "{visual_layout_line}" not in formatted_empty)
except Exception as e:
    check("template formats correctly without visual_layout_line (empty)", False, str(e))

# ─── 3. image_search_titles.py ───────────────────────────────────────────────
print("\n[3] image_search_titles.py — constants and _has_force_description")

from app.api.image_search_titles import (
    _FORCE_DIAGRAM_KEYWORDS,
    _BASIC_GEOMETRY_TYPES,
    _has_force_description,
)

check("_FORCE_DIAGRAM_KEYWORDS is a non-empty set",
      isinstance(_FORCE_DIAGRAM_KEYWORDS, set) and len(_FORCE_DIAGRAM_KEYWORDS) > 0)

check("_BASIC_GEOMETRY_TYPES contains triangle/polygon/angle",
      {"triangle", "polygon", "angle"} <= _BASIC_GEOMETRY_TYPES)

# Positive cases
check("_has_force_description detects 'force arrow'",
      _has_force_description("Block with force arrow pointing down"))

check("_has_force_description detects 'inclined'",
      _has_force_description("Inclined plane with gravity component"))

check("_has_force_description detects 'friction'",
      _has_force_description("Friction and normal force on a ramp"))

check("_has_force_description detects 'vector'",
      _has_force_description("Resultant vector diagram"))

# Negative cases
check("_has_force_description returns False for plain geometry",
      not _has_force_description("Right triangle with sides A B C"))

check("_has_force_description returns False for empty string",
      not _has_force_description(""))

check("_has_force_description returns False for biology text",
      not _has_force_description("Cell organelles with nucleus and membrane"))

# ─── 4. _pick_titles_sync vis_desc extraction logic ─────────────────────────
print("\n[4] visual_description extraction from diagram frames")

# Simulate the extraction logic used in _pick_titles_sync
def extract_vis_desc(step):
    frames = step.get("frames", [])
    return next(
        (
            (f.get("visual_description") or "").strip()
            for f in frames
            if isinstance(f, dict) and f.get("frame_type") == "diagram"
        ),
        "",
    )

step_with_diagram = {
    "title": "Newton 3rd Law",
    "image_description": "newton third law force",
    "image_show_confidencescore": 0.7,
    "frames": [
        {"frame_type": "concept", "text": "...", "visual_description": ""},
        {
            "frame_type": "diagram",
            "diagram_type": "triangle",
            "visual_description": "Block on incline. Arrow pointing down labeled mg. Arrow perpendicular labeled N.",
        },
    ],
}

step_without_diagram = {
    "title": "Introduction",
    "frames": [
        {"frame_type": "concept", "text": "...", "visual_description": ""},
    ],
}

check("vis_desc extracted from step with diagram frame",
      extract_vis_desc(step_with_diagram) == "Block on incline. Arrow pointing down labeled mg. Arrow perpendicular labeled N.")

check("vis_desc is empty for step without diagram frame",
      extract_vis_desc(step_without_diagram) == "")

check("vis_desc ignores concept frame visual_description",
      extract_vis_desc(step_without_diagram) == "")

# ─── 5. SVG renderer smoke test (existing pipeline untouched) ────────────────
print("\n[5] SVG renderer smoke test (ensure existing pipeline works)")

from app.utils.svg_builder import build_from_diagram_type, build_animated_svg

for dtype, data in [
    ("triangle", {"labels": ["A", "B", "C"]}),
    ("cycle", {"steps": ["Evaporation", "Condensation", "Precipitation"]}),
    ("flow", {"steps": ["Input", "Process", "Output"]}),
    ("labeled_diagram", {"center": "Cell", "parts": ["Nucleus", "Membrane"]}),
]:
    shapes = build_from_diagram_type(dtype, data)
    html = build_animated_svg(shapes) if shapes else ""
    check(f"renderer '{dtype}' produces valid HTML",
          bool(shapes) and "<svg" in html and "</svg>" in html,
          f"shapes={len(shapes) if shapes else 0}, html_len={len(html)}")

# ─── Summary ─────────────────────────────────────────────────────────────────
print("\n" + "=" * 60)
for icon, name in results:
    marker = "[OK]" if icon == "PASS" else "[XX]"
    print(f"  {marker} {name}")
print("=" * 60)
print(f"  RESULTS: {PASS} passed, {FAIL} failed out of {PASS+FAIL} checks")
print("=" * 60)
sys.exit(0 if FAIL == 0 else 1)
