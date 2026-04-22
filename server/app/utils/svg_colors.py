"""
svg_colors.py — Colour palette, layout utilities, and HTML escaping.

Imported by all other svg_* modules.
"""

import re as _re

# ── Colour palette ────────────────────────────────────────────────────────────
STROKE      = "#F0EDD0"   # chalk white
LABEL_COLOR = "#F5E3A0"   # chalk yellow
ACCENT      = "#4FC3F7"   # sky blue
HIGHLIGHT   = "#FF6B6B"   # warm red / emphasis
DIM         = "#8BAB8B"   # muted green
BG_COLOR    = "#1A2B1A"   # blackboard green

COLORS = {
    "primary":   STROKE,
    "label":     LABEL_COLOR,
    "highlight": HIGHLIGHT,
    "secondary": ACCENT,
    "dim":       DIM,
    # ── Extended palette ──────────────────────────────────────────────────
    "orange":    "#FFB74D",
    "green":     "#81C784",
    "pink":      "#F48FB1",
    "purple":    "#CE93D8",
    "teal":      "#4DB6AC",
    "yellow":    "#FFF176",
    "red":       "#EF5350",
    "blue":      "#42A5F5",
    "gold":      "#FFD54F",
    "coral":     "#FF7043",
    "mint":      "#A8D8A8",
    "white":     "#FFFFFF",
    "sky":       "#87CEEB",
    "brown":     "#A1887F",
}

# Base delay between animation stages (seconds)
_STAGE_STEP = 0.42


# ── Layout utilities ──────────────────────────────────────────────────────────

def _clamp(val: float, lo: float, hi: float) -> float:
    return max(lo, min(hi, val))


# Grid columns/rows for snapping legacy raw coords
_GRID_X = [50.0, 150.0, 200.0, 250.0, 350.0]
_GRID_Y = [60.0, 120.0, 150.0, 190.0, 240.0]


def _snap_x(val: float) -> float:
    return min(_GRID_X, key=lambda g: abs(g - val))


def _snap_y(val: float) -> float:
    return min(_GRID_Y, key=lambda g: abs(g - val))


def _escape(value: str) -> str:
    """HTML-escape a label value — prevents XSS via LLM label strings."""
    return (
        str(value)
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace('"', "&quot;")
    )


def _resolve_color(key: str) -> str:
    """Resolve a colour key or raw hex string.

    Priority:
      1. Key in COLORS dict   (e.g. "orange", "highlight")
      2. CSS hex value        (e.g. "#FF6B6B")
      3. 3-char shorthand     (e.g. "#F00")
      4. Falls back to STROKE so lines are always visible.
    """
    k = str(key).strip()
    if k in COLORS:
        return COLORS[k]
    if _re.match(r"^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$", k):
        return k
    return COLORS.get(k, STROKE)
