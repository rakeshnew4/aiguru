"""
validate_diagrams.py — SVG diagram validation test suite.

Tests 10 cases covering all diagram types.
For each test, checks:
  1. Renderer returns non-empty shapes list
  2. build_animated_svg returns valid HTML
  3. All text labels are within the 400×300 viewBox
  4. No shapes overlap excessively (centre-of-mass check)
  5. Minimum element count
  6. Specific layout correctness (type-specific assertions)

Run:
    cd server
    python validate_diagrams.py
"""

import math
import sys
import os
import re

sys.path.insert(0, os.path.dirname(__file__))

from app.utils.svg_builder import build_from_diagram_type, build_animated_svg
from app.utils.svg_colors import _clamp

# ─────────────────────────────────────────────────────────────────────────────
# Test cases — (name, diagram_type, data, min_shapes, specific_checks_fn)
# ─────────────────────────────────────────────────────────────────────────────

TESTS = [
    # 1. Triangle — basic geometry
    {
        "name": "Triangle ABC with height",
        "dtype": "triangle",
        "data": {"labels": ["A", "B", "C"], "show_height": True},
        "min_shapes": 9,
        "checks": lambda shapes, html: [
            _assert("has lines for all 3 sides",
                    sum(1 for s in shapes if s.get("shape") == "line") >= 3),
            _assert("has vertex labels A B C",
                    {"A", "B", "C"} <= {s.get("value","") for s in shapes if s.get("shape")=="text"}),
            _assert("height dashed line present",
                    any(s.get("shape") == "dashed_line" for s in shapes)),
        ],
    },

    # 2. Triangle — incircle (the problem case — should show circle inside triangle)
    {
        "name": "Incircle of a triangle",
        "dtype": "triangle",
        "data": {"labels": ["A", "B", "C"], "show_height": False, "show_incircle": True},
        "min_shapes": 8,
        "checks": lambda shapes, html: [
            _assert("triangle sides drawn",
                    sum(1 for s in shapes if s.get("shape") == "line") >= 3),
            _assert("incircle (circle inside triangle) present",
                    any(s.get("shape") == "circle" for s in shapes)),
            _assert("incircle circle is inside triangle bounds (y>55, y<235)",
                    all(
                        55 < s.get("cy", 200) < 235
                        for s in shapes
                        if s.get("shape") == "circle"
                    )),
        ],
    },

    # 3. Circle with radius label
    {
        "name": "Circle with radius",
        "dtype": "circle_radius",
        "data": {"radius": 80, "label": "r"},
        "min_shapes": 4,
        "checks": lambda shapes, html: [
            _assert("circle shape present",
                    any(s.get("shape") == "circle" for s in shapes)),
            _assert("radius line drawn",
                    any(s.get("shape") == "line" for s in shapes)),
            _assert("label 'r' or radius text present",
                    any("r" in str(s.get("value","")).lower() for s in shapes if s.get("shape")=="text")),
            _assert("centre dot present",
                    any(s.get("shape") == "dot" for s in shapes)),
        ],
    },

    # 4. Rectangle area
    {
        "name": "Rectangle area 150x80",
        "dtype": "rectangle_area",
        "data": {"width": 150, "height": 80},
        "min_shapes": 4,
        "checks": lambda shapes, html: [
            _assert("rect shape present",
                    any(s.get("shape") == "rect" for s in shapes)),
            _assert("area formula text present",
                    any("A" in str(s.get("value","")) or "×" in str(s.get("value",""))
                        for s in shapes if s.get("shape")=="text")),
        ],
    },

    # 5. Flow — 3 steps horizontal
    {
        "name": "Flow 3 steps",
        "dtype": "flow",
        "data": {"steps": ["Input", "Process", "Output"]},
        "min_shapes": 9,
        "checks": lambda shapes, html: [
            _assert("3 node circles",
                    sum(1 for s in shapes if s.get("shape")=="circle") >= 3),
            _assert("arrows between nodes",
                    sum(1 for s in shapes if s.get("shape")=="arrow") >= 2),
            _assert("step labels present",
                    sum(1 for s in shapes if s.get("shape")=="text"
                        and any(w in str(s.get("value",""))
                                for w in ["Input","Process","Output"])) >= 3),
            _assert("nodes horizontally spread (x range > 150)",
                    _x_spread([s for s in shapes if s.get("shape")=="circle"]) > 150),
        ],
    },

    # 6. Flow — 5 steps staircase layout
    {
        "name": "Flow 5 steps staircase",
        "dtype": "flow",
        "data": {"steps": ["Gather", "Analyse", "Design", "Build", "Test"]},
        "min_shapes": 15,
        "checks": lambda shapes, html: [
            _assert("5 circles",
                    sum(1 for s in shapes if s.get("shape")=="circle") >= 5),
            _assert("4 arrows",
                    sum(1 for s in shapes if s.get("shape")=="arrow") >= 4),
            _assert("labels cover most of 400px width",
                    _x_spread([s for s in shapes if s.get("shape")=="circle"]) > 200),
        ],
    },

    # 7. Cycle — water cycle
    {
        "name": "Cycle water cycle 4 steps",
        "dtype": "cycle",
        "data": {"steps": ["Evaporation", "Condensation", "Precipitation", "Collection"],
                 "title": "Water"},
        "min_shapes": 12,
        "checks": lambda shapes, html: [
            _assert("4 node circles (+ hub = 5 circles total)",
                    sum(1 for s in shapes if s.get("shape")=="circle") >= 5),
            _assert("hub circle at centre (~200,148)",
                    any(abs(s.get("cx",0)-200)<30 and abs(s.get("cy",0)-148)<30
                        for s in shapes if s.get("shape")=="circle")),
            _assert("curved arrows present",
                    any(s.get("shape")=="curved_arrow" for s in shapes)),
            _assert("nodes spread in a ring (x range > 100)",
                    _x_spread([s for s in shapes if s.get("shape")=="circle"
                               and not(abs(s.get("cx",0)-200)<30 and abs(s.get("cy",0)-148)<30)
                               ]) > 100),
        ],
    },

    # 8. Comparison
    {
        "name": "Comparison Plant vs Animal cell",
        "dtype": "comparison",
        "data": {
            "left": "Plant Cell",
            "right": "Animal Cell",
            "left_points": ["Has cell wall", "Has chloroplast", "Large vacuole"],
            "right_points": ["No cell wall", "No chloroplast", "Small vacuole"],
        },
        "min_shapes": 14,
        "checks": lambda shapes, html: [
            _assert("left panel present",
                    any(s.get("shape")=="rounded_rect" and s.get("x",999)<50 for s in shapes)),
            _assert("right panel present",
                    any(s.get("shape")=="rounded_rect" and s.get("x",0)>100 for s in shapes)),
            _assert("vs badge circle at centre",
                    any(s.get("shape")=="circle" and abs(s.get("cx",0)-200)<15 for s in shapes)),
            _assert("bullet dots for left points",
                    sum(1 for s in shapes if s.get("shape")=="dot" and s.get("cx",999)<100) >= 3),
        ],
    },

    # 9. Line graph
    {
        "name": "Line graph population growth",
        "dtype": "line_graph",
        "data": {
            "points": [[0,1],[1,2],[2,4],[3,7],[4,11]],
            "x_label": "Year",
            "y_label": "Pop",
        },
        "min_shapes": 10,
        "checks": lambda shapes, html: [
            _assert("x and y axis arrows",
                    sum(1 for s in shapes if s.get("shape")=="arrow") >= 2),
            _assert("4 line segments",
                    sum(1 for s in shapes if s.get("shape")=="line") >= 4),
            _assert("5 data-point dots",
                    sum(1 for s in shapes if s.get("shape")=="dot") >= 5),
            _assert("axis labels present",
                    any("Year" in str(s.get("value","")) for s in shapes if s.get("shape")=="text")),
        ],
    },

    # 10. Labeled diagram (anatomy style)
    {
        "name": "Labeled diagram cell parts",
        "dtype": "labeled_diagram",
        "data": {
            "center": "Cell",
            "center_shape": "circle",
            "parts": ["Nucleus", "Mitochondria", "Ribosome", "Membrane"],
        },
        "min_shapes": 12,
        "checks": lambda shapes, html: [
            _assert("centre circle present",
                    any(s.get("shape")=="circle" and abs(s.get("cx",0)-200)<30
                        and abs(s.get("cy",0)-148)<30 for s in shapes)),
            _assert("4 dashed pointer lines",
                    sum(1 for s in shapes if s.get("shape")=="dashed_line") >= 4),
            _assert("4 part labels",
                    sum(1 for s in shapes
                        if s.get("shape")=="text"
                        and any(p in str(s.get("value",""))
                                for p in ["Nucleus","Mitochondria","Ribosome","Membrane"])) >= 4),
        ],
    },
]

# ─────────────────────────────────────────────────────────────────────────────
# Helpers
# ─────────────────────────────────────────────────────────────────────────────

def _assert(name, cond):
    return {"check": name, "passed": bool(cond)}

def _x_spread(circles):
    xs = [s.get("cx", s.get("x", 200)) for s in circles]
    return (max(xs) - min(xs)) if xs else 0

def _check_bounds(shapes):
    """Return list of shapes that are out of the 400×300 viewBox."""
    out = []
    for s in shapes:
        shape = s.get("shape", "")
        if shape in ("text", "emoji"):
            x, y = s.get("x", 200), s.get("y", 150)
            if not (0 <= x <= 400 and 0 <= y <= 300):
                out.append((shape, x, y, s.get("value", "")))
        elif shape == "circle":
            cx, cy, r = s.get("cx",200), s.get("cy",150), s.get("r",10)
            if cx-r < 0 or cx+r > 400 or cy-r < 0 or cy+r > 300:
                out.append((shape, cx, cy, f"r={r}"))
        elif shape in ("line", "arrow", "dashed_line"):
            for coord, bound in [
                (s.get("x1",0), 400), (s.get("x2",0), 400),
                (s.get("y1",0), 300), (s.get("y2",0), 300),
            ]:
                if coord < 0 or coord > bound:
                    out.append((shape, s.get("x1"), s.get("y1"), "out-of-bounds"))
                    break
    return out

def _check_label_overlap(shapes, min_dist=18):
    """Return pairs of text labels whose centres are too close."""
    texts = [(s.get("x",0), s.get("y",0), s.get("value","")) for s in shapes
             if s.get("shape") == "text"]
    overlaps = []
    for i in range(len(texts)):
        for j in range(i+1, len(texts)):
            dx = texts[i][0] - texts[j][0]
            dy = texts[i][1] - texts[j][1]
            d = math.sqrt(dx*dx + dy*dy)
            if d < min_dist:
                overlaps.append((texts[i][2], texts[j][2], round(d,1)))
    return overlaps

def _check_html(html):
    """Check HTML validity. Note: SVG shapes with SMIL <animate> children are
    intentionally non-self-closing — that is correct SVG syntax."""
    issues = []
    if not html:
        issues.append("EMPTY HTML")
        return issues
    if "viewBox" not in html:
        issues.append("missing viewBox")
    if "<svg" not in html:
        issues.append("missing <svg>")
    if "</svg>" not in html:
        issues.append("missing </svg>")
    if "animate" not in html:
        issues.append("no SMIL animations")
    # Only flag shapes that have NO closing tag at all and are also not self-closed
    for tag in ("circle", "rect", "polygon", "ellipse"):
        opens  = len(re.findall(rf'<{tag}[\s>]', html))
        closes = len(re.findall(rf'</{tag}>', html))
        selfcl = len(re.findall(rf'<{tag}[^>]*/>', html))
        if opens != closes + selfcl:
            issues.append(f"unmatched <{tag}> open={opens} close={closes} self={selfcl}")
    return issues

# ─────────────────────────────────────────────────────────────────────────────
# Runner
# ─────────────────────────────────────────────────────────────────────────────

def run_tests():
    total = len(TESTS)
    passed_total = 0
    issues_found = []  # (test_name, issue_desc)

    print("=" * 70)
    print(f"  SVG DIAGRAM VALIDATION — {total} tests")
    print("=" * 70)

    for idx, t in enumerate(TESTS, 1):
        name  = t["name"]
        dtype = t["dtype"]
        data  = t["data"]

        print(f"\n[{idx:02d}] {name}")
        print(f"     type={dtype}")

        # ── Build shapes
        shapes = build_from_diagram_type(dtype, data)
        if not shapes:
            print(f"     ❌ FATAL: renderer returned empty shapes list")
            issues_found.append((name, "renderer returned empty list"))
            continue

        print(f"     shapes: {len(shapes)}")

        # ── Minimum count
        min_ok = len(shapes) >= t["min_shapes"]
        status = "✅" if min_ok else "⚠️ "
        print(f"     {status} shape count {len(shapes)} (min {t['min_shapes']})")
        if not min_ok:
            issues_found.append((name, f"only {len(shapes)} shapes, expected >= {t['min_shapes']}"))

        # ── Bounds check
        oob = _check_bounds(shapes)
        if oob:
            print(f"     ⚠️  out-of-bounds elements: {oob[:3]}")
            issues_found.append((name, f"out-of-bounds: {oob[:3]}"))
        else:
            print(f"     ✅ all elements within 400×300")

        # ── Label overlap
        overlaps = _check_label_overlap(shapes)
        if overlaps:
            print(f"     ⚠️  overlapping labels: {overlaps[:3]}")
            issues_found.append((name, f"label overlap: {overlaps[:3]}"))
        else:
            print(f"     ✅ no label overlaps")

        # ── Build HTML
        html = build_animated_svg(shapes)
        html_issues = _check_html(html)
        if html_issues:
            print(f"     ❌ HTML issues: {html_issues}")
            issues_found.append((name, f"HTML issues: {html_issues}"))
        else:
            print(f"     ✅ HTML valid ({len(html)} chars)")

        # ── Type-specific checks
        check_results = t["checks"](shapes, html)
        sub_pass = 0
        for cr in check_results:
            icon = "✅" if cr["passed"] else "❌"
            print(f"     {icon} {cr['check']}")
            if cr["passed"]:
                sub_pass += 1
            else:
                issues_found.append((name, f"FAILED: {cr['check']}"))

        test_ok = min_ok and not oob and not html_issues and sub_pass == len(check_results)
        if test_ok:
            passed_total += 1
            print(f"     → PASS")
        else:
            print(f"     → FAIL ({sub_pass}/{len(check_results)} checks)")

    print("\n" + "=" * 70)
    print(f"  RESULTS: {passed_total}/{total} tests fully passed")
    print("=" * 70)

    if issues_found:
        print(f"\n  Issues to fix ({len(issues_found)} total):")
        seen = set()
        for name, issue in issues_found:
            key = f"  [{name}] {issue}"
            if key not in seen:
                seen.add(key)
                print(key)
    else:
        print("\n  All checks passed!")

    return issues_found


if __name__ == "__main__":
    issues = run_tests()
    sys.exit(0 if not issues else 1)
