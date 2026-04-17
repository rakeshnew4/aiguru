"""
svg_builder.py — Server-side animated SVG HTML builder.

Converts a JSON array of simple shape objects into a self-contained HTML page
with chalk-style SMIL animations.  The server builds the HTML so:
  - LLM only needs to output simple JSON coordinates (reliable)
  - Python handles all math (stroke-dashoffset, circumference)
  - Android WebView receives ready-to-render HTML (zero client-side work)

Canvas: 400 × 300 viewBox.
Shapes: line, circle, rect, text.
"""

import json
import math

STROKE = "#F0EDD0"        # chalk white
LABEL_COLOR = "#F5E3A0"   # chalk yellow for text labels
BG_COLOR = "#1A2B1A"      # dark blackboard green


def build_animated_svg(elements_json: str) -> str:
    """
    Convert a JSON array of shape objects into a full animated HTML page.

    Returns empty string on any parse error or empty input.

    Example element objects:
      {"shape":"line","x1":200,"y1":50,"x2":50,"y2":270}
      {"shape":"circle","cx":200,"cy":200,"r":60}
      {"shape":"rect","x":10,"y":10,"w":100,"h":60}
      {"shape":"text","x":150,"y":290,"value":"incircle"}
    """
    try:
        elements = json.loads(elements_json) if isinstance(elements_json, str) else elements_json
    except (json.JSONDecodeError, TypeError):
        return ""

    if not elements or not isinstance(elements, list):
        return ""

    parts = []
    delay = 0.0

    for el in elements:
        if not isinstance(el, dict):
            continue
        shape = el.get("shape", "")

        if shape == "line":
            x1 = float(el.get("x1", 0))
            y1 = float(el.get("y1", 0))
            x2 = float(el.get("x2", 0))
            y2 = float(el.get("y2", 0))
            length = math.sqrt((x2 - x1) ** 2 + (y2 - y1) ** 2)
            parts.append(
                f'<line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" '
                f'stroke="{STROKE}" stroke-width="2.5" stroke-linecap="round" '
                f'stroke-dasharray="{length:.2f}" stroke-dashoffset="{length:.2f}">'
                f'<animate attributeName="stroke-dashoffset" from="{length:.2f}" to="0" '
                f'dur="0.5s" begin="{delay:.2f}s" fill="freeze"/></line>'
            )
            delay += 0.4

        elif shape == "circle":
            cx = float(el.get("cx", 0))
            cy = float(el.get("cy", 0))
            r = float(el.get("r", 10))
            circ = 2 * math.pi * r
            parts.append(
                f'<circle cx="{cx}" cy="{cy}" r="{r}" '
                f'stroke="{STROKE}" stroke-width="2.5" fill="none" '
                f'stroke-dasharray="{circ:.2f}" stroke-dashoffset="{circ:.2f}">'
                f'<animate attributeName="stroke-dashoffset" from="{circ:.2f}" to="0" '
                f'dur="0.6s" begin="{delay:.2f}s" fill="freeze"/></circle>'
            )
            delay += 0.5

        elif shape == "rect":
            x = float(el.get("x", 0))
            y = float(el.get("y", 0))
            w = float(el.get("w", 0))
            h = float(el.get("h", 0))
            parts.append(
                f'<rect x="{x}" y="{y}" width="{w}" height="{h}" '
                f'stroke="{STROKE}" stroke-width="2.5" fill="none" opacity="0">'
                f'<animate attributeName="opacity" from="0" to="1" '
                f'dur="0.4s" begin="{delay:.2f}s" fill="freeze"/></rect>'
            )
            delay += 0.4

        elif shape == "text":
            x = float(el.get("x", 0))
            y = float(el.get("y", 0))
            value = str(el.get("value", ""))
            # Escape HTML special characters to prevent XSS via LLM output
            value = (
                value.replace("&", "&amp;")
                     .replace("<", "&lt;")
                     .replace(">", "&gt;")
                     .replace('"', "&quot;")
            )
            parts.append(
                f'<text x="{x}" y="{y}" font-family="serif" font-size="13" '
                f'fill="{LABEL_COLOR}" opacity="0">{value}'
                f'<animate attributeName="opacity" from="0" to="1" '
                f'dur="0.4s" begin="{delay:.2f}s" fill="freeze"/></text>'
            )
            delay += 0.3

    if not parts:
        return ""

    svg_body = "\n".join(parts)

    return (
        '<!DOCTYPE html><html>'
        '<head><meta name="viewport" content="width=device-width,initial-scale=1,user-scalable=no">'
        f'<style>*{{margin:0;padding:0}}body{{background:{BG_COLOR};display:flex;'
        'align-items:center;justify-content:center;width:100%;height:100vh}}'
        'svg{max-width:100%}</style></head>'
        f'<body><svg viewBox="0 0 400 300" width="100%" xmlns="http://www.w3.org/2000/svg">'
        f'{svg_body}</svg></body></html>'
    )
