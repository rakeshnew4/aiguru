# Server File Index
> Auto-maintained. Update this file every time you read a server file.
> Format: file path → key symbols with line numbers and one-line purpose.
> If line numbers are unknown, mark as `?` and fill on next read.

---

## api/chat.py
**Path:** `server/app/api/chat.py` | **Size:** ~1300+ lines | **⚠️ Expensive — never read top-to-bottom**

| Symbol | Lines | What it does |
|--------|-------|--------------|
| `ChatRequest` model | 60–75 | Pydantic model; includes `image_base64: Optional[str]`, `images: Optional[List[str]]`, `image_data: Optional[Dict]` |
| `_normalize_images()` | 90–98 | Converts `image_base64` → `["data:image/jpeg;base64,..."]`; returns list for LLM |
| `_merge_context_with_image_data()` | 119–150 | Merges structured `image_data` (transcript, key_terms, diagrams) into context string |
| `chat_stream()` | ~937–1326 | Main POST /chat-stream handler; BB + normal chat flow |
| `has_image` flag | 981 | `bool(normalized_images)` — controls intent override + context merge |
| `_bb_plan()` | 196–240 | Fast BB planner (tier=faster); called with NO images; parses JSON → plan dict with defaults |
| `_classify_intent()` | ~1051–1057 | Intent classification LLM call (tier=faster); skipped when `has_image=True` (→ image_explain) |
| BB path entry | 992–1030 | `mode="blackboard"`: calls `_bb_plan()` → `build_blackboard_mode_user_content()` → `generate_response(user_content, normalized_images, ...)` |
| `mode="blackboard_intent"` path | 1031–1036 | Fast planner only; `user_content = req.question`; passes `normalized_images` to LLM |
| `mode="normal"` path | 1039–1079 | Intent classify → `build_normal_mode_user_content()` → `generate_response(user_content, normalized_images, ...)` |
| image-less retry | 1176–1192 | On LLM exception with images present → retries without images |
| TTS engine default | ~672–673 | Defaults tts_engine to "gemini" |
| prompt.txt write | ~1082–1086 | Debug artifact: writes constructed prompt to file |
| ⚠️ BB planner no-image | 217 | `_bb_plan()` calls `generate_response(planner_prompt, [], ...)` — always empty images, even when user attached a photo |

---

## api/image_search_titles.py
**Path:** `server/app/api/image_search_titles.py` | **Size:** ~700+ lines | **⚠️ Expensive**

| Symbol | Lines | What it does |
|--------|-------|--------------|
| `get_http_client()` | 15–26 | Shared async HTTP client with connection pooling |
| `_GENERIC_IMAGE_WORDS` | 31–34 | Words excluded from image relevance scoring |
| `_FORCE_DIAGRAM_KEYWORDS` | 39–44 | Physics/force terms that trigger LLM SVG builder |
| `_BASIC_GEOMETRY_TYPES` | 45 | Geometry types that may be overridden by force check |
| `_FRAME_DEFAULTS` | 53–72 | Default values for BB frame fields after sparse LLM output |
| `_normalize_frame()` | 75–80 | Fills missing frame fields with defaults |
| `_best_title_match()` | 83–111 | Word-overlap scorer for Wikimedia image title matching (threshold lowered to 0.3 from 0.5) |
| `extract_json_safe()` | 114–163 | Robust JSON extractor with 5 fallback strategies |
| `search_wikimedia_images()` | 172–? | Async Wikimedia Commons image search; returns thumbnail URLs |
| `get_titles()` | ~400+ | BB post-processor: enrichment + wikimedia + SVG build. Parallel: image picker future started before Phase 3; all LLM SVG calls run in parallel via `asyncio.gather` |
| `_build_llm_svg_cached()` | inside get_titles | Wraps `build_llm_svg` with md5-keyed cache (TTL 24h); 3 call sites inside `get_titles()` use this |
| `_CIRCULAR_LAYOUT_TYPES` | ~51 | `{"cycle", "labeled"}` — these bypass JS engine and go to LLM SVG builder |
| `build_enrichment_tasks()` call | ~407 | Unpacks `(enr_futs, diagram_refs)` — 2-tuple |
| diagram enrichment apply | ~444–447 | Writes enriched data back into frame["data"] |
| tts_engine default | 58 | `_FRAME_DEFAULTS` sets tts_engine="gemini" |

---

## services/enrichment_service.py
**Path:** `server/app/services/enrichment_service.py` | **Size:** ~300 lines

| Symbol | Lines | What it does |
|--------|-------|--------------|
| `_SCHEMAS` | 28–163 | Diagram type → schema + hint dict (atom, solar_system, triangle, flow, cycle, etc.) |
| `_COLOR_KEYS` | 165–168 | Color key reference string |
| `_ENRICH_SYSTEM` | 170–173 | System prompt for diagram enricher |
| `enrich_diagram_data()` | 177–254 | LLM call: fills diagram data dict for a frame |
| `build_enrichment_tasks()` | ~270–310 | Builds diagram enrichment futures; returns `(futs, refs)` 2-tuple |
| `_MAX_DIAGRAM_ENRICHMENTS` | ~268 | Cap = 2 enrichments per session |
| ~~`validate_quiz_mcq()`~~ | removed Apr 2026 | Quiz validator deleted — main LLM is reliable |

---

## services/llm_service.py
**Path:** `server/app/services/llm_service.py`

| Symbol | Lines | What it does |
|--------|-------|--------------|
| `_init_gemini()` | 19–29 | Initializes Gemini client from GEMINI_API_KEY |
| `_init_groq()` | 31–41 | Initializes Groq client |
| `_init_bedrock()` | 43–68 | Initializes Bedrock client (access keys or bearer token) |
| `_genai_client`, `_groq_client`, `_bedrock_client` | 70–72 | Module-level client singletons |
| `_images_to_gemini_parts()` | 76–139 | Converts base64/URL images to Gemini Part objects |
| `_images_to_bedrock_content()` | 142–? | Converts images to Bedrock converse() content blocks |
| `_call_litellm_proxy()` | ~406–500 | Active LLM call path; sends to LiteLLM proxy on localhost |
| request body construction | ~446–454 | Builds JSON body with model, messages, temp, max_tokens, thinking, extra_body |
| thinking config | ~451 | `{"type": "enabled", "budget_tokens": 32}` |
| cache_control | ~452–454 | `extra_body: {cache_control: {type: ephemeral}}` for Gemini explicit caching |
| cachedTokens parse | ~486 | Reads `prompt_tokens_details.cached_tokens` from response |
| `generate_response()` | ~502–540 | Public entry point; routes to `_call_litellm_proxy()` |
| `stream_generate_response()` | ~540+ | Async generator; hits LiteLLM with `stream=True`; yields str chunks then sentinel `{"_stream_done": True, "tokens": ...}`. Falls back to blocking call if stream fails before any text. `max_tokens` param supported. |

---

## services/prompt_service.py
**Path:** `server/app/services/prompt_service.py` | **⚠️ Expensive**

| Symbol | Lines | What it does |
|--------|-------|--------------|
| `BB_PLANNER_PROMPT` | ~157–199 | BB planner prompt: topic_type, scope, steps_count, hook_question |
| BB main system prompt | ~203–399 | Full blackboard lesson generation prompt |
| frame types definition | ~238 | `concept \| memory \| diagram \| quiz_mcq \| summary` |
| quiz_mcq instructions | ~260–261 | 4 options, 1 correct, use misconceptions as distractors |
| `build_blackboard_mode_user_content()` | 724–900+ | Builds dynamic BB user content: chapter context, level, lesson brief, diagram hints; takes `image_data` dict NOT raw image bytes; `image_data` → appends transcript + key_terms to prompt |

---

## api/tts.py
**Path:** `server/app/api/tts.py`

| Symbol | Lines | What it does |
|--------|-------|--------------|
| `GEMINI_VOICE_DEFAULT` | ~? | `"Kore"` — default Gemini voice name |
| `_pcm_to_wav()` | ~? | Wraps raw 24kHz PCM bytes in WAV container using stdlib `wave` module |
| `_gemini_tts()` | ~? | Calls `generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-preview-tts`; decodes base64 PCM; returns WAV bytes |
| `synthesize()` endpoint | ~205–254 | Routes: gemini first (→ `audio/wav`), then google TTS (→ `audio/mpeg`), then ElevenLabs/OpenAI (English only). `X-TTS-Engine` header in response. |
| `TtsSynthesizeRequest.tts_engine` default | — | `"gemini"` (changed from `"google"` in 2026-05-15) |
| `_clean_for_tts()` | ~195–198 | Strips LaTeX symbols; no LLM |
| Fallback chain | — | gemini → google → elevenlabs (en only) → openai (en only) |

---

## api/users.py
**Path:** `server/app/api/users.py`

| Symbol | Lines | What it does |
|--------|-------|--------------|
| `GET /users/lookup?code=XXXX` | ~413 | Resolves `referralCodes/{code}` → `{uid, name, code}`; rejects own code (for add-friend flow) |
| `POST /users/share-session` | ~450 | Resolves recipient via `to_code`; writes to `users/{recipientUid}/shared_with_me/`; auto-adds both as friends in `users/{uid}/friends/` |
| Login handler | ~87–89 | Calls `ensure_user_credits(uid)` on every login so existing users get credits doc if missing |
| `get_user_quota` | ~200–205 | Adds referral bonus + tts_balance to quota response |
| `get_quota_status` | ~322–326 | Same bonus additions |

---

## api/referrals.py
**Path:** `server/app/api/referrals.py` (NEW — added 2026-05-09)

| Symbol | Lines | What it does |
|--------|-------|--------------|
| `POST /referrals/apply` | ~20 | Validates code from `referralCodes`; prevents self-referral + double-claim; writes `referral_bb_bonus_per_day` + `referral_bb_bonus_expiry_at` to both claimant + referrer |
| `_get_referral_config()` | ~? | Reads `app_config/referral_settings` `{bonus_per_day, bonus_days}`; defaults to 3/30 on error |

---

## api/bb.py
**Path:** `server/app/api/bb.py` | Lines: ?

| Symbol | Lines | What it does |
|--------|-------|--------------|
| BB grading endpoint | ? | Grades quiz typed/voice answers |

---

## api/quiz.py
**Path:** `server/app/api/quiz.py` | Lines: ~320

| Symbol | Lines | What it does |
|--------|-------|--------------|
| `generate_quiz()` | 55–118 | POST /quiz/generate — tracks usage, schedules bg indexing if context_text sent, calls quiz_service |
| `_bg_index_chapter()` | 38–44 | async helper: calls chapter_index_service.index_chapter, swallows errors |
| `index_chapter()` | 150–183 | POST /quiz/index-chapter — dedup check → bg ES indexing; returns scheduled/already_indexed |
| `index_status()` | 188–194 | GET /quiz/index-status?chapter_id=… — returns {indexed: bool} |
| `submit_quiz()` | 200–237 | POST /quiz/submit — loads from Firestore, evaluates MCQ/fill/short-answer |
| `IndexChapterRequest` | 123–127 | Pydantic model: chapter_id, chapter_title, subject, text |
| `IndexChapterResponse` | 129–133 | Pydantic model: chapter_id, already_indexed, scheduled, message |

---

## services/quiz_service.py
**Path:** `server/app/services/quiz_service.py` | Lines: ~235

| Symbol | Lines | What it does |
|--------|-------|--------------|
| `QUIZ_SYSTEM_PROMPT` | 32 | System prompt for quiz LLM calls |
| `_build_prompt()` | 35–109 | Builds LLM prompt with type instructions + context_text section |
| `_extract_json()` | 114–117 | Strips markdown fences, parses JSON from LLM output |
| `_parse_question()` | 120–152 | Converts raw dict to typed question model; MCQ fallback if options empty |
| `generate_quiz()` | 157–235 | Main entry: 1) Redis cache check 2) ES context retrieval 3) LLM call 3 retries |
| ES context injection | 182–192 | Calls chapter_index_service.is_indexed + retrieve_context; ES beats context_text |

---

## services/chapter_index_service.py  ← NEW (Apr 2026)
**Path:** `server/app/services/chapter_index_service.py` | Lines: ~240

| Symbol | Lines | What it does |
|--------|-------|--------------|
| `ES_INDEX` | 34 | `"chapter_segments"` — separate from `yt_video_segments` |
| `CHUNK_SIZE_WORDS` | 35 | 280 words per chunk target |
| `TOP_K_CHUNKS` | 38 | 6 chunks returned per retrieval |
| `USAGE_WARM_THRESHOLD` | 40 | Background-index triggers after 3 requests |
| `_get_es()` | 52–57 | Lazy AsyncElasticsearch singleton |
| `_get_vertex_model()` | 73–89 | Lazy Vertex AI TextEmbeddingModel (text-embedding-005, 768 dims) |
| `_embed_texts()` | 97–99 | Async wrapper: runs _embed_batch_sync in executor |
| `_ensure_index()` | 103–130 | Creates ES index with dense_vector mapping if absent |
| `is_indexed()` | 134–153 | Redis fast-path → ES count fallback; sets Redis flag on ES hit |
| `track_usage()` | 156–163 | Increments Redis counter ch_usage:{chapter_id}, returns new count |
| `_is_content_rich()` | 167–178 | Filters chunks: keeps definitions/concepts/examples (50-70% cost saving) |
| `_chunk_text()` | 181–212 | Paragraph-aware chunking with CHUNK_OVERLAP_WORDS=40 overlap |
| `index_chapter()` | 216–248 | Dedup → chunk → embed → ES bulk upsert → Redis flag |
| `retrieve_context()` | 252–278 | kNN search → score threshold filter → sort by chunk_index → concat |

---

## utils/svg_llm_builder.py
**Path:** `server/app/utils/svg_llm_builder.py`

| Symbol | Lines | What it does |
|--------|-------|--------------|
| SVG LLM builder | ~144–254 | For `diagram_type="custom"`: generates SVG via LLM (tier=cheaper, max_tokens=4096) |
| Phase 1 animation only | — | Rejects output containing `<script>` |

---

## core/config.py
**Path:** `server/app/core/config.py`

| Symbol | Lines | What it does |
|--------|-------|--------------|
| `Settings` class | ? | Pydantic settings: GEMINI_API_KEY, LITELLM_MASTER_KEY, USE_LITELLM_PROXY, POWER/CHEAPER/FASTER model IDs |
| `settings` singleton | ? | Global config instance |
| Active models (Apr 2026) | ? | All 3 tiers → `gemini-3.1-flash-lite-preview` |

---

## core/auth.py
**Path:** `server/app/core/auth.py`

| Symbol | Lines | What it does |
|--------|-------|--------------|
| (unread) | ? | FastAPI dependency for Bearer token validation |

---

## core/firebase_auth.py
**Path:** `server/app/core/firebase_auth.py`

| Symbol | Lines | What it does |
|--------|-------|--------------|
| (unread) | ? | Firebase Admin SDK token verification |

---

## core/logger.py
**Path:** `server/app/core/logger.py`

| Symbol | Lines | What it does |
|--------|-------|--------------|
| (unread) | ? | Structured logging setup |

---

## services/litellm_service.py
**Path:** `server/app/services/litellm_service.py`

| Symbol | Lines | What it does |
|--------|-------|--------------|
| (unread) | ? | LiteLLM proxy management / key generation helpers |

---

## services/strands_agent.py
**Path:** `server/app/services/strands_agent.py`

| Symbol | Lines | What it does |
|--------|-------|--------------|
| (unread) | ? | AWS Strands agent integration (possibly for Bedrock) |

---

## services/cache_service.py
**Path:** `server/app/services/cache_service.py`

| Symbol | Lines | What it does |
|--------|-------|--------------|
| (unread) | ? | Server-side response caching layer |

---

## services/context_service.py
**Path:** `server/app/services/context_service.py`

| Symbol | Lines | What it does |
|--------|-------|--------------|
| `get_context()` | ? | **Stub** — returns empty/minimal context. Do NOT expand. |

---

## services/user_service.py
**Path:** `server/app/services/user_service.py`

| Symbol | Lines | What it does |
|--------|-------|--------------|
| `_lookup_plan_limits()` | ~54–87 | Reads `limits` sub-map first, then falls back to root-level fields for `credits_on_activation`, `tts_credits_on_activation`, `daily_chat_limit`, etc. Logs found credits value. |
| `create_user_if_missing()` | ~130–140 | Now reads from `_lookup_plan_limits(db, "free")` (dynamic from Firestore); mirrors identity to `users/{uid}`; calls `init_user_credits` |
| Free plan defaults | 107–144 | `plan_daily_chat_limit=12`, `plan_daily_bb_limit=10` (updated), `plan_tts_enabled=True`, `plan_ai_tts_enabled=False`, `plan_image_enabled=False` |
| `copy_samples_to_user()` | 157–188 | Copies up to 10 docs from `bb_samples` → `users/{uid}/saved_bb_sessions_flat/`; sets `is_sample=True` |
| `copy_default_data_to_user()` | ~230–250 | Reads `admin_config/user_defaults.default_data`; merges into `users/{uid}` with `merge=True`; called fire-and-forget on new user registration |
| `activate_plan()` | ~217–340 | Activates plan; reads `activation_tts_credits` from limits; calls `_award_activation_credits(uid, amount, tts_amount, ...)` |
| ⚠️ Webhook+verify race | 191 | `activate_plan()` called from both `/payments/razorpay/verify` and webhook handler; `_award_activation_credits` can fire twice if both complete concurrently |
| `_award_activation_credits()` | ~641–700 | Awards `balance` + `lifetime_earned`; if `tts_amount > 0` also increments `tts_balance` + `tts_lifetime_earned`; logs `plan_activation_tts` transaction |
| `init_user_credits()` | ~730–784 | Accepts `starter_credits` + `starter_tts_credits` params; initializes `user_credits/{uid}` doc |
| `ensure_user_credits()` | ~784+ | Public wrapper: calls `init_user_credits` if doc missing; called on every login |
| quota logic | 537–619 | `check_and_record_quota()`: returns `(allowed, reason, credit_mode)` 3-tuple; credit_mode=True when on credits; adds referral bonus to BB limit |

---

## services/cache_service.py
**Path:** `server/app/services/cache_service.py`

| Symbol | Lines | What it does |
|--------|-------|--------------|
| `get_cache(namespace, key)` | ~? | Returns cached value or None |
| `set_cache(namespace, key, value, ttl=2592000)` | ~? | Stores with custom TTL (default 30d); bb_main uses 6h, SVG uses 24h |

---

## services/diagram_service.py
**Path:** `server/app/services/diagram_service.py`

| Symbol | Lines | What it does |
|--------|-------|--------------|
| `_call_llm(question, uid)` | ~? | LLM call for diagram intent; now takes `uid` for per-user key |
| `generate_diagram(question, uid)` | ~? | Entry point; passes `uid` to `_call_llm` |

---

## utils/diagram_router.py
**Path:** `server/app/utils/diagram_router.py`

| Symbol | Lines | What it does |
|--------|-------|--------------|
| keyword map | 65–200 | Maps topic keywords → diagram type; `cycle`-related keywords (`mitosis`, `meiosis`, `water cycle`, `life cycle`, etc.) now map to `"custom"` instead of `"cycle"` |

---

## services/evaluation_service.py
**Path:** `server/app/services/evaluation_service.py`

| Symbol | Lines | What it does |
|--------|-------|--------------|
| (unread) | ? | Evaluates student answers / quiz submissions |

---

## services/gamification_service.py
**Path:** `server/app/services/gamification_service.py`

| Symbol | Lines | What it does |
|--------|-------|--------------|
| (unread) | ? | XP / streak / badge award logic |

---

## services/library_service.py
**Path:** `server/app/services/library_service.py`

| Symbol | Lines | What it does |
|--------|-------|--------------|
| (unread) | ? | NCERT/library resource fetch and metadata |

---


## models/request.py
**Path:** `server/app/models/request.py`

| Symbol | Lines | What it does |
|--------|-------|--------------|
| (unread) | ? | Pydantic request body models |

---

## models/response.py
**Path:** `server/app/models/response.py`

| Symbol | Lines | What it does |
|--------|-------|--------------|
| (unread) | ? | Pydantic response models |

---

## models/quiz.py
**Path:** `server/app/models/quiz.py`

| Symbol | Lines | What it does |
|--------|-------|--------------|
| (unread) | ? | Quiz question/submission/result models |

---

## models/payment.py
**Path:** `server/app/models/payment.py`

| Symbol | Lines | What it does |
|--------|-------|--------------|
| (unread) | ? | Payment order/verification models |

---

## models/library.py
**Path:** `server/app/models/library.py`

| Symbol | Lines | What it does |
|--------|-------|--------------|
| (unread) | ? | Library resource models |

---

## api/analyze_image.py
**Path:** `server/app/api/analyze_image.py`

| Symbol | Lines | What it does |
|--------|-------|--------------|
| (unread) | ? | POST /analyze-image — describes PDF/image page for Ask AI |

---

## api/library.py
**Path:** `server/app/api/library.py`

| Symbol | Lines | What it does |
|--------|-------|--------------|
| (unread) | ? | GET/POST library resources |

---

## api/tasks.py
**Path:** `server/app/api/tasks.py`

| Symbol | Lines | What it does |
|--------|-------|--------------|
| (unread) | ? | Teacher task assignment/submission endpoints |

---

## api/diagram.py
**Path:** `server/app/api/diagram.py`

| Symbol | Lines | What it does |
|--------|-------|--------------|
| (unread) | ? | POST /diagram/generate — standalone diagram generation |

---

## api/analyze_image.py
**Path:** `server/app/api/analyze_image.py`

| Symbol | Lines | What it does |
|--------|-------|--------------|
| (unread) | ? | POST /analyze-image — describes PDF/image page for Ask AI |

---

## api/admin.py
**Path:** `server/app/api/admin.py` | Lines: ~980 (after 2026-05-16 rewrite)

**Helper functions:**

| Symbol | Lines | What it does |
|--------|-------|--------------|
| `_require_admin()` | 35–44 | HTTP Basic Auth: checks ADMIN_USERNAME / ADMIN_PASSWORD env vars |
| `_get_db()` | 49–64 | Lazy singleton for sync Firestore client using service account |
| `_count_collection()` | 78–85 | Uses Firestore `count()` aggregation (1 read/call) with stream() fallback |
| `_to_epoch_s()` | 88–95 | Normalises int ms, int s, datetime → epoch seconds float |
| `_stats_cache`, `_analytics_cache` | 97–99 | In-memory dicts; TTL = 300s (5 min) each |

**Dashboard (cached, 5 min TTL):**

| Symbol | Lines | What it does |
|--------|-------|--------------|
| `GET /stats` | 103–120 | Returns 8 collection counts; uses count() + cache — very cheap |
| `GET /analytics` | 123–195 | One-pass: reads users(500) + payments(200) + LiteLLM; returns growth, plan dist, revenue, LLM cost |

**Users Management** (NEW 2026-05-16):

| Symbol | Lines | What it does |
|--------|-------|--------------|
| `GET /users_table` | 803–850 | Paginated user list (50/page) with search + filters (plan, grade, school); returns total, page, pages |
| `GET /users_table/{uid}` | 853–875 | Full profile + credits doc + recent 10 transactions |
| `PUT /users_table/{uid}` | 878–885 | Merge-update user; filters out credits/transactions |
| `POST /users_table/{uid}/quota` | 888–908 | Safe quota-only update; whitelisted fields: planId, plan_daily_chat_limit, plan_daily_bb_limit, plan_tts_enabled, plan_ai_tts_enabled, plan_blackboard_enabled, plan_image_enabled, plan_expiry_date, ai_tts_quota_chars |
| `POST /users_table/{uid}/credits/grant` | 911–945 | Grant credits: increments balance + lifetime_earned, logs to credit_transactions |
| `POST /users_table/{uid}/credits/deduct` | 948–980 | Deduct credits: decrements balance (min 0), logs with type=admin_deduct |
| `DELETE /users_table/{uid}` | 983–987 | Hard-delete user + credits docs |

**Plans** (NEW):

| Symbol | Lines | What it does |
|--------|-------|--------------|
| `GET /plans_new` | 990–993 | List all plans |
| `POST /plans_new` | 996–1002 | Create plan |
| `PUT /plans_new/{plan_id}` | 1005–1011 | Update plan |
| `DELETE /plans_new/{plan_id}` | 1014–1018 | Delete plan |

**Credit Topups** (NEW):

| Symbol | Lines | What it does |
|--------|-------|--------------|
| `GET /credit-topups_new` | 1021–1024 | List all credit packs |
| `POST /credit-topups_new` | 1027–1033 | Create pack |
| `PUT /credit-topups_new/{pack_id}` | 1036–1042 | Update pack |
| `DELETE /credit-topups_new/{pack_id}` | 1045–1049 | Delete pack |

**Offers** (NEW):

| Symbol | Lines | What it does |
|--------|-------|--------------|
| `GET /offers_new` | 1052–1055 | List all offers |
| `POST /offers_new` | 1058–1064 | Create offer |
| `PUT /offers_new/{offer_id}` | 1067–1073 | Update offer |
| `DELETE /offers_new/{offer_id}` | 1076–1080 | Delete offer |

**Schools** (NEW):

| Symbol | Lines | What it does |
|--------|-------|--------------|
| `GET /schools_new` | 1083–1086 | List schools |
| `POST /schools_new` | 1089–1095 | Create school |
| `PUT /schools_new/{school_id}` | 1098–1104 | Update school |
| `DELETE /schools_new/{school_id}` | 1107–1111 | Delete school |

**Subjects & Chapters** (NEW):

| Symbol | Lines | What it does |
|--------|-------|--------------|
| `GET /subjects_new` | 1114–1117 | List subjects |
| `POST /subjects_new` | 1120–1126 | Create subject |
| `PUT /subjects_new/{subject_id}` | 1129–1135 | Update subject |
| `DELETE /subjects_new/{subject_id}` | 1138–1142 | Delete subject |
| `GET /chapters_new` | 1145–1154 | List chapters; optional filter by subject_id |
| `POST /chapters_new` | 1157–1163 | Create chapter |
| `PUT /chapters_new/{chapter_id}` | 1166–1172 | Update chapter |
| `DELETE /chapters_new/{chapter_id}` | 1175–1179 | Delete chapter |

**Activity Logs** (NEW):

| Symbol | Lines | What it does |
|--------|-------|--------------|
| `GET /activity-logs_new` | 1182–1209 | Paginated logs (50/page) with optional uid/event_type filters; sorted descending by timestamp |

**Legacy Endpoints** (pre-2026-05-16):

| Symbol | Lines | What it does |
|--------|-------|--------------|
| ~~`GET /users`~~ | — | Replaced by GET /users_table (paginated) |
| ~~`GET /users/{uid}`~~ | — | Replaced by GET /users_table/{uid} |
| CRUD: subjects, chapters, plans, schools, offers | 237–385 | (Old implementation; superseded by _new endpoints) |
| `GET /model-config` | 281–305 | Returns live env tiers + Firestore admin_config/global |
| `PUT /model-config` | 308–312 | Updates admin_config/global |
| `GET /payments/intents` | 317–322 | Payment intents, ordered by created_at DESC |
| `GET /payments/receipts` | 325–329 | Payment receipts |
| `GET /app-config` | 337–340 | Reads updates/app_config doc |
| `GET /bb-samples` | 387–389 | Lists global onboarding BB samples |
| `GET /notifications` | 432–433 | Lists notification docs |
| `GET /referral-codes` | 461–463 | Lists referralCodes collection |
| LiteLLM endpoints | 479–608 | Key create/list/revoke + usage per-user + all-users + health check |
| ~~`GET /activity-logs`~~ | — | Replaced by GET /activity-logs_new (paginated 50/page) |
| ~~`GET /activity-logs/stats`~~ | — | Removed; use activity-logs_new with aggregation on client |
| `GET /collection/{name}` | 653–668 | Read-only hatch for ALLOWED_COLLECTIONS set |

---

## api/credits.py
**Path:** `server/app/api/credits.py`

| Symbol | Lines | What it does |
|--------|-------|--------------|
| `GET /credits/balance` | ~170 lines total | Returns user credit balance |
| `POST /credits/spend` | ? | Deducts credits |
| `GET /credits/transactions` | ? | Returns credit history |
| `GET /credits/topup-packs` | ? | Returns available top-up options |

---

## utils/diagram_router.py
**Path:** `server/app/utils/diagram_router.py`

| Symbol | Lines | What it does |
|--------|-------|--------------|
| (unread) | ? | Routes diagram type to correct SVG builder |

---

## utils/js_engine.py
**Path:** `server/app/utils/js_engine.py`

| Symbol | Lines | What it does |
|--------|-------|--------------|
| (unread) | ? | JS evaluation engine for diagram rendering |

---

## utils/json_utils.py
**Path:** `server/app/utils/json_utils.py`

| Symbol | Lines | What it does |
|--------|-------|--------------|
| (unread) | ? | JSON parsing helpers (probably shared with extract_json_safe) |

---

## utils/text_utils.py
**Path:** `server/app/utils/text_utils.py`

| Symbol | Lines | What it does |
|--------|-------|--------------|
| (unread) | ? | Text cleaning, truncation, token estimation helpers |

---

## utils/svg_builder.py (family)
> `svg_builder.py`, `svg_builder_new.py`, `svg_renderers.py`, `svg_renderers_math.py`, `svg_renderers_sci.py`, `svg_primitives.py`, `svg_primitives_ext.py`, `svg_atom.py`, `svg_colors.py`
> Full SVG rendering pipeline for BB diagrams. Routed via `diagram_router.py`.
> **⚠️ Never open `svg_builder_original_backup.py`**

---

## main.py
**Path:** `server/app/main.py` | **Size:** 103 lines

| Symbol | Lines | What it does |
|--------|-------|--------------|
| FastAPI `app` | 51–55 | App instance with title/description/version |
| CORS middleware | 58–70 | `allow_origins` from `ALLOWED_ORIGINS` env var; default hardcoded `http://108.181.187.227:8003` (prod IP) |
| Router mounts | 73–85 | 13 routers: chat, payments, library, quiz, analyze_image, tts, admin, users, bb, diagram, daily_questions, credits, tasks |
| Static mount | 88–90 | `/static` → `server/app/static/` |
| Admin portal | 92–94 | `GET /admin` → `static/admin/index.html` (no auth check) |
| Health endpoint | 96–98 | `GET /health` → `{status: ok}` |
| GOOGLE_APPLICATION_CREDENTIALS auto-set | 45–49 | Auto-sets to `firebase_serviceaccount.json` if file exists and env var not set |
| ⚠️ CORS risk | 63 | Default CORS origin is a hardcoded IP — if server IP or HTTPS changes, Android breaks. Always set `ALLOWED_ORIGINS` env var in prod. |

---

## core/config.py
**Path:** `server/app/core/config.py` | **Size:** 143 lines

| Symbol | Lines | What it does |
|--------|-------|--------------|
| `ModelConfig` class | 6–18 | Simple config DTO: provider, model_id, temperature, max_tokens, supports_images |
| `Settings` class | 22–141 | Pydantic settings; reads from `.env`; `extra="ignore"` |
| POWER tier config | 43–47 | `gemini-3.1-flash-lite-preview`, temp=0.7, max_tokens=16384 |
| CHEAPER tier config | 49–53 | `gemini-3.1-flash-lite-preview`, temp=0.7, max_tokens=14096 |
| FASTER tier config | 55–59 | `gemini-3.1-flash-lite-preview`, temp=0.3, max_tokens=20000 |
| AUTH_REQUIRED | 101 | `bool = True` — must stay True in prod |
| LiteLLM proxy URL | 91 | `http://localhost:8005` default |
| LiteLLM master key default | 92 | `sk-1234567890abcdefghijklmnopqrstuvwxyz` — insecure default, MUST override in `.env` |
| `get_model_config()` | 114–139 | Returns `ModelConfig` for tier; POWER tier `supports_images=True` only for "bedrock" |
| ⚠️ POWER image bug | 122 | `supports_images=self.POWER_PROVIDER in ["bedrock"]` — "gemini" NOT in list → POWER tier image support is False even though provider=gemini |
| `settings` singleton | 142 | Module-level instance; imported everywhere |

---

## core/auth.py
**Path:** `server/app/core/auth.py` | **Size:** 149 lines

| Symbol | Lines | What it does |
|--------|-------|--------------|
| `AuthUser` dataclass | 42–43 | `uid: str`, `email: str = ""`; frozen |
| `require_auth()` | 49–128 | FastAPI dependency; validates Firebase Bearer token; raises HTTP 401 on failure |
| DEV bypass path | 67–84 | When `AUTH_REQUIRED=False`: decodes JWT payload without sig check, returns `AuthUser(uid=extracted_uid, email="dev@local")` |
| Commented bypass | 63–66 | Dead code: `# return AuthUser(uid="BujsVJE2cMX6wU7Jg3acMUlRChm1")` — old hardcoded guest bypass, safe to delete |
| `require_teacher()` | 131–148 | Chains `require_auth` + Firestore role check on `users_table/{uid}.role`; raises HTTP 403 if role not in ("teacher", "admin") |
| Token expiry handling | 96–100 | `ExpiredIdTokenError` → 401 "token has expired" |
| Token revoke handling | 101–104 | `RevokedIdTokenError` → 401 |
| Network error handling | 115–120 | Unexpected exceptions (JWKS fetch fail) → 401 "Token verification failed. Please try again." |

---

## api/payments.py (updated)
**Path:** `server/app/api/payments.py`

| Symbol | Lines | What it does |
|--------|-------|--------------|
| Router prefix | 20 | `/payments/razorpay` |
| `get_razorpay_client()` | 36–50 | Creates Razorpay client from settings; raises HTTP 500 if keys missing; logs masked key for audit |
| `CreateOrderResponse` | 67–75 | `order_id, amount, currency, key_id, prefill_*` |
| `VerifyPaymentResponse` | 78–80 | `verified: bool, message: str` |
| ⚠️ Race condition | — | Both webhook and /verify call `activate_plan()` — idempotency relies on merge=True but `_award_activation_credits` can double-fire |

---

## Key Server Architecture Facts
- **Entry point:** `server/app/main.py` (FastAPI, 13 routers mounted)
- **All LLM calls:** `generate_response()` → `_call_litellm_proxy()` in `llm_service.py`
- **Active LLM:** POWER=`gemini-3.1-flash-lite-preview` (16384 tokens), CHEAPER/FASTER=`gemini-3.1-flash-lite-preview`; all via LiteLLM proxy on localhost:8005
- **⚠️ gemini-3.1-flash-lite-preview:** preview model — has thinking ON by default; may be deprecated by Google without warning
- **Per-user LLM keys:** stored in `users_table/{uid}.litellm_key`, cached in `_user_key_cache` dict
- **BB pipeline:** `api/chat.py` → BB planner LLM → main BB LLM → `image_search_titles.get_titles()` → enrichment + SVG + wikimedia
- **Enrichment:** `enrichment_service.build_enrichment_tasks()` returns `(futs, refs)` 2-tuple (quiz validator removed Apr 2026)
- **TTS:** `api/tts.py` → Google/ElevenLabs/OpenAI audio API — **zero LLM calls**
- **Credits:** welcome bonus 500, daily question awards 5 each, 1 credit per 100 tokens/TTS chars; plans award `credits_on_activation` (basic=200, premium=500, school=1000)
- **context_service.get_context():** stub — do NOT expand
- **blackboard_prompt:** ~400 tokens — do NOT expand
- **CORS:** default origin is hardcoded IP `http://108.181.187.227:8003` — always set `ALLOWED_ORIGINS` env var in prod
- **POWER tier image support:** `supports_images=False` for gemini provider (only bedrock) — bug, pro users' image context silently dropped
- **Auth:** `AUTH_REQUIRED=True` in prod; DEV bypass available via env var; `require_teacher()` reads Firestore on every call (no cache)
- **Free plan limits:** 12 chat/day, 2 BB sessions/day; reset at UTC midnight
