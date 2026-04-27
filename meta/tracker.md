# Change Tracker
> Read the last 30 entries before starting any task.
> Append one entry after every task. Never delete entries — they are history.
> Format: date | what user asked | what was changed (file:lines)

---

## 2026-04-26

### Session: LLM call analysis + cleanup

**Asked:** How many LLM calls happen in a BB session? What are the prompts?
**Answer:** 8 calls in sample session — BB Planner, pre-calls, Main BB LLM, Quiz Validator, 3x Diagram Enrichers. No code change.

---

**Asked:** Remove quiz validator LLM call from backend + Android. Fix Android quiz popup skip button. Add Gemini explicit caching + thinking budget. Clarify TTS LLM calls.
**Changed:**
- `server/app/services/enrichment_service.py` — deleted `validate_quiz_mcq()`, `_QUIZ_SYSTEM`, updated docstring, `build_enrichment_tasks()` now returns 2-tuple instead of 3-tuple
- `server/app/api/image_search_titles.py:407` — updated to unpack 2-tuple; removed quiz answer update loop (~lines 449–454)
- `server/app/services/llm_service.py:446–454` — added `thinking={type:enabled, budget_tokens:32}` and `extra_body={cache_control:{type:ephemeral}}` to LiteLLM proxy request
- `app/.../bb/BbInteractivePopup.kt:218,298,413,576,732` — added `dialog.dismiss()` before `onResult()` in all 5 skip button handlers
**Clarified:** TTS adds zero LLM calls. The 3 extra calls observed were diagram enrichers.

---

**Asked:** Add rules to always update app_context after changes. Add session management rules (/clear guidance).
**Changed:**
- `CLAUDE.md` — added "Editing Rules" mandate to update app_context, new "app_context Update Rules" section, new "Session Management Rules" section
- `app_context/backend_chat_services.py:154` — updated pseudocode: quiz validator removed note
- `app_context/backend_architecture.py:52–54` — updated LLM behavior: added Gemini caching + thinking budget note

---

**Asked:** Create line-number metadata index files for server + Android instead of py app_context files.
**Changed:**
- `meta/server_index.md` — created: maps all server Python files with key symbols + line numbers
- `meta/android_index.md` — created: maps all Android Kotlin files with key symbols + line numbers
- `CLAUDE.md` — updated "First Read Routing" to use meta/ index first; added "meta/ Index Update Rules" section

---

**Asked:** Add a tracker file (this file) with rules to read last entries and update after every task.
**Changed:**
- `meta/tracker.md` — created (this file)
- `CLAUDE.md` — added tracker read/write rule

---

**Asked:** CLAUDE.md is too large (164 lines/8KB), causing context to grow from 15%→25%. Make it minimal, move rules out, add smart read logic based on tracker.
**Changed:**
- `CLAUDE.md` — rewritten from 164 lines to ~40 lines; now just pointers + decision logic + key facts
- `meta/rules.md` — created: holds all verbose rules (session mgmt, validation, response style, task-specific). Only read when needed.
- `meta/tracker.md` — this entry

---

**Asked:** Why did you read app_context files again? Remove that habit.
**Changed:**
- `meta/rules.md` — removed app_context read routing, replaced with "do NOT read app_context" rule
- No code changes

---

**Asked:** You are not updating the tracker. Why?
**Changed:**
- `meta/tracker.md` — added these two missing entries (this one + above)
- No code changes

---

**Asked:** Add the tracker update rule to CLAUDE.md so it's never forgotten.
**Changed:**
- `CLAUDE.md` — strengthened "Every Task — Do This After" with "NO EXCEPTIONS" and explicit note that all tasks including questions/clarifications require a tracker entry

---

**Asked:** Keep-alive ping.
**Changed:** No code changes.

---

**Date:** 2026-04-27
**Asked:** Investigate Play Console warnings for edge-to-edge compatibility, deprecated window APIs, large-screen orientation/resizability restrictions, and 16 KB native alignment.
**Changed:**
- `app/src/main/AndroidManifest.xml` — removed portrait `screenOrientation` locks from `SplashActivity`, `OnboardingActivity`, `BlackboardActivity`, and `UCropActivity` declarations.
- `app/src/main/res/values/themes.xml` — removed app-level/splash/fullscreen status/navigation bar color and `windowFullscreen` parameters tied to deprecated edge-to-edge/window-display behavior.
- `app/src/main/java/com/aiguruapp/student/SplashActivity.kt` — kept `enableEdgeToEdge()` and removed manual `window.statusBarColor`/`window.navigationBarColor` writes.
- `app/src/main/java/com/aiguruapp/student/utils/SchoolTheme.kt` — removed direct window bar color writes from `applyStatusBar`; now only controls icon appearance with `WindowCompat.getInsetsController`.
- Added `enableEdgeToEdge()` in non-`BaseActivity` screens:
  - `app/src/main/java/com/aiguruapp/student/BlackboardActivity.kt`
  - `app/src/main/java/com/aiguruapp/student/ChatHostActivity.kt`
  - `app/src/main/java/com/aiguruapp/student/TeacherChatHostActivity.kt`
  - `app/src/main/java/com/aiguruapp/student/TeacherQuizValidationActivity.kt`
  - `app/src/main/java/com/aiguruapp/student/OnboardingActivity.kt`
  - `app/src/main/java/com/aiguruapp/student/NcertViewerActivity.kt`
  - `app/src/main/java/com/aiguruapp/student/PageViewerActivity.kt`
  - `app/src/main/java/com/aiguruapp/student/notes/NotesActivity.kt`
  - `app/src/main/java/com/aiguruapp/student/FullscreenImageActivity.kt`
- `meta/android_index.md` — updated with the newly-read Android file symbols/line references.

---

**Date:** 2026-04-27
**Asked:** Validate Play Console fixes — confirm all 4 issues resolved.
**Result:** All confirmed fixed via code inspection:
1. ✅ Edge-to-edge: `enableEdgeToEdge()` in `BaseActivity` + `SplashActivity` + 9 other activities
2. ✅ Deprecated APIs: `SchoolTheme.applyStatusBar()` — `window.statusBarColor`/`navigationBarColor` removed; `FullscreenImageActivity` replaced `setDecorFitsSystemWindows` with `enableEdgeToEdge()`
3. ✅ Orientation/resizability: `screenOrientation="portrait"` removed from all activity declarations in `AndroidManifest.xml`
4. ✅ 16 KB alignment: `useLegacyPackaging = false` in `build.gradle.kts`; UCrop `2.2.9` (16 KB-aligned)
**Files read (validation only, no code changes):**
- `app/src/main/AndroidManifest.xml` — full file, confirmed no screenOrientation locks
- `app/build.gradle.kts` — lines 1–95, confirmed useLegacyPackaging = false
- `app/src/main/java/com/aiguruapp/student/utils/SchoolTheme.kt` — lines 1–135, confirmed applyStatusBar clean
- `app/src/main/java/com/aiguruapp/student/BaseActivity.kt` — lines 1–50, confirmed enableEdgeToEdge()
- `app/src/main/java/com/aiguruapp/student/FullscreenImageActivity.kt` — lines 1–70, confirmed enableEdgeToEdge()
- `app/src/main/java/com/aiguruapp/student/HomeActivity.kt` — lines 1355–1380 (setStatusBarColor = UCrop.Options, not Window)
- `app/src/main/java/com/aiguruapp/student/BlackboardActivity.kt` — lines 3370–3395 (same)
- `app/src/main/java/com/aiguruapp/student/FullChatFragment.kt` — lines 1086–1105 (same)

---

**Asked:** Add rule: on every new session check/create the 4-min keep-alive cron. Also auto-suggest /clear when new question is unrelated to previous tracker context.
**Changed:**
- `CLAUDE.md` — added "Every Session Start" section with: (1) keep-alive cron check/create, (2) tracker read, (3) topic comparison → suggest /clear if unrelated

---

**Asked:** Keep-alive ping.
**Changed:** No code changes.

---

**Date:** 2026-04-26
**Asked:** Push code to GitHub (aiguru repo).
**Changed:**
- All modified files committed and pushed: `CLAUDE.md`, `app/src/main/java/com/aiguruapp/student/bb/BbInteractivePopup.kt`, `app_context/backend_architecture.py`, `app_context/backend_chat_services.py`, `server/app/api/image_search_titles.py`, `server/app/services/enrichment_service.py`, `server/app/services/llm_service.py`, `server/bb_presanitize.json`, `server/response.json`
- `meta/` folder added (tracker.md, rules.md, server_index.md, android_index.md)
- Pushed to origin/main (commit 821bfa6)

---

**Date:** 2026-04-26
**Asked:** Why didn't you follow CLAUDE.md? (session start rules not followed — no cron check, no tracker read, no topic comparison)
**Changed:**
- `meta/tracker.md` — added missing entries (this one + above)
- Keep-alive cron created (job e6e9bdbf, */4 * * * *)

---

**Asked:** Keep-alive ping.
**Changed:** No code changes.

---

**Asked:** Keep-alive ping.
**Changed:** No code changes.

---

**Asked:** Keep-alive ping.
**Changed:** No code changes.

---

**Date:** 2026-04-26
**Asked:** How does the credit system work — calculation and UI display?
**Changed:** No code changes — read-only investigation.
**Files read:**
- `server/app/api/credits.py` — all lines (short file ~170 lines): balance, spend, transactions, topup-packs endpoints
- `server/app/services/user_service.py` — lines 142, 231–238, 311–392, 537–619: credit charge/award/init logic
- `server/app/api/daily_questions.py` — lines 38, 187, 243–299, 365–402: daily question credit award (5 credits/question)
- `app/src/main/java/com/aiguruapp/student/HomeActivity.kt` — lines 307–396, 712–755: credit display (chip + drawer)
- `app/src/main/java/com/aiguruapp/student/daily/DailyQuestionsManager.kt` — lines 22–223: fetchCreditBalance() hits GET /credits/balance
**Key facts:**
- Welcome bonus: 50 credits on signup
- Daily question: 5 credits each
- Auto-charge: 1 credit per 100 tokens, 1 credit per 100 TTS chars
- UI: ⭐ balance chip in HomeActivity + nav drawer; fetched via DailyQuestionsManager.fetchCreditBalance()
- Warning: "chatLeft credits" in HomeActivity is daily plan quota, NOT the credit balance — two separate concepts

---

**Asked:** Keep-alive ping.
**Changed:** No code changes.

---

**Asked:** Keep-alive ping.
**Changed:** No code changes.

---

**Date:** 2026-04-26
**Asked:** Fix LiteLLM 400 error — "Unknown name cache_control: Cannot find field" from Vertex AI.
**Changed:**
- `server/app/services/llm_service.py` lines 446–455 → removed `thinking` and `extra_body.cache_control` from request body (Vertex AI rejects both; they were Anthropic-specific)
- Pushed commit a631a67

---

**Asked:** Keep-alive ping.
**Changed:** No code changes.
Note: llm_service.py was modified externally by user/linter — full file now visible in context. Key update: file starts with client initializations (_init_gemini, _init_groq, _init_bedrock at lines 19-72), image processing utilities at lines 76-139. Meta index needs update.

---

**Date:** 2026-04-26
**Asked:** Switch LiteLLM call to use SDK (litellm.completion) with Gemini thinking_config instead of raw HTTP proxy.
**Changed:**
- `server/app/services/llm_service.py` — `_call_litellm_proxy()` rewritten: raw http.client → litellm.completion() SDK; model format now `gemini/<model_id>`; added `extra_body.thinking_config` (include_thoughts=True, thinking_level=LOW); removed unused `import http.client`
- Pushed commit dd197ae
**Key facts:**
- LiteLLM SDK model string for Gemini: `gemini/<model_id>` (e.g. `gemini/gemini-3.1-flash-lite-preview`)
- thinking_config goes in extra_body (NOT in top-level body like Anthropic's thinking field)
- api_key=settings.GEMINI_API_KEY passed directly to litellm.completion()

---

**Asked:** Keep-alive ping.
**Changed:** No code changes.

---

**Asked:** Keep-alive ping.
**Changed:** No code changes.

---

**Date:** 2026-04-26
**Asked:** Per-user LiteLLM key — store in Firestore users_table/{uid}.litellm_key, cache in memory, use when calling proxy. Auto-create if missing.
**Changed:**
- `server/app/services/llm_service.py`:
  - Added `_user_key_cache: Dict[str, str]` (module-level, uid→key)
  - Added `get_or_create_litellm_key(uid)`: checks cache → Firestore users_table/{uid}.litellm_key → POST /key/generate on proxy → stores in Firestore
  - Rewrote `_call_litellm_proxy`: litellm SDK → httpx.Client to proxy with per-user Bearer key; thinking_config in body
  - `generate_response()`: added `uid` param, passes to _call_litellm_proxy
- `server/app/api/chat.py`:
  - `_classify_intent`, `_bb_plan` signatures: added `uid` param
  - All 4 `generate_response` call sites: pass `uid=_uid`
  - `_bb_plan` and `_classify_intent` call sites: pass `uid=_uid`
- Pushed commit 6a35358
**Key facts:**
- Firestore field: `users_table/{uid}.litellm_key`
- Fallback to master key for guest_user or if key creation fails
- Cache lives for server lifetime (no TTL) — restart clears it

---

**Asked:** Keep-alive ping.
**Changed:** No code changes.

---

**Date:** 2026-04-26
**Asked:** Fix 400 error — thinking_level not supported by gemini-2.5-flash on Vertex AI.
**Changed:**
- `server/app/services/llm_service.py` — removed `thinking_config` block from httpx proxy request body
- Pushed commit 5482e06

---

**Asked:** Keep-alive ping.
**Changed:** No code changes.

---

**Date:** 2026-04-26
**Asked:** Fix 400 error — model routing to Claude/Bedrock instead of Gemini. Use gemini-3.1-flash-lite-preview for all tiers always.
**Changed:**
- `server/app/core/config.py` — POWER_PROVIDER: bedrock→gemini, POWER_MODEL_ID: anthropic.claude-3-5-sonnet → gemini-3.1-flash-lite-preview
- All 3 tiers (power/cheaper/faster) now use gemini-3.1-flash-lite-preview
- Pushed commit 6f69662

---

**Asked:** Keep-alive ping.
**Changed:** No code changes.

---

**Date:** 2026-04-26
**Asked:** Server stuck after YouTube extraction — LLM not responding.
**Root cause:** `gemini-3.1-flash-lite-preview` has thinking enabled by default (117+ reasoning tokens even for "Say hi"). For BB lessons, this consumed all max_tokens on reasoning, returning `content: null` and causing extreme slowness.
**Changed:**
- `server/app/core/config.py` — all 3 tiers switched from `gemini-3.1-flash-lite-preview` → `gemini-2.5-flash-lite`
- `gemini-2.5-flash-lite` confirmed: no reasoning tokens, direct text, fast
- Pushed commit 4c6abc3
**Key facts:**
- gemini-3.1-flash-lite-preview: thinking ON by default → avoid
- gemini-2.5-flash-lite: no thinking → use this
- Available proxy models: gemini-2.5-flash-lite, gemini-2.5-pro, gemini-2.5-flash, gemini-3.1-flash-lite-preview, nvidia/nemotron-nano-12b-v2-vl:free

---

**Asked:** Keep-alive ping.
**Changed:** No code changes.

---

**Asked:** Keep-alive ping.
**Changed:** No code changes.

---

**Date:** 2026-04-26
**Asked:** Still calling Claude model — why? Fix to use gemini-2.5-flash-lite only.
**Root cause:** `.env` overrides config.py defaults. `POWER_MODEL_ID=anthropic.claude-3-5-sonnet-20241022-v2:0` was hardcoded in `.env`.
**Changed:**
- `server/.env` — POWER_PROVIDER→gemini, POWER_MODEL_ID→gemini-2.5-flash-lite, CHEAPER_MODEL_ID→gemini-2.5-flash-lite, FASTER_MODEL_ID→gemini-2.5-flash-lite
- Server restart required for .env changes to take effect

---

**Asked:** Keep-alive ping.
**Changed:** No code changes.

---

**Asked:** Keep-alive ping.
**Changed:** No code changes.

---

**Date:** 2026-04-26
**Asked:** Add LLM call logging — write prompt to named file, append response. One file per call type.
**Changed:**
- `server/app/services/llm_service.py` — added `_log_llm_call()`, `_LLM_LOG_DIR="llm_logs"`, `call_name` param to `_call_litellm_proxy()` and `generate_response()`
- 10 call sites updated with names:
  - `intent_classifier` (chat.py _classify_intent)
  - `bb_planner` (chat.py _bb_plan)
  - `bb_main` / `chat_main` (chat.py main response)
  - `bb_main_retry` / `chat_main_retry` (chat.py image-less retry)
  - `bb_enrichment` (enrichment_service.py)
  - `bb_grading` (bb.py)
  - `bb_svg_builder` (svg_llm_builder.py — calls _call_litellm_proxy directly)
  - `quiz_generate` (quiz_service.py)
  - `quiz_evaluation` (evaluation_service.py)
  - `image_analyze` (analyze_image.py)
  - `image_picker` (image_search_titles.py)
  - `daily_question_gen` (daily_questions.py)
  - `diagram_generate` (diagram_service.py)
- Files written to `server/llm_logs/<name>.txt` (relative to server run dir)
- Pushed commit b9e2e85

---

**Asked:** Keep-alive ping.
**Changed:** No code changes.
Note: enrichment_service.py and image_search_titles.py loaded into context by linter. Updating meta index.

---

**Date:** 2026-04-26
**Asked:** Fix Kotlin compile error "Unresolved reference 'dialog'" at BbInteractivePopup.kt:218.
**Root cause:** All 5 skipButton lambdas referenced `dialog` before it was declared (declared after `buildDialog()` call).
**Changed:**
- `app/src/main/java/com/aiguruapp/student/bb/BbInteractivePopup.kt` — lines 218, 300, 415, 578, 734: replaced `dialog.dismiss()` with `skipDialogN?.dismiss()` using a var holder set immediately after `buildDialog()`
- Pushed commit 1914d94

---

**Asked:** Keep-alive ping.
**Changed:** No code changes.
Note: BbInteractivePopup.kt loaded into context by linter. Updating android meta index with real line numbers.

---

**Asked:** Keep-alive ping.
**Changed:** No code changes.

---

**Date:** 2026-04-26
**Asked:** Why LLM calls after session ends? Add tracking (session_id, call_name) to correlate calls.
**Explanation:** Post-BB calls are enrichment pipeline (diagram enrichment + SVG builder) that run as background futures after main lesson — intentional, not runaway.
**Changed:**
- `server/app/services/llm_service.py` — `_log_llm_call` adds timestamp+session_id header; `_call_litellm_proxy` sends `metadata:{call_name, uid, session_id}` to LiteLLM proxy (stored in PostgreSQL); `session_id` param added to both functions and `generate_response()`
- `server/app/api/chat.py` — `_session_id = f"{uid[:8]}_{uuid8}"` generated per request; threaded through `_bb_plan`, `_classify_intent`, all 4 `generate_response` calls
- Pushed commit 286f535
**Key facts:**
- LiteLLM admin UI now shows call_name + session_id in metadata column
- Log files now show: `=== BB_MAIN | 2026-04-26 18:07:15 | session=KkhkqOQ_a1b2c3d4 ===`
- No OpenTelemetry needed — LiteLLM PostgreSQL + metadata is sufficient

---

**Asked:** Keep-alive ping.
**Changed:** No code changes.

---

**Date:** 2026-04-26
**Asked:** Prompts not showing in LiteLLM logs. Enable context caching for gemini-3.1-flash-lite-preview.
**Changed:**
- `server/litellm/litellm_config.yaml` — added `litellm_settings.store_prompts_in_spend_logs: true` (stores full req/resp in spend_logs table), `cache: true` + `cache_params: {type: redis, host: localhost, port: 6379}`, `general_settings.store_model_in_db: true`
- `server/app/services/llm_service.py` — system message now has `cache_control: {type: ephemeral}` for Gemini implicit caching; request body includes `cache: {no-cache: false}` for LiteLLM cache layer
- **Requires LiteLLM proxy restart** to pick up config changes
- Pushed commit 2423ed5
**Key facts:**
- Prompt logging: needs LiteLLM restart + `store_prompts_in_spend_logs: true`
- Gemini implicit caching kicks in only when system prompt is identical across requests AND ≥1024 tokens (Vertex AI threshold); BB main system prompt is ~400 tokens — may not qualify
- LiteLLM Redis cache is a separate layer that caches identical (model+messages) requests

---

**Asked:** Keep-alive ping.
**Changed:** No code changes.
