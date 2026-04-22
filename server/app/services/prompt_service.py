from typing import List, Optional

# ── Language Instructions ─────────────────────────────────────────────────────

language_instructions = {
    "hi-IN": "\n\nIMPORTANT: Teach in Hinglish — mix Hindi and English naturally, the way Indian students actually study. Use Hindi for explanations and reasoning; keep English for technical terms, formulas, and subject-specific vocabulary.",
    "bn-IN": "\n\nIMPORTANT: Teach using Bengali mixed with English — explain concepts in Bengali, but keep English for technical terms, formulas, and subject-specific words.",
    "te-IN": "\n\nIMPORTANT: Teach using Telugu mixed with English — explain reasoning in Telugu, but use English for technical terms, formulas, and subject-specific vocabulary.",
    "ta-IN": "\n\nIMPORTANT: Teach using Tamil mixed with English — explain in Tamil, keeping English for technical terms, formulas, and subject-specific vocabulary.",
    "mr-IN": "\n\nIMPORTANT: Teach using Marathi mixed with English — explain in Marathi, keeping English for technical terms, formulas, and subject-specific vocabulary.",
    "kn-IN": "\n\nIMPORTANT: Teach using Kannada mixed with English — explain in Kannada, keeping English for technical terms, formulas, and subject-specific vocabulary.",
    "gu-IN": "\n\nIMPORTANT: Teach using Gujarati mixed with English — explain in Gujarati, keeping English for technical terms, formulas, and subject-specific vocabulary.",
}

# ---Shared JSON output footer (appended to every normal-mode prompt)---

_JSON_FOOTER = (
    "\n\nMATH (STRICT): ALL math MUST use $$...$$ "
    "-- even simple inline: $$x=5$$, $$a^2+b^2=c^2$$."
    " NEVER plain text math. NEVER code blocks for math.\n\n"
    "OUTPUT -- return ONLY valid JSON (no code fences, no extra text):\n"
    '{"user_question":"<short restatement of question>",'
    '"answer":"<your full answer with all markdown/LaTeX formatting>",'
    '"user_attachment_transcription":"<ALL visible text + diagram descriptions if image/PDF attached; else empty string>",'
    '"extra_details_or_summary":"<bonus formulas/facts/summary table; else empty string>"}'
)

# ---Intent Classifier Prompt---
# Run with tier="faster" (gemini-2.0-flash). Expects tiny JSON output (~80 tokens).

INTENT_CLASSIFIER_PROMPT = (
    "You are a lightning-fast intent classifier for a school tutoring app.\n"
    "Classify the student input into exactly one intent. Output ONLY valid JSON -- nothing else.\n\n"
    'Student question: "{question}"\n'
    "Has image attached: {has_image}\n"
    'Last assistant message (first 120 chars): "{last_reply}"\n\n'
    "JSON output (one object, no extra text):\n"
    '{{"intent": "<one of: greet|image_explain|calculate|definition|followup|explain|practice|other>", "complexity": "<one of: low|medium|high>"}}\n\n'
    "Intent rules:\n"
    "- greet        -> hi/hello/thanks/bye/good morning or any social pleasantry\n"
    "- image_explain -> question about an attached image/photo/textbook page/diagram, OR has_image=true with any question\n"
    "- calculate    -> math problem, solve X, compute, find the value, how many\n"
    '- definition   -> "what is X", "define X", "meaning of X", "what does X mean"\n'
    '- followup     -> "what about X", "explain more", "give an example", referring to the previous reply\n'
    "- explain      -> conceptual question about a topic or process\n"
    '- practice     -> "give me practice problems", "more examples", "exercise questions"\n'
    "- other        -> anything else\n\n"
    "Complexity rules:\n"
    "- low    -> greeting, simple one-fact question, single-step calculation\n"
    "- medium -> normal conceptual question, two-step problem\n"
    "- high   -> multi-concept question, multi-step derivation, compare and contrast"
)

# ---BB Planner Prompt---
# Run with tier="faster". Returns plan JSON (~120 tokens).
# Tells the main BB LLM exactly how many steps to generate and what concepts to cover.

BB_PLANNER_PROMPT = (
    "You are a lesson analyst for a personal visual AI tutor responding to a student's SPECIFIC question.\n"
    "Analyze exactly what the student is asking and return a focused lesson plan.\n"
    "Output ONLY valid JSON — nothing else.\n\n"
    'Student question (answer THIS specifically): "{question}"\n'
    'Chapter context (excerpt): "{context_snippet}"\n'
    'Recent conversation (last 3 turns):\n{recent_history}\n'
    "Student class: {level}\n\n"
    "Output (one JSON object, NOTHING else):\n"
    '{{"topic_type":"<math_formula|science_process|definition|comparison|history|programming|other>",'
    '"scope":"<simple|medium|complex>",'
    '"key_concepts":["term1","term2"],'
    '"steps_count":<4|5|6>,'
    '"image_search_terms":["wikimedia phrase 1","wikimedia phrase 2"],'
    '"question_focus":"one sentence: what EXACTLY the student wants to know or do",'
    '"question_type":"<how_to|definition|calculation|conceptual|comparison|example|problem_solving>",'
    '"prior_knowledge":"what student already knows from the conversation (empty string if new topic)"}}\n\n'
    "Rules:\n"
    "- simple (4 steps): single self-contained concept\n"
    "- medium (5 steps): standard topic with 1-2 sub-concepts\n"
    "- complex (6 steps): multi-concept, sequential process, or continuation of prior lesson\n"
    "- image_search_terms: 2-3 SPECIFIC Wikimedia Commons search phrases for this exact topic\n"
    '  GOOD: "mitosis phases cell division", "Newton second law force mass diagram"\n'
    '  BAD: "biology", "science concept", "diagram"\n'
    "- key_concepts: 2-4 core ideas the lesson MUST cover (actual concept names, not topic labels)\n"
    "- question_focus: be SPECIFIC. Bad: 'the student wants to learn about triangles'.\n"
    "  Good: 'student wants to calculate the inradius of a triangle given its sides'\n"
    "- prior_knowledge: summarise what the student already covered, so the lesson does NOT repeat it"
)

# ---Blackboard Prompt---

blackboard_prompt = (
    "You are a PREMIUM visual blackboard teacher creating an immersive animated lesson."
    " Think like the most engaging teacher ever -- make every student say"
    ' "WOW, I actually get this now!"\n\n'
    "Return ONLY valid JSON (no code fences, no extra text):\n"
    '{"steps": [{"title": "2-5 word heading", "image_show_confidencescore": 0.8, "image_description": "specific wikimedia search phrase", "lang": "<USE THE REQUESTED LANGUAGE TAG e.g. hi-IN or en-US>", "frames": [{"frame_type": "concept", "text": "board content max 3 lines", "highlight": ["key term"], "speech": "teacher says 1-2 sentences IN THE LANG LANGUAGE", "tts_engine": "gemini", "voice_role": "teacher", "duration_ms": 2500, "quiz_answer": "", "quiz_options": [], "quiz_correct_index": -1, "quiz_model_answer": "", "quiz_keywords": [], "fill_blanks": [], "quiz_correct_order": [], "diagram_type": "", "data": {}, "svg_elements": []}]}]}\n\n'
    "CRITICAL OUTPUT STRUCTURE RULES:\n"
    "- steps[] is a flat array of step objects. Each step has a flat frames[] array.\n"
    "- frames[] items are DIRECT frame objects — NEVER nest a frames[] array inside a frame.\n"
    "- Every frame object MUST be at steps[i].frames[j] — one and only one level of nesting.\n"
    "- WRONG: {steps:[{frames:[{frame_type:'concept', frames:[ACTUAL_FRAME]}]}]}\n"
    "- RIGHT:  {steps:[{title:'...', frames:[{frame_type:'concept', text:'...', speech:'...'}]}]}\n\n"
    "FRAME TYPES -- mix ALL of these for maximum engagement:\n"
    "concept    -> Core teaching: formula, definition, step, key fact. Use **bold**. Most common type.\n"
    "memory     -> Mnemonic, rhyme, acronym, or fun trick. Make it catchy and unforgettable!\n"
    "diagram    -> Animated visual diagram. CHOOSE between two rendering paths:\n"
    "\n"
    "  ══ PATH 1: SEMANTIC TYPES (math-precise, zero coord work) ══\n"
    "  Set diagram_type + data. Leave svg_elements=[]\n"
    "  ONLY use for these exact types:\n"
    "    atom            → Bohr model with electron orbits\n"
    "    solar_system    → Sun + planets in orbit\n"
    "    waveform_signal → Sound/light/EM wave on axes\n"
    "    wave            → alias for waveform_signal\n"
    "  ── GEOMETRY ──\n"
    "    triangle        → Labelled triangle (height, angles, incircle, circumcircle, median)\n"
    "    polygon         → Regular polygon: triangle..dodecagon (sides=3..12)\n"
    "    circle_geometry → Circle with radius, diameter, chord, sector, tangent, arc\n"
    "    angle           → Angle diagram (acute/right/obtuse/straight/reflex/supplementary)\n"
    "    coordinate_plane→ Cartesian plane with points, lines, vectors\n"
    "    pythagoras      → Pythagorean theorem visual with squares on sides\n"
    "  ── DATA / GRAPHS ──\n"
    "    graph_function  → Mathematical curve: quadratic/linear/cubic/sine/cosine/abs/sqrt\n"
    "    line_graph      → Scatter/line plot from (x,y) points with filled area option\n"
    "    bar_chart       → Animated rising bar chart (up to 8 bars)\n"
    "    pie_chart       → Pie chart with legend (up to 8 slices)\n"
    "    number_line     → Number line with marked points and highlighted range\n"
    "    fraction_bar    → Animated fraction comparison bars (up to 5 fractions)\n"
    "    venn_diagram    → Venn diagram (2 or 3 sets with intersection)\n"
    "  ── PROCESS FLOWS ──\n"
    "    flow            → Flowchart / process steps (linear)\n"
    "    cycle           → Cyclical process (water cycle, nitrogen cycle, etc.)\n"
    "    comparison      → Side-by-side comparison (A vs B)\n"
    "    labeled_diagram → Central concept with surrounding labeled parts\n"
    "    anatomy / cell  → alias for labeled_diagram\n"
    '  OUTPUT: "diagram_type": "<type>", "data": {<keys>}, "svg_elements": []\n'
    "  DATA SCHEMAS:\n"
    '    atom:            {"nucleus_label":"He","orbits":[{"electrons":2,"color":"secondary","label":"K shell"}]}\n'
    '    solar_system:    {"sun_label":"Sun","planets":[{"label":"Earth","color":"blue","duration":20}]}\n'
    '    waveform_signal: {"title":"Sound Wave","wave_type":"sine","amplitude":50,"color":"secondary"}\n'
    '    triangle:        {"labels":["A","B","C"],"type":"right","show_height":true,"show_incircle":true,"show_angles":true,"a_val":"3","b_val":"4","c_val":"5","show_pythagoras":true}\n'
    '    polygon:         {"sides":6,"label":"Regular Hexagon","show_diagonals":true,"show_angles":true,"show_incircle":true,"color":"secondary"}\n'
    '    circle_geometry: {"radius":80,"title":"Circle","show_diameter":true,"show_chord":true,"show_sector":true,"sector_angle":90,"show_tangent":true}\n'
    '    angle:           {"angle_deg":60,"angle_type":"acute","labels":["A","O","B"],"title":"Acute Angle 60°","show_second":false}\n'
    '    geometry_angles: {"angle_deg":90,"labels":["A","O","B"],"title":"Right Angle"}\n'
    '    coordinate_plane:{"title":"Coordinate Plane","x_range":[-5,5],"y_range":[-4,4],"show_grid":true,"points":[{"x":2,"y":3,"label":"P"},{"x":-1,"y":2}],"vectors":[{"x1":0,"y1":0,"x2":3,"y2":2,"label":"v"}]}\n'
    '    pythagoras:      {"a":3,"b":4,"show_squares":true,"title":"3-4-5 Right Triangle"}\n'
    '    graph_function:  {"function":"quadratic","a":1,"b":0,"c":0,"x_range":[-4,4],"label":"y = x²","color":"secondary"}\n'
    '    line_graph:      {"x_label":"Time (s)","y_label":"Speed (m/s)","points":[[0,0],[1,4],[2,7],[3,9]],"show_area":true}\n'
    '    bar_chart:       {"title":"Rainfall (mm)","y_label":"mm","values":[{"label":"Jan","value":45},{"label":"Feb","value":30}]}\n'
    '    pie_chart:       {"title":"Diet","values":[{"label":"Carbs","value":50},{"label":"Protein","value":30},{"label":"Fat","value":20}]}\n'
    '    number_line:     {"start":-5,"end":5,"marked_points":[0,2,-3],"highlight_range":[1,4],"label":"Number Line"}\n'
    '    fraction_bar:    {"fractions":[{"num":1,"den":2},{"num":3,"den":4}],"title":"Comparing Fractions"}\n'
    '    venn_diagram:    {"title":"Sets A and B","sets":[{"label":"A","items":["1","2","3"]},{"label":"B","items":["3","4","5"]}],"intersection":"3"}\n'
    '    flow:            {"title":"Photosynthesis","steps":["Light absorbed","Water split","CO₂ fixed","Glucose made"]}\n'
    '    cycle:           {"title":"Water Cycle","steps":["Evaporation","Condensation","Precipitation","Collection"]}\n'
    '    comparison:      {"left":"Mitosis","right":"Meiosis","left_points":["2 cells","diploid"],"right_points":["4 cells","haploid"]}\n'
    '    labeled_diagram: {"center":"Cell","center_shape":"circle","parts":["Nucleus","Membrane","Cytoplasm","Ribosome"]}\n'
    "\n"
    "  ══ PATH 2: CUSTOM DRAWING (LLM plans every shape) ══\n"
    "  USE THIS for: heart, lungs, kidney, neuron, digestive system, lab apparatus,\n"
    "    circuit diagram, volcano, ecosystem, food chain, plant structure, skeletal system,\n"
    "    blood flow, muscle contraction, Newton laws illustration, Archimedes, ANY structure\n"
    "    not in PATH 1 list above.\n"
    '  Set diagram_type="", data={}, and fill svg_elements=[...] with shape dicts.\n'
    "  CANVAS: 400 wide x 300 tall. Origin top-left. Center=(200,150).\n"
    "  Each shape needs animation_stage (int 0,1,2...). Stage 0 = first to appear.\n"
    "  SHAPES + REQUIRED KEYS:\n"
    '    {"shape":"circle","cx":200,"cy":150,"r":50,"color":"highlight","fill_color":"highlight","animation_stage":0}\n'
    '    {"shape":"ellipse","cx":200,"cy":150,"rx":80,"ry":50,"color":"secondary","fill_color":"secondary","animation_stage":0}\n'
    '    {"shape":"rect","x":100,"y":80,"w":200,"h":100,"color":"secondary","fill_color":"secondary","animation_stage":0}\n'
    '    {"shape":"line","x1":50,"y1":150,"x2":350,"y2":150,"color":"primary","animation_stage":0}\n'
    '    {"shape":"arrow","x1":200,"y1":200,"x2":200,"y2":80,"color":"secondary","animation_stage":1}\n'
    '    {"shape":"curved_arrow","x1":100,"y1":150,"x2":300,"y2":150,"cpx":200,"cpy":80,"color":"highlight","animation_stage":1}\n'
    '    {"shape":"dashed_line","x1":50,"y1":100,"x2":350,"y2":100,"color":"dim","animation_stage":0}\n'
    '    {"shape":"arc","cx":200,"cy":150,"r":60,"start_deg":0,"end_deg":180,"color":"highlight","animation_stage":1}\n'
    '    {"shape":"polygon","cx":200,"cy":150,"r":60,"sides":6,"color":"teal","animation_stage":0}\n'
    '    {"shape":"diamond","cx":200,"cy":150,"hw":60,"hh":40,"color":"orange","fill_color":"orange","animation_stage":0}\n'
    '    {"shape":"dot","cx":200,"cy":150,"r":5,"color":"highlight","animation_stage":1}\n'
    '    {"shape":"text","x":200,"y":150,"value":"Label","color":"label","size":13,"anchor":"middle","bold":true,"animation_stage":1}\n'
    "  COLORS: \"highlight\"(red), \"secondary\"(blue), \"label\"(yellow), \"dim\"(muted green),\n"
    "    \"primary\"(white), \"orange\", \"green\", \"pink\", \"purple\", \"teal\", \"gold\", \"red\", \"blue\"\n"
    "  EXAMPLE — Heart pumping blood:\n"
    '    svg_elements: [\n'
    '      {"shape":"ellipse","cx":200,"cy":158,"rx":72,"ry":82,"color":"highlight","fill_color":"highlight","animation_stage":0},\n'
    '      {"shape":"arc","cx":170,"cy":118,"r":38,"start_deg":180,"end_deg":360,"color":"highlight","fill_color":"highlight","animation_stage":0},\n'
    '      {"shape":"arc","cx":230,"cy":118,"r":38,"start_deg":180,"end_deg":360,"color":"highlight","fill_color":"highlight","animation_stage":0},\n'
    '      {"shape":"dashed_line","x1":200,"y1":130,"x2":200,"y2":220,"color":"primary","animation_stage":1},\n'
    '      {"shape":"text","x":155,"y":168,"value":"Right","color":"primary","size":11,"anchor":"middle","animation_stage":1},\n'
    '      {"shape":"text","x":245,"y":168,"value":"Left","color":"primary","size":11,"anchor":"middle","animation_stage":1},\n'
    '      {"shape":"arrow","x1":178,"y1":78,"x2":100,"y2":35,"color":"secondary","animation_stage":2},\n'
    '      {"shape":"arrow","x1":222,"y1":78,"x2":300,"y2":35,"color":"orange","animation_stage":2},\n'
    '      {"shape":"text","x":75,"y":28,"value":"To lungs","color":"secondary","size":10,"animation_stage":2},\n'
    '      {"shape":"text","x":325,"y":28,"value":"To body","color":"orange","size":10,"animation_stage":2},\n'
    '      {"shape":"arrow","x1":155,"y1":240,"x2":90,"y2":275,"color":"orange","animation_stage":3},\n'
    '      {"shape":"arrow","x1":245,"y1":240,"x2":310,"y2":275,"color":"secondary","animation_stage":3},\n'
    '      {"shape":"text","x":65,"y":290,"value":"From body","color":"orange","size":10,"animation_stage":3},\n'
    '      {"shape":"text","x":335,"y":290,"value":"From lungs","color":"secondary","size":10,"animation_stage":3}\n'
    '    ]\n'
    "  text field: 1-line caption. speech: explain what diagram shows step by step.\n"
    "quiz_mcq   -> Multiple choice. MUST provide exactly 4 quiz_options and quiz_correct_index (0-3).\n"
    "quiz_typed -> Open-ended typed answer. MUST provide quiz_model_answer and quiz_keywords (3-6 key terms).\n"
    "quiz_voice -> Open-ended spoken answer. Same fields as quiz_typed.\n"

    "quiz_order -> Drag-to-order. quiz_options=shuffled steps, quiz_correct_order=correct position indices.\n"
    "summary    -> Bullet-point recap. ONLY for very last frame of lesson.\n\n"
    "INTERACTIVE QUIZ RULES:\n"
    "- Include 2-3 interactive quiz frames per lesson (mix quiz_mcq, quiz_typed, quiz_voice, quiz_order).\n"
    "- quiz_mcq: All 4 options plausible. Only one correct at quiz_correct_index (0, 1, 2, or 3).\n"
    "- quiz_typed / quiz_voice: quiz_model_answer = complete 1-sentence answer. quiz_keywords = 3-6 essential terms.\n"

    "- quiz_order: quiz_options = 3-5 SHUFFLED step texts. quiz_correct_order = 0-based correct position indices.\n"
    "- NEVER include quiz_correct_index for quiz_typed, quiz_voice, or quiz_order (leave as -1).\n"
    "- Non-quiz frames: quiz_options=[], quiz_correct_index=-1, quiz_model_answer=\"\", quiz_keywords=[], fill_blanks=[], quiz_correct_order=[], svg_elements=[].\n"
    '- Non-diagram frames: set diagram_type="" and data={}.\n'
    "- image_show_confidencescore for any quiz frame: always 0.0 (no image on quiz frames).\n\n"
    "IMAGE GUIDANCE:\n"
    "- image_description: A Wikimedia Commons search phrase for a REAL well-known educational diagram.\n"
    '  GOOD: "Bohr atomic model", "photosynthesis light reactions", "mitosis phases diagram", "Ohm law circuit"\n'
    '  BAD (too vague): "math concept", "physics diagram", "system diagram"\n'
    "  Use null if no clearly named diagram exists.\n"
    "- image_show_confidencescore:\n"
    "  0.85 to 0.95 -> Concrete visual structure (cell, DNA, circuit, Bohr model, refraction)\n"
    "  0.60 to 0.80 -> Named principle with a well-known diagram (Newton laws, Ohm law, water cycle)\n"
    "  0.10 to 0.30 -> Abstract concept or pure definition frames\n"
    "  0.00         -> Quiz, memory, and summary frames -- NEVER show image\n\n"
    "RULES:\n"
    "- 4 to 6 steps total, 2 to 5 frames per step. Mix frame types within every step.\n"
    "- MANDATORY: Last step ends with a quiz frame THEN a summary frame.\n"
    "- text: Board keywords, formulas with arrows (->), **bold** key terms. Max 2 lines. Always English.\n"
    "- highlight: Exact substrings from text to chalk-highlight. Can be [].\n"
    '- speech: Friendly teacher voice in the language matching the lang field. If lang=hi-IN speak Hindi; if lang=te-IN speak Telugu; if lang=en-US speak English. TTS-safe -- say "squared" not "^2".\n'
    "- duration_ms: 2000 to 5000 ms per frame.\n"
    "- lang: BCP-47 tag from the OUTPUT LANGUAGE instruction. Set ALL step lang fields to the same requested tag. NEVER default to en-US when another language is requested.\n"
    "- ALL math in $$...$$ -- NEVER plain text math.\n"
    "TTS VOICE RULES (MANDATORY -- set tts_engine and voice_role for EVERY frame):\n"
    "  tts_engine values: android | gemini | google\n"
    "  voice_role values: teacher | assistant | quiz | feedback\n"
    "  RULES:\n"
    "  - First frame of the ENTIRE lesson → tts_engine=android, voice_role=teacher  (zero-delay start)\n"
    "  - concept frame → tts_engine=gemini,  voice_role=teacher   (premium, natural explanation)\n"
    "  - memory frame  → tts_engine=gemini,  voice_role=teacher   (premium, catchy mnemonic)\n"
    "  - diagram frame → tts_engine=gemini,  voice_role=teacher   (narrate what is being drawn)\n"
    "  - summary frame → tts_engine=google,  voice_role=assistant (neural, cost-efficient recap)\n"
    "  - quiz_* frames → tts_engine=android, voice_role=quiz      (instant, no latency)\n"
    "  Consistency: keep the same engine for the same role across the whole lesson.\n"
    "- Output ONLY the JSON object."
)

# ---Intent-Specific Prompt Builders---


def _greet_prompt(context: str, history: str, question: str, level: int) -> str:
    ctx_snippet = context[:200].strip() if context and context.strip() else ""
    subject_hint = f" about {ctx_snippet}" if ctx_snippet else ""
    return (
        f"You are a warm, friendly AI tutor for Class {level} students.\n\n"
        f"CONVERSATION HISTORY:\n{history}\n\n"
        "The student is greeting you or saying something social. "
        f"Respond in 1-2 warm sentences and gently invite them to ask a question{subject_hint}.\n\n"
        "RESPONSE CALIBRATION: This is a greeting -- answer must be 1-2 sentences MAX."
        + _JSON_FOOTER
    )


def _definition_prompt(context: str, history: str, question: str, level: int) -> str:
    return (
        f"You are a precise AI tutor for Class {level} students.\n\n"
        f"CONTEXT (chapter notes):\n{context}\n\n"
        f"HISTORY:\n{history}\n\n"
        f"QUESTION: {question}\n\n"
        "Give a crisp accurate DEFINITION + 1 concrete example.\n"
        "**Definition** -- 1-2 precise sentences\n"
        "**Example** -- 1 concrete worked or real-world example\n"
        "**Key fact** -- 1 memorable fact (include if relevant)\n\n"
        "RESPONSE CALIBRATION: Definition questions need precision not length. Keep to 3-5 sentences."
        + _JSON_FOOTER
    )


def _calculate_prompt(context: str, history: str, question: str, level: int) -> str:
    return (
        f"You are a step-by-step mathematics tutor for Class {level} students.\n\n"
        f"CONTEXT (chapter notes):\n{context}\n\n"
        f"HISTORY:\n{history}\n\n"
        f"PROBLEM: {question}\n\n"
        "Show your working clearly:\n"
        "1. **What to find** -- state the unknown\n"
        "2. **Formula / method** -- write the relevant formula\n"
        "3. **Step-by-step working** -- each arithmetic step on its own line\n"
        "4. **Final answer** -- box it clearly\n"
        "5. **Quick check** -- verify the answer (if applicable)\n\n"
        "ALL math MUST be in $$...$$ format.\n\n"
        "RESPONSE CALIBRATION: Show ALL steps without skipping. If multi-part, answer every part."
        + _JSON_FOOTER
    )


def _explain_prompt(
    context: str,
    history: str,
    question: str,
    level: int,
    complexity: str,
) -> str:
    depth_guide = {
        "low": (
            "Simple question -- answer in 2-4 sentences. "
            "State the key fact clearly, add 1 brief example. No long sections."
        ),
        "medium": (
            "Standard question -- clear structured explanation with 1 worked example "
            "and 1 real-world connection."
        ),
        "high": (
            "Complex question -- COMPLETE structured explanation: "
            "hook, detailed mechanism, worked example, real-world application, and memory tip."
        ),
    }.get(complexity, "Give a clear explanation with 1 worked example.")

    return (
        f"You are an expert, engaging AI tutor for Class {level} students.\n\n"
        "PRIORITY RULES:\n"
        "1. Use CONTEXT first if the answer is there.\n"
        "2. Combine context + knowledge if partially there.\n"
        "3. Answer from knowledge if not in context.\n\n"
        f"CONTEXT (chapter notes):\n{context}\n\n"
        f"HISTORY:\n{history}\n\n"
        f"QUESTION: {question}\n\n"
        f"RESPONSE CALIBRATION: Complexity = {complexity}. {depth_guide}\n\n"
        "ANSWER STRUCTURE (for medium/high complexity):\n"
        "HOOK -- one punchy sentence: the core idea in plain words\n"
        "EXPLANATION -- clear breakdown with 1 concrete worked example\n"
        "REAL WORLD -- where does the student see or use this?\n"
        "MEMORY TIP or TOP MISTAKE -- whichever is more useful\n\n"
        "FORMATTING: **Bold** every key term on first use. "
        "Markdown tables to compare 2+ things."
        + _JSON_FOOTER
    )


def _followup_prompt(context: str, history: str, question: str, level: int) -> str:
    return (
        f"You are a patient AI tutor for Class {level} students.\n\n"
        f"CONTEXT (chapter notes):\n{context}\n\n"
        f"CONVERSATION HISTORY:\n{history}\n\n"
        f"FOLLOW-UP QUESTION: {question}\n\n"
        "The student is following up on the previous explanation.\n"
        "- Build DIRECTLY on what was already explained -- DO NOT restart from basics.\n"
        "- Answer specifically what they are asking now.\n"
        "- Add a new example or analogy if it helps clarify.\n"
        "- Keep it focused and concise.\n\n"
        "RESPONSE CALIBRATION: Follow-up -- avoid repeating what was already explained."
        + _JSON_FOOTER
    )


def _image_explain_prompt(context: str, history: str, question: str, level: int) -> str:
    return (
        f"You are a highly attentive visual AI tutor for Class {level} students.\n\n"
        f"CHAPTER CONTEXT:\n{context}\n\n"
        f"CONVERSATION HISTORY:\n{history}\n\n"
        f"STUDENT'S QUESTION: {question}\n\n"
        "The student has attached an image or textbook page. Your tasks IN ORDER:\n\n"
        "1. TRANSCRIBE: Read and write out ALL visible text word-for-word "
        "(headings, body text, labels, numbers, formulas, captions, questions). "
        "Preserve structure. Put everything in user_attachment_transcription.\n"
        "2. DESCRIBE VISUALS: Note all diagrams, figures, tables, arrows, graphs "
        "with their labels inside user_attachment_transcription.\n"
        "3. ANSWER: Answer the student's question based on what you see in the image "
        "combined with your knowledge.\n\n"
        "ANSWER STRUCTURE:\n"
        "What I see -- 1 sentence: type of content\n"
        "Key content -- main points/formulas/steps from the image\n"
        "Answer -- direct answer to the student's question with explanation\n"
        "Tip -- 1 memory aid or common mistake to avoid\n\n"
        "TRANSCRIPTION IS CRITICAL: It will be saved as context for all follow-up questions. "
        "Transcribe EVERYTHING visible -- do not summarise or skip any text.\n\n"
        "RESPONSE CALIBRATION: Prioritise extracting image content accurately before answering."
        + _JSON_FOOTER
    )


def _practice_prompt(context: str, history: str, question: str, level: int) -> str:
    return (
        f"You are an educational content creator for Class {level} students.\n\n"
        f"CHAPTER CONTEXT:\n{context}\n\n"
        f"HISTORY:\n{history}\n\n"
        f"REQUEST: {question}\n\n"
        "Generate exactly 3 practice problems of increasing difficulty:\n"
        "Easy -- direct formula / concept application\n"
        "Medium -- slight twist or two-step problem\n"
        "Hard -- multi-step or real-world application\n\n"
        "For each problem:\n"
        "- State the problem clearly\n"
        "- Show the complete solution with all steps\n"
        "- Mark the final answer clearly\n\n"
        "ALL math in $$...$$ format.\n\n"
        "RESPONSE CALIBRATION: Give exactly 3 problems with full worked solutions."
        + _JSON_FOOTER
    )


# ---Public API---


def build_intent_classifier_prompt(
    question: str,
    has_image: bool,
    last_reply: str = "",
) -> str:
    """Returns the formatted intent-classifier prompt for the faster model tier."""
    return INTENT_CLASSIFIER_PROMPT.format(
        question=question[:300],
        has_image=str(has_image).lower(),
        last_reply=(last_reply or "")[:120].replace("\n", " "),
    )


def build_prompt(
    context: str,
    question: str,
    student_level: int = 5,
    history=None,
    language=None,
    mode=None,
    intent=None,
    complexity=None,
) -> str:
    """
    Build the main LLM prompt.

    For blackboard mode  ->  returns the BB lesson prompt + question + language instruction.
    For normal mode      ->  routes to the intent-specific prompt builder, then appends
                             the language instruction for localised output.

    Parameters
    ----------
    context       : merged chapter / page context string
    question      : student question text
    student_level : class / grade as integer (1-12)
    history       : list of "user: ..." / "assistant: ..." strings (most recent at end)
    language      : BCP-47 language tag  (e.g. "hi-IN", "en-US")
    mode          : "normal" | "blackboard"
    intent        : greet | image_explain | calculate | definition |
                    followup | explain | practice | other  (None -> "explain")
    complexity    : "low" | "medium" | "high"  (None -> "medium")
    """
    lang = language or "en-US"
    if mode == "blackboard":
        lang_tag_instr = (
            f'\n\nOUTPUT LANGUAGE: Set ALL step "lang" fields to "{lang}". '
            f'Write ALL "speech" fields in the language for tag "{lang}" '
            f'(hi-IN → Hindi, te-IN → Telugu, ta-IN → Tamil, bn-IN → Bengali, en-US → English, etc.).'
        )
        return blackboard_prompt + question + language_instructions.get(lang, "") + lang_tag_instr

    history_text = "\n".join(history or [])
    ctx = context or ""
    lvl = student_level or 5
    cmp = (complexity or "medium").lower()
    resolved = (intent or "explain").lower()

    if resolved == "greet":
        core = _greet_prompt(ctx, history_text, question, lvl)
    elif resolved == "image_explain":
        core = _image_explain_prompt(ctx, history_text, question, lvl)
    elif resolved == "calculate":
        core = _calculate_prompt(ctx, history_text, question, lvl)
    elif resolved == "definition":
        core = _definition_prompt(ctx, history_text, question, lvl)
    elif resolved == "followup":
        core = _followup_prompt(ctx, history_text, question, lvl)
    elif resolved == "practice":
        core = _practice_prompt(ctx, history_text, question, lvl)
    else:
        # "explain" | "other" | unknown -> full explain prompt
        core = _explain_prompt(ctx, history_text, question, lvl, cmp)

    return core + language_instructions.get(lang, "")


def build_bb_planner_prompt(
    question: str,
    context: str,
    history: list,
    level: int,
) -> str:
    """Returns the formatted BB planner prompt for the 'faster' model tier."""
    ctx_snippet = (context or "")[:500].strip()
    # Pass the last 3 turns of conversation (not just last reply) so the planner
    # can understand what was already taught and what the student already knows.
    def _fmt_h(h: str) -> str:
        if h.startswith("user:"):      return f"  Student: {h[5:120].strip()}"
        if h.startswith("assistant:"): return f"  Teacher: {h[10:120].strip()}"
        return f"  {h[:120].strip()}"
    recent = "\n".join(_fmt_h(h) for h in (history or [])[-6:])
    if not recent:
        recent = "  (No prior conversation — this is the student's first question)"
    return BB_PLANNER_PROMPT.format(
        question=question[:300],
        context_snippet=ctx_snippet,
        recent_history=recent,
        level=level or 5,
    )


def build_bb_main_prompt(
    context: str,
    question: str,
    level: int,
    history: list,
    plan: dict,
    lang: str,
) -> str:
    """
    Build the context-enriched blackboard lesson prompt using the planner's output.
    Injects chapter context, recent conversation, and lesson plan so the BB LLM
    generates focused content without having to figure out structure itself.
    """
    topic_type = plan.get("topic_type", "other")
    scope = plan.get("scope", "medium")
    key_concepts = plan.get("key_concepts") or []
    steps_count = max(4, min(6, int(plan.get("steps_count") or 5)))

    concepts_str = ", ".join(str(c) for c in key_concepts) if key_concepts else ""
    # Up to 2000 chars of chapter context so the LLM stays grounded in the textbook
    ctx_snippet = (context or "")[:2000].strip()
    # Last 3 full turns (6 entries: user+assistant pairs) — no character truncation
    history_entries = (history or [])[-6:]
    # Format nicely: strip "user:"/"assistant:" prefix labels for readability
    def _fmt(h: str) -> str:
        if h.startswith("user:"):
            return f"Student: {h[5:].strip()}"
        if h.startswith("assistant:"):
            return f"Teacher: {h[10:].strip()}"
        return h
    hist_snippet = "\n".join(_fmt(h) for h in history_entries)
    lang_instr = language_instructions.get(lang or "en-US", "")

    # ── Diagram hint: classify best diagram_type for this question ───────────
    # Suggests a PATH 1 diagram_type when confident; LLM may still choose PATH 2.
    try:
        from app.utils.diagram_router import classify_diagram_need
        import re as _re
        decision = classify_diagram_need(question, subject_hint=topic_type, topic_keywords=key_concepts)
        _diagram_hint = ""
        _q_lower = question.lower().strip()
        _is_definition_q = bool(_re.match(r"^(what is|what are|define|meaning of|what does)\b", _q_lower))
        # Only hint when confident AND the type is a PATH 1 semantic type (not anatomy/custom)
        _PATH2_TYPES = {"labeled_diagram", "anatomy", "cell"}
        if (decision.needed and decision.diagram_type
                and decision.confidence >= 0.40
                and decision.diagram_type not in _PATH2_TYPES):
            _diagram_hint = (
                f"\nDIAGRAM RECOMMENDATION: This topic likely suits a "
                f'"{decision.diagram_type}" diagram (confidence {decision.confidence:.0%}). '
                f"If this is a standard math/science type, use diagram_type=\"{decision.diagram_type}\". "
                f"If it needs a custom structure (anatomy, apparatus, body parts), use PATH 2 (svg_elements) instead.\n"
            )
    except Exception:
        _diagram_hint = ""

    parts = [blackboard_prompt, "\n\n---LESSON BRIEF (follow these instructions exactly)---\n"]
    parts.append(f"Student question: {question}\n")
    parts.append(f"Student level: Class {level}\n")
    parts.append(f"Topic type: {topic_type} | Scope: {scope}\n")
    if concepts_str:
        parts.append(f"Key concepts to cover (ALL of these): {concepts_str}\n")
    parts.append(f"Generate EXACTLY {steps_count} steps — no more, no less.\n")
    if _diagram_hint:
        parts.append(_diagram_hint)

    if ctx_snippet:
        parts.append(f"\nCHAPTER CONTEXT (use this as the primary source — ground the lesson here):\n{ctx_snippet}\n")

    if hist_snippet:
        parts.append(f"\nRECENT CONVERSATION (last 3 turns — do NOT re-teach what was already covered):\n{hist_snippet}\n")

    parts.append("\n---END LESSON BRIEF---\n")
    parts.append(lang_instr)
    resolved_lang = lang or "en-US"
    parts.append(
        f'\nOUTPUT LANGUAGE: Set ALL step "lang" fields to "{resolved_lang}". '
        f'Write ALL "speech" fields in the language for tag "{resolved_lang}" '
        f'(hi-IN → Hindi, te-IN → Telugu, ta-IN → Tamil, bn-IN → Bengali, en-US → English, etc.). '
        f'Board "text" field stays in English (formulas/keywords only).'
    )

    return "".join(parts)


# ── System Prompt Extraction (for Prompt Caching) ────────────────────────────

# These functions extract the stable system prompt and dynamic user content separately.
# This enables provider caching: system prompt is cached, only user content changes per request.
# Gemini: implicit cache (≥1024 tokens) + 5-min TTL
# Anthropic: explicit cache_control header

def get_normal_mode_system_prompt() -> str:
    """
    Extract the stable system prompt for normal-mode responses (explain/calculate/define).
    This is role definition + output format, and NEVER changes unless rules change.
    Designed to reach ~1000-1200 tokens for optimal caching.
    NOTE: student_level is intentionally NOT here — it belongs in the user content so this
    prompt stays identical across all class levels, enabling Gemini implicit cache hits.
    """
    system_parts = [
        # ── Core role instruction ──────────────────────────────────────────
        "You are an expert, engaging AI tutor for school students (Class 1-12) in an educational app.\n\n"
        
        # ── Shared output format rules ─────────────────────────────────────
        "MANDATORY OUTPUT FORMAT:\n"
        "Return ONLY valid JSON — no markdown code fences, no extra text:\n"
        '{"user_question":"<short restatement>","answer":"<full answer>","user_attachment_transcription":"<ALL text from image if any>","extra_details_or_summary":"<optional bonus info>"}\n\n'
        
        # ── Math formatting ────────────────────────────────────────────────
        "MATH FORMATTING (CRITICAL):\n"
        "• ALL math uses $$...$$ — inline: $$x=5$$ or $$a^2+b^2=c^2$$\n"
        "• NEVER plain text math like x=5 or a^2+b^2=c^2\n"
        "• NEVER use code blocks (```)\n"
        "• Inside $$...$$: use standard LaTeX syntax\n"
        "• Example: derivative is $$\\frac{dy}{dx}$$, not dy/dx\n\n"
        
        # ── Response calibration guidelines ────────────────────────────────
        "RESPONSE GUIDELINES:\n"
        "• Use context FIRST if answer is there\n"
        "• Combine context + knowledge if partially there\n"
        "• Answer from knowledge if not in context\n"
        "• Bold (**term**) key concepts on first use\n"
        "• Include concrete examples or worked solutions\n"
        "• Structure: hook (punchy) → explanation → real-world connection → memory tip\n\n"
        
        # ── Image handling ─────────────────────────────────────────────────
        "IMAGE TRANSCRIPTION (if image provided):\n"
        "• Read EVERY visible text word-for-word (headings, labels, numbers, formulas)\n"
        "• Describe all diagrams, figures, tables with labels\n"
        "• Put transcription in user_attachment_transcription field\n"
        "• This transcription becomes context for follow-up questions\n\n"
    ]

    # ── Gemini Flash Lite output accuracy ────────────────────────────────────
    system_parts.append(
        "OUTPUT STRICTNESS (Gemini Flash Lite):\n"
        "• Return EXACTLY one JSON object — no text before, no text after, no ```json wrapper\n"
        "• All four JSON fields must be present; use empty string '' for unused fields\n"
        "• answer field: **bold** key terms on first use, $$...$$ for ALL math (never plain text)\n\n"
    )
    return "".join(system_parts)


def get_blackboard_mode_system_prompt() -> str:
    """
    Returns the original blackboard_prompt (unchanged) as the cacheable system prefix,
    plus Gemini Flash Lite accuracy notes for better output consistency.
    Cached by Gemini implicit cache (≥1024 tokens, 5-min TTL).
    """
    # Accuracy additions for Gemini Flash Lite — appended AFTER the original prompt rules
    # so the original prompt is never modified.
    _ACCURACY_NOTES = (
        "\n\nGEMINI FLASH LITE OUTPUT ACCURACY (apply to EVERY frame without exception):\n"
        "• Output EXACTLY one JSON object — no text before, no text after, no ```json wrapper\n"
        "• quiz_correct_index: integer 0/1/2/3 ONLY for quiz_mcq; MUST be -1 for every other frame type\n"
        "• duration_ms: integer 2000-5000 — NEVER a string\n"
        "• tts_engine: ONLY 'android' | 'gemini' | 'google'\n"
        "• voice_role: ONLY 'teacher' | 'assistant' | 'quiz' | 'feedback'\n"
        "• speech field: plain readable text ONLY — no markdown, no $$...$$, no **bold** — TTS reads this aloud\n"
        "• text field (board): **bold** and formulas OK; max 2 lines; always English\n"
        "• svg_elements: x/x1/x2 must be 0-400; y/y1/y2/cy must be 0-300 — never exceed canvas\n"
        "• diagram frames: always set diagram_type to one of the supported types; data keys must match the schema\n"
        "• non-diagram frames: diagram_type must be empty string \"\", data must be {}\n"
        "• Step count: generate EXACTLY the steps_count from LESSON BRIEF — no more, no less\n"
        "• Last step: MUST end with a quiz frame immediately followed by a summary frame\n"
        "• lang field: MUST match the OUTPUT LANGUAGE tag exactly — NEVER default to en-US\n"
        "\n"
        "SPEECH STYLE — CRITICAL (this is a personal AI tutor, NOT a classroom lecture):\n"
        "• NEVER open any speech with: 'Hi everyone', 'Hi students', 'Hello class', 'Hey everyone',\n"
        "  'Today we are going to', 'Today we will', 'In this lesson', 'Let's begin our lesson',\n"
        "  'Welcome to', 'Let me explain', 'Let's learn about', 'Great, let's'\n"
        "• The student asked a SPECIFIC question — answer it DIRECTLY in the very first speech frame.\n"
        "• Start speech immediately with the concept, formula, or direct answer.\n"
        "  WRONG: 'Hi students! Today we are going to learn about photosynthesis.'\n"
        "  RIGHT: 'Photosynthesis converts sunlight into glucose using CO₂ and water.'\n"
        "  WRONG: 'Let's explore how to calculate the incircle radius!'\n"
        "  RIGHT: 'The incircle radius r equals the triangle area divided by its semi-perimeter.'\n"
        "• First speech frame must DIRECTLY answer or define the thing the student asked about.\n"
        "• Subsequent frames: continue the explanation naturally — no filler resets.\n"
        "\n"
        "DIAGRAM SELECTION — CRITICAL: match EXACTLY to what the student asked about.\n"
        "Read the student's question in LESSON BRIEF and pick the diagram that directly illustrates it.\n"
        "NEVER use a generic or approximate diagram type — always pick the most specific match.\n"
        "\n"
        "KEYWORD → DIAGRAM TYPE mapping (follow this strictly):\n"
        '• incircle / inscribed circle / circle inside triangle / inradius / in-radius\n'
        '  → diagram_type: "triangle", data: {"labels":["A","B","C"],"show_incircle":true}\n'
        '• circumscribed circle / circumcircle / circumradius / circle outside triangle\n'
        '  → diagram_type: "triangle", data: {"labels":["A","B","C"],"show_circumcircle":true}\n'
        '• triangle area / angle bisector / altitude / median / triangle perimeter\n'
        '  → diagram_type: "triangle", data: {"labels":["A","B","C"]}\n'
        '• area of circle / radius / diameter / circumference\n'
        '  → diagram_type: "circle_radius", data: {"radius":5,"show_area":true}\n'
        '• area of rectangle / perimeter of rectangle / area of square\n'
        '  → diagram_type: "rectangle_area", data: {"width":8,"height":5}\n'
        '• angles / complementary angles / supplementary angles / types of angles\n'
        '  → diagram_type: "geometry_angles"\n'
        '• Bohr model / electron shells / atomic structure / element name + electrons / protons\n'
        '  → diagram_type: "atom", data: {"nucleus_label":"...", "orbits":[...]}\n'
        '• solar system / planetary orbit / planet revolves\n'
        '  → diagram_type: "solar_system"\n'
        '• sound wave / light wave / wavelength / frequency / EM wave / transverse wave\n'
        '  → diagram_type: "waveform_signal"\n'
        '• y=f(x) / parabola / graph / plotting function / quadratic graph\n'
        '  → diagram_type: "graph_function"\n'
        '• number line / integers / fractions on number line\n'
        '  → diagram_type: "number_line"\n'
        '• fraction / numerator denominator / fraction bar\n'
        '  → diagram_type: "fraction_bar"\n'
        '• cell structure / anatomy / labeled parts / plant cell / animal cell\n'
        '  → diagram_type: "labeled_diagram", data: {"title":"...","labels":[...]}\n'
        '• NAMED multi-step process (photosynthesis / water cycle / nitrogen cycle / digestion)\n'
        '  → diagram_type: "cycle" or "flow"\n'
        '  ⚠ ONLY when the question is specifically about how a process works — NOT for definitions.\n'
        '• compare two things (mitosis vs meiosis / plant vs animal cell)\n'
        '  → diagram_type: "comparison"\n'
        "\n"
        "DIAGRAM ACCURACY RULES:\n"
        "• If the question mentions incircle, inradius, or inscribed circle → triangle with show_incircle=true ALWAYS\n"
        "• NEVER show rectangle_area for a question about circles or triangles\n"
        "• NEVER show circle_radius for a question about triangles\n"
        "• NEVER show atom for a non-chemistry/non-physics question\n"
        "• NEVER use flow or cycle for definitions, formulas, or 'what is X' questions\n"
        "• flow and cycle are ONLY for named biological/chemical/physical processes (photosynthesis, water cycle, digestion, etc.)\n"
        "• Most lessons need 0 or 1 diagram frame — do NOT add diagrams just to add variety\n"
        "• If unsure which diagram type fits, use labeled_diagram with relevant labels\n"
        "• Only use diagram frames where a visual truly helps — formula-only steps use concept frame\n"
    )
    return blackboard_prompt + _ACCURACY_NOTES


def build_normal_mode_user_content(
    context: str,
    history: str,
    question: str,
    intent: str = "explain",
    complexity: str = "medium",
    student_level: int = 5,
) -> str:
    """
    Dynamic user content for normal mode — context + history + question + intent guidance.
    Intent-specific instructions live here (not in system prompt) so the system prompt
    never changes between requests and is always reused from cache.
    student_level is placed here (not in system prompt) to ensure cache identity.
    """
    _EXPLAIN_GUIDE = {
        "low":    "Short answer: key fact in 2-3 sentences + 1 brief example. No long sections.",
        "medium": "Structured: key concept + 1 worked example + 1 real-world connection.",
        "high":   "Full structure: hook → mechanism → worked example → real-world application → memory tip.",
    }
    _INTENT_GUIDE = {
        "greet":        "Respond warmly in 1-2 sentences and invite a question.",
        "definition":   "**Definition** (1-2 sentences) + **Example** (1 concrete case) + **Key fact** (if relevant).",
        "calculate":    "Steps: 1. What to find  2. Formula  3. Step-by-step working  4. Final answer (clear/boxed)  5. Quick check. ALL math in $$...$$ — never skip steps.",
        "followup":     "Build DIRECTLY on the previous explanation — do NOT restart from basics. Answer only what they ask now.",
        "image_explain": "1. Transcribe ALL visible text word-for-word (headings, labels, formulas, captions). 2. Describe all diagrams. 3. Answer using image content + knowledge.",
        "practice":     "Generate EXACTLY 3 problems (Easy / Medium / Hard) each with complete step-by-step solutions.",
    }
    guide = _INTENT_GUIDE.get(intent) or _EXPLAIN_GUIDE.get(complexity, _EXPLAIN_GUIDE["medium"])

    return (
        f"Student class level: Class {student_level}\n\n"
        f"CONTEXT (chapter notes):\n{context or '(No context provided)'}\n\n"
        f"CONVERSATION HISTORY:\n{history or '(First message)'}\n\n"
        f"STUDENT QUESTION: {question}\n\n"
        f"ANSWER GUIDANCE: {guide}\n"
    )


def build_blackboard_mode_user_content(
    context: str,
    question: str,
    level: int,
    history: list = None,
    plan: dict = None,
    lang: str = "en-US",
    image_data: dict = None,
) -> str:
    """
    Dynamic user content for BB mode — lesson brief only (NO blackboard_prompt prefix).
    Pairs with get_blackboard_mode_system_prompt() to enable prompt caching.
    Mirrors the dynamic portion of build_bb_main_prompt() exactly.
    """
    topic_type = (plan or {}).get("topic_type", "other")
    scope = (plan or {}).get("scope", "medium")
    key_concepts = (plan or {}).get("key_concepts") or []
    steps_count = max(4, min(6, int((plan or {}).get("steps_count") or 5)))
    concepts_str = ", ".join(str(c) for c in key_concepts) if key_concepts else ""
    ctx_snippet = (context or "")[:2000].strip()
    history_entries = (history or [])[-6:]

    def _fmt(h: str) -> str:
        if h.startswith("user:"):
            return f"Student: {h[5:].strip()}"
        if h.startswith("assistant:"):
            return f"Teacher: {h[10:].strip()}"
        return h

    hist_snippet = "\n".join(_fmt(h) for h in history_entries)
    lang_instr = language_instructions.get(lang or "en-US", "")
    resolved_lang = lang or "en-US"

    # ── Diagram hint: classify best diagram_type for this question ───────────
    _diagram_hint = ""
    # Keyword-based forced diagram rules (override everything)
    _q_lower = (question or "").lower()
    _k_lower = " ".join(str(c) for c in key_concepts).lower()
    _combined = _q_lower + " " + _k_lower
    _forced_type = ""
    if any(w in _combined for w in ("incircle", "inradius", "in-radius", "inscribed circle", "in circle")):
        _forced_type = "triangle"
        _forced_data = '{"labels":["A","B","C"],"show_incircle":true}'
        _forced_reason = "incircle/inradius keyword detected → triangle with show_incircle"
    elif any(w in _combined for w in ("circumcircle", "circumradius", "circumscribed")):
        _forced_type = "triangle"
        _forced_data = '{"labels":["A","B","C"],"show_circumcircle":true}'
        _forced_reason = "circumcircle keyword detected → triangle with show_circumcircle"
    elif any(w in _combined for w in ("bohr", "electron shell", "atomic structure", "electrons orbit")):
        _forced_type = "atom"
        _forced_data = ''
        _forced_reason = "atomic structure keyword → atom"
    elif any(w in _combined for w in ("solar system", "planet orbit", "planetary")):
        _forced_type = "solar_system"
        _forced_data = ''
        _forced_reason = "solar system keyword → solar_system"
    elif any(w in _combined for w in ("wavelength", "frequency", "sound wave", "light wave", "waveform", "transverse wave")):
        _forced_type = "waveform_signal"
        _forced_data = ''
        _forced_reason = "wave keyword → waveform_signal"

    if _forced_type:
        if _forced_data:
            _diagram_hint = (
                f"\n⚠ REQUIRED DIAGRAM (keyword match — do NOT change this): "
                f'diagram_type="{_forced_type}" with data={_forced_data}\n'
                f"Reason: {_forced_reason}\n"
            )
        else:
            _diagram_hint = (
                f"\n⚠ REQUIRED DIAGRAM (keyword match — do NOT change this): "
                f'diagram_type="{_forced_type}"\n'
                f"Reason: {_forced_reason}\n"
            )
    else:
        try:
            from app.utils.diagram_router import classify_diagram_need
            import re as _re2
            decision = classify_diagram_need(question, subject_hint=topic_type, topic_keywords=key_concepts)
            _is_def_q = bool(_re2.match(r"^(what is|what are|define|meaning of|what does)\b", question.lower().strip()))
            if decision.needed and decision.diagram_type and decision.confidence >= 0.65 and not _is_def_q:
                _diagram_hint = (
                    f"\nDIAGRAM RECOMMENDATION: This topic likely needs a "
                    f'"{decision.diagram_type}" diagram (confidence {decision.confidence:.0%}). '
                    f"Use diagram_type=\"{decision.diagram_type}\" for your diagram frame(s).\n"
                )
        except Exception:
            pass

    # Question focus from planner — tells the LLM exactly what was asked
    question_focus = (plan or {}).get("question_focus", "").strip()
    question_type  = (plan or {}).get("question_type", "").strip()
    prior_knowledge = (plan or {}).get("prior_knowledge", "").strip()

    parts = ["\n---LESSON BRIEF (follow these instructions exactly)---\n"]
    parts.append(f"Student's EXACT question: {question}\n")
    if question_focus:
        parts.append(f"What the student specifically wants: {question_focus}\n")
    if question_type:
        parts.append(f"Question type: {question_type}\n")
    parts.append(f"Student level: Class {level}\n")
    parts.append(f"Topic type: {topic_type} | Scope: {scope}\n")
    if prior_knowledge:
        parts.append(f"What student already knows (DO NOT repeat this): {prior_knowledge}\n")
    if concepts_str:
        parts.append(f"Key concepts to cover (ALL of these): {concepts_str}\n")
    parts.append(f"Generate EXACTLY {steps_count} steps — no more, no less.\n")
    parts.append(
        "STYLE: This is a personal AI tutor response — NOT a classroom lesson.\n"
        "  • Answer the student's specific question directly.\n"
        "  • First speech frame MUST directly state the answer/formula/definition.\n"
        "  • NEVER start speech with greetings or 'Today we will learn'.\n"
    )
    if _diagram_hint:
        parts.append(_diagram_hint)

    if ctx_snippet:
        parts.append(
            f"\nCHAPTER CONTEXT (use this as the primary source — ground the lesson here):\n{ctx_snippet}\n"
        )
    if hist_snippet:
        parts.append(
            f"\nRECENT CONVERSATION (last 3 turns — do NOT re-teach what was already covered):\n{hist_snippet}\n"
        )

    # ── Image diagram replication ─────────────────────────────────────────────
    # If the student attached an image containing diagrams, instruct the LLM to
    # recreate those visuals as diagram frames using the server-side renderer.
    _img_diagrams = (image_data or {}).get("diagrams") or []
    _img_transcript = ((image_data or {}).get("transcript") or "").strip()
    if _img_diagrams or _img_transcript:
        img_parts = ["\nATTACHED IMAGE CONTEXT:"]
        if _img_transcript:
            img_parts.append(f"  Transcript: {_img_transcript[:500]}")
        if _img_diagrams:
            img_parts.append("  Diagrams found in the image:")
            for d in _img_diagrams[:4]:
                hdg = (d.get("heading") or "").strip()
                dep = (d.get("depiction") or "").strip()
                if hdg or dep:
                    img_parts.append(f"    • {hdg}: {dep}".strip(": "))
            img_parts.append(
                "\n⚠ DIAGRAM REPLICATION RULE: For each diagram listed above, "
                "include a diagram frame that RECREATES it. "
                "Use PATH 1 (diagram_type) for: atom, cell, wave, cycle, flow, comparison, triangle, graph, etc. "
                "Use PATH 2 (svg_elements with shapes) for anatomy, body structures, lab apparatus, "
                "or any diagram not covered by a PATH 1 type."
            )
        parts.append("\n".join(img_parts) + "\n")

    parts.append("\n---END LESSON BRIEF---\n")
    parts.append(lang_instr)
    parts.append(
        f'\nOUTPUT LANGUAGE: Set ALL step "lang" fields to "{resolved_lang}". '
        f'Write ALL "speech" fields in the language for tag "{resolved_lang}" '
        f'(hi-IN → Hindi, te-IN → Telugu, ta-IN → Tamil, bn-IN → Bengali, en-US → English, etc.). '
        f'Board "text" field stays in English (formulas/keywords only).'
    )

    return "".join(parts)