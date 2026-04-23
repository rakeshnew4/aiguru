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
            "Fetches page context",
            "Normalizes image input",
            "Routes by req.mode: normal vs blackboard",
            "Uses static system prompts plus dynamic user content",
            "Calls generate_response() and emits text/status/done SSE frames",
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
    has_image = bool(images)

    if req.mode == "blackboard":
        plan = bb_plan(req.question, context, req.history, req.student_level)
        wiki_task = prefetch_wikimedia(plan.image_search_terms)
        system_prompt = get_blackboard_mode_system_prompt()
        user_content = build_blackboard_mode_user_content(...)
        model_tier = plan_to_model_tier(req.user_plan)
    else:
        if has_image:
            intent = "image_explain"
            complexity = "high"
        else:
            intent, complexity = classify_intent(...)
        merged_context = context if has_image else merge_context_with_image_data(context, req.image_data)
        system_prompt = get_normal_mode_system_prompt()
        user_content = build_normal_mode_user_content(...)
        model_tier = "faster" if intent == "greet" else plan_to_model_tier(req.user_plan)

    result = generate_response(user_content, images, tier=model_tier, system_prompt=system_prompt)
    if result.provider == "error":
        stream_error_frame(...)
        return

    if req.mode == "blackboard":
        result.text = await get_titles(result.text, extra_candidates=await wiki_task)

    stream_status_and_text_frames(...)
    stream_done_frame(tokens=result.tokens, page_transcript=extract_page_transcript(...))
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
    "chat.py writes prompt.txt and llm_service.py writes response.json as debug artifacts; these are side effects of the current implementation.",
    "Normal chat and Blackboard share the same /chat-stream endpoint but use different prompt builders and post-processing.",
    "The backend is the quota authority; Android now treats HTTP 429 as the real limit signal.",
    "Users/register is idempotent and safe to call after each login.",
]

