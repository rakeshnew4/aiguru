"""
SVG and diagram pipeline summary for AI agents.

Update this file when changing:
- server/app/services/diagram_service.py
- server/app/utils/diagram_router.py
- server/app/utils/svg_builder.py
- server/app/utils/svg_primitives*.py
- server/app/utils/svg_renderers*.py
- server/app/utils/js_engine.py
- server/app/utils/svg_llm_builder.py
- server/app/api/image_search_titles.py
- server/app/static/engine/*
"""

ACTIVE_DIAGRAM_PATHS = {
    "standalone_diagram_endpoint": {
        "entrypoint": "server/app/api/diagram.py -> server/app/services/diagram_service.py",
        "purpose": "Generate a single explanation + diagram_html payload for /diagram/generate.",
    },
    "blackboard_diagram_frames": {
        "entrypoint": "server/app/api/chat.py -> server/app/api/image_search_titles.py::get_titles()",
        "purpose": "Convert Blackboard lesson frames into svg_html during BB post-processing.",
    },
}


SVG_STACK = {
    "decision_layer": "server/app/utils/diagram_router.py",
    "deterministic_builder": "server/app/utils/svg_builder.py",
    "shape_renderer": "server/app/utils/svg_primitives.py + svg_primitives_ext.py",
    "python_layout_modules": [
        "server/app/utils/svg_renderers.py",
        "server/app/utils/svg_renderers_sci.py",
        "server/app/utils/svg_renderers_math.py",
    ],
    "js_engine_bridge": "server/app/utils/js_engine.py",
    "js_engine_assets": "server/app/static/engine/{core,shapes,motion,diagrams}.js",
    "raw_svg_llm_path": "server/app/utils/svg_llm_builder.py",
    "legacy_backup_files": [
        "server/app/utils/svg_builder_new.py",
        "server/app/utils/svg_builder_original_backup.py",
    ],
}


KEY_REALITIES = [
    "diagram_service.py is a smaller, simpler pipeline than the Blackboard rendering pipeline.",
    "diagram_router.classify_diagram_need() is rule-based and decides whether a topic should become a diagram and which semantic type fits best.",
    "svg_builder.py expects semantic diagram_type + data, not raw coordinates from the LLM.",
    "Blackboard rendering prefers richer engines before falling back to basic SMIL output.",
    "svg_llm_builder.py is used for custom anatomy/apparatus/structure cases where semantic renderers are insufficient.",
]


BLACKBOARD_RENDER_ORDER = [
    "1. If diagram_type == atom -> build_atom_html()",
    "2. Else try build_js_diagram_html(diagram_type, data)",
    "3. Else try build_llm_svg(diagram_type, data, topic, speech)",
    "4. Else try build_from_diagram_type() + build_animated_svg()",
    "5. Else, if legacy svg_elements exist, feed them to build_animated_svg()",
]


STANDALONE_DIAGRAM_PSEUDOCODE = """
def generate_diagram(question):
    detected_type = auto_detect_from_keywords(question)
    source = "auto"

    if detected_type is usable_without_llm:
        data = default_or_question_specific_data(detected_type, question)
        explanation = ""
        visual_intent = ""
    else:
        payload = call_llm_for_json(question)
        detected_type = payload.diagram_type
        data = sanitise_data(detected_type, payload.data)
        explanation = payload.explanation
        visual_intent = payload.visual_intent
        source = "llm"

    shapes = build_from_diagram_type(detected_type, data)
    html = build_animated_svg(shapes)
    if html is empty:
        fall back to a simple flow diagram
    return {diagram_type, explanation, visual_intent, diagram_html, source}
""".strip()


BLACKBOARD_DIAGRAM_PSEUDOCODE = """
async def get_titles(bb_json, extra_candidates=None):
    data = parse_blackboard_json(bb_json)
    launch enrichment tasks for diagram data + quiz answer validation
    launch wikimedia searches for eligible steps
    apply enrichment results back into frames

    for each step:
        if step contains diagram frame:
            suppress wikimedia image for that step
            for each diagram frame:
                d_type = frame.diagram_type
                d_data = frame.data
                if neither diagram_type nor svg_elements is present:
                    auto-classify with diagram_router
                html = render_with_priority_order(atom -> js engine -> llm svg -> python smil -> legacy svg_elements)
                if html:
                    frame["svg_html"] = html
                remove frame["svg_elements"], frame["diagram_type"], frame["data"]

    attach best Wikimedia titles to remaining image-based steps
    return updated Blackboard JSON
""".strip()


SUPPORTED_TYPE_HINTS = {
    "rule-based semantic types": [
        "flow",
        "cycle",
        "comparison",
        "triangle",
        "circle_radius / circle_geometry",
        "rectangle_area",
        "line_graph",
        "graph_function",
        "number_line",
        "fraction_bar",
        "waveform_signal",
        "solar_system",
        "atom",
        "labeled_diagram",
    ],
    "js-engine-friendly": [
        "atom",
        "solar_system",
        "waveform_signal",
        "flow",
        "cycle",
        "comparison",
        "labeled_diagram",
        "heart",
        "neuron",
        "dna",
        "pendulum",
        "spring_mass",
        "lens",
        "electric_field",
        "triangle",
        "polygon",
        "circle_geometry",
        "coordinate_plane",
        "venn_diagram",
        "bar_chart",
        "pie_chart",
        "line_graph",
        "pythagoras",
    ],
    "best fit for raw svg llm path": [
        "custom anatomy",
        "body structures",
        "lab apparatus",
        "diagrams whose exact silhouette matters more than a simple semantic template",
    ],
}


IMPORTANT_FILES = {
    "server/app/services/diagram_service.py": "Simple auto-detect + LLM JSON + deterministic SVG path for /diagram/generate.",
    "server/app/utils/diagram_router.py": "Rule-based classifier used mainly to hint or auto-select Blackboard diagram types.",
    "server/app/utils/svg_builder.py": "Public Python SVG entrypoint: renderer registry + HTML assembly.",
    "server/app/utils/js_engine.py": "Builds self-contained HTML that inlines the JS diagram engine.",
    "server/app/utils/svg_llm_builder.py": "Asks the LLM for raw SVG XML and validates/repairs it.",
    "server/app/api/image_search_titles.py": "Blackboard post-processor that enriches frames and builds svg_html.",
}


MAINTENANCE_RULES = [
    "If you add a new diagram type, update the classifier, at least one renderer path, and this file.",
    "If a type should be animated continuously, prefer the JS engine when possible.",
    "If a diagram needs precise organic shapes, the raw SVG LLM path is usually more suitable than the SMIL semantic builder.",
    "If you only change fallback HTML wrapping or SMIL timing, the active file is svg_builder.py, not the backup builders.",
]
