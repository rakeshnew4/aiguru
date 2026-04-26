"""
Endpoint and service flow summary for AI agents.

Update this file when changing:
- server/app/api/chat.py
- server/app/api/bb.py
- server/app/api/quiz.py
- server/app/api/users.py
- server/app/api/tts.py
- server/app/api/payments.py
- server/app/api/library.py
- server/app/api/image_search_titles.py
- Android caller classes that talk to these endpoints
"""

ANDROID_TO_BACKEND_MAP = {
    "app/src/main/java/com/aiguruapp/student/chat/ServerProxyClient.kt": {
        "calls": ["/chat-stream", "/users/register"],
        "notes": "Streams SSE tokens/status frames and retries once on HTTP 401.",
    },
    "app/src/main/java/com/aiguruapp/student/quiz/QuizApiClient.kt": {
        "calls": ["/quiz/generate", "/quiz/evaluate-answer", "/quiz/submit"],
        "notes": "Uses blocking OkHttp and Firebase Bearer auth.",
    },
    "app/src/main/java/com/aiguruapp/student/payments/PaymentApiClient.kt": {
        "calls": ["/payments/razorpay/create-order", "/payments/razorpay/verify"],
        "notes": "Handles plan purchase flow after Firebase auth.",
    },
}


API_SUMMARY = {
    "/chat-stream": {
        "file": "server/app/api/chat.py",
        "mode": "SSE streaming endpoint",
        "major_behaviors": [
            "Checks chat/blackboard quota before opening the stream",
            "Fetches page context and normalizes image input",
            "Routes by req.mode: normal vs blackboard",
            "In blackboard mode, builds a plan and can prefetch Wikimedia candidates while the main LLM runs",
            "Calls generate_response() and emits text/status/done SSE frames",
            "In blackboard mode, post-processes the LLM JSON through image_search_titles.get_titles() before streaming the final text payload",
        ],
    },
    "/bb/grade": {
        "file": "server/app/api/bb.py",
        "mode": "single JSON response",
        "major_behaviors": [
            "Grades open-ended Blackboard answers",
            "Tries faster-tier LLM first",
            "Falls back to keyword-based grading if LLM parsing fails",
        ],
    },
    "/quiz/*": {
        "file": "server/app/api/quiz.py",
        "major_behaviors": [
            "Generate quiz via quiz_service",
            "Persist quizzes and attempts in Firestore",
            "Use rule-based grading for MCQ/fill blank",
            "Use LLM evaluation for short answers",
            "Update gamification and chapter progress",
        ],
    },
    "/users/register": {
        "file": "server/app/api/users.py",
        "major_behaviors": [
            "Creates users_table doc if missing",
            "Copies sample Blackboard sessions for brand-new users",
            "Creates LiteLLM API key if proxy is enabled",
        ],
    },
    "/users/quota": {
        "file": "server/app/api/users.py",
        "mode": "GET, authenticated",
        "major_behaviors": [
            "Returns live daily quota counters and plan limits for the caller",
            "Handles UTC day rollover — treats stale counter as 0",
            "Respects plan expiry — reverts to free limits when plan is expired",
            "Android calls this after each AI response to refresh quota display",
            "Server is sole writer of quota counters; Android only reads via this endpoint",
        ],
    },
    "/api/tts/synthesize": {
        "file": "server/app/api/tts.py",
        "major_behaviors": [
            "Cleans math markup",
            "Tries Google Cloud TTS first",
            "Falls back to ElevenLabs then OpenAI TTS",
            "Returns raw MP3 bytes",
        ],
    },
    "/payments/razorpay/*": {
        "file": "server/app/api/payments.py",
        "major_behaviors": [
            "Creates Razorpay orders",
            "Verifies payment signatures",
            "Writes payment audit documents to Firestore",
            "Activates user plans through user_service.activate_plan()",
            "Processes webhook reconciliation if verify step is missed",
        ],
    },
    "/library/*": {
        "file": "server/app/api/library.py",
        "major_behaviors": [
            "Lists subjects and chapters",
            "Stores selected chapters",
            "Returns chapter progress",
        ],
    },
}


CHAT_PIPELINE_PSEUDOCODE = """
def chat_stream(req, auth):
    uid = req.user_id or auth.uid
    request_type = "blackboard" if req.mode == "blackboard" else "chat"
    allowed, reason = check_and_record_quota(uid, request_type)
    if not allowed:
        raise HTTP 429

    log_activity_async("chat", ...)
    context = get_context(req.page_id)
    images = normalize_images(req)

    if req.mode == "blackboard":
        plan = bb_plan(req.question, context, req.history, req.student_level)
        wiki_task = prefetch_wikimedia(plan.image_search_terms)
        system_prompt = get_blackboard_mode_system_prompt()
        user_content = build_blackboard_mode_user_content(...)
        model_tier = plan_to_model_tier(req.user_plan)
    else:
        intent, complexity = classify_or_derive_intent(...)
        system_prompt = get_normal_mode_system_prompt()
        user_content = build_normal_mode_user_content(...)
        model_tier = choose_model_tier_from_intent_and_plan(...)

    result = generate_response(user_content, images, tier=model_tier, system_prompt=system_prompt)
    if result.provider == "error":
        stream_error_frame(...)
        return

    if req.mode == "blackboard":
        prefetched = await wiki_task
        result.text = await get_titles(result.text, extra_candidates=prefetched)

    stream_status_and_text_frames(...)
    stream_done_frame(tokens=result.tokens, page_transcript=extract_page_transcript(...))
""".strip()


BLACKBOARD_POST_PROCESSOR_PSEUDOCODE = """
async def get_titles(bb_json, extra_candidates=None):
    data = parse_blackboard_json(bb_json)
    launch diagram-data enrichment tasks (quiz-answer validation removed — main LLM is reliable)
    launch per-step Wikimedia searches for eligible image descriptions
    await everything together
    write diagram enrichment results back into step frames

    for each diagram frame:
        d_type = frame.diagram_type
        d_data = frame.data
        if diagram_type and svg_elements are both missing:
            d_type = classify_diagram_need(step_title + frame_speech)
        html = try_in_order(
            atom_html,
            js_engine_html,
            raw_llm_svg,
            python_smil_builder,
            legacy_svg_elements,
        )
        if html:
            frame["svg_html"] = html
        drop frame["svg_elements"], frame["diagram_type"], frame["data"]

    merge prefetched Wikimedia candidates with live search results
    dedupe candidates by URL
    picks = choose_best_image_url_per_step_with_llm_fallback()
    write direct URLs back into step["image_description"]
    clear unmatched image_description fields
    return updated_blackboard_json
""".strip()


QUIZ_PIPELINE_PSEUDOCODE = """
async def generate_quiz(req):
    quiz = await quiz_service.generate_quiz(...)
    try_save_quiz_to_firestore_non_fatally(quiz)
    log_activity_async("quiz_generate", ...)
    return quiz


async def submit_quiz(req):
    quiz_doc = load_quiz_from_firestore(req.quiz_id)
    for each submitted answer:
        if question_type in {mcq, fill_blank_*}:
            grade locally with evaluation_service
        elif question_type == short_answer:
            grade with evaluation_service.evaluate_short_answer()
        append QuestionResult(...)
    persist attempt
    update gamification
    update chapter progress
    return AttemptResult
""".strip()


PAYMENTS_PSEUDOCODE = """
async def create_order(req, auth):
    assert auth.uid == req.user_id
    order = razorpay.order.create(...)
    firestore.payment_intents[user_id + order_id] = pending intent
    return order info + Razorpay key id


async def verify_payment(req, auth):
    assert auth.uid == req.user_id
    verify_hmac_signature(order_id, payment_id, signature)
    payment_data = razorpay.payment.fetch(...)
    write payment_receipts doc
    activate_plan(user_id, plan_id, plan_name, start, expiry)
    mark payment_intent completed
    return verified=True


async def webhook(payload):
    verify webhook secret if configured
    store raw webhook event
    if event == "payment.captured":
        idempotently activate plan if /verify did not already finish
""".strip()


NOTES_FOR_AGENTS = [
    "ServerProxyClient expects SSE frames shaped like data: {\"text\": ...}, status frames, and a terminal done frame.",
    "Normal chat and Blackboard share the same /chat-stream endpoint but use different prompt builders and post-processing.",
    "image_search_titles.get_titles() is now effectively a Blackboard post-processor, not just an image-title helper.",
    "After Blackboard post-processing, image_description usually contains a direct Wikimedia image URL that Android can load directly.",
    "Blackboard diagram frame render order is atom -> JS engine -> raw SVG LLM -> Python SMIL -> legacy svg_elements.",
    "get_titles() removes diagram_type, data, and svg_elements from frames after svg_html is built, so downstream consumers should not expect those planning keys to survive.",
    "chat.py writes prompt.txt and llm_service.py writes response.json as debug artifacts; these are side effects of the current implementation.",
    "The backend is the quota authority; Android now treats HTTP 429 as the real limit signal.",
    "Users/register is idempotent and safe to call after each login.",
    # ── Recent changes ──────────────────────────────────────────────────────
    "QUOTA WRITES: The server (check_and_record_quota) is the SOLE writer of chat_questions_today / bb_sessions_today. Android no longer writes these fields — double-counting was the previous bug.",
    "QUOTA READ: GET /users/quota returns live counters + plan limits for Android quota display. Android no longer reads Firestore plans/ directly for quota.",
    "JSON SAFETY: _sanitize_normal_response never returns raw JSON/LLM text to users; falls back to a friendly message or rescued answer field. BB fallback returns empty steps JSON.",
    "SUGGEST_BLACKBOARD: Normal mode JSON now includes suggest_blackboard boolean field. LLM sets it based on whether topic benefits from a visual lesson.",
    "LOGIN: Only Google sign-in is supported. Guest and email/password login removed.",
]
