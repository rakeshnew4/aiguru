from typing import List, Optional

# ── Topic-Type Teaching Hints ─────────────────────────────────────────────────
# Injected into the DYNAMIC user content (not system prompt) so Gemini implicit
# cache is never invalidated. Each hint is ≤6 lines of targeted guidance.

_TOPIC_TYPE_TEACHING_HINTS: dict[str, str] = {
    "math_formula": (
        "SUBJECT GUIDANCE (Math — Formula/Algebra):\n"
        "• Lead with the formula in a concept frame. Show what each variable means.\n"
        "• Dedicate 2 concept frames to worked substitution (step-by-step numbers).\n"
        "• Prefer diagram_type: graph_function, fraction_bar, number_line, or pythagoras.\n"
        "• Quiz: quiz_typed asking student to apply the formula to a new set of values."
    ),
    "math_geometry": (
        "SUBJECT GUIDANCE (Math — Geometry):\n"
        "• Use diagram_type triangle, polygon, circle_geometry, angle, or coordinate_plane.\n"
        "• Show labelled measurements in the diagram data (a_val, b_val, show_angles, etc.).\n"
        "• Concept frames: state property → prove/derive → example with numbers.\n"
        "• Quiz: quiz_mcq with numerically distinct distractor values."
    ),
    "math_graph": (
        "SUBJECT GUIDANCE (Math — Graphs/Functions):\n"
        "• Always include a graph_function or coordinate_plane diagram frame.\n"
        "• Concept frames: equation form → what the graph looks like → key points (intercepts, vertex).\n"
        "• Include a bar_chart or line_graph if comparing data sets.\n"
        "• Quiz: quiz_typed asking student to identify the graph type or a key coordinate."
    ),
    "science_biology": (
        "SUBJECT GUIDANCE (Science — Biology):\n"
        "• Use cycle for life cycles/processes (mitosis, water cycle, photosynthesis).\n"
        "• Use labeled_diagram for cell, organ, or body-system structures.\n"
        "• Use PATH 2 (diagram_type=custom) for anatomy cross-sections (heart, leaf, kidney).\n"
        "• Concept frames: function → structure → real-body example.\n"
        "• Quiz: quiz_order (correct biological sequence) or quiz_mcq."
    ),
    "science_chemistry": (
        "SUBJECT GUIDANCE (Science — Chemistry):\n"
        "• Use diagram_type atom for atomic structure / electron shells.\n"
        "• Use comparison for reactants vs products or acid vs base.\n"
        "• Use PATH 2 (diagram_type=custom) for lab apparatus (test tube, flask, burner).\n"
        "• Concept frames: particles/structure → reaction rule → balanced equation.\n"
        "• Quiz: quiz_typed with quiz_keywords = key chemical terms."
    ),
    "science_physics": (
        "SUBJECT GUIDANCE (Science — Physics):\n"
        "• Use waveform_signal for waves/sound/light; coordinate_plane for motion graphs.\n"
        "• Use PATH 2 (diagram_type=custom) for force diagrams, ray optics, circuit layouts.\n"
        "• Concept frames: state law/formula → physical meaning → real-world application.\n"
        "• Include units in every formula (e.g. m/s², Newton, Joule).\n"
        "• Quiz: quiz_mcq with plausible numerical distractors."
    ),
    "definition": (
        "SUBJECT GUIDANCE (Definition/Vocabulary):\n"
        "• Frame 1: crisp 1-sentence definition in bold. Frame 2: concrete real-world analogy.\n"
        "• Keep image_show_confidencescore ≤ 0.50 — use diagram only if concept has visible structure.\n"
        "• Include a memory frame (mnemonic, acronym, or 'think of it as...').\n"
        "• Quiz: quiz_typed asking student to define in their own words."
    ),
    "comparison": (
        "SUBJECT GUIDANCE (Comparison/Contrast):\n"
        "• Use comparison diagram_type as the centrepiece (left_points vs right_points).\n"
        "• Structure: what is A → what is B → key differences table → when to use which.\n"
        "• Use **bold** for terms being compared in text fields.\n"
        "• Quiz: quiz_mcq testing which scenario belongs to which concept."
    ),
    "history_civics": (
        "SUBJECT GUIDANCE (History / Civics / Social Studies):\n"
        "• Use labeled_diagram for timelines (center = era, parts = key events in order).\n"
        "• NEVER use cycle, waveform, or heavy SVG — history has no process diagrams.\n"
        "• Keep image_show_confidencescore ≤ 0.40. Prioritise concept + memory frames.\n"
        "• Memory frame: key date + person + outcome in one catchy sentence.\n"
        "• Quiz: quiz_mcq with plausible historical distractors."
    ),
    "programming": (
        "SUBJECT GUIDANCE (Computer Science / Programming):\n"
        "• Use flow diagram for algorithms, control flow, or function call sequences.\n"
        "• Concept frames: syntax rule → example code snippet (use ``` in text) → output.\n"
        "• Use comparison for two data structures or two approaches (e.g. array vs list).\n"
        "• Quiz: quiz_typed asking student to write or complete a small code expression."
    ),
    "geography_environment": (
        "SUBJECT GUIDANCE (Geography / Environment):\n"
        "• Use cycle for water cycle, carbon cycle, rock cycle, nutrient cycles.\n"
        "• Use labeled_diagram for map features (parts of a river, climate zones).\n"
        "• Use PATH 2 (diagram_type=custom) for cross-section diagrams (mountain, valley).\n"
        "• Concept frames: location/name → characteristic → human impact or real example.\n"
        "• Quiz: quiz_mcq with plausible geographic distractors."
    ),
    "other": (
        "SUBJECT GUIDANCE (General):\n"
        "• Mix concept + memory + one diagram frame appropriate to the topic.\n"
        "• Choose PATH 1 diagram if a standard type fits; PATH 2 (diagram_type=custom) for custom structures.\n"
        "• End with quiz_mcq and summary."
    ),
}

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
    "SUGGEST_BLACKBOARD: Set to true ONLY when the topic clearly benefits from a "
    "step-by-step visual diagram lesson (processes, multi-step derivations, structures). "
    "Set to false for greetings, simple definitions, single-fact questions, calculations already shown.\n\n"
    "OUTPUT -- return ONLY valid JSON (no code fences, no extra text):\n"
    '{"user_question":"<short restatement of question>",'
    '"answer":"<your full answer with all markdown/LaTeX formatting>",'
    '"user_attachment_transcription":"<ALL visible text + diagram descriptions if image/PDF attached; else empty string>",'
    '"extra_details_or_summary":"<bonus formulas/facts/summary table; else empty string>",'
    '"suggest_blackboard":<true|false>}'
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
    '{{"topic_type":"<math_formula|math_geometry|math_graph|science_biology|science_chemistry|science_physics|definition|comparison|history_civics|programming|geography_environment|other>",'
    '"scope":"<simple|medium|complex>",'
    '"key_concepts":["term1","term2"],'
    '"steps_count":<4|5>,'
    '"image_search_terms":["wikimedia phrase 1","wikimedia phrase 2"],'
    '"question_focus":"one sentence: what EXACTLY the student wants to know or do",'
    '"question_type":"<how_to|definition|calculation|conceptual|comparison|example|problem_solving>",'
    '"prior_knowledge":"what student already knows from the conversation (empty string if new topic)",'
    '"hook_question":"1 short real-world curiosity question to open the lesson (e.g. Ever noticed why ice floats?)",'
    '"continuation_topic":"the single most natural next topic to learn after this one (3-6 words, e.g. Condensation and Cloud Formation)"}}\n\n'
    "Rules:\n"
    "- simple (4 steps): single self-contained concept\n"
    "- medium (5 steps): standard topic with 1-2 sub-concepts, or multi-concept/continuation\n"
    "- topic_type: pick the MOST SPECIFIC type:\n"
    "    math_formula → algebra, equations, arithmetic operations, calculus formulas\n"
    "    math_geometry → shapes, angles, triangles, circles, area, perimeter, volume\n"
    "    math_graph → coordinate plane, functions, plotting, graphs, scatter plots\n"
    "    science_biology → cell, body systems, photosynthesis, genetics, ecology, evolution\n"
    "    science_chemistry → atoms, periodic table, reactions, bonding, solutions, acids/bases\n"
    "    science_physics → motion, forces, waves, electricity, optics, thermodynamics\n"
    "    definition → what is X, define X, meaning of X (any subject)\n"
    "    comparison → X vs Y, difference between A and B (any subject)\n"
    "    history_civics → historical events, timelines, governance, constitution, civics\n"
    "    programming → code, algorithms, data structures, debugging, logic\n"
    "    geography_environment → maps, landforms, climate, ecosystems, rivers, countries\n"
    "    other → language arts, economics, general topics not covered above\n"
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
    "You are a visual AI tutor. Return ONLY valid JSON — no text outside JSON.\n\n"
    "OUTPUT FORMAT (STRICT):\n"
    "Return ONLY valid JSON.\n"
    "Top-level MUST be an OBJECT with key \"steps\".\n"
    "NEVER return a list at top level.\n\n"

    '{'
    '"steps":[{'
    '"title":"2-5 words",'
    '"lang":"en-US",'
    '"image_description":"short wikimedia phrase",'
    '"image_show_confidencescore":0.7,'
    '"frames":[{'
    '"frame_type":"concept",'
    '"text":"max 2 lines",'
    '"speech":"1-2 sentences",'
    '"tts_engine":"gemini",'
    '"voice_role":"teacher",'
    '"duration_ms":2500'
    '"diagram_type":"",'
    '"diagram_data":{},'
    '"visual_description":""'
    '}]}]}'
    '\n\n'
    "SPARSE: Omit empty/default fields. Required on every frame: frame_type, text, speech, tts_engine, voice_role, duration_ms.\n"
    "Step fields (title, lang, image_description, image_show_confidencescore) are on the STEP — NEVER inside frames.\n\n"
    "FRAME TYPES: concept | memory | diagram | quiz_mcq | quiz_typed | quiz_voice | quiz_order | summary\n\n"
    "DIAGRAM — set diagram_type + data (no svg_elements):\n"
    "PATH 1 (semantic engine): atom, solar_system, waveform_signal, triangle, polygon,\n"
    "  circle_geometry, angle, pythagoras, coordinate_plane, graph_function, line_graph,\n"
    "  bar_chart, pie_chart, number_line, fraction_bar, venn_diagram, cycle, comparison, labeled_diagram\n"
    'PATH 2 (custom): heart, neuron, circuit, force diagram, apparatus, any organic shape\n'
    '  → diagram_type="custom", data={"intent":"1 sentence: what to show"}, visual_description="same"\n\n'

    "PSYCHOLOGICAL FLOW (follow this arc every lesson):\n"
    "1. HOOK — first frame of Step 1 MUST be a real-world curiosity question or surprising fact.\n"
    "   Good: 'Ever wonder why the sky is blue?' Bad: 'Today we will learn about light.'\n"
    "   Keep it 1 line. It grabs attention and activates prior knowledge.\n"
    "2. EXPLAIN + VISUALIZE — explain the concept simply, then show a diagram when it adds clarity.\n"
    "3. PREDICT + REVEAL — once per lesson, ask 'What do you think happens when...?' (concept frame),\n"
    "   then immediately follow with the reveal in the next frame. NO user input needed — flow continues.\n"
    "4. APPLY — each step must have at least one frame anchoring the concept to real life.\n"
    "   Good: 'That is why puddles disappear after rain.' Keep it to 1 line.\n"
    "5. CURIOSITY BRIDGE — the SECOND-TO-LAST frame of each step must end with a forward-looking question\n"
    "   that pulls the student into the next concept. 'But where does this vapor go next? 🌥️'\n"
    "   This replaces quiz as the primary engagement mechanism in Steps 1–(N-1).\n"
    "6. LAST STEP — ends with quiz_mcq + summary. Summary frame should answer: 'You now understand X.'\n"
    "   and hint at what comes next in the subject.\n\n"

    "RULES:\n"
    "- 4-5 steps, 2-4 frames/step. Last step: quiz_mcq then summary (final).\n"
    "- Step 2 MUST have a diagram or populated image_description.\n"
    "- speech: ≤2 sentences ≤50 words. Conversational, calm. No openers ('Today we learn...', 'Hi class!'). TTS-safe: say 'squared' not '^2'.\n"
    "- speech language matches lang field (hi-IN→Hindi, te-IN→Telugu, en-US→English).\n"
    "- text: **bold** key terms, $$math$$, max 2 lines, always English.\n"
    "- lang: same BCP-47 tag on all steps from OUTPUT LANGUAGE instruction.\n"
    "- diagram only when visual adds clarity.\n"
    "- quiz_mcq: 4 options, only 1 correct (quiz_correct_index 0-3), use misconceptions as distractors.\n"
    "- quiz_typed/voice: quiz_model_answer (1 sentence), quiz_keywords (3-6 essential terms).\n"
    "- image_description: specific Wikimedia phrase. NEVER null or omit.\n"
    "  image_show_confidencescore: 0.85-0.95 concrete visuals, 0.60-0.80 named concepts, 0.10-0.30 abstract, 0.0 quiz/memory/summary.\n"
    "TTS every frame: first frame→android/teacher; concept/memory/diagram→gemini/teacher; summary→google/assistant; quiz→android/quiz.\n"
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
    hook_question = (plan.get("hook_question") or "").strip()

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
    if hook_question:
        parts.append(f"HOOK (use this exact question as the very first frame text of Step 1): \"{hook_question}\"\n")
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
        "You are an expert, engaging AI tutor for school students (Class 1-12) in an educational app.\n"
        "You teach like a smart, patient friend — direct, clear, and genuinely interesting.\n\n"

        # ── Shared output format rules ─────────────────────────────────────
        "MANDATORY OUTPUT FORMAT:\n"
        "Return ONLY valid JSON — no markdown code fences, no extra text:\n"
        '{"user_question":"<short restatement>","answer":"<full answer>","user_attachment_transcription":"<ALL text from image if any>","extra_details_or_summary":"<optional bonus info>","suggest_blackboard":<true|false>}\n\n'

        # ── Math formatting ────────────────────────────────────────────────
        "MATH FORMATTING (CRITICAL):\n"
        "• ALL math uses $$...$$ — inline: $$x=5$$ or $$a^2+b^2=c^2$$\n"
        "• NEVER plain text math. NEVER code blocks for math.\n"
        "• LaTeX inside $$...$$: $$\\frac{dy}{dx}$$, $$\\sqrt{x}$$, $$\\pi r^2$$\n\n"

        # ── Response quality guidelines ────────────────────────────────────
        "ANSWER QUALITY:\n"
        "• Use context FIRST, knowledge second — never contradict the textbook context.\n"
        "• Bold (**term**) every key concept on first use.\n"
        "• Include ONE concrete worked example or real-world application.\n"
        "• For complex topics: hook (surprising / punchy opening) → explanation → example → tip.\n"
        "• For calculations: show every step clearly. NEVER skip steps. Box the final answer.\n"
        "• For definitions: precise 1-2 sentence definition + 1 concrete example.\n"
        "• For follow-ups: build directly on the previous explanation — never restart from basics.\n\n"

        # ── suggest_blackboard ─────────────────────────────────────────────
        "SUGGEST_BLACKBOARD RULE:\n"
        "• Set suggest_blackboard=true when the topic genuinely benefits from a step-by-step visual lesson:\n"
        "  multi-step processes (photosynthesis, digestion), complex derivations, structural diagrams (cell, atom)\n"
        "• Set suggest_blackboard=false for: greetings, simple definitions, single-step calculations,\n"
        "  conversational follow-ups, practice problem requests.\n\n"

        # ── Image handling ─────────────────────────────────────────────────
        "IMAGE TRANSCRIPTION (when image attached):\n"
        "• Transcribe EVERY visible word, number, formula, heading, and label — word-for-word.\n"
        "• Describe all diagrams, figures, arrows with their labels.\n"
        "• Put ALL of this in user_attachment_transcription (it becomes context for future questions).\n\n"
    ]

    system_parts.append(
        "OUTPUT STRICTNESS:\n"
        "• Return EXACTLY one JSON object — no text before, no text after, no ```json wrapper.\n"
        "• All five JSON fields must be present; use '' for unused string fields, false for suggest_blackboard.\n"
        "• answer field: **bold** key terms, $$...$$ for ALL math — never plain text math.\n\n"
    )
    return "".join(system_parts)


def get_blackboard_mode_system_prompt() -> str:
    """
    Returns the compact blackboard_prompt as the cacheable system prefix.
    Accuracy notes and diagram selection rules are folded into the prompt itself.
    Cached by Gemini implicit cache (≥1024 tokens, 5-min TTL).
    """
    return blackboard_prompt


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

    # ── Topic-type specific teaching hint ────────────────────────────────────
    _topic_hint = _TOPIC_TYPE_TEACHING_HINTS.get(topic_type) or _TOPIC_TYPE_TEACHING_HINTS["other"]

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
                "Use PATH 1 (diagram_type) for: atom, cell, wave, cycle, comparison, triangle, graph, etc. "
                'Use PATH 2 (diagram_type="custom", data={"intent":"..."}) for anatomy, body structures, '
                "lab apparatus, or any diagram not covered by a PATH 1 type."
            )
        parts.append("\n".join(img_parts) + "\n")

    parts.append(f"\n{_topic_hint}\n")
    parts.append("\n---END LESSON BRIEF---\n")
    parts.append(lang_instr)
    parts.append(
        f'\nOUTPUT LANGUAGE: Set ALL step "lang" fields to "{resolved_lang}". '
        f'Write ALL "speech" fields in the language for tag "{resolved_lang}" '
        f'(hi-IN → Hindi, te-IN → Telugu, ta-IN → Tamil, bn-IN → Bengali, en-US → English, etc.). '
        f'Board "text" field stays in English (formulas/keywords only).'
    )

    return "".join(parts)