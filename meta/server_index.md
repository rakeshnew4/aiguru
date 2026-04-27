# Server File Index
> Auto-maintained. Update this file every time you read a server file.
> Format: file path → key symbols with line numbers and one-line purpose.
> If line numbers are unknown, mark as `?` and fill on next read.

---

## api/chat.py
**Path:** `server/app/api/chat.py` | **Size:** ~1300+ lines | **⚠️ Expensive — never read top-to-bottom**

| Symbol | Lines | What it does |
|--------|-------|--------------|
| `chat_stream()` | ~937–1300 | Main POST /chat-stream handler; BB + normal chat flow |
| `_bb_plan()` | ~992–1001 | Calls BB planner LLM (tier=faster) |
| `_classify_intent()` | ~1045–1048 | Intent classification LLM call (tier=faster) |
| TTS engine default | ~672–673 | Defaults tts_engine to "gemini" |
| prompt.txt write | ~1076–1079 | Debug artifact: writes constructed prompt to file |

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
| `_best_title_match()` | 83–111 | Word-overlap scorer for Wikimedia image title matching (≥50% threshold) |
| `extract_json_safe()` | 114–163 | Robust JSON extractor with 5 fallback strategies |
| `search_wikimedia_images()` | 172–? | Async Wikimedia Commons image search; returns thumbnail URLs |
| `get_titles()` | ~400+ | BB post-processor: enrichment + wikimedia + SVG build |
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

---

## services/prompt_service.py
**Path:** `server/app/services/prompt_service.py` | **⚠️ Expensive**

| Symbol | Lines | What it does |
|--------|-------|--------------|
| `BB_PLANNER_PROMPT` | ~157–199 | BB planner prompt: topic_type, scope, steps_count, hook_question |
| BB main system prompt | ~203–399 | Full blackboard lesson generation prompt |
| frame types definition | ~238 | `concept \| memory \| diagram \| quiz_mcq \| summary` |
| quiz_mcq instructions | ~260–261 | 4 options, 1 correct, use misconceptions as distractors |
| `build_blackboard_mode_user_content()` | ~726+ | Builds dynamic user content: chapter context, level, lesson brief |

---

## api/tts.py
**Path:** `server/app/api/tts.py`

| Symbol | Lines | What it does |
|--------|-------|--------------|
| `/api/tts/synthesize` endpoint | ~205–254 | Calls Google/ElevenLabs/OpenAI TTS — NO LLM involved |
| `_clean_for_tts()` | ~195–198 | Strips LaTeX symbols; no LLM |

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
| Active models (Apr 2026) | ? | All 3 tiers → `gemini-2.5-flash-lite` |

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

## services/user_service.py
**Path:** `server/app/services/user_service.py`

| Symbol | Lines | What it does |
|--------|-------|--------------|
| credit charge/award | 142, 231–238 | Deducts or adds credits to user balance |
| credit init | 311–392 | Initializes new user with welcome credits (50) |
| quota logic | 537–619 | Per-plan daily quota enforcement |

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

## api/users.py
**Path:** `server/app/api/users.py`

| Symbol | Lines | What it does |
|--------|-------|--------------|
| (unread) | ? | User profile CRUD, signup, plan info |

---

## api/admin.py
**Path:** `server/app/api/admin.py`

| Symbol | Lines | What it does |
|--------|-------|--------------|
| (unread) | ? | Admin-only endpoints (school management, config) |

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
**Path:** `server/app/main.py`

| Symbol | Lines | What it does |
|--------|-------|--------------|
| FastAPI `app` | ? | App instance; mounts all routers |
| Router mounts | ? | Registers api/chat.py, bb.py, tts.py, quiz.py, etc. |

---

## Key Server Architecture Facts
- **Entry point:** `server/app/main.py` (FastAPI)
- **All LLM calls:** `generate_response()` → `_call_litellm_proxy()` in `llm_service.py`
- **Active LLM:** `gemini-2.5-flash-lite` (all 3 tiers) via LiteLLM proxy on localhost
- **Per-user LLM keys:** stored in `users_table/{uid}.litellm_key`, cached in `_user_key_cache` dict
- **BB pipeline:** `api/chat.py` → BB planner LLM → main BB LLM → `image_search_titles.get_titles()` → enrichment + SVG + wikimedia
- **Enrichment:** `enrichment_service.build_enrichment_tasks()` returns `(futs, refs)` 2-tuple (quiz validator removed Apr 2026)
- **TTS:** `api/tts.py` → Google/ElevenLabs/OpenAI audio API — **zero LLM calls**
- **Credits:** `api/credits.py` + `services/user_service.py` — welcome bonus 50, daily question awards 5 each, 1 credit per 100 tokens/TTS chars
- **context_service.get_context():** stub — do NOT expand
- **blackboard_prompt:** ~400 tokens — do NOT expand
