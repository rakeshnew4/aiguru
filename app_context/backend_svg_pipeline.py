"""
SVG and diagram pipeline summary for AI agents.

Update this file when changing:
- server/app/services/diagram_service.py
- server/app/utils/diagram_router.py
- server/app/utils/svg_builder.py
- server/app/utils/svg_atom.py
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
    "standalone_service": "server/app/services/diagram_service.py",
    "python_entrypoint": "server/app/utils/svg_builder.py",
    "shape_renderer": "server/app/utils/svg_primitives.py + svg_primitives_ext.py",
    "python_layout_modules": [
        "server/app/utils/svg_renderers.py",
        "server/app/utils/svg_renderers_sci.py",
        "server/app/utils/svg_renderers_math.py",
    ],
    "atom_html_generator": "server/app/utils/svg_atom.py (re-exported via svg_builder.build_atom_html)",
    "js_engine_bridge": "server/app/utils/js_engine.py",
    "js_engine_assets": "server/app/static/engine/{core,shapes,motion,diagrams}.js",
    "raw_svg_llm_path": "server/app/utils/svg_llm_builder.py",
    "blackboard_post_processor": "server/app/api/image_search_titles.py",
    "legacy_backup_files": [
        "server/app/utils/svg_builder_new.py",
        "server/app/utils/svg_builder_original_backup.py",
    ],
}


KEY_REALITIES = [
    "diagram_service.py is a narrower standalone pipeline than the Blackboard rendering pipeline.",
    "diagram_service.py now derives valid standalone types from svg_builder._RENDERERS, so standalone support is defined by the live renderer registry, not by a stale hardcoded list.",
    "diagram_service.py keeps a per-process _diagram_cache keyed by lowercased question text, so repeated standalone requests can avoid a second LLM call.",
    "diagram_router.classify_diagram_need() has grown into a broad CBSE-oriented keyword map for science and math topics, not just a tiny generic classifier.",
    "The router and JS engine support some types that the standalone Python renderer registry does not; Blackboard can often render them, while /diagram/generate may coerce or fall back.",
    "Blackboard rendering prefers richer engines before falling back to basic SMIL output.",
    "image_search_titles.py no longer just picks titles; it enriches frames, validates quiz answers, renders diagrams, and stores direct Wikimedia image URLs back into image_description.",
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
    question = question.strip()
    if not question:
        return empty_flow_shell()

    cache_key = question.lower()
    if cache_key in _diagram_cache:
        return _diagram_cache[cache_key]

    detected_type = auto_detect_from_keywords(question)
    source = "auto"

    if detected_type and detected_type not in {"comparison", "line_graph"}:
        if detected_type == "cycle":
            data = auto_data_for_known_cycle(question)
        elif detected_type == "triangle":
            data = triangle_defaults_plus_keyword_flags(question)
        else:
            data = default_data_for(detected_type)
        explanation = ""
        visual_intent = ""
    else:
        payload = call_llm_for_json(question)
        detected_type = validated_against(svg_builder._RENDERERS.keys(), payload.diagram_type)
        data = sanitise_data(detected_type, payload.data)
        explanation = payload.explanation
        visual_intent = payload.visual_intent
        source = "llm"

    shapes = build_from_diagram_type(detected_type, data)
    html = build_animated_svg(shapes)
    if html is empty:
        detected_type = "flow"
        html = render_simple_flow_fallback(question)

    result = {diagram_type, explanation, visual_intent, diagram_html, source}
    _diagram_cache[cache_key] = result
    return result
""".strip()


BLACKBOARD_DIAGRAM_PSEUDOCODE = """
async def get_titles(bb_json, extra_candidates=None):
    data = parse_blackboard_json(bb_json)
    launch enrichment tasks for diagram data + quiz answer validation
    launch Wikimedia searches for eligible image descriptions
    await everything together
    apply enrichment results back into frames

    for each step:
        for each diagram frame:
            d_type = frame.diagram_type
            d_data = frame.data
            if neither diagram_type nor svg_elements is present:
                d_type = classify_diagram_need(step_title + frame_speech)
            html = render_with_priority_order(atom -> js engine -> llm svg -> python smil -> legacy svg_elements)
            if html:
                frame["svg_html"] = html
            remove frame["svg_elements"], frame["diagram_type"], frame["data"]

    merge Wikimedia results with planner-prefetched candidates
    dedupe by URL
    pick best candidate URL per step with LLM-based chooser and overlap fallback
    write direct URLs into step["image_description"]
    clear unmatched image_description values
    return updated Blackboard JSON
""".strip()


SUPPORTED_TYPE_HINTS = {
    "standalone_python_renderer_registry": [
        "triangle",
        "circle_radius",
        "rectangle_area",
        "geometry_angles / angle / angles",
        "pythagoras",
        "circle_geometry",
        "line_graph",
        "graph_function / function_plot / parabola",
        "number_line",
        "fraction_bar / fractions",
        "flow",
        "comparison",
        "cycle",
        "labeled_diagram / anatomy / cell / cell_diagram",
        "atom",
        "solar_system",
        "waveform_signal / sine_wave / wave",
    ],
    "blackboard_js_engine_examples": [
        "atom",
        "solar_system",
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
        "geometry_angles",
        "pythagoras",
    ],
    "router_keyword_expansion_examples": [
        "CBSE physics: laws of motion, distance-time graph, sound waves, reflection/refraction, electric circuit",
        "CBSE chemistry: atomic structure, ph scale, ionic/covalent bond, metals and non-metals",
        "CBSE biology: animal cell, plant cell, digestive system, circulatory system, photosynthesis",
        "CBSE math: integers on number line, equivalent fractions, mean/median/mode, complementary angles, tangent to a circle",
    ],
    "important_mismatches": [
        "diagram_service._SYSTEM_PROMPT advertises bar_chart, but bar_chart is not in svg_builder._RENDERERS, so standalone /diagram/generate cannot deterministically render it today.",
        "diagram_router and js_engine support types such as polygon, coordinate_plane, venn_diagram, pie_chart, and bar_chart that are not part of the standalone Python renderer registry.",
        "If a new type should work in /diagram/generate, adding it to the router or JS engine is not enough; it must also exist in svg_builder._RENDERERS and diagram_service._sanitise_data().",
    ],
}


IMPORTANT_FILES = {
    "server/app/services/diagram_service.py": "Simple standalone flow: auto-detect + optional LLM JSON + deterministic SVG path + per-process cache.",
    "server/app/utils/diagram_router.py": "Rule-based classifier used mainly to hint or auto-select Blackboard diagram types.",
    "server/app/utils/svg_builder.py": "Public Python SVG entrypoint: renderer registry + HTML assembly.",
    "server/app/utils/svg_atom.py": "Atom-specific HTML generator used by build_atom_html().",
    "server/app/utils/js_engine.py": "Builds self-contained HTML that inlines the JS diagram engine.",
    "server/app/utils/svg_llm_builder.py": "Asks the LLM for raw SVG XML and validates/repairs it.",
    "server/app/api/image_search_titles.py": "Blackboard post-processor that enriches frames, renders svg_html, and attaches direct image URLs.",
}


MAINTENANCE_RULES = [
    "If you add a new standalone diagram type, update svg_builder._RENDERERS, diagram_service._sanitise_data(), and this file.",
    "If you add a new Blackboard-only type, update the router, JS engine or raw SVG path, and this file.",
    "If a type should be animated continuously, prefer the JS engine when possible.",
    "If a diagram needs precise organic shapes, the raw SVG LLM path is usually more suitable than the SMIL semantic builder.",
    "If you only change fallback HTML wrapping or SMIL timing, the active file is svg_builder.py, not the backup builders.",
]
