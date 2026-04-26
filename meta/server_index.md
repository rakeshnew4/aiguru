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
**Path:** `server/app/api/quiz.py` | Lines: ?

| Symbol | Lines | What it does |
|--------|-------|--------------|
| `generate_quiz()` | ? | Generates quiz via quiz_service, saves to Firestore |
| `submit_quiz()` | ? | Loads quiz from Firestore, evaluates submission |

---

## services/quiz_service.py | Lines: ?
| Symbol | Lines | What it does |
|--------|-------|--------------|
| (unread) | ? | — |

---

## utils/svg_llm_builder.py
**Path:** `server/app/utils/svg_llm_builder.py`

| Symbol | Lines | What it does |
|--------|-------|--------------|
| SVG LLM builder | ~144–254 | For `diagram_type="custom"`: generates SVG via LLM (tier=cheaper, max_tokens=4096) |
| Phase 1 animation only | — | Rejects output containing `<script>` |

---

## core/config.py | Lines: ?
| Symbol | Lines | What it does |
|--------|-------|--------------|
| `settings` | ? | App config: LITELLM_MASTER_KEY, USE_LITELLM_PROXY, etc. |

---

## api/payments.py | api/users.py | api/admin.py | api/credits.py
> Unread. Update on first access.

---

## services/diagram_service.py
> Standalone `/diagram/generate` endpoint — separate from BB pipeline. Unread.

---

## utils/svg_builder.py | utils/svg_renderers.py | utils/svg_renderers_sci.py | utils/svg_renderers_math.py
> SVG rendering pipeline. Unread — read `app_context/backend_svg_pipeline.py` first.
