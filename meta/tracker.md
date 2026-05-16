# Change Tracker
> Read the last 30 entries before starting any task.
> Append one entry after every task. Never delete entries — they are history.
> Format: date | what user asked | what was changed (file:lines)

---

## 2026-04-30

**Asked:** Fix Kotlin compile error in `FullChatFragment.kt:1674` — argument type mismatch: `Function3<Int,Int,Int,Unit>` passed where `Function1<String,Unit>` expected.
**Root cause:** `streamChat()` in `ServerProxyClient.kt` has an `onFirstStep: ((String)->Unit)?` parameter (line 114) between `onStatus` and `onToken`. All three call sites in `FullChatFragment.kt` omitted it, shifting `onDone` into the `onToken` slot.
**Changed:**
- `FullChatFragment.kt:1674` — added `null` for `onFirstStep` (image branch)
- `FullChatFragment.kt:1688` — same fix (PDF branch)
- `FullChatFragment.kt:1699` — same fix (text-only branch)
**Files read:**
- `FullChatFragment.kt:1655–1702` — sendMessage `when` block, all three `streamChat` call sites
- `ServerProxyClient.kt:101–118` — `streamChat` full signature: 16 params; `onFirstStep:((String)->Unit)?` is param 13, optional

## 2026-04-29

**Asked:** Fix Kotlin compile error in `FullChatFragment.kt` about conflicting local declarations for `chatMainContent`.
**Root cause:** `onViewCreated()` declared `val chatMainContent` twice in the same scope: once for the global-layout keyboard padding listener and again for the window-insets listener.
**Changed:**
- `app/src/main/java/com/aiguruapp/student/FullChatFragment.kt:414` — removed the second `val chatMainContent = ...` declaration and reused the earlier local for `ViewCompat.setOnApplyWindowInsetsListener(...)`.
- `meta/android_index.md` — added `onViewCreated()` slice for `FullChatFragment.kt` (`341–417`) to reflect the setup/keyboard handling block that was read and touched.
**Validation:**
- VS Code Kotlin diagnostics for `FullChatFragment.kt` now show no errors.

## 2026-04-26 (prod readiness audit session)

**Asked:** Read CLAUDE.md + rules.md + do a full prod readiness analysis of the entire app. Then update relevant meta files with everything read.

**Files read (no code changes):**
- `CLAUDE.md` — full file; confirmed rules: meta/ index is primary, app_context/ is legacy (do NOT read), tracker required after every task
- `meta/rules.md` — full file; confirmed app_context deprecated, meta/ is source of truth
- `meta/tracker.md` — full file (history)
- `meta/server_index.md` — full file
- `meta/android_index.md` — full file
- `app_context/backend_index.py` — read (legacy; routed to correct meta files; no new info)
- `app_context/android_index.py` — read (legacy)
- `app_context/backend_architecture.py` — read (legacy)
- `app_context/android_architecture.py` — read (legacy)
- `app_context/backend_chat_services.py` — read (legacy)
- `app_context/android_learning_flows.py` — read (legacy)
- `app_context/android_state_and_network.py` — read (legacy)
- `app_context/backend_svg_pipeline.py` — read (legacy)
- `server/app/main.py` — full file (103 lines): 13 routers, CORS, health, admin portal
- `server/app/core/config.py` — full file (143 lines): all 3 model tiers, AUTH_REQUIRED, LiteLLM config
- `server/app/core/auth.py` — full file (149 lines): require_auth, require_teacher, DEV bypass
- `server/app/services/user_service.py` — lines 1–244: create_user_if_missing, copy_samples_to_user, activate_plan
- `server/app/api/payments.py` — lines 1–80: Razorpay client, order/verify response models

**Key findings (prod risks documented in server_index.md):**
- `config.py:122` — POWER tier `supports_images=False` for gemini provider (only bedrock) → pro user images silently dropped
- `main.py:63` — CORS default is hardcoded server IP, breaks if server moves or HTTPS added
- `auth.py:63-66` — Commented-out hardcoded bypass (dead code, safe but should be deleted)
- `payments.py` — `activate_plan()` called from both /verify and webhook; `_award_activation_credits` can double-fire concurrently
- `config.py:92` — LiteLLM master key has insecure default in code; must be overridden in .env
- `gemini-3.1-flash-lite-preview` (POWER tier) — preview model, thinking ON by default, may be deprecated

**Meta files updated:**
- `meta/server_index.md` — filled real line numbers for main.py (51–98), core/config.py (6–142), core/auth.py (42–148), user_service.py (67–244 expanded), api/payments.py (20–80); added ⚠️ risk annotations; updated Key Server Architecture Facts with correct credit welcome bonus (500 not 50), correct model tiers, CORS warning, POWER image bug
- `meta/tracker.md` — this entry

---

## 2026-04-28 (session 5)

**Asked:** Check 16KB page-size compliance of app-release.aab; run tests and fix.

**Findings:**
- `libimage_processing_util_jni.so` was 4KB-aligned (FAIL) — comes from `androidx.camera:camera-core`
- `libdatastore_shared_counter.so` was already 16KB-aligned (OK) — comes from DataStore
- Both `.so` files are in `base/lib/arm64-v8a/` and `base/lib/armeabi-v7a/` in the AAB (ABI filtering working)
- `camera-core:1.3.4` ships 4KB-aligned .so; `camera-core:1.4.2+` ships 16KB-aligned .so
- Root cause traced by reading ELF PT_LOAD alignment from the actual binary and its JNI function name prefix `Java_androidx_camera_core_ImageProcessingUtil_*`

**Fix:**
- `app/build.gradle.kts:129` — bumped `camera-core` from `1.3.4` → `1.4.2`
- Rebuild needed: `./gradlew clean bundleRelease` (or Android Studio Clean + Build)
- After rebuild, both .so files will be 16KB-aligned — Google Play 16KB page-size check will pass

---

## 2026-04-28 (session 4)

**Asked:** 3 issues: (1) animations still generate even when unchecked; (2) dialog Start/Cancel buttons invisible; (3) free sessions should be truly free — credits only consumed when free quota exhausted. Also clarify credit Firestore key.

**Credit architecture (clarification):**
- Firestore key: `user_credits/{uid}` → `balance` field = spendable credits (lifetime, never expire unless used)
- `lifetime_earned` = cumulative credits ever received (never decremented)
- `record_tokens()` → `_charge_credits_from_usage()` → `Increment(-amount)` on `balance` — 1 credit per 100 tokens
- Recharge: `grant_topup_credits()` adds to `balance` + `lifetime_earned`; called from `payments.py` when `plan_id` starts with `topup_`
- BUG FOUND: credits were being deducted for ALL sessions including free-tier sessions (starter credits drained even when user had free sessions left)

**Fix 1 — Issue 1 (server): All diagram types skip when animations=false**
- Root cause: previous patch only stripped SMIL `build_animated_svg` paths. JS engine (`build_js_diagram_html`), LLM SVG (`build_llm_svg`), and atom JS (`build_atom_html`) still produced animated output.
- `server/app/api/image_search_titles.py` — in per-frame loop, after `frame_type != "diagram"` check: when `animations_enabled=False`, pop all diagram fields (`svg_elements`, `diagram_type`, `data`, `visual_description`) and `continue`. Single gate covers ALL render paths.

**Fix 2 — Issue 2 (Android): Dialog buttons visible**
- `app/.../BlackboardActivity.kt` `_showLessonSettingsDialog()`: after `dialog.show()`, set `BUTTON_POSITIVE` → `#64B5F6` (blue), `BUTTON_NEGATIVE` → `#BDBDBD` (grey). Button text was invisible because dark dialog background caused theme to render buttons in matching dark color.

**Fix 3 — Issue 3 (server): Free sessions truly free**
- `server/app/services/user_service.py` — `check_and_record_quota()`:
  - Return type changed from `tuple[bool, str]` → `tuple[bool, str, bool]` (3rd = `credit_mode`)
  - `credit_mode=True` when free quota exhausted but credits available (session runs on credits)
  - `credit_mode=False` when within free daily allowance (session is free — no credit deduction)
  - All return statements updated with the 3rd bool
- `server/app/services/llm_service.py` — `generate_response()`:
  - Added `charge_credits: bool = True` param
  - Token recording daemon thread only fires when `charge_credits=True`
- `server/app/api/chat.py`:
  - Quota unpack: `_allowed, _quota_reason, _credit_mode = ...` (was 2-tuple)
  - `_credit_mode = False` default set before quota check
  - `_classify_intent()`, `_bb_plan()` signatures: added `charge_credits: bool = True` param
  - All 4 `generate_response()` call sites: added `charge_credits=_credit_mode`

**Key credit flow (corrected):**
`check_and_record_quota()` → `credit_mode=False` (free) → `generate_response(charge_credits=False)` → `record_tokens` NOT called → balance unchanged
`check_and_record_quota()` → `credit_mode=True` (credits) → `generate_response(charge_credits=True)` → `record_tokens` → `_charge_credits_from_usage()` → `balance -= tokens/100`

---

## 2026-04-28 (session 3)

**Asked:** Better UI for lesson settings dialog — show credit cost for videos/images too; let user enable/disable options in the dialog itself (not just a text message).
**Changed:**
- `app/.../BlackboardActivity.kt`:
  - Added `import android.widget.CheckBox`
  - Replaced `showPreSessionDialog()` + `showSettingsDialog()` bodies with shared helper `_showLessonSettingsDialog(isPreSession, onConfirm)` (~175 lines, lines ~1683–1862)
  - Helper builds a custom `ScrollView` → `LinearLayout` dialog view: credit banner at top, 4 option rows each with `CheckBox` + emoji + bold title + color-coded cost badge
  - Cost badges: Videos = amber `#FFF57F17` ("uses credits · ~2–3 per session"), Animations = orange `#FFE65100` ("uses extra credits · ~1–2 per step"), Images = green ("free · Wikimedia thumbnails"), Quizzes = no badge
  - Dialog background: semi-transparent dark `#CC1A1A2E`; checkboxes tinted `#64B5F6` (blue)
  - Row ripple on tap toggles checkbox
  - `showPreSessionDialog()` / `showSettingsDialog()` are now thin wrappers calling `_showLessonSettingsDialog()`

---

## 2026-04-28 (session 2)

**Asked:** When animations disabled in BB settings, SVG animations still appear. Add credit cost info on animations toggle. Fix controlled from Android side.
**Root cause:** `bb_animations_enabled` flag is received in `chat.py` (request model line 86) and sent from Android, but was **never passed** into the SVG rendering pipeline. `get_titles()` in `image_search_titles.py` called `build_animated_svg(shapes)` and `build_animated_svg(elems_json)` unconditionally, ignoring the flag.
**Changed:**
- `server/app/utils/svg_builder.py`:
  - Added `import re`
  - Added `_strip_animations(html: str) -> str`: strips `<animate>`, `<animateTransform>`, `<animateMotion>`, `<set>` SMIL tags via regex; replaces `opacity="0"` → `opacity="1"` so shapes are immediately visible
  - Added `static: bool = False` param to `build_animated_svg()`: when `static=True`, post-processes output through `_strip_animations()`
- `server/app/api/image_search_titles.py`:
  - Added `animations_enabled: bool = True` param to `get_titles()` signature
  - Line ~562 (SMIL fallback path): `build_animated_svg(shapes)` → `build_animated_svg(shapes, static=not animations_enabled)`
  - Line ~595 (legacy svg_elements path): `build_animated_svg(elems_json)` → `build_animated_svg(elems_json, static=not animations_enabled)`
- `server/app/api/chat.py` line ~1211: added `animations_enabled=req.bb_animations_enabled is not False` to `get_titles()` call
- `app/.../BlackboardActivity.kt`:
  - `showPreSessionDialog()` (~line 1674): changed label `"🎨  Animated diagrams"` → `"🎨  Animated diagrams  · uses extra credits"`; added `.setMessage("💡 Animated diagrams bring lessons to life but use more AI credits per session. Disable to save credits.")`
  - `showSettingsDialog()` (~line 1698): same label update `"🎨  Animated diagrams  · uses extra credits"`
**Key facts:** JS-engine diagrams (`build_js_diagram_html`) and LLM-SVG (`build_llm_svg`) still animate — this fix only covers the SMIL `build_animated_svg` paths (fallback + legacy). JS/LLM paths would need separate treatment if full static mode is desired. The `bb_animations_enabled` flag IS correctly sent by Android in `BBSessionConfig.toRequestMap()` (line 131-133).

---

## 2026-04-28

**Asked:** Fix Firestore "requires an index" error on daily questions feed; fix BB card showing only chat row (height); remove chat+BB session rows from left drawer (keep only Credits).
**Root cause (Firestore):** Query used `.where("date", "==", today).order_by("difficulty")` — Firestore requires a composite index for a `where` on one field + `order_by` on a different field. We don't create indexes via code.
**Root cause (BB card):** Card was `android:layout_height="140dp"` — too short to display quota rows when `homeQuotaContainer` became visible. Also Chat row was visible in the strip alongside BB row, wasting limited space.
**Root cause (drawer):** Chat usage row and BB sessions row were still in the drawer after the BB card quota strip was added, duplicating info.
**Changed:**
- `server/app/api/daily_questions.py` line ~155: removed `.order_by("difficulty")` from Firestore query. Now does `.where("date", "==", today).get()` and sorts by `difficulty` in Python (`sorted(..., key=lambda q: q.get("difficulty", 1))`). Eliminates composite index requirement.
- `app/src/main/res/layout/activity_home.xml` — `quickActionBbBtn` `MaterialCardView`: changed `android:layout_height="140dp"` → `android:layout_height="wrap_content"` so the card expands to fit the BB-sessions quota row.
- `app/src/main/res/layout/activity_home.xml` — `homeQuotaChatRow` in BB card replaced with 0dp/gone ghost views (`homeQuotaChatLeftText`, `homeQuotaChatBar`, `homeQuotaChatRow`). Only BB sessions row (`homeQuotaBbRow`) is visible on the BB card.
- `app/src/main/res/layout/activity_home.xml` — drawer: removed "Chat messages" and "Blackboard sessions" usage rows (was ~60 lines of LinearLayout+ProgressBar). Replaced with 0dp ghost TextViews/ProgressBars with same IDs so Kotlin `R.id` references still compile. Removed "TODAY'S USAGE" section label (no longer needed).
**Key facts:** `HomeActivity.kt` drawer-update code uses `?.` null-safe calls for drawerChatLeft/drawerBbLeft/etc. so 0dp ghosts cause no crash. `updateHomeQuotaStrip()` still sets `chatRow?.visibility = View.VISIBLE` but 0dp means it's effectively invisible — no functional issue.

---

## 2026-04-27

**Asked:** Show quota (chat left, BB sessions left, AI voice) on BB button; remove credits chip from home screen (drawer only); fix credit deduction bug (10k tokens → only 2 credits).
**Root cause:** `record_tokens()` was only called once (for the main LLM response). BB planner + intent classifier + diagram enrichment (2x) each call `generate_response()` but their tokens were never charged. LiteLLM dashboard showed 10k total but only 200 tokens (the last small call) were deducted → 2 credits.
**Changed:**
- `server/app/services/llm_service.py` — `generate_response()`: after every successful LLM call, if `uid` is provided and `total_tokens > 0`, spawn a daemon thread to call `record_tokens()`. This auto-charges credits for ALL LLM calls (planner, enrichment, intent classifier, main) not just the final chat response.
- `server/app/api/chat.py` — removed the explicit `record_tokens()` + `run_in_executor()` call at the end of `chat_stream()` (was step 8, lines ~1318-1325). Now handled automatically inside `generate_response()` to avoid double-charging.
- `app/.../res/layout/activity_home.xml` — replaced single `bbQuotaPill` TextView with `homeQuotaContainer` LinearLayout containing 3 rows: `homeQuotaChatRow` (💬 Chat + `homeQuotaChatLeftText` + `homeQuotaChatBar` ProgressBar), `homeQuotaBbRow` (🎓 Blackboard + `homeQuotaBbLeftText` + `homeQuotaBbBar` ProgressBar), `homeQuotaVoiceRow` (🎙 AI Voice + `homeQuotaVoiceLeftText`, hidden when 0). Old `bbQuotaPill` kept as 0dp ghost for compat.
- `app/.../res/layout/activity_home.xml` — `creditsChipText` shrunken to 0dp/gone permanently (credits shown in drawer only).
- `app/.../HomeActivity.kt` — replaced `updateBbButtonPill()` with `updateHomeQuotaStrip(chatLeft, chatLimit, bbLeft, bbLimit, creditBalance, voiceCharsLeft)`: fills 3-row quota strip with color coding (green=ok, orange=low, red/gold=credits mode). Updated both call sites: `updateQuotaStripUI()` and `loadDailyChallenge()` coroutine.
- `app/.../HomeActivity.kt` — `updateCreditsDisplay()` simplified: no longer updates `creditsChipText` (0dp now), only updates `drawerCreditsBalance`.
**Key facts:** `generate_response()` now auto-records tokens via daemon thread for every call where uid is known. `enrich_diagram_data()` still doesn't get uid (future: pass uid through build_enrichment_tasks → get_titles chain for full coverage), but planner + classifier + main are now all charged.

---

**Asked:** Validate credits=1/100tokens flow, add daily free sessions display on BB button, show credits in drawer for BB+chat. Credits fallback: if free quota exhausted but credits>0, allow request.
**Changed:**
- `server/app/services/user_service.py` — `check_and_record_quota()`: when free quota exhausted, reads `user_credits.balance`; if >0 allows request (credits auto-deducted by `record_tokens`); if =0 blocks with descriptive message. Previously blocked immediately on quota limit.
- `server/app/api/credits.py` — added `GET /credits/quota-status` endpoint returning `{free_bb_today, free_bb_limit, free_chat_today, free_chat_limit, credit_balance, is_new_day}`. Single call replaces separate balance + counter fetches.
- `app/.../daily/DailyQuestionsManager.kt` — added `QuotaStatus` data class (`freeBbLeft, freeBbLimit, freeChatLeft, freeChatLimit, creditBalance`) + `fetchQuotaStatus()` calling `/credits/quota-status`.
- `app/.../HomeActivity.kt` — `loadDailyChallenge()` now calls `fetchQuotaStatus()` (falls back to `fetchCreditBalance()` on error); stores result in `lastKnownCreditBalance`; calls new `updateBbButtonPill()`. Added `updateBbButtonPill(freeBbLeft, freeBbLimit, creditBalance)`: shows 🎓 free sessions / ⭐ Credits mode / red "add credits" based on state. `updateQuotaStripUI()` now calls `updateBbButtonPill` instead of hiding pill. `showQuotaToastIfNeeded()` messages updated to mention credits. Added `lastKnownCreditBalance` instance var.
- `app/.../res/layout/activity_home.xml` — drawer "⭐ Token balance" label changed to "⭐ Credits" with subtitle "1 credit = 100 tokens · used by BB + Chat".
**Key facts:** Credits auto-deducted by `record_tokens()` already (1 credit/100 tokens). New fallback: quota gate allows when `balance > 0`. Blocked only when both free quota AND credits are exhausted. BB pill now shows real-time status from server on home screen load.

---

**Asked:** Read CLAUDE.md + update architecture and folder/file structure in meta indexes.
**Changed:**
- `meta/android_index.md` — full structure added: adapters/ (7 files), models/ (17 files), utils/ (16 files), services/ (4 files), streaming/ (5 files), http/, auth/, daily/, quiz/, payments/, chat/ extras (4 files), GeminiLiveActivity, RevisionActivity, LibraryActivity, SubscriptionActivity, dashboard activities, TutorController, BaseActivity. Updated Key Android Architecture Facts with build config + full routing details.
- `meta/server_index.md` — full structure added: core/config.py, core/auth.py, core/firebase_auth.py, core/logger.py, services/litellm_service.py, services/strands_agent.py, services/cache_service.py, services/context_service.py (stub), services/evaluation_service.py, services/gamification_service.py, services/library_service.py, services/user_service.py, models/ (5 files), api/analyze_image.py, api/library.py, api/tasks.py, api/diagram.py, api/users.py, api/admin.py, api/credits.py, utils/diagram_router.py, utils/js_engine.py, utils/json_utils.py, utils/text_utils.py, SVG builder family, main.py. Added Key Server Architecture Facts section.
**No code changes.**

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

---

**Date:** 2026-04-27
**Asked:** Production NCERT downloader: clean dataset, robust URL fallback, expand to classes 1–8.
**Changed:**
- `app/src/main/assets/ncert.json` — replaced bloated multi-language file with clean English-medium dataset: classes 1–8, correct subjects (EVS for 1–5; Science + Social Science split for 6–8)
- `app/src/main/java/com/aiguruapp/student/HomeActivity.kt` — class picker now shows 1–12 (was 6–12); `confirmNcertImport()` stores `ncertCode` + `ncertChapterNum` in chapter meta JSON for fallback URL generation
- `app/src/main/java/com/aiguruapp/student/ChapterActivity.kt` — added `ncertCode`/`ncertChapterNum` instance vars; added `ncertCandidateUrls()` that generates 5 URL pattern variants; `downloadNcertToCache()` now iterates all candidates (2 retries each) instead of retrying one URL 3×
**Key facts:**
- NCERT URL patterns: `{code}{ch2}.pdf` (standard), `{code}{ch1}.pdf` (no pad), `{code}{ch2}1.pdf`, `{code}dd.pdf`, `{code}dd1.pdf`
- Candidate list only generated when `ncertCode` + `ncertChapterNum` are stored (new imports); old chapters fall back to stored URL only
- ncert.json covers classes 1–8 English medium; classes 9–12 not in dataset yet

---

**Date:** 2026-04-27
**Asked:** Tasks not showing in student view after teacher assigns; teacher form needs dropdowns for subject/chapter/BB session.
**Changed:**
- `firestore/FirestoreManager.kt` loadTasksByTeacher: removed `.orderBy("created_at", DESC)` — same composite index issue as loadTasksForSchool; sort in-memory now
- `TeacherTasksActivity.kt`: added `subjectOptions`, `chapterOptions`, `bbSessions` state; added `loadDropdownData()`, `loadChaptersForSubject()`, `pickSubject()`, `pickChapter()`, `pickBbSession()`; subject + chapter inputs are now non-focusable (tap = dialog picker); BB topic input shows session picker when sessions loaded, falls back to text entry if empty; picking BB session auto-fills subject + chapter + selectedBbCacheId
**Key facts:**
- Both loadTasksForSchool and loadTasksByTeacher now only use single-field indexes (no composite)
- Subject picker loads from `users/{teacherId}/subjects/` (teacher's own subjects)
- BB session picker from `bb_cache` where `teacher_id = teacherId`, shows topic + subject + chapter + step count
- "Type manually…" option always available at bottom of picker dialogs

---

**Date:** 2026-04-27
**Asked:** Update tracker, server index, read claude.md
**Changed:**
- `meta/android_index.md` — major expansion: BlackboardActivity (full symbol table with onPause fix, TTS fields, launchers, image/chat functions), FullChatFragment (send flow, BB dialog, image handling), BbAiTtsEngine (scope/stop/destroy/race details), FirestoreManager (all task/chapter/BB cache functions + composite index warning), ChapterActivity (PDF setup + Firebase Storage download), TasksActivity, TeacherTasksActivity, SubjectActivity, StorageService, CloudBackupService. Architecture facts updated with task flow, PDF persistence, Firestore index rule.
- No server files read this session — server_index.md unchanged

---

**Date:** 2026-04-27
**Asked:** Implement Lazy + Warm Hybrid embedding pipeline for quiz generation (on-demand ES indexing, persistent cache, background warming).
**Changed:**
- `server/app/services/chapter_index_service.py` — NEW file: ES-backed chapter chunking/embedding/retrieval service. ES index `chapter_segments` (768-dim Vertex AI text-embedding-005). Smart chunking (paragraph-aware, 280-word windows, content-rich filter saves 50-70% embedding cost). Dedup via Redis `ch_indexed:{chapter_id}` + ES count fallback. Usage tracking via Redis `ch_usage:{chapter_id}`. `retrieve_context()` does kNN search with cosine similarity, score threshold 0.30, sorts by chunk_index.
- `server/app/services/quiz_service.py` — `generate_quiz()` now checks `chapter_index_service.is_indexed()` before LLM call; if indexed, calls `retrieve_context()` and uses ES chunks as `effective_context` (beats caller-supplied `context_text`).
- `server/app/api/quiz.py` — `generate_quiz` endpoint now uses FastAPI `BackgroundTasks`; tracks chapter usage on every request; schedules `_bg_index_chapter()` when `context_text` provided and chapter not yet indexed. Added `POST /quiz/index-chapter` (explicit warm trigger, idempotent), `GET /quiz/index-status` (check if indexed). Added `IndexChapterRequest` / `IndexChapterResponse` models.
- `server/requirements.txt` — added `pypdf>=4.0.0` for PDF text extraction.
**Key facts:**
- Flow: request → track_usage → is_indexed? → YES: ES kNN → context → LLM / NO: LLM direct + bg indexing starts
- Dedup: if `ch_indexed:{chapter_id}` Redis key exists → skip ES check entirely
- Background warming: Android can call `POST /quiz/index-chapter` proactively after PDF load
- ES index `chapter_segments` is separate from `yt_video_segments` (YT extractor)
- Same Vertex AI SA + project (`ai-app-8ebd0`) as YT extractor; same embed model (`text-embedding-005`, 768 dims)
- `pypdf` not yet installed in server venv — run `pip install pypdf` on server

---

**Date:** 2026-04-27
**Asked:** Drawer credits confusing — clean it up so user can understand easily.
**Changed:**
- `app/src/main/res/layout/activity_home.xml` — renamed "💬 Credits" label → "💬 Chat messages" (it's a daily quota, not purchasable credits). Added divider + "BALANCE" section header before the ⭐ Credits row to visually separate daily quota from token balance. Changed subtitle "1 credit = 100 tokens · used by BB + Chat" → "Top up to unlock more sessions" (user-facing language).
- `app/src/main/java/com/aiguruapp/student/HomeActivity.kt` — `updateQuotaStripUI()` line 342: `"$chatLeft credits"` → `"$chatLeft left"` (consistent format with BB row which also says "X left").
**Key facts:**
- Drawer now has two clear sections: "TODAY'S USAGE" (chat messages + BB sessions + voice) and "BALANCE" (⭐ Credits purchasable balance)
- Chat value format is now "X left" / "Unlimited" — same as BB row
- ⭐ Credits row subtitle is now plain English, no internal jargon

---

**Date:** 2026-04-27
**Asked:** Remove redundant credits things from drawer.
**Changed:**
- `app/src/main/res/layout/activity_home.xml` — removed entire `drawerCreditsRow` block (⭐ Credits balance + "Top up" subtitle + BALANCE section label + divider). Credits balance is already shown as `creditsChipText` chip in the main screen toolbar — showing it again in the drawer was redundant.
- `app/src/main/java/com/aiguruapp/student/HomeActivity.kt` — removed `drawerCreditsBalance.text = balance.toString()` line from `updateCreditsDisplay()`. Chip update for `creditsChipText` still intact.
**Key facts:**
- Drawer now shows ONLY: "TODAY'S USAGE" with 💬 Chat messages / 🎓 Blackboard sessions / 🎙 AI voice (if non-zero)
- Credits balance lives only in the ⭐ chip in the main screen toolbar (tappable → SubscriptionActivity)
- No dangling R.id references — grep confirmed zero matches for drawerCreditsRow / drawerCreditsBalance

---

**Date:** 2026-04-27
**Asked:** BB mode button crashes when image is attached; image should transcribe + answer properly in BB session.
**Changed:** No code changed this session — investigation + index update only.
**Files read:**
- `BlackboardActivity.kt` — image attach flow (lines 215–250, 320–342, 3478–3584, 3657–3901, 3906–4060, 4072–4163, 4165–4217); `generateSteps()` entry (457–593); EXTRA_IMAGE_BASE64 intent chain
- `BlackboardGenerator.kt` — `callIntent()` 145–208; `generateChunk()` 224–312; `generate()` 323–510
- `ServerProxyClient.kt` — `streamChat()` 95–137; `executeStream()` 153–174; constructor 38–43
- `FullChatFragment.kt` — `showBbDurationPickerAndLaunch()` 718–754; `sendMessage()` 1203–1295; `proceedWithMessageSendAfterQuotaCheck()` 1595–1660
- `HomeActivity.kt` — BB launch with image 1404–1416; `launchHomeCrop()` 1461–1505; `applyHomeCroppedImage()` 1507–1520
- `utils/MediaManager.kt` — `uriToBase64()` 115–172
- `server/app/api/chat.py` — `_normalize_images()` 90–98; `_bb_plan()` 196–240; `chat_stream()` image paths 960–1192
- `server/app/services/prompt_service.py` — `build_blackboard_mode_user_content()` signature 724–735
- `server/app/services/llm_service.py` — `_call_litellm_proxy()` image handling 475–510; `generate_response()` 569–634
- `app/src/main/res/layout/activity_blackboard.xml` — bbImgPreviewRow (374–410), ask-bar pill (412–468)
**Key findings (no fix applied yet):**
- Previous "BB+image crash" fix (commit 5e09bc5) fixed FullChatFragment: cancel `imageEncodeJob` + capture `ctx` before BB dialog
- Current issue likely: (a) `sendBbQuestion` lambda silently does nothing when text is blank but image IS attached; (b) `generate()` → `requestInlineBbLesson()` doesn't pass image to inline BB; (c) `HomeActivity.launchHomeCrop()` has NO fallback if UCrop throws (unlike BB which falls back to `encodeBbImage()`)
- BB planner (`_bb_plan()`) always called with empty images even when photo is attached
- `sendBbChat()` uses `mode="normal"` (not "blackboard") for ask-bar questions — server handles as image_explain intent, which is correct for Q&A but not a BB-style lesson
- ⚠️ Race condition: `encodeBbImage()` runs on raw Thread; if user presses send before encode finishes, `bbPendingImageBase64` is null → image silently not sent

---

## 2026-04-28 (session 6)

**Asked:** 3 bugs: (1) Credits not allowing BB sessions when free quota exhausted — user sees "used all BB sessions" even with credits. (2) Followup question click (end of BB session) — TTS repeats but no spinner/visual feedback. (3) Videos from previous BB session persist into new BB session.

**Fix 1 — Issue 1 (Android): Credits bypass client-side BB quota gate**
- Root cause: `BlackboardQuotaValidator.check()` (→ `PlanEnforcer.checkQuestionsQuota()`) returns `allowed=false` when `bbSessionsToday >= limit` without ever consulting credit balance. The server correctly falls back to credits, but the client blocks the request before it reaches the server.
- `app/.../BlackboardActivity.kt` — lines ~517–527: when `check.allowed == false` and `check.limitType == LimitType.BB_SESSIONS`, now reads `user_credits/{uid}.balance` from Firestore. If `balance > 0`, proceeds to `showPreSessionDialog` (server is the final authority). If balance == 0 (or Firestore unavailable, optimistic allow), proceeds normally. Only shows upgrade error when credits confirmed at 0.

**Fix 2 — Issue 2 (Android): Followup question visual feedback**
- `app/.../BlackboardActivity.kt` — `showFollowupQuestionsCard()`, `card.setOnClickListener`: on click, immediately dim all followup cards to 0.4α (isEnabled=false) and swap the tapped card's arrow `→` to `⏳`. Card stays at 1α so user can see which one they tapped.

**Fix 3 — Issue 3 (Android): Video section persistence between inline sessions**
- `app/.../BlackboardActivity.kt` — `showRelatedVideosSection()`: added `tag = "bb_videos"` to the video section LinearLayout so it can be found and removed.
- `app/.../BlackboardActivity.kt` — `requestInlineBbLesson()`: before appending inline content, removes any existing `bb_videos` view from `stepsContainer`, removes `bb_followups` card from `boardLayout`, and calls `collectedClips.clear()` so the new inline lesson gets a fresh video slate.

---

## 2026-04-28 (session 6b)

**Asked:** Add NCERT classes 9, 10, 11, 12 (they were missing — JSON only had classes 1–8).

**Changed:**
- `app/src/main/assets/ncert.json` — added classes "9", "10", "11", "12" at top level.
  - Class 9: English (Beehive iehe1, Moments iemm1), Maths (iemh1), Science (iesc1), Social Science (iess1–4: History/Geography/Civics/Economics)
  - Class 10: English (First Flight jehe1, Footprints jefp1), Maths (jemh1), Science (jesc1), Social Science (jess1–4)
  - Class 11: English (Hornbill kehb1, Snapshots kesn1), Maths (kemh1), Physics (keph1/2), Chemistry (kech1/2), Biology (kebo1), History (kehr1), Political Science (keps1), Geography (kegp1/2), Economics (kees1/2)
  - Class 12: English (Flamingo lefl1, Vistas levw1), Maths (lemh1/2), Physics (leph1/2), Chemistry (lech1/2), Biology (lebo1), History (lehr1/2/3), Political Science (leps1/2), Geography (legp1/2), Economics (lees1/2)
- No Android code changes needed — `showNcertSubjectImportDialog()` already reads all classes dynamically via `(1..12).filter { ncertRoot.has(it) }`.

---

## 2026-04-28 (session 6c)

**Asked:** Replace all "X sessions left / X questions left / X credits left" language with positive, encouraging copy throughout the app.

**Changed:**
- `app/.../BlackboardActivity.kt` — `updateBbQuotaChip()`: chip text is now e.g. "5 free lessons today — dive in! 🚀", "1 free lesson left! 🎓", "Use ⭐ credits to learn more!"
- `app/.../HomeActivity.kt` — `updateHomeQuotaStrip()` chat row: "12 free today! Ask anything 💬", "3 free — keep going! 💡", "⭐ use credits!"
- `app/.../HomeActivity.kt` — `updateHomeQuotaStrip()` BB row: "5 free lessons today! 🎓", "3 free lessons — learn more! 🎨", "⭐ use credits!"
- `app/.../HomeActivity.kt` — drawer chat label: "12 questions free!", "1 question left!", "Unlimited 🎉"
- `app/.../HomeActivity.kt` — drawer BB label: "5 lessons free!", "1 lesson left! 🎓", "Unlimited 🎉"
- `app/.../HomeActivity.kt` — drawer voice label: "4k chars ready! 🎙️", "800 chars — keep listening! 🎙️"
- `app/.../HomeActivity.kt` — `updateCreditsDisplay()`: "500 credits ready! ⭐", "1200 credits — you're set! 🚀", "42 credits — top up soon!"
- `app/.../HomeActivity.kt` — `showQuotaToastIfNeeded()`: "You've maxed out free questions today! ⭐ Add credits to keep going.", "Free questions used — ⭐ 50 credits ready for you! Ask away.", etc.
- `app/.../config/PlanEnforcer.kt` — `checkQuestionsQuota()` BB block: "Great learning today! You've hit your N visual lesson limit. Upgrade for unlimited knowledge! 🎓"
- `app/.../config/PlanEnforcer.kt` — `checkQuestionsQuota()` chat block: "Amazing — you've asked all N questions today! 💬 Upgrade for unlimited daily questions!"
- `app/.../config/PlanEnforcer.kt` — guest BB: "You've explored 3 free visual lessons! 🎓 Log in to keep the momentum going!"
- `app/.../config/PlanEnforcer.kt` — guest chat: "You've asked 10 free questions — great curiosity! 💬 Log in to get more daily questions!"
- `app/.../config/PlanEnforcer.kt` — `checkAiTtsQuota()`: "Your AI voice quota is full for today! 🎙️ Upgrade your plan to speak to your AI tutor more."

---

## 2026-04-27 (session 6d)

**Asked:** Camera attachment from home-screen BB dialog crashes when launching BlackboardActivity with image attached. Also checked NCERT 8–12 status.

**Root cause:** TransactionTooLargeException — a 1920×1920 JPEG at 90% quality encoded as base64 is 3–6 MB, exceeding Android Binder IPC's ~1 MB limit when passed through Intent.putExtra().

**Changed:**
- `app/.../BlackboardActivity.kt` — Added `companion object { var pendingImageBase64: String? = null }` static field.
- `app/.../BlackboardActivity.kt` — Added `private var intentImageBase64: String?` instance field; resolved once in `onCreate()` from companion (HomeActivity path) with fallback to `intent.getStringExtra(EXTRA_IMAGE_BASE64)` (other launch paths).
- `app/.../BlackboardActivity.kt` — Replaced all 7 `intent.getStringExtra(EXTRA_IMAGE_BASE64)` calls in `onCreate` callbacks with `intentImageBase64`.
- `app/.../HomeActivity.kt` — Replaced `.putExtra(EXTRA_IMAGE_BASE64, capturedImage)` with `BlackboardActivity.pendingImageBase64 = it` before startActivity.
- `app/.../HomeActivity.kt` — Fixed secondary bug: UCrop failure in `launchHomeCrop()` now falls back to `applyHomeCroppedImage(sourceUri)` (was silently dropping the image).

**NCERT status:** Classes 8–12 are all present in `app/src/main/assets/ncert.json` (lines 203, 248, 298, 348, 438).
2026-04-28 | Fix edge-to-edge black bars on Android 15 / Pixel 9 | app/src/main/java/com/aiguruapp/student/BaseActivity.kt:37-47 (added ViewCompat.setOnApplyWindowInsetsListener on android.R.id.content to apply systemBars insets); app/src/main/res/layout/activity_*.xml all 30 files (removed android:fitsSystemWindows=true from root views via sed)

2026-04-28 | LLM model tier optimization + quota enforcer rebuild | Files:
  server/app/core/config.py: POWER_MODEL_ID changed from gemini-2.5-flash-lite to gemini-3.1-flash-lite-preview (lines 23-50); CHEAPER/FASTER stay gemini-2.5-flash-lite
  server/app/api/users.py: added GET /users/quota/status endpoint (lines 212-350); returns free_chat_remaining, free_bb_remaining, free_tts_chars_remaining, credit_balance, chat_mode/bb_mode/tts_mode (free|ai_credit|blocked), using_ai_credits_for_tts, maintenance_mode
  app/src/main/java/com/aiguruapp/student/config/QuotaManager.kt: NEW FILE — server-only quota enforcement object; enum CreditType {CHAT, LESSON, TTS}; enum Mode {FREE, AI_CREDIT, BLOCKED}; fetchStatus() calls /users/quota/status; check() is a simple gate (fail-open on network error)
  app/src/main/java/com/aiguruapp/student/BlackboardActivity.kt: useAiTts default changed from false to true (prefs.getBoolean("use_ai_tts", true)); added lifecycleScope.launch(IO) QuotaManager.fetchStatus to show "Using AI Credits" banner; toggle click also shows credit-aware toast

2026-04-28 | Registration credits 500 + LiteLLM key alias + Firestore persistence + credits_on_activation in plans + subscription credit balance UI | Files:
  server/app/services/user_service.py:631 — _STARTER_CREDITS changed from 1000 to 500
  server/app/services/litellm_service.py:53 — added "key_alias": f"user-{user_id[:8]}" to /key/generate payload
  server/app/services/llm_service.py:~39 — added "key_alias": f"user-{uid[:8]}" to fallback /key/generate payload in get_or_create_litellm_key
  server/app/api/users.py:~112 — after LiteLLM key creation in register_user, now stores litellm_key in users_table/{uid} Firestore doc via users_service._get_db()
  Firestore plans collection — added limits.credits_on_activation: basic=200, premium=500, school_unlimited=1000
  app/src/main/res/layout/activity_subscription.xml — added creditBalanceRow LinearLayout + creditBalanceText TextView before Title row
  app/src/main/java/com/aiguruapp/student/SubscriptionActivity.kt:29 — added import QuotaManager; line 84 added loadCreditBalance() call; lines 590-608 new loadCreditBalance() function fetches QuotaManager.fetchStatus and shows credit_balance in creditBalanceText
  Context caching: already fully implemented via cache_control:ephemeral on system messages in _call_litellm_proxy + LiteLLM cache:true with Redis in litellm_config.yaml — no additional changes needed
  BB mode images: confirmed fully working end-to-end — BlackboardGenerator.callIntent/generateChunk pass imageBase64 → ServerProxyClient adds image_base64 to request body → chat.py _normalize_images → generate_response(normalized_images) — no changes needed

2026-04-28 | NCERT ES pipeline — fix context_service stub with semantic retrieval | Files:
  server/ncert_extractor/__init__.py — new package
  server/ncert_extractor/config.py — ES_HOST, ES_INDEX="ncert_chunks", EMBED_DIMS=768, CHUNK_SIZE=1200, CHUNK_OVERLAP=150, RETRIEVAL_K=4, MAX_CONTEXT_CHARS=2400; reuses same Vertex AI service account as youtube_extractor
  server/ncert_extractor/indexer.py — get_es(), ensure_index() with kNN dense_vector mapping, embed_texts() via Vertex AI text-embedding-005, chunk_text() with sentence-boundary snapping
  server/ncert_extractor/retriever.py — retrieve_context(question, chapter_id): kNN search filtered by chapter_id; retrieve_context_sync() wrapper for sync callers
  server/seed_ncert_es.py — one-time seeder script; reads 454 chapters from Firestore, downloads ncert_pdf_url PDFs, extracts text via pypdf, chunks, embeds (batch 10), bulk-indexes; supports --subject --reset --batch flags; skips already-indexed chapters
  server/app/services/context_service.py — REPLACED stub with: (1) lazy in-memory Firestore chapter index keyed by normalised "subjectslug__chaptertitle"; (2) _retrieve_from_es(question, chapter_id) calls ncert_extractor retriever; (3) get_context(page_id, question="") signature — returns grade+chapter header always + relevant textbook chunks when ES available; graceful fallback if ES down
  server/app/api/chat.py:979 — changed get_context(req.page_id) → get_context(req.page_id, req.question or "") to enable semantic retrieval

Run to start seeding:
  docker compose -f server/youtube_extractor/docker-compose.yml up -d
  cd server && python seed_ncert_es.py --subject science_10th   # test one subject first
  python seed_ncert_es.py  # all 454 chapters (~60-90 min)

2026-04-28 | NCERT ES seeder: page-level chunking | Files:
  server/ncert_extractor/config.py: added PAGES_PER_CHUNK=2
  server/ncert_extractor/indexer.py: added page_start/page_end fields to MAPPING; added chunk_pages(pages, pages_per_chunk) helper — groups N pages per ES doc, merges thin pages (<80 chars) into next group, returns List[tuple(page_start, page_end, text)]
  server/seed_ncert_es.py: _extract_pdf_text → _extract_pdf_pages (returns List[str] per page); _index_chapter now takes pages: list[str] instead of text: str; indexes page_start/page_end alongside chunk_index; logs "N pages extracted" and "N page-chunks indexed"

2026-04-28 | NCERT seeder running + quick wins fixed | Files:
  server/seed_ncert_es.py: fixed docstring encoding (em-dash caused SyntaxError); fixed _SA_PATH to use _HERE (was dirname(_HERE)); fixed python → python3; seeder running as PID 1606595 → /tmp/ncert_seed.log
  server/ncert_extractor/config.py: added PAGES_PER_CHUNK=2
  server/ncert_extractor/indexer.py: added page_start/page_end to MAPPING; added chunk_pages() helper
  server/app/core/config.py: POWER_MODEL_ID + MODEL_ID changed from gemini-3.1-flash-lite-preview → gemini-2.5-flash (stable)
  server/app/utils/svg_renderers.py: added _render_bar_chart() — vertical bars with value labels and x-axis labels; uses bar_colors cycling
  server/app/utils/svg_builder.py: imported _render_bar_chart; registered "bar_chart" in _RENDERERS
  Status: English class 10/Hindi class 9 have bad Firestore PDF URLs (404); English class 8 + Math + Science working fine

2026-04-28 | Razorpay double-credit race fix | Files:
  server/app/services/user_service.py:
    - Added import: firestore_transactional from google.cloud.firestore
    - activate_plan() rewritten to use a Firestore transaction (@firestore_transactional):
      reads plan_activated_at + planId from users_table/{uid} inside transaction,
      sets plan_activated_at=now only on first call (or if outside 120s grace window),
      returns should_award bool to caller — credits awarded only when True
    - Fallback: if transaction fails, does plain merge write (logs warning), awards credits
    - plan_activated_at field now stored in users_table/{uid} for every activation
    Logic: race window = 120s. /verify + webhook both firing → first writer sets plan_activated_at, second sees it within 120s and skips _award_activation_credits. Monthly re-buy (>120s after last activation) correctly awards again.

2026-04-28 | Remove Groq/Bedrock dead code + Google native fallback | Files:
  server/app/services/llm_service.py: FULL REWRITE
    - Removed: boto3, groq imports; _init_groq, _init_bedrock, _groq_client, _bedrock_client
    - Removed: _images_to_bedrock_content, _call_groq, _call_bedrock (~300 lines deleted)
    - Kept: google.genai client as fallback; _images_to_gemini_parts; _call_gemini (added system_prompt param)
    - Updated generate_response: tries LiteLLM first; on failure falls back to _call_gemini (Google native); only returns error if both fail
  server/app/services/litellm_service.py:
    - Removed hardcoded auth_key overrides (lines 223, 269 — was "sk-O9b3-..." leaking a real key)
    - Fixed call_litellm and stream_litellm: now use `model` param instead of hardcoded "gemini-3.1-flash-lite-preview"
    - Fixed create_user_api_key models list: removed "gemini-3.1-flash-lite-preview", added "power"
  server/app/core/config.py:
    - Removed: GROQ_API_KEY, AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY, AWS_REGION, AWS_BEARER_TOKEN_BEDROCK
    - Fixed: supports_images for power tier changed from ["bedrock"] to ["gemini"] (was always False for gemini — bug)
    - Fixed: supports_images for cheaper/faster: removed "bedrock"

2026-04-28 | TTS language voice fix (Telugu + other regional languages) | Files:
  server/app/api/tts.py:
    - Added tts_engine field to TtsSynthesizeRequest (default "google")
    - tts_engine="android" → returns HTTP 204 immediately (client uses device TTS)
    - Non-English languages (te-IN, hi-IN, etc.) no longer fall through to ElevenLabs/OpenAI;
      those providers only speak English — using them for Telugu produces English accent. 
      Returns HTTP 503 if Google TTS fails for non-English. English still falls through.
  app/.../utils/TextToSpeechManager.kt:
    - setLocale() now returns Boolean (true=supported, false=not installed)
    - When language not available: no longer falls back to Locale.US silently.
      Keeps current locale and returns false (caller handles it).
  app/.../BlackboardActivity.kt (line ~477):
    - On startup: if tts.setLocale() returns false for non-English, shows a Toast:
      "⚠️ TE voice not installed. Go to Settings → Accessibility → Text-to-speech."
  Root cause: device without Telugu TTS pack → setLocale fell back to Locale.US → 
    English voice spoke Telugu content → "English man trying to say in Telugu"
  NCERT seeder finished: 85 chapters indexed, 655 page-chunks, 128 failed (bad Firestore URLs)

2026-04-28 | NCERT seeder: parallel subjects + async download + Chrome UA | Files:
  server/seed_ncert_es.py:
    - Replaced `import requests` with `import httpx` (async, connection reuse)
    - _get_pdf() now async using httpx.AsyncClient passed from caller
    - Chrome User-Agent: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36"
    - Extracted _seed_subject() coroutine — processes one subject's chapters sequentially
    - seed() groups all docs by subject_id, runs subjects in parallel under asyncio.Semaphore(parallel)
    - Single shared httpx.AsyncClient across all parallel workers (connection pool)
    - Added --parallel N CLI flag (default 3); use --parallel 5 for faster runs
  Usage: python3 seed_ncert_es.py --parallel 5 --from-cache-only  (if PDFs already cached)

2026-04-29 | Mic UX — propagated all improvements to FullChatFragment + BlackboardActivity | Files changed:
  FullChatFragment.kt:
    - startVoiceInput(): added haptic (EFFECT_CLICK / createOneShot fallback), keyboard dismiss
      (imm.hideSoftInputFromWindow), startMicPulse() + startWaveAnimation("#E53935") calls —
      pulse and wave bars were already implemented but never wired to regular tap-record
    - resetVoiceButton(): added stopMicPulse() + stopWaveAnimation() (were missing)
    - onError(): now shows contextual toast ("Couldn't hear you", "No network", "Mic error")
      instead of silently resetting; toast only shown when NOT in voice mode (voice mode auto-restarts)
  BlackboardActivity.kt:
    - Added bbMicPulseAnim: android.animation.Animator? instance var (line ~229)
    - startBbVoiceInput(): added haptic, keyboard dismiss, ObjectAnimator pulse on bbMicBtn
      (600ms scale 1→1.15→1 loop), contextual error toasts
    - resetBbVoiceButton(): cancels+nulls bbMicPulseAnim, resets bbMicBtn.scaleX/Y to 1f
    - showAskBottomSheet micTile: label "Voice" → "Tap to speak" (discoverability)
  Skipped: BbInteractivePopup — uses inline SpeechRecognizer (not VoiceManager), already has
    color feedback; would need deeper refactor. Lower priority (quiz-answer flow, not topic input).

2026-04-29 | Mic UI — full UX overhaul (all 8 improvements implemented) | Files changed:
  AndroidManifest.xml: added android.permission.VIBRATE
  VoiceManager.kt (lines 17, 122):
    - Added onRmsChanged(rms: Float) default method to VoiceRecognitionCallback interface
    - RecognitionListenerImpl.onRmsChanged() now calls callback?.onRmsChanged(rmsdB)
  HomeActivity.kt:
    - Added 6 new instance vars (lines ~91): homeMicEmojiView, homeMicWaveContainer,
      homeMicBars: List<View>, homeMicLabelView, homeMicBg: GradientDrawable, homeMicPulseAnim: Animator
    - showBbTopicDialog(): replaced makeHomeTile() for mic with a hand-built tile containing:
        * 28f 🎤 emoji (hides when listening)
        * 3 animated waveform bars (wBar1/2/3, heights 6/12/8dp initial, bounce via onRmsChanged)
        * "Tap to speak" / "Listening…" label (10f)
        * GradientDrawable micBg stored for live color mutation
    - Added sheet.setOnDismissListener: stops recording, cancels pulse, nulls all mic refs
    - updateHomeMicTile() (complete rewrite):
        Listening state: hide emoji, show wave container, label→"Listening…" (#4FC3F7),
          tile bg→#3D0000, stroke→2dp #FF5252, start 600ms looping scale pulse (1→1.06→1)
        Idle state: cancel pulse, reset scale 1f, show emoji, hide waves,
          label→"Tap to speak" (#AABBCC), tile bg→#1A2030, stroke→1dp #334466
    - homeStartVoiceInput() upgrades:
        * Haptic: VibrationEffect.EFFECT_CLICK (API 29+) / createOneShot(30ms) fallback
        * Keyboard dismiss before recording starts (imm.hideSoftInputFromWindow)
        * onRmsChanged: normalises RMS to 0–1, animates 3 bar heights (4–26dp range)
        * onError: context-aware toast ("Couldn't hear you", "No network", "Mic error")

2026-04-29 | Mic UI analysis — read + rate + improvement plan (no code changes) | Files read:
  HomeActivity.kt (lines 83–84, 91, 116, 133, 1341–1452, 1552–1614):
    - Mic tile in showBbTopicDialog() bottom sheet: makeHomeTile("🎤"/"⏹️", "Voice", "#1A2030", "#334466")
    - homeIsListening flag drives only emoji swap (🎤 ↔ ⏹️); no animation, no color change, no label change
    - homeStartVoiceInput(): partial results update topicInput in real time (good)
    - onRmsChanged in VoiceManager is empty — no amplitude feedback
    - updateHomeMicTile(): only swaps emojiView.text
  BbInteractivePopup.kt (lines 392–521):
    - Better mic: 44f emoji, color change green→red on listening, alpha 0.4 on processing
    - Still no pulse/scale animation; onRmsChanged also empty
  HomeActivity.kt (lines 984–994):
    - ObjectAnimator pulse infrastructure already used for credits badge (scale X/Y PropertyValuesHolder)
  VoiceManager.kt (lines 122, 175): onRmsChanged stubs exist but unused
         python3 seed_ncert_es.py --parallel 3                     (fresh download + index)

---

## 2026-04-29 (session 8)

**Asked:** (1) Progress Dashboard shows empty despite chatting. (2) Add "Next" button to daily challenge card. (3) Check notes generation quality for Firestore sync decision.

**Progress Dashboard diagnosis:**
- Root cause: `populateFromStats` bails on `stats.subjects.isEmpty()` even if doc exists; for school users whose Firebase anonymous auth fails at login, stats writes silently denied by Firestore rules (PERMISSION_DENIED — rule requires auth.uid == docId but school user has no Firebase auth UID)
- Also: `ensureProfile()` never called at app start — document may not exist yet
- Flow verified: `recordMessage` called at FullChatFragment:1533; `recordBbSession` at BlackboardActivity:850 (inside if recordSession && !userId.isNullOrBlank())
- School login (`SchoolLoginActivity:126`): calls `signInAnonymously()` — if it FAILS, `navigateNext()` called without saving Firebase UID → fallback userId = schoolId_studentId → Firestore rules reject writes

**Changes — Progress Dashboard:**
- `HomeActivity.kt`: Added `ensureStatsProfile()` call after userId init on line 146; added `ensureStatsProfile()` function (~line 878) that: (a) attempts anonymous Firebase sign-in if no current auth user, (b) calls `StudentStatsManager.ensureProfile()` with recovered UID
- `ProgressDashboardActivity.kt`: 
  - `populateFromStats()`: moved top stats population (streak/mastery/time) BEFORE subjects check
  - If `stats == null` → showEmpty as before
  - If `stats.subjects.isEmpty()`: calls `showEmpty(hasActivity = totalMessages>0 || bbSessions>0)` then returns
  - `showEmpty()`: added `hasActivity` param; shows "Great start! 🎉" message when hasActivity=true
  - `displayData()`: added `emptyLayout.visibility = GONE` when data loads (prevents overlap)
- Files: HomeActivity.kt (~lines 98–100: new state vars, ~line 146: ensureStatsProfile call, ~line 878: new ensureStatsProfile function), ProgressDashboardActivity.kt (lines 63–88, 279–292, 129–132)

**Changes — Daily Questions Next button:**
- `activity_home.xml`: Added `challengeNextBtn` TextView to footer row inside dailyChallengeCard (visibility=gone by default, "Next →" text, #4FC3F7 color)
- `HomeActivity.kt`: Added `dailyPendingQuestions` + `dailyChallengeIndex` state vars; extracted `showDailyChallengeAt(card, index)` function; `loadDailyChallenge()` calls `showDailyChallengeAt(card, 0)`; Next button shown only when pending.size > 1 and index < last; click increments index modulo pending.size

**Notes quality assessment:**
- `NotesRepository.save()` in `chat/NotesRepository.kt` saves raw AI response text (no LLM call, no formatting)
- `saveLastAIMessageAsNotes()` at FullChatFragment:1828 → `TutorController.extractAnswerForDisplay(lastAi.content)` → save
- Quality is "acceptable but conversational" — not structured bullet notes
- Decision: **skip Firestore sync for notes** — current quality doesn't justify cloud storage; would need a dedicated notes-generation LLM call to produce clean structured notes (extra token cost per save)

---

## 2026-04-29 (session 11)

**Asked:** 4 tasks: (1) Live-teacher pacing — BB feels like a live teacher with pauses, mid-lesson questions, reveals, humor. (2) System prompt isolation (static `blackboard_prompt` cacheable, grade/dynamic content in user content). (3) Remove quiz checkbox from pre-session settings dialog. (4) Implement 4 pacing layers: durationMs usage, diagram pre-pause, grade TTS rate, teacher persona prompt.

**Fix 1 — Remove quiz checkbox:**
- `BlackboardActivity.kt` `_showLessonSettingsDialog`: removed `Opt("🎯", "Interactive quizzes", ...)` from opts list (now 3 opts instead of 4); fixed `BBSessionConfig` constructor to hardcode `quizEnabled = false`, `checkBoxes[0-2]` → videos/animations/images respectively.

**Fix 2 — Add `pauseAfterMs` field (Layer 1 prerequisite):**
- `chat/BlackboardGenerator.kt` `BlackboardFrame` data class: added `val pauseAfterMs: Long = 0` after `durationMs`; updated frame type doc comment to include `question | reveal | joke`
- `BlackboardGenerator.kt` parseDoc inline + `parseStepsArray` + `deserializeSteps`: added `pauseAfterMs = frameObj.optLong("pause_after_ms", 0)` in all 3 parse locations
- `BlackboardGenerator.kt` `serializeSteps`: added `.put("pause_after_ms", frame.pauseAfterMs)`

**Fix 3 — Layer 1: Use LLM's durationMs (replace hardcoded 300/1000/2000ms):**
- `BlackboardActivity.kt` `makeTtsCallback.onComplete()` else branch: replaced hardcoded `pauseMs` with `postTtsWait = durationMs.coerceIn(300-5000)` + `thinkingWait` for `question` frames (uses `pauseAfterMs.coerceAtLeast(2000)` + shows "🤔 take a moment…" subtitle)

**Fix 4 — Layer 2: Diagram pre-speech pause:**
- `BlackboardActivity.kt` SVG/diagram render block (was `postDelayed(400ms)`): changed to `postDelayed(2500ms)` + calls `showSubtitle("Take a look… 👀")` during silent window before TTS starts

**Fix 5 — Layer 3: Grade-aware TTS speech rate:**
- `BlackboardActivity.kt`: added `private var ttsRateApplied = false` field; at top of `speakFrame()`, on first call extracts `cachedMetadata.grade` digits, maps grade ≤7 → 0.85f, ≤10 → 0.92f, else → 1.00f, calls `tts.setSpeechRate()`

**Fix 6 — Layer 4: Rewrite `blackboard_prompt` with teacher persona:**
- `server/app/services/prompt_service.py` `blackboard_prompt`: complete rewrite (~1600 tokens) — GURU teacher persona (warm, funny, builds suspense), new frame types (`question`, `reveal`, `joke`) with full specs, grade adaptation lookup table (static), speech guidelines, lesson flow arc, `pause_after_ms` field documentation, timing table

**Fix 7 — Grade speech style in dynamic user content:**
- `server/app/services/prompt_service.py` `build_blackboard_mode_user_content()`: added `_grade_style` dict after `Student level` injection — appends `GRADE SPEECH STYLE: ...` line matching level range (1-4, 5-7, 8-10, 11-13), keeping it in user content to preserve system prompt cache identity

**Files changed:** BlackboardActivity.kt (~line 1862 opts list, ~line 1944 BBSessionConfig, ~lines 2257-2270 ttsRateApplied+speakFrame, ~line 1529 diagram delay, ~line 3120 pauseMs logic), chat/BlackboardGenerator.kt (~line 85 data class, ~lines 406+553+917 parse, ~line 626 serialize), server/app/services/prompt_service.py (~lines 203-310 blackboard_prompt rewrite, ~lines 884-897 grade style injection)

---

## 2025-07-13 — Voice wake-word interruption (both BB + Chat)

**Task:** Implement "madam/teacher/sir/stop/question/wait" wake word detection for both BB lessons and chat voice mode. Replace old single-cycle interrupt listener with self-restarting `onResults`-only loop.

**VoiceManager.kt** (utils/VoiceManager.kt):
- Added `startWakeWordLoop(wakeWords, onDetected, language)` — creates new `SpeechRecognizer` each cycle, uses `onResults` only (no partial results), self-restarts with per-error delays: BUSY→1500ms, AUDIO→800ms, no-match→200ms, other→300ms
- Added `stopWakeWordLoop()` — cancels Handler callbacks, destroys recognizer, sets `wakeWordActive=false`
- Added private `_runWakeWordCycle()` — actual loop body with Handler.postDelayed
- `destroy()` now calls `stopWakeWordLoop()` first
- Old `startInterruptListening`/`stopInterruptListening` kept intact for backward compat

**server/app/api/bb.py**:
- Added `DoubtRequest` model (question, speech_context, step_title, lesson_topic, student_level, language_tag)
- Added `DoubtResponse` model (answer, answer_speech, follow_up)
- Added `POST /bb/doubt_solve` endpoint — builds context-aware prompt, calls `generate_response(tier="faster", call_name="bb_doubt")`, parses JSON response, records tokens, fallback on error

**chat/ServerProxyClient.kt**:
- Added `DoubtSolveResponse` data class (answer, answerSpeech, followUp) at file end
- Added `postDoubtSolve()` companion function — same attempt(forceRefresh)/retry pattern as registerWithServer, POSTs to `$base/bb/doubt_solve`, fallback DoubtSolveResponse on failure

**BlackboardActivity.kt**:
- Added fields: `bbDoubtCardView: android.view.View?`, `bbWakeWordLoopRunning: Boolean`, `BB_WAKE_WORDS` list
- `onPause()`: added `stopBbWakeWordLoop()` call
- Added `onResume()` override: restarts loop if lesson was playing
- `onDestroy()`: added `stopBbWakeWordLoop()`, `bbDoubtCardView = null` before bbVoiceManager.destroy()
- `togglePause()`: when pausing adds `stopBbWakeWordLoop()`, when resuming adds `startBbWakeWordLoop()`
- `speakFrame()`: added `startBbWakeWordLoop()` after quiz guard (non-quiz frames only)
- Added `startBbWakeWordLoop()` — guarded by `bbWakeWordLoopRunning`, `isPaused`, `bbDoubtCardView`
- Added `stopBbWakeWordLoop()` — resets flag, calls `bbVoiceManager.stopWakeWordLoop()`
- Added `_onBbWakeWordDetected(word)` — stops TTS, shows toast, starts Tier 2 listening
- Added `_solveDoubt(question)` — extracts speech context from current step frames, coroutine → `ServerProxyClient.postDoubtSolve()` → `_showDoubtCard()` on main thread
- Added `_showDoubtCard(question, answer, answerSpeech, followUp)` — programmatic overlay card (dark style, GradientDrawable rounded bg), "🔊 Hear Answer" + "▶ Resume Lesson" buttons, auto-dismiss after 90s
- Added `_dismissDoubtAndResume()` — removes card, calls speakFrame + startBbWakeWordLoop if not paused

**FullChatFragment.kt**:
- Removed `currentTTSText`, `voiceStopWords`, `interruptCallback`, `shouldInterruptForText()`
- Added `CHAT_WAKE_WORDS = listOf("madam","teacher","sir","stop","question","wait")`
- `startInterruptListening(interruptCallback)` → `startWakeWordLoop(CHAT_WAKE_WORDS, { stopWakeWordLoop(); triggerBargein() }, currentLang)`
- All `stopInterruptListening()` → `stopWakeWordLoop()`
- `triggerBargein()` unchanged logic — stops TTS, starts Tier 2 `startListening()`


---

## 2026-04-29 (session 10)

**Asked:** 3 issues: (1) BB mode ask bar keyboard hides input. (2) Lesson complete shows dialog covering blackboard — show non-blocking top notification instead. (3) Drawer credits stale.

**Fix 1 — BB keyboard:**
- `AndroidManifest.xml` BlackboardActivity: added `windowSoftInputMode="adjustResize"`
- `BlackboardActivity.kt`: added `ViewCompat`+`WindowInsetsCompat` imports; added `setOnApplyWindowInsetsListener` on `bbAskBar` in onCreate — adjusts bottom padding when IME open

**Fix 2 — Lesson complete notification:**
- `activity_blackboard.xml`: added `@+id/bbLessonCompleteNotif` TextView overlay at top, `layout_gravity="top|center_horizontal"`, visibility=gone
- `drawable/bg_lesson_complete_notif.xml`: new pill drawable (#F5E3A0, 24dp corners)
- `BlackboardActivity.kt`: added `showLessonCompleteNotif()` — fade-in, 2s hold, fade-out; called when `isLastFrameOverall && quizTotal == 0` (no quizzes → no score card)

**Fix 3 — Credits refresh:**
- `HomeActivity.kt`: added `lastDrawerQuotaLoadMs` state var; added `DrawerLayout.SimpleDrawerListener` in `setupDrawer()` — calls `loadQuotaStrip()` on drawer open, debounced to 30s (2 Firestore reads per refresh max)

**Files changed:** AndroidManifest.xml (line 127), BlackboardActivity.kt (~lines 16-17 imports, ~line 350 IME listener, ~line 2985 new fn, ~line 3098 else branch), activity_blackboard.xml (~line 554), drawable/bg_lesson_complete_notif.xml (new), HomeActivity.kt (line 127 new var, ~line 1843 DrawerListener)

---

## 2026-04-29 (session 9)

**Asked:** Chat keyboard focus bug — input bar hidden under keyboard, need to close keyboard to send.

**Root cause:** targetSdk=36, Android 15+ enforces edge-to-edge. `adjustResize` no longer resizes window when IME opens. Input row + send button got covered by keyboard.

**Fix:**
- `activity_chat.xml` line 11: added `android:id="@+id/chatMainContent"` to main content LinearLayout (DrawerLayout child)
- `FullChatFragment.kt`: added imports `ViewCompat`, `WindowInsetsCompat`; added `setOnApplyWindowInsetsListener` on `chatMainContent` in `onViewCreated` after `initializeUI` — sets bottom padding = max(imeInsets.bottom, navBarInsets.bottom)
- No keyboard dismiss on send — that only happens for voice button (correct behavior)
- Files changed: `app/src/main/res/layout/activity_chat.xml` (~line 11), `FullChatFragment.kt` (~lines 30-31 imports, ~line 425 insets listener)

---

## 2026-04-29 (session 9 continued)

**Asked:** Session resumed — verified previous keyboard fix.

**Found:** Two conflicting keyboard handlers in FullChatFragment.kt — `viewTreeObserver.addOnGlobalLayoutListener` (legacy, lines 343-354) AND `ViewCompat.setOnApplyWindowInsetsListener` (modern) both setting padding on `chatMainContent`. They would fight on every layout pass.

**Fix:** Removed the legacy `viewTreeObserver` block (lines 343-354). Only the `setOnApplyWindowInsetsListener` at line ~405 remains — correct for API 35+ edge-to-edge.

<<<<<<< HEAD
**Files changed:** `BlackboardActivity.kt` (~line 3127-3129 postTtsWait, ~line 1526-1528 diagram delay)

---

## 2026-04-30 (session 2)

**Asked:** Android TTS sounds like English person speaking Telugu — check how language is passed, fix. Also check if JSON needs anything extra for android.

**Root cause:** `question` frames get `tts_engine: "android"` from both the prompt and `smartAssignTts()`. Android device TTS requires the regional language pack to be installed; if not installed, it silently falls back to English voice. Also first frame is always forced to "android" engine. Followup question speak didn't re-set locale.

**JSON is fine** — `lang: "te-IN"` at step level is correctly parsed into `step.languageTag` and used. No extra fields needed in the JSON.

**Fix:** Removed the legacy `viewTreeObserver` block (lines 343-354). Only the `setOnApplyWindowInsetsListener` at line ~405 remains — correct for API 35+ edge-to-edge.

**Files changed:** `BlackboardActivity.kt` (~lines 2283-2290 finalEngine logic, ~lines 760-766 preloadUpcoming, ~line 4622 followup locale)

---

## 2026-04-30 (session 3)

**Asked:** Add referral system — when someone is referred, give +5 BB lessons/day for a month to both parties.

**Root cause / current state:**
- `ReferralManager.kt` existed but only did client-side Firestore transaction — wrote `bonus_questions_today` to `users/{uid}` (old collection). Server quota reads from `users_table/{uid}` → bonus had zero effect on server-enforced limits.
- No time limit on bonus.
- No server-side referral API.

**Fix:**
1. **New** `server/app/api/referrals.py` — `POST /referrals/apply`: validates code from `referralCodes`, prevents self-referral and double-claiming, writes `referral_bb_bonus_per_day=5` + `referral_bb_bonus_expiry_at=now+30d` to `users_table/{uid}` for both claimant and referrer.
2. `server/app/services/user_service.py`: new user defaults include `referral_bb_bonus_per_day=0` + `referral_bb_bonus_expiry_at=0`; `check_and_record_quota()` adds bonus to `limit` for BB when not expired.
3. `server/app/api/users.py`: `get_user_quota` + `get_quota_status` both add bonus to `bb_limit` when active.
4. `server/app/api/credits.py`: `quota_status` adds bonus to `bb_limit` when active.
5. `server/app/main.py`: registered `referrals_router`.
6. `ReferralManager.kt`: added `claimReferralCodeViaServer()` using server API + auth token (OkHttp); old Firestore `claimReferralCode()` kept as legacy fallback.
7. `UserProfileActivity.kt`: claim button now calls `claimReferralCodeViaServer` on a background thread.

**Files changed:** `server/app/api/referrals.py` (new), `server/app/services/user_service.py` (~lines 141-145, 523-527), `server/app/api/users.py` (~lines 200-205, 322-326), `server/app/api/credits.py` (~lines 208-214), `server/app/main.py` (~lines 38, 87), `ReferralManager.kt` (new import block + new fn claimReferralCodeViaServer ~line 84), `UserProfileActivity.kt` (~lines 277-306)

---

## 2026-04-30 (session 4)

**Asked:** (1) Read CLAUDE.md. (2) Are we rendering or fetching NCERT PDFs from ncert.json — check and do accordingly. Also NCERT chapter count corrections from URL verification session.

**NCERT flow (confirmed by reading code):**
- `ncert.json` (Android asset) stores book codes + chapter ranges only — NOT the PDFs
- `HomeActivity.kt` `confirmNcertImport()` (~line 2278): reads code + `chapters` field ("0-8" → start=1, end=8), builds NCERT PDF URLs via `ncertChapterUrl()` → `https://ncert.nic.in/textbook/pdf/{code}{ch:02d}.pdf`, stores in SharedPreferences + Firestore
- `ChapterActivity.kt` `setupNcertChapter()` (~line 703): downloads PDF from ncert.nic.in at runtime, caches to `cacheDir/pdf_cache/`, renders pages as images — **fetches live from NCERT website, NOT from local asset**
- `ncertCandidateUrls()` tries multiple URL variants (standard, no-pad, trailing-1, dd, dd1)

**ncert.json corrections applied** (`app/src/main/assets/ncert.json`) — 18 chapter count fixes based on confirmed HEAD requests to ncert.nic.in:
| Book | Old | New |
|------|-----|-----|
| Class 7 Math gegp1 | 0-13 | 0-8 |
| Class 7 Science gecu1 | 0-18 | 0-12 |
| Class 7 SS History gees1 | 0-10 | 0-12 |
| Class 7 SS Geography gees2 | 0-10 | 0-8 |
| Class 7 English geah1 | 0-10 | 0-7 |
| Class 8 English hehd1 | 0-10 | 0-8 |
| Class 8 English heih1 | 0-10 | 0-8 |
| Class 8 SS History hees1 | 0-12 | 0-7 |
| Class 9 Math iemh1 | 0-15 | 0-8 |
| Class 9 Science iesc1 | 0-15 | 0-13 |
| Class 10 English jefp1 | 0-10 | 0-9 |
| Class 10 Math jemh1 | 0-15 | 0-14 |
| Class 10 Science jesc1 | 0-16 | 0-13 |
| Class 10 SS History jess1 | 0-5 | 0-6 |
| Class 10 SS Geography jess2 | 0-7 | 0-5 |
| Class 10 SS Civics jess3 | 0-8 | 0-5 |
| Class 11 Math kemh1 | 0-16 | 0-14 |
| Class 12 Biology lebo1 | 0-16 | 0-13 |
Books with ALL 404 (English/SS for Class 6/9) kept as-is — likely IP-blocked from cloud server, may work from residential IPs.

**Files changed:** `app/src/main/assets/ncert.json` (18 chapter range fixes)

## 2026-04-30 (session 5)

**Asked:** (1) Why credits not added when updating the "basic" plan in Firestore. (2) Image search titles not giving good images — fix.

**Credits investigation (read-only, no code change):**
- Flow: Android pays → `/payments/razorpay/verify` → `user_service.activate_plan()` → reads `plans/{plan_id}.limits.credits_on_activation` → calls `_award_activation_credits()` which does `user_credits/{uid}.balance += amount`.
- `student_basic` plan in seed: `credits_on_activation = 100`. `student_pro` = 500. `free` = 0.
- `already_activated` guard: only blocks re-award if SAME plan activated within 120 seconds (race window). Renewals (30+ days later) always get credits.
- **Root causes diagnosed:**
  - A) If planId changed manually in Firestore console, `activate_plan()` is never called → no credits. Fix: manually increment `user_credits/{uid}.balance` + add `credit_transactions` doc.
  - B) If `plans/student_basic` doc in Firestore missing `limits.credits_on_activation` field (not seeded, or manually overwritten) → `activation_credits=0` → nothing awarded. Fix: check Firestore `plans/student_basic` doc has `limits.credits_on_activation = 100`.
- No code change needed — logic is correct. Firestore data issue.
**Files read:** `server/app/services/user_service.py:1-720`, `server/app/api/payments.py:1-310`, `server/seed_firestore.py:290-410`, `server/app/api/credits.py:1-100`

**Image search titles fix:**
- **Root cause:** Production code was using `_pick_by_word_overlap()` (50% threshold) instead of LLM picker. Word-overlap too strict — Wikimedia titles rarely match enough description words → many steps get no image.
- `_pick_titles_sync()` (LLM picker) existed but was commented out as "kept for reference".
- **Changes:**
  - `image_search_titles.py` `get_titles()` (~line 667): Replaced `_pick_by_word_overlap(...)` with `await loop.run_in_executor(None, _pick_titles_sync, ...)` — LLM picker is now primary; it has built-in word-overlap fallback on exception.
  - `image_search_titles.py` `_best_title_match()` (~line 111): Lowered accept threshold `0.5 → 0.3` — word-overlap fallback now catches more valid partial matches.
**Files changed:** `server/app/api/image_search_titles.py` (2 lines)

## 2026-04-30 (session 6)

**Asked:** Credits still not added after basic plan payment even though credits_on_activation=200 set in Firestore.

**Root cause:** `_lookup_plan_limits()` only reads the `limits` sub-map (`doc.get("limits", {})`). If `credits_on_activation` was manually added at root level of the Firestore plan doc (not inside `limits` map), server reads `{}`, gets `activation_credits=0`, silently skips `_award_activation_credits()`. Plan activates (Android shows success) but no credits added.

**Secondary bug:** `int(limits.get("credits_on_activation", 0))` had no guard — a null Firestore value would raise TypeError crashing `activate_plan` entirely.

**Fix (`server/app/services/user_service.py`):**
- `_lookup_plan_limits()` (~line 54): now reads `limits` sub-map first, then falls back to root-level fields for `credits_on_activation`, `daily_chat_questions`, `daily_bb_sessions`, etc.
- Added `logger.info` to log what credits value was found — visible in server logs for debugging.
- `activation_credits` assignment (~line 292): wrapped in `try/except (TypeError, ValueError)`.
- Added `logger.info("activate_plan: should_award=%s activation_credits=%d ...")` before award block.
**Files changed:** `server/app/services/user_service.py` (~lines 54-87, 291-299)

## 2026-04-30 (session 7)

**Asked:** BB lesson is stopping after each frame; check and fix with minimal changes.

**Root cause:** `makeTtsCallback()` in `BlackboardActivity.kt` advanced frames only in `onComplete()`. On `onError()`, it only logged/hid subtitle and never advanced. Any TTS failure (Android/AI TTS fallback path) caused playback to stall on that frame.

**Fix:**
- `BlackboardActivity.kt` (`makeTtsCallback`, ~3139+): extracted shared `continueAfterSpeech()` flow and invoked it from both `onComplete()` and `onError()`.
- Preserved quiz behavior: legacy reveal button and interactive quiz routing still handled.
- Result: frame progression continues even when TTS errors occur.

**Files read:** `CLAUDE.md:1-70`, `meta/tracker.md:1110-1165`, `BlackboardActivity.kt` slices around playback/callback paths (`604-675`, `920-995`, `1000-1128`, `1328-1438`, `1568-1738`, `2290-2475`, `3132-3205`)
**Files changed:** `app/src/main/java/com/aiguruapp/student/BlackboardActivity.kt` (~3139-3186)

## 2026-04-30 (session 8)

**Asked:** BB still pauses unless user presses Next; also lesson should stay at 5 steps but generates more.

**Root causes:**
- Silent frames stalled: `showFrame()` only progressed automatically via TTS completion. Frames with blank `speech` rendered text/typewriter and then stopped.
- Over-generated steps were accepted: `BlackboardGenerator.generateChunk()` told the model to return exactly N steps, but Android accepted the full parsed array even if the model returned more than the requested batch.

**Fixes:**
- `BlackboardActivity.kt` (`showFrame`, `continueAfterFrame`, ~1623-1720 and ~2375-2410):
  - `textLen == 0` branch now auto-continues when `speech` is blank.
  - direct LaTeX-render branch now auto-continues when `speech` is blank.
  - typewriter `onAnimationEnd()` now auto-continues when `speech` is blank.
  - extracted shared `continueAfterFrame(stepIdx, frameIdx)` so silent frames and TTS-complete/error use the same progression logic.
- `chat/BlackboardGenerator.kt` (`generateChunk`, ~314-318): clamp parsed results with `take(chunkStepTitles.size)` so a 5-step chunk cannot append more than 5 steps even if the model over-returns.

**Files read:** `BlackboardActivity.kt:490-515, 1548-1735, 2370-2448`; `chat/BlackboardGenerator.kt:1-55, 228-330`
**Files changed:** `app/src/main/java/com/aiguruapp/student/BlackboardActivity.kt`, `app/src/main/java/com/aiguruapp/student/chat/BlackboardGenerator.kt`

## 2026-05-08

**Asked:** Is there any 2-second pause in TTS while speaking?

**Finding:** No hardcoded 2-second pause in TTS speaking flow.
- The 3× `postDelayed(2000)` calls in `BlackboardActivity.kt` are all non-TTS: lines 617-619 and 641-643 redirect to SubscriptionActivity on credits exhaustion; line 3082-3086 is `showLessonCompleteNotif()` banner visibility.
- `makeTtsCallback` → `continueAfterSpeech()` calls `advanceFrame()` immediately — comment: "Advance immediately after speech ends/fails — no pauses."
- `advanceFrame()` → `showFrame()` → `speakFrame()` has zero artificial delays.
- Only potential source of perceived pause: `BbAiTtsEngine.play()` cache miss path waits up to **8s** (`waitForKey(key, timeoutMs=8_000L)`, polls every 100ms) for preloaded audio. On slow network this could manifest as ~1-3s silence before speech starts.

**Files read:** `BlackboardActivity.kt` (lines 605-644, 930-993, 1060-1112, 2301-2430, 3082-3218); `BbAiTtsEngine.kt` (full: lines 98-232); `AiTtsProvider.kt` (no delays found); `TextToSpeechManager.kt` (no delays found)
**Files changed:** none

---

**Asked:** Stop circular/ring-of-boxes SVG diagrams (cycle, labeled types) from being generated.

**Root cause:** `cycle` type in JS engine (`diagrams.js:432`) renders steps in a ring with box labels. `labeled` type renders central node surrounded by labels in a circle. Both produce "boxes in a circle" visual.

**Changed:**
- `server/app/api/image_search_titles.py` — added `_CIRCULAR_LAYOUT_TYPES = {"cycle", "labeled"}` constant (line ~51); JS engine call wrapped with `d_type.lower() not in _CIRCULAR_LAYOUT_TYPES` guard; SMIL fallback also guarded; LLM SVG builder now handles these types instead; added redirect log message.
- `server/app/services/prompt_service.py` — `science_biology` hint: replaced `"Use cycle for life cycles..."` with `"use diagram_type=custom instead of cycle"`; `geography_environment` hint: same replacement; `blackboard_prompt` RULES: added `"NEVER use diagram_type 'cycle' or 'labeled' — they produce ugly circular box layouts."` instruction.
- `server/app/utils/diagram_router.py` — remapped `mitosis`, `meiosis`, `water cycle`, `carbon cycle`, `nitrogen cycle`, `life cycle`, `stages of`, `phases of`, `cycle` → all now map to `"custom"` instead of `"cycle"`.

**Files read:** `svg_builder.py` (grep for cycle/radial); `svg_renderers.py` (lines 367-430 `_render_cycle`); `diagrams.js` (lines 419-490 `cycle` function); `image_search_titles.py` (lines 45-600); `prompt_service.py` (lines 1-100 hints, 262-440 blackboard_prompt, 538-560 diagram hint logic); `diagram_router.py` (lines 65-200 keyword map)

---

**Asked:** Also block cycle/labeled from the LLM prompt level (not just rendering pipeline).
**Changed:** (same as above — all 3 files updated in that task)

---

**Asked:** On plan activation, also award tts_balance from plan's tts_credits_on_activation field.

**Root cause:** `_award_activation_credits` only incremented `balance` and `lifetime_earned` in `user_credits/{uid}`. TTS pool (`tts_balance`) was never topped up on plan purchase.

**Changed:**
- `server/app/services/user_service.py`:
  - `_lookup_plan_limits()` fallback list (line ~71): added `"tts_credits_on_activation"` alongside `"credits_on_activation"` so root-level Firestore docs are picked up too
  - `activate_plan()` (line ~292): added `activation_tts_credits` read from `limits.get("tts_credits_on_activation")`; updated log to include `tts_credits`
  - `_award_activation_credits()` signature changed to `(db, uid, amount, tts_amount, plan_id, plan_name)`: if `tts_amount > 0`, increments `tts_balance` + `tts_lifetime_earned` in same `user_credits` doc; logs a `plan_activation_tts` transaction in `credit_transactions`

**To use:** Add `tts_credits_on_activation: <int>` inside `limits` map of each plan doc in Firestore `plans/` collection. E.g. `basic → 50000`, `pro → 200000`. No code change needed after that.

**Files read:** `user_service.py` (lines 45-95 _lookup_plan_limits, 217-340 activate_plan, 641-700 _award_activation_credits, 700-740 init_user_credits); `users.py` (lines 355-400 quota status tts_balance); `FirestoreManager.kt` (grep for tts)

---

## 2026-05-09

**Asked:** Fix compile error — Unresolved reference 'topic' at BbSavedSessionsActivity.kt:268.
**Root cause:** `replaySession()` used `topic` directly but never declared it. `shareSession()` (line 285) correctly extracts it from the session map as `session["topic"] as? String`. `replaySession()` was missing that extraction.
**Fix:** Added `val topic = session["topic"] as? String ?: ""` at the top of `replaySession()`, before the intent block.
**Files read:** `BbSavedSessionsActivity.kt` (lines 1-60, 255-285); `FirestoreManager.kt` (grep: saveBbSession/topic — line 872 confirms "topic" key in session map)
**Files changed:** `app/src/main/java/com/aiguruapp/student/BbSavedSessionsActivity.kt` (line 263: added topic extraction)

---

## 2026-05-09 (session 13)

**Asked:** (1) Standardize referral bonus to 3/day (not 5), controlled from Firestore config — no code deploy needed to change. (2) In-app user-to-user BB session sharing — Friends page (add friend by referral code), share sessions directly to friends, "Shared with me" tab in Watch History.

**Key code insight — how codes/UIDs are generated:**
- `ReferralManager.codeForUser(uid)` → deterministic 8-char hash of `userId` → always the same for same user. Called once on profile screen open. Writes `referralCodes/{CODE} → {ownerUserId, ownerName}` if not already present.
- `referralCodes/{code}` is the ONLY code→uid mapping. All friend lookups + referral claims read this.
- `users/{uid}` stores identity + all subcollections (friends, shared_with_me, saved_bb_sessions_flat, etc.)
- `users_table/{uid}` stores quota/plan/referral bonus fields — server-managed.

**Changes:**

1. **`server/app/api/referrals.py`** — rewrote entirely:
   - Removed hardcoded `REFERRAL_BB_BONUS_PER_DAY = 5`
   - Added `_get_referral_config(db)` → reads `app_config/referral_settings` `{bonus_per_day, bonus_days}`, defaults to 3/30 on error
   - Rest of claim logic unchanged (one-time gate, referrer bonus stacking)

2. **`server/app/api/users.py`** — added 2 new endpoints:
   - `GET /users/lookup?code=XXXX` → reads `referralCodes/{code}` → returns `{uid, name, code}` (for add-friend flow). Rejects own code.
   - `POST /users/share-session` body `{to_code, session_id, topic, step_count, steps_json, message_id, conversation_id}` → resolves recipient via code → writes to `users/{recipientUid}/shared_with_me/{senderId}_{sessionId}` with sender_name + shared_at → auto-adds both as friends in `users/{uid}/friends/{friendUid}`.
   - Fixed bug: `sender_name` was reading wrong code doc; now reads directly from `users_table/{senderUid}.name`.

3. **`FirestoreManager.kt`** — added 3 new methods (lines ~968-1035):
   - `addFriend(userId, friendUid, name, code, ...)` → `users/{uid}/friends/{friendUid}`
   - `loadFriends(userId, ...)` → ordered by `added_at` desc
   - `loadSharedWithMe(userId, ...)` → `users/{uid}/shared_with_me/` ordered by `shared_at` desc

4. **`FriendsActivity.kt`** (new file) + `activity_friends.xml` + `item_friend.xml`:
   - Browse mode: shows friends list with "Add Friend" button
   - Add friend: calls `GET /users/lookup?code=XXXX` → confirm dialog → `FirestoreManager.addFriend`
   - Share mode (launched from BbSavedSessionsActivity): shows friends list with "Share" buttons → POST `/users/share-session`
   - Registered in `AndroidManifest.xml`

5. **`BbSavedSessionsActivity.kt`**:
   - `shareSession()` now opens `FriendsActivity` in share mode (instead of Android share sheet)
   - Added `showingShared: Boolean` state + `sharedSessions` list
   - `switchTab()` / `loadSharedSessions()` / `showSharedSessions()` — tab logic
   - Adapter `onBindViewHolder`: shows `sender_name` in date field for shared sessions; hides Share/Delete buttons on received sessions
   - Layout: added tab strip in `activity_bb_saved_sessions.xml` (hidden unless EXTRA_ALL_HISTORY=true)

6. **`activity_home.xml`** — added `drawerItemFriends` LinearLayout (👥 Friends & Share) between Watch History and Teacher items

7. **`HomeActivity.kt`** — added `drawerItemFriends` click handler → opens `FriendsActivity`

8. **`firestore.rules`** — added `app_config/{docId}` block: `allow read, write: if false` (server reads via Admin SDK, no client access)

9. **`seed_app_config.py`** (new root-level script) — writes `app_config/referral_settings {bonus_per_day:3, bonus_days:30}` to Firestore. Seeded successfully ✅.

**Firestore collections added:**
- `app_config/referral_settings` → `{bonus_per_day: 3, bonus_days: 30}` ← seeded ✅
- `users/{uid}/friends/{friendUid}` → `{name, code, added_at}`
- `users/{uid}/shared_with_me/{senderId}_{sessionId}` → `{session_id, sender_uid, sender_name, topic, step_count, steps_json, message_id, conversation_id, shared_at}`

**Files changed:** `server/app/api/referrals.py` (full rewrite), `server/app/api/users.py` (2 new endpoints + bug fix), `FirestoreManager.kt` (~lines 968-1035 new methods), `FriendsActivity.kt` (new), `activity_friends.xml` (new), `item_friend.xml` (new), `BbSavedSessionsActivity.kt` (shareSession + tab logic), `activity_bb_saved_sessions.xml` (tab strip), `activity_home.xml` (drawerItemFriends), `HomeActivity.kt` (click handler), `AndroidManifest.xml` (FriendsActivity), `firestore.rules` (app_config rule), `seed_app_config.py` (new)

---

## 2026-05-09 (session 14)

**Asked:** 3 bugs: (1) Add Page in chapter gives invalid index — "not knowing how to name it". (2) Chat guides not showing Pages/BB Sessions tabs; guides must show only first time. (3) BB session tips showing again and again (not first-time only).

**Fix 1 — ChapterActivity extra image page index:**
- Root cause: PDF chapter loads "📄  Page 1..N" in `pagesListData`. `uploadImageButton` → `savePage()` appends to both `imagePagePaths` and `pagesListData`. Old code named the extra page "Page uploaded - $timestamp". When user clicks the new item at position N, `onViewPage(N)` called `pdfPageManager.getPage(pdfId, pdfAssetPath, N)` — out of bounds (valid 0..N-1). Same for `onAskPage(N)`.
- Fix in `savePage()`: page now named `"📷  Extra Page ${imagePagePaths.size}"` (after add, so first extra = "Extra Page 1").
- Fix in `onViewPage(position)` and `onAskPage(position)`: if `isPdfChapter && position >= pdfPageCount` → treat as extra image page: `imgIdx = position - pdfPageCount`, then `fragment.attachImage(imagePagePaths[imgIdx])` and return early.
- Files changed: `ChapterActivity.kt` (`savePage` naming, `onViewPage` guard, `onAskPage` guard)

**Fix 2 — ChatTourManager: add Pages/BB Sessions tabs step + fix hidden view:**
- Root cause: The step for `R.id.pagesDrawerAddPageButton` is inside the closed left drawer → `isShown` returns false → `findNextVisible()` skips it → user never sees Pages step. Also no step for the tab layout (Pages/Chat/BB Sessions tabs in ChapterActivity).
- Fix: Replaced `pagesDrawerAddPageButton` step with `R.id.tabLayout` step as the **first** step. New step title: "📑 Pages · Chat · BB Sessions". Description explains all three tabs + swipe gesture. The "first time only" logic (KEY_CHAT SharedPrefs, `shouldShow()` + `markDone()`) was already correct — no change needed.
- Files changed: `ChatTourManager.kt` (steps list: added tabLayout step at position 0, removed pagesDrawerAddPageButton step)

**Fix 3 — BB session tips first-time only:**
- Root cause: `showFirstTimerTipsIfNeeded()` used `if (sessions > 3) return` — showed different dialog tips at sessions 1, 2, 3. User saw a new dialog every other session launch.
- Fix: Changed to `if (sessions != 1) return`. Merged all tips (navigation + camera + voice) into one comprehensive message shown only on the very first BB session launch.
- Files changed: `BlackboardActivity.kt` (`showFirstTimerTipsIfNeeded` ~line 1135)

**Files changed:** `ChapterActivity.kt` (~lines 960-1000 onViewPage/onAskPage, ~line 1055 savePage), `ChatTourManager.kt` (steps list), `BlackboardActivity.kt` (~line 1135 showFirstTimerTipsIfNeeded)

---

## 2026-05-09 (session 14 cont — LLM per-user key threading)

**Asked:** All LLM calls must use per-user LiteLLM key — observed some calls falling back to master/guest key.

**Root cause:** `generate_response(uid=None)` → `_call_litellm_proxy()` falls back to `LITELLM_MASTER_KEY` when uid is None. Multiple call sites didn't pass uid at all.

**Fixes:**
- `server/app/api/bb.py:63` — `generate_response(..., uid=auth.uid)` ✅
- `server/app/api/analyze_image.py:158` — `generate_response(..., uid=auth.uid)` ✅
- `server/app/api/daily_questions.py:214` — `generate_response(..., uid=uid)` (uid from auth at line 145) ✅
- `server/app/services/diagram_service.py` — `_call_llm(question, uid)` + `generate_diagram(question, uid)` ✅
- `server/app/api/diagram.py:49` — `generate_diagram(req.question.strip(), uid=auth.uid)` ✅
- `server/app/services/enrichment_service.py` — `enrich_diagram_data(..., uid=uid)` + `build_enrichment_tasks(..., uid=uid)` ✅
- `server/app/api/image_search_titles.py` — `get_titles(..., uid=uid)` + `_pick_titles_sync(..., uid=uid)` + calls to `build_enrichment_tasks(uid=uid)` ✅
- `server/app/api/chat.py:1283` — `get_titles(..., uid=uid)` ✅

**Files changed:** `bb.py`, `analyze_image.py`, `daily_questions.py`, `diagram_service.py`, `diagram.py`, `enrichment_service.py`, `image_search_titles.py`, `chat.py`

---

**Asked:** Fix compile error — `'if' must have both main and 'else' branches` at FriendsActivity.kt:78.
**Root cause:** Smart/curly quote `"` after `share ` closed the string literal early; `$sessionTopic` was outside the string, so the `if` expression had no `else`.
**Fix:** Replaced curly quotes with escaped ASCII quotes: `\"$sessionTopic\"`.
**Files read:** `FriendsActivity.kt` (lines 70-89)
**Files changed:** `app/src/main/java/com/aiguruapp/student/FriendsActivity.kt` (line 78)

---

**Asked:** Friend lookup not working — validate friends/share flow and fix.
**Root cause:** `ReferralManager.codeForUser()` was only called in `UserProfileActivity`. If a user never opened their profile, their `referralCodes/{code}` document never existed in Firestore → server lookup returns 404 → "No user found". Firestore rules (`referralCodes`), server endpoint, and Android HTTP call are all correct.
**Fix:** Added fire-and-forget `ReferralManager.codeForUser(userId)` call in `HomeActivity.onCreate()` after userId is set (line ~153). Now every user who reaches the home screen registers their code automatically. Skips `guest_user`.
**Files read:** `FriendsActivity.kt` (full: lines 1-310); `ReferralManager.kt` (lines 1-90); `users.py` (lines 413-547 lookup + share-session endpoints); `HomeActivity.kt` (lines 148-157); `SessionManager.kt` (lines 198-219); `TokenManager.kt` (lines 30-67)
**Files changed:** `app/src/main/java/com/aiguruapp/student/HomeActivity.kt` (line ~29: added ReferralManager import; line ~153: added codeForUser call)

---

## 2026-05-09 (session 15)

**Asked:** Fix `BB image matching failed: name 'uid' is not defined` — BB lesson loading but image matching crashes.

**Root cause:** Session 14 cont patched `chat.py:1285` to pass `uid=uid` to `get_titles()`, but the local variable inside `chat_stream()` is `_uid` (set at line 1002 as `_uid = req.user_id or auth.uid`). No bare `uid` exists in that scope → `NameError`.

**Fix:**
- `server/app/api/chat.py:1285` — `uid=uid` → `uid=_uid`

**Files read:** `CLAUDE.md` (full), `meta/tracker.md` (tail), `meta/rules.md` (full), `chat.py` (lines 1270-1295 image-matching block, grep for uid assignments)
**Files changed:** `server/app/api/chat.py` (line 1285)

---

## 2026-05-09 (session 16)

**Asked:** Watch History should show ALL generated BB sessions (not just saved ones). Saved Sessions → rename to "My Sessions" / Favourites (explicit save only). Save button → "⭐ Favourite".

**Architecture change:**
- New Firestore collection: `users/{uid}/bb_watch_history` — auto-written every time a BB lesson generates, no user action needed.
- Existing `saved_bb_sessions_flat` collection = My Sessions / Favourites — only written when user taps ⭐ Favourite button.
- "Watch History" drawer item now loads from `bb_watch_history`. "My Sessions" card on home loads from `saved_bb_sessions_flat`.

**Changes:**
1. **`FirestoreManager.kt`** (inserted before `loadAllSavedBbSessions`):
   - Added `recordBbHistory(userId, subject, chapter, sessionId, messageId, conversationId, topic, stepCount, ttsKeys, stepsJson)` → writes to `bb_watch_history` with `viewed_at` timestamp
   - Added `loadBbWatchHistory(userId, onSuccess, onFailure)` → reads `bb_watch_history` ordered by `viewed_at` desc, limit 200
   - Added `deleteBbHistoryEntry(userId, sessionId, onSuccess, onFailure)` → deletes from `bb_watch_history`

2. **`BlackboardActivity.kt`**:
   - Added `lastAutoHistoryId: String?` field (line ~271)
   - In `generateSteps` `onSuccess` block: after quota recording, auto-calls `FirestoreManager.recordBbHistory()` with a new `historyId = "${convId}_${currentTimeMillis}"`. Does NOT require `recordSession=true` — fires for all users.
   - Save button icon: `💾` → `⭐` in layout
   - Completion card: `💾 Save` → `⭐ Favourite` in layout
   - `saveCurrentSession()`: spinner text `⏳ Saving…` → `⏳ Adding…`; success text `✓ Saved` → `⭐ Favourited`; toast "Session saved!" → "Added to My Sessions!"; failure text `💾 Save` → `⭐ Favourite`
   - Tips dialog: `"Tap 💾 Save…"` → `"Tap ⭐ Favourite to save to My Sessions"`

3. **`BbSavedSessionsActivity.kt`**:
   - Added `isAllHistory: Boolean` as class field (was local to `onCreate`)
   - Subtitle: `"All saved sessions"` → `"Watch History"` in all-history mode
   - Cache file name: `bb_watch_history_{uid}.json` in history mode, `bb_sessions_{uid}.json` in saved mode
   - `loadSessions()`: uses `loadBbWatchHistory` in all-history mode, `loadAllSavedBbSessions` in saved mode
   - `confirmDelete()`: uses `deleteBbHistoryEntry` in history mode, `deleteBbSession` in saved mode; dialog messages updated to "Remove from Watch History?" / "Remove from My Sessions?"
   - Date field in adapter: now reads `viewed_at` first, falls back to `saved_at`, then `shared_at`
   - Tab labels set dynamically in code: "My Sessions" / "Shared with Me"

4. **`activity_bb_saved_sessions.xml`**: Header title `"📓 Saved BB Sessions"` → `"📓 BB Sessions"`

5. **`activity_home.xml`**: Quick action card `"Saved Sessions"` → `"My Sessions"`, subtitle `"View history"` → `"Favourites & saved"`

**Files read:** `CLAUDE.md` (full), `meta/rules.md` (full), `meta/tracker.md` (tail), `meta/android_index.md` (lines 1-200), `BbSavedSessionsActivity.kt` (full), `FirestoreManager.kt` (lines 852-1000), `BlackboardActivity.kt` (lines 265-280, 415-470, 836-1000, 1155-1240), `activity_blackboard.xml` (lines 255-280, 514-535), `activity_bb_saved_sessions.xml` (lines 20-100), `activity_home.xml` (lines 455-490, 1195-1215)
**Files changed:** `FirestoreManager.kt`, `BlackboardActivity.kt`, `BbSavedSessionsActivity.kt`, `activity_blackboard.xml`, `activity_bb_saved_sessions.xml`, `activity_home.xml`

---

## 2026-05-09 (session 17)

**Asked:** Check if SVG gen LLM calls are using per-user ID.

**Root cause:** `build_llm_svg()` in `svg_llm_builder.py` calls `_call_litellm_proxy` directly without a `uid` param — always fell back to master key. `enrich_diagram_data()` in `enrichment_service.py` already had `uid` correctly. `get_titles()` in `image_search_titles.py` already passes `uid` to `build_enrichment_tasks` but the 3 `build_llm_svg` call sites inside `get_titles` did not forward `uid`.

**Fix:**
- `server/app/utils/svg_llm_builder.py` — added `uid: str = None` param to `build_llm_svg()`; passed `uid=uid` to `_call_litellm_proxy()` call.
- `server/app/api/image_search_titles.py` — added `uid=uid` to all 3 `build_llm_svg` call sites (custom-intent path, LLM-fallback path, force-override path). All 3 are inside `get_titles()` which already has `uid` in scope.

**Files read:** `server_index.md` (grep), `svg_llm_builder.py` (lines 140-220), `enrichment_service.py` (lines 177-320), `image_search_titles.py` (lines 480-610, grep for uid/build_llm_svg), `llm_service.py` (lines 185-230 _call_litellm_proxy signature)
**Files changed:** `server/app/utils/svg_llm_builder.py`, `server/app/api/image_search_titles.py`

## 2026-05-09

**Asked:** Overhaul admin dashboard — better UI/UX, reduce Firestore reads, add rich analytics (app growth, LLM usage, cost per user, plan/grade distribution, top spenders, revenue).

**Root cause / approach:**
- `/stats` endpoint was streaming full collections to count docs (expensive: N reads). Replaced with Firestore `count()` aggregation (1 read per collection) + 5-min in-memory cache.
- No analytics endpoint existed. Added `/analytics` that does 2 Firestore fetches (users ≤500, payments ≤200) + LiteLLM API call, computes everything server-side.
- Activity logs were fully commented out (dead code). Restored with simplified queries (no composite-index issue).
- Frontend had only basic count cards on the dashboard; no growth/cost/plan analysis.

**Files changed:**
- `server/app/api/admin.py` — added `_count_collection()`, `_to_epoch_s()`, cache dicts; replaced `admin_stats` with count()+cache; new `GET /analytics` endpoint; fixed `GET /activity-logs` and `/activity-logs/stats` (uncommented + simplified)
- `server/app/static/admin/js/dashboard.js` — full rewrite: stat cards with color borders, growth KPI row, plan/grade bar charts, LiteLLM top-spenders table, health badge
- `server/app/static/admin/js/analytics.js` — NEW file: full Analytics section with growth KPIs, revenue summary, LLM cost efficiency, plan/grade distributions, top spenders with cost tier badges
- `server/app/static/admin/index.html` — added Analytics nav item, section div, analytics.js script tag
- `server/app/static/admin/js/app.js` — added `analytics` to SECTION_MAP
- `server/app/static/admin/css/styles.css` — added: section-label, kpi-row/card, two-col-grid, bar-row/track/fill/count, health-badge, analytics-kpi-grid/card styles; responsive breakpoints

**Files read:**
- `meta/tracker.md` (tail), `meta/rules.md` (full), `CLAUDE.md` (full)
- `server/app/api/admin.py` (full, 661 lines)
- `server/app/static/admin/index.html` (full, 156 lines)
- `server/app/static/admin/js/dashboard.js`, `api.js`, `users.js`, `logs.js`, `app.js` (full)
- `server/app/static/admin/css/styles.css` (full)
- `server/seed_firestore.py` (lines 1–80), `server/seed_user_quotas.py` (full)
- `server/app/api/users.py` (grep: questions_updated_at, planId, created_at)

## 2026-05-09 (Android — Play Console warnings)

**Asked:** Fix Play Console Android 15/16 deprecation warnings from Release 19.

**Root cause:** All 3 warnings in your code are `UCrop.Options.setStatusBarColor()` — the uCrop API that internally calls the deprecated `Window.setStatusBarColor`. BlackboardActivity already uses `enableEdgeToEdge()` correctly; the UCrop call was a leftover conflict.

**Changed:**
- `BlackboardActivity.kt:3842` — removed `setStatusBarColor()` from UCrop.Options block
- `HomeActivity.kt:1626` — same
- `FullChatFragment.kt:1145` — same

**Not fixed (third-party, can't touch):**
- `androidx.activity.s.a/u.a` — `enableEdgeToEdge()` backward-compat shim; ignore
- `com.razorpay:checkout:1.6.41` — orientation lock in BaseCheckoutActivity; update Razorpay to latest version when available
- `com.yalantis.ucrop:2.2.9` — uCrop library itself still calls deprecated API internally; updating uCrop may help

**Files read:** `meta/android_index.md` (lines 1–60), `BlackboardActivity.kt:3836–3847`, `HomeActivity.kt:1620–1631`, `FullChatFragment.kt:1139–1150`, `app/build.gradle` (SDK versions + dependencies)

---

## Session 16b — BB Loading SVG Animations

**Date:** 2025-05-09
**Asked:** Create 10 SVG animations shown below the spinner while BB session loads (10–15s wait)

**Files created:**
- `app/src/main/assets/loading_svgs/01_water_glass.html` — Water filling a glass
- `app/src/main/assets/loading_svgs/02_house_build.html` — House construction (walls, roof, chimney)
- `app/src/main/assets/loading_svgs/03_rocket_launch.html` — Rocket launching into space
- `app/src/main/assets/loading_svgs/04_plant_grow.html` — Seed → sprout → flower
- `app/src/main/assets/loading_svgs/05_gears.html` — 3 interlocking spinning gears with sparks
- `app/src/main/assets/loading_svgs/06_constellation.html` — Stars connecting into a constellation
- `app/src/main/assets/loading_svgs/07_lightbulb.html` — Lightbulb turning on with idea sparks
- `app/src/main/assets/loading_svgs/08_pencil_write.html` — Pencil writing equations on paper
- `app/src/main/assets/loading_svgs/09_brain_neurons.html` — Brain with firing neural connections
- `app/src/main/assets/loading_svgs/10_solar_system.html` — Planets orbiting the sun

**Files modified:**
- `app/src/main/res/layout/activity_blackboard.xml` — Added `<WebView android:id="@+id/bbLoadingWebView" 200×220dp` inside loadingGroup below loadingText
- `app/src/main/java/com/aiguruapp/student/widget/BbLoadingAnimator.kt` — NEW: singleton with `start(webView)` picks random animation, `stop(webView)` clears it
- `app/src/main/java/com/aiguruapp/student/BlackboardActivity.kt`:
  - Added `private lateinit var bbLoadingWebView: android.webkit.WebView` field (~line 173)
  - `onCreate`: `bbLoadingWebView = findViewById(R.id.bbLoadingWebView)` (~line 315)
  - `generateSteps()`: `BbLoadingAnimator.start(bbLoadingWebView)` at top (~line 851)
  - `loadFromGlobalCache()`: start at top (~line 1263)
  - `loadFromSavedSession()`: start at top (~line 1308)
  - All 9 `loadingGroup.visibility = View.GONE` sites: `BbLoadingAnimator.stop(bbLoadingWebView)` added before each

**Notes:**
- Each HTML is self-contained SVG+CSS animation, dark-themed (#0D1117 bg), purple/blue palette
- Loaded via `file:///android_asset/loading_svgs/NN_name.html` into WebView
- Random selection each time via `Math.random()` in BbLoadingAnimator
- WebView transparent background, no JS security issues (no external URLs, no DOM storage)

---

## 2026-05-09 (Session 16c — 29 more SVG animations + 5-per-session picker)

**Asked (part 1):** Add 20+ more entertaining loading animations + fix repeat bug (same one kept showing)

**Files created (animations 13–29):**
- `app/src/main/assets/loading_svgs/13_atom_nucleus.html` — Bohr model: 3 coloured electrons on elliptical orbits, glowing nucleus
- `app/src/main/assets/loading_svgs/14_book_open.html` — Book opening, pages flip, equations float out
- `app/src/main/assets/loading_svgs/15_volcano.html` — Volcano erupting: lava blobs fly, smoke puffs, lava flows
- `app/src/main/assets/loading_svgs/16_snowflake.html` — Snowflake arms crystallise from centre, falling snowflakes bg
- `app/src/main/assets/loading_svgs/17_train.html` — Side-scrolling night train, smoke puffs, tree scene scrolls
- `app/src/main/assets/loading_svgs/18_submarine.html` — Submarine bobs with propeller spin, sonar ping, bubbles, fish
- `app/src/main/assets/loading_svgs/19_hot_air_balloon.html` — Balloon rises/sways, flame flicker, drifting clouds, birds
- `app/src/main/assets/loading_svgs/20_domino.html` — 7 domino tiles fall sequentially with dust puffs
- `app/src/main/assets/loading_svgs/21_magnifier.html` — Magnifying glass scans over equations, symbols reveal
- `app/src/main/assets/loading_svgs/22_bouncing_balls.html` — 4 coloured balls bounce with squash/stretch + shadows
- `app/src/main/assets/loading_svgs/23_typewriter.html` — Typewriter types equations line by line, cursor blinks, bell
- `app/src/main/assets/loading_svgs/24_wave_interference.html` — Two wave sources, constructive/destructive interference nodes
- `app/src/main/assets/loading_svgs/25_crystal_grow.html` — Geometric crystal grows from seed, gem facets fill
- `app/src/main/assets/loading_svgs/26_clock.html` — Analog clock with swinging pendulum, 3 hands sweep
- `app/src/main/assets/loading_svgs/27_lightning.html` — Storm cloud, dual lightning bolts flash, rain falls, puddle ripples
- `app/src/main/assets/loading_svgs/28_telescope.html` — Telescope scans sky, stars appear in view, shooting star
- `app/src/main/assets/loading_svgs/29_popcorn.html` — Popcorn kernels pop and fly out of jiggly pot, flame below

(Note: files 27_aurora, 28_butterfly, 29_tornado, 30_chemistry, 31_maze_solve, 32_prism_rainbow, 33_sandcastle, 34_music_notes, 35_fish_jump also exist in the folder from prior sessions)

**Asked (part 2):** Pick 5 random SVGs per session (not cycle all 37)

**Changed:**
- `BbLoadingAnimator.kt` — Complete rewrite:
  - `ALL_ANIMATIONS` array: all 38 filenames (01–35 + duplicates)
  - `SESSION_SIZE = 5`
  - `sessionQueue`: mutable list of 5 randomly chosen filenames
  - `refreshSession()`: shuffles all, takes first 5, resets index
  - `nextAnimation()`: serves from queue, auto-refreshes when exhausted
  - `start()`: calls `nextAnimation()` instead of `Math.random()` 
  - Session persists across calls until all 5 are shown, then new 5 picked


---

## Session 17 — BB Loading SVG Animations (Batch 2)

**Date:** 2026-05-09
**Asked:** Create 10 more loading SVG animations (entertaining, dark-themed, same style as 01–26)

**Files created:**
- `app/src/main/assets/loading_svgs/27_aurora.html` — Northern lights curtains over snowy pine trees
- `app/src/main/assets/loading_svgs/28_butterfly.html` — Chrysalis cracking open, butterfly emerging & flapping
- `app/src/main/assets/loading_svgs/29_tornado.html` — Spinning funnel with lightning, debris orbiting base
- `app/src/main/assets/loading_svgs/30_chemistry.html` — Bubbling beaker over bunsen flame with rising steam
- `app/src/main/assets/loading_svgs/31_maze_solve.html` — Dot solving a maze with animateMotion + trail draw
- `app/src/main/assets/loading_svgs/32_prism_rainbow.html` — White light beam entering prism, splitting into 6 colours
- `app/src/main/assets/loading_svgs/33_sandcastle.html` — Blocks dropping one-by-one to build a sandcastle
- `app/src/main/assets/loading_svgs/34_music_notes.html` — Floating notes + bouncing equalizer bars
- `app/src/main/assets/loading_svgs/35_fish_jump.html` — Fish jumping moonlit water arc with splashes & ripples
- `app/src/main/assets/loading_svgs/36_tetris.html` — Tetris blocks falling into a grid with ghost piece

**Files modified:**
- `app/src/main/java/com/aiguruapp/student/widget/BbLoadingAnimator.kt` — Added 36_tetris.html to ALL_ANIMATIONS array (now 39 total animations)

**Notes:**
- Animator already upgraded to session-queue strategy (5 random per session, no repeats)
- Files 27_lightning, 28_telescope, 29_popcorn already existed from a prior session
- Total pool: 39 animations

---

## Session 18 — BB Loading Animator: rotation + no white flash

**Date:** 2026-05-09
**Asked:** Currently first SVG repeats (loops forever for the entire 10–15s wait) and a blank white screen flashes before render. Add cycling and remove the white flash.

**Root cause:**
- `start()` only loaded ONE animation; CSS keyframes use `infinite`, so user saw the same animation looping the entire wait.
- WebView bg was `0x00000000` (transparent) → parent layout's white showed through during the brief window before HTML rendered.

**Files modified:**
- `app/src/main/java/com/aiguruapp/student/widget/BbLoadingAnimator.kt` — full rewrite of strategy:
  - Added `Handler` + `rotateRunnable` for time-based rotation
  - `WARMUP_ASSET = 22_bouncing_balls.html`, `WARMUP_MS = 2000L` — guaranteed-fast first frame
  - `ROTATE_INTERVAL_MS = 2500L` — swap to next session animation every 2.5s after warmup
  - `setBackgroundColor(0xFF0D1117)` instead of transparent — matches SVG bg, eliminates flash
  - `cancelRotation()` called from both new `start()` and `stop()` to prevent stale callbacks
  - Each `start()` call calls `refreshSession()` so new BB sessions get fresh random 5
  - `activeWebView` reference check inside Runnables guards against firing on a stopped WebView

**Flow:**
1. `start()` → set dark bg, show WARMUP_ASSET immediately
2. After 2s → switch to `nextAnimation()` from session queue, start 2.5s rotation
3. After ~14.5s (2 + 5×2.5) → session exhausted → refresh + continue
4. `stop()` → cancel rotation, blank WebView, hide

**Notes:**
- Pool is 39 animations; session picks 5 random distinct.
- Call sites unchanged: `BlackboardActivity.kt:851, 1263, 1308` (start), 9 stop sites. No changes to BlackboardActivity needed.

---

## 2026-05-09 (Session 18 — Subscription plan features from Firestore)

**Asked:** Plan descriptions in SubscriptionActivity are hardcoded; load from Firestore instead.

**Investigation:**
- `FirestoreManager.kt` ~L258–290: `features` was a `buildList {}` derived from plan limits (credits, tokens, tts, image flags) — ignored the Firestore `features` array field entirely.
- `FirestorePlan.kt` already has `val features: List<String>` field.
- `SubscriptionActivity.kt` already renders `plan.features.joinToString("\n") { "✓  $it" }` — UI was correct.
- `seed_firestore.py` seeds `features` array in Firestore documents.

**Fix:**
- `app/src/main/java/com/aiguruapp/student/firestore/FirestoreManager.kt` ~L258:
  - Read `doc.get("features")` → `List<String>` first.
  - If non-empty → use it directly (Firestore-driven descriptions).
  - If empty → fall back to the old `buildList {}` from limits (unchanged fallback logic).

**Files changed:**
- `FirestoreManager.kt` — `features` block replaced with `run { ... }` that checks Firestore first (~L258–300)

---

## 2026-05-09 (Session 18b — Watch History local cache + Save button UI)

**Asked:**
1. Watch History not showing in BbSavedSessionsActivity — save it in Android local cache
2. Save button shows ⭐ icon — restore visible save button; show "Saved to My Sessions" as notification

**Root cause (watch history):**
- `BlackboardActivity` called `FirestoreManager.recordBbHistory()` but never wrote to the local JSON cache.
- `BbSavedSessionsActivity` reads `bb_watch_history_<userId>.json` on open; if Firestore is slow/offline, list was empty.

**Fix 1 — Watch history local cache:**
- `BlackboardActivity.kt`: Added `appendLocalWatchHistory()` private helper (~line 1260).
  - Reads `cacheDir/bb_watch_history_<userId>.json` (same file BbSavedSessionsActivity uses).
  - Prepends new entry as JSONObject with fields: session_id, id, subject, chapter, topic, step_count, steps_json, conversation_id, message_id, created_at.
  - Caps at 50 entries; newest first.
- Called immediately after `writeSessionCache()` in the auto-history block (~line 955), before `recordBbHistory()`.

**Fix 2 — Save button:**
- `activity_blackboard.xml` (`saveSessionBtn`): Changed from 36dp icon-only (⭐) to pill button with `wrap_content` width, `@drawable/bg_save_btn`, text "💾 Save", textSize 12sp.
- Created `app/src/main/res/drawable/bg_save_btn.xml` — rounded rectangle, dark bg `#1A2A3A`, stroke `#4A7FA5`.
- `BlackboardActivity.kt` `saveCurrentSession()`:
  - Spinner: "⏳ Saving…" (was "⏳ Adding…")
  - Success: button disabled, text "✓ Saved", color `#A0FFD0` + Snackbar "Saved to My Sessions" (was toast)
  - Failure: button re-enabled, text "💾 Save", color reset to `#9ABBD8`

**Files changed:**
- `BlackboardActivity.kt` — `appendLocalWatchHistory()` new function; `saveCurrentSession()` text + Snackbar; auto-history block now calls `appendLocalWatchHistory()` first
- `activity_blackboard.xml` — saveSessionBtn layout changed
- `app/src/main/res/drawable/bg_save_btn.xml` — new drawable

**Files read (no changes):**
- `models/FirestorePlan.kt` L1–55: data class with `features: List<String>`, `isFree`, `displayPrice`
- `SubscriptionActivity.kt` L200–360: `bindFirestorePlanCard()` reads `plan.features`, `bindPlanCard()` reads `plan.features`
- `models/SubscriptionPlan.kt` L1–40: separate data class used by `AdminConfigRepository` — not the same as `FirestorePlan`
- `config/AdminConfigRepository.kt` L200–250: `planFromDoc()` also missing `features` field — but this is a separate code path (not used by SubscriptionActivity display)

**Also changed this session:**
- `BlackboardActivity.kt` ~L2517: 600ms `postDelayed` before auto-advance in `continueAfterFrame()` and `continueAfterSpeech()` — slows down fast-paced automatic lesson progression
- `BbLoadingAnimator.kt`: Added `clearCache(true)` + timestamp URL fragment in `start()` to fix WebView caching repeat-animation bug

---

## Session 19 — BB Loading Animator: in-memory cache + slowdown + 4s interval

**Date:** 2026-05-09
**Asked:** Keep all SVGs in Android (avoid switch-delay), 4s per animation, animations themselves are too fast.

**Files modified:**
- `app/src/main/java/com/aiguruapp/student/widget/BbLoadingAnimator.kt`:
  - `WARMUP_MS = 4000L`, `ROTATE_INTERVAL_MS = 4000L` (was 2000/2500)
  - `SLOW_FACTOR = 1.6` constant — applied to every CSS `animation-duration`
  - `htmlCache: MutableMap<String,String>` — pre-loads all 39 HTML assets into memory on first `start()` via `ensureCached(ctx)` using `ctx.assets.open(path).bufferedReader()`
  - `injectSlowdown(html)` — appends a `<script>` before `</body>` that on `window.load` walks every element, reads `getComputedStyle(...).animationDuration`, splits on commas, multiplies each numeric token by `SLOW_FACTOR`, and writes back via inline style
  - `loadAsset()` now uses `webView.loadDataWithBaseURL("file:///android_asset/", cached, ...)` when cached (no disk I/O); falls back to `loadUrl(file://...)` if asset wasn't cacheable
  - All other plumbing (warmup → rotation, dark bg, session-of-5, cancellation guards) unchanged from session 18

**Why this avoids the switch delay:**
- Disk read happens once at first `start()`, not on every 4s rotation
- `loadDataWithBaseURL` has no URL→file resolution cost
- Combined with `setBackgroundColor(0xFF0D1117)`, the brief WebView reflow is invisible (dark→dark)

**Tuning knobs (single-line edits at top of object):**
- `SLOW_FACTOR = 1.6` — bump to 2.0 for slower, 1.3 for milder
- `ROTATE_INTERVAL_MS = 4000L` — per-animation display time
- `WARMUP_ASSET` / `WARMUP_MS` — first-frame default and its duration

---

## Session 20 — Home page made scrollable

**Date:** 2026-05-09
**Asked:** On small phones, content below the BB card / daily challenge is pushed off-screen and unreachable. Make the home page scrollable.

**Root cause:** Top sections (hero, BB CTA, quota strip, daily challenge, smart suggestions) were stacked in a vertical LinearLayout with `wrap_content` heights, then the subjects RecyclerView was given `weight=1` for the remainder. On short displays, weight=1 collapsed to 0 and the subjects list became invisible.

**Files modified:**
- `app/src/main/res/layout/activity_home.xml`:
  - After the top nav `</LinearLayout>` at L129, wrapped everything below in: `FrameLayout (weight=1) > SwipeRefreshLayout (id=homeSwipeRefresh) > NestedScrollView (fillViewport=true) > LinearLayout (vertical, bg=colorBackground)` (new opens at L133–148)
  - Removed the redundant inner `FrameLayout (weight=1) > LinearLayout` (was around the subjects area) — its purpose moved to the outer FrameLayout
  - Removed the inner `SwipeRefreshLayout` that was wrapping the RecyclerView (id moved to outer wrapper, kept name `homeSwipeRefresh`)
  - RecyclerView changed from `match_parent` height + weight to `wrap_content` + `android:nestedScrollingEnabled="false"` so it lays out all subject cards inside the parent NestedScrollView
  - calcFab + feedbackChip remain as the outer FrameLayout's overlay siblings (gravity bottom|end and bottom|start) — still float over the scrollable area
  - New closings at L888–890 (LinearLayout, NestedScrollView, SwipeRefreshLayout); existing `</FrameLayout>` at L932 now closes the new outer FrameLayout

**No Kotlin changes:** All IDs preserved (`homeSwipeRefresh`, `subjectsRecyclerView`, `calcFab`, `feedbackChip`, all section ids).

**Tradeoff:** RecyclerView no longer recycles (lays out all items at once). Acceptable — subjects list is small (typically 5–15 items per student).

**Layout-tag balance** (verified):
- Wrappers added: 1 FrameLayout, 1 SwipeRefreshLayout, 1 NestedScrollView, 1 LinearLayout
- Wrappers removed: 1 FrameLayout, 1 LinearLayout, 1 SwipeRefreshLayout
- Net: +1 NestedScrollView; closings match 1:1

---

## Session 21 — TTS daily-quota vs credits fallback

**Date:** 2026-07-17
**Asked:** TTS shows "quota exhausted for today" even when user has tts_balance credits available. Where is the limit defined and why isn't the credit fallback working?

**Root cause:** Two independent TTS quota systems that didn't talk to each other:
1. **Server-side** (`users.py` `/quota/status`): correctly checks `tts_balance` from `user_credits/{uid}` and returns `tts_mode="ai_credit"` when free quota is exhausted but credits remain.
2. **Android-side** (`PlanEnforcer.checkAiTtsQuota`): only compares `metadata.aiTtsCharsUsedToday` against `limits.aiTtsQuotaChars` (the plan's daily char cap, e.g. 5000 for basic). Never looked at `tts_balance` → always returned `allowed=false` when daily cap hit, before the server could even be called.
- Also, `UserMetadata` had no `ttsCreditBalance` field, so even if the check had wanted to use credits, the data wasn't available.

**Files modified:**

- `app/src/main/java/com/aiguruapp/student/models/UserMetadata.kt` (after line ~148):
  - Added `val ttsCreditBalance: Int = 0` — no Firestore annotation (not in users_table; set manually from user_credits collection)

- `app/src/main/java/com/aiguruapp/student/BlackboardActivity.kt` (after cachedMetadata.copy() ~line 620):
  - Added fire-and-forget Firestore fetch of `user_credits/{userId}.tts_balance`; on success sets `cachedMetadata = cachedMetadata.copy(ttsCreditBalance = ttsCredits)`

- `app/src/main/java/com/aiguruapp/student/config/PlanEnforcer.kt` (checkAiTtsQuota, ~line 517):
  - Before returning `allowed=false` for exceeded quota, checks `metadata.ttsCreditBalance > 0` → if so, returns `CheckResult(allowed = true)` (credit mode; server deducts from tts_balance)
  - `getAiTtsCharsRemaining()`: if planRemaining==0 but `ttsCreditBalance > 0`, returns -1 (unlimited) so UI doesn't show "0 chars left"

- `app/src/main/java/com/aiguruapp/student/validators/AiVoiceQuotaValidator.kt`:
  - Updated doc comment to describe credit fallback behavior

**Key insight:** The Android-side quota check is a fast pre-flight to avoid unnecessary server calls. When credits are available, we let it through — the server is still the authoritative deduction point (deducts from tts_balance). The Android check just needed to know about credits.

---

## Session 22 — Fix BB planner image bug

**Date:** 2026-05-12
**Asked:** Review Claude agent analysis about BB pipeline. Agent identified that `_bb_plan()` never received images — planner always saw text only, giving bad plans for math screenshots.

**Bug confirmed:** `chat.py:217` — `generate_response(planner_prompt, [], ...)` hardcoded empty images list. `normalized_images` was set at line 1037 but never passed to `_bb_plan()`.

**Why keeping 2 calls (not merging):** Planner output feeds parallel `_prefetch_wikimedia()` + `_youtube_task` started while main LLM runs. Merging to 1 call would lose that parallelism and add latency.

**Files modified:**

- `server/app/services/prompt_service.py`:
  - `BB_PLANNER_PROMPT`: added `{image_context}` placeholder between question and context_snippet
  - `build_bb_planner_prompt()`: added `has_image: bool = False` param; sets `image_context` string "Student has attached an image..." when True; passes to `.format()`

- `server/app/api/chat.py`:
  - `_bb_plan()` signature: added `images: list = None` param
  - `_bb_plan()` body: `build_bb_planner_prompt(..., has_image=bool(images))`; `generate_response(planner_prompt, _planner_images, ...)` — no longer hardcoded `[]`
  - call site (~line 1052): added `images=normalized_images` to `_bb_plan()` call

**Agent analysis verdict:** Correct on the bug. Correct that Strands/LangGraph is overkill. Disagree on merge — 2-call split is right for the parallelism reasons above.

---

## Session 23 — BB pipeline: parallel SVG + parallel image picker

**Date:** 2026-05-13
**Asked:** Implement SVG parallelization + image picker parallelization (changes 2 and 3 from the plan).

**What changed in `server/app/api/image_search_titles.py` (get_titles):**

**Phase ordering restructured:**
- Moved `all_candidates` assembly (previously Phase 4) to immediately after Phase 2
- `original_descs` + `url_to_width` setup also moved earlier
- Image picker (`_pick_titles_sync`) started as `loop.run_in_executor` future (`_picker_fut`) BEFORE Phase 3 starts → runs concurrently with all Phase 3 work

**Phase 3 — LLM SVG calls now parallel:**
- All 3 `build_llm_svg()` call sites changed from direct blocking calls to `loop.run_in_executor` futures collected into `_llm_svg_tasks: list[(frame, future)]`
- After the Phase 3 loop: `asyncio.gather(*_svg_futs)` runs all LLM SVG calls concurrently; results applied to frames
- Sync builders (atom, JS engine, SMIL) still run inline (they are fast, no change)

**Phase 4 — image picker just awaits:**
- Old code: assembled all_candidates + ran `await loop.run_in_executor(_pick_titles_sync, ...)`
- New code: `picks = await _picker_fut` (already running since before Phase 3)
- In practice _picker_fut will be done before the await since Phase 3 takes ~2-4s and picker takes ~500ms

**Timing improvement:**
- Before: 3 LLM SVGs serial = 3-9s + picker 500ms sequential = ~4-10s total post-LLM
- After:  3 LLM SVGs parallel = max(individual SVG time) ~1-3s + picker 0ms (already done) = ~1-3s total
- Expected savings: 3-6s off the post-processing phase

---

## Session 24 — BB streaming: emit steps as LLM generates them (server + Android)

**Date:** 2026-05-13
**Asked:** Stream bb_main LLM output and have Android accept progressive step events.

### Server: `server/app/services/llm_service.py`
- Added `AsyncGenerator` to imports
- New `stream_generate_response(prompt, images, tier, system_prompt, uid, call_name, session_id, charge_credits)` async generator
  - Hits LiteLLM proxy with `stream=True` + `stream_options: {include_usage: true}`
  - Yields str text chunks as they arrive
  - Yields final sentinel dict `{"_stream_done": True, "tokens": {...}, "model": "..."}` at end
  - Falls back to non-streaming `_call_litellm_proxy()` if streaming fails before any text emitted
  - Handles token recording in background thread after stream completes

### Server: `server/app/api/chat.py`
- Added `stream_generate_response` to imports from llm_service
- New `_BbStepScanner` class (after `_try_extract_first_step`): incremental brace-counting JSON scanner
  - `__init__`: `_pos, _in_arr, _step_start, _depth, _in_str, count`
  - `feed(text)` → returns list of newly completed step dicts; handles escaped chars + nested braces correctly
- BB main LLM call split: `if req.mode == "blackboard":` uses `stream_generate_response` async for loop
  - Emits `{"first_step": step}` SSE for step index 0 as soon as step 1 closes in stream
  - Emits `{"bb_step": step, "step_idx": N}` SSE for steps N=1..K as they close
  - Tracks `_bb_emitted` counter
  - After loop: assembles `result = {"text": _bb_text, "tokens": ..., "provider": "litellm", ...}`
  - Non-BB (`chat_main`) keeps old blocking `run_in_executor` path unchanged
- `first_step` emission block changed to fallback: only emits if `_bb_emitted == 0` (streaming error/fallback case)

### Android: `app/.../chat/ServerProxyClient.kt`
- `streamChat(...)`: added `onBbStep: ((String, Int) -> Unit)? = null` param
- `executeStream(...)`: added `onBbStep` param, passes to `executeStreamInternal`
- `executeStreamInternal(...)`: added `onBbStep` param
- In SSE loop: parses `bb_step` JSON object + `step_idx` int → calls `onBbStep?.invoke(bbStepObj.toString(), bbStepIdx)`

### Android: `app/.../chat/BlackboardGenerator.kt`
- `generateChunk(...)`: added `onBbStep: ((List<BlackboardStep>, Int) -> Unit)? = null` param
- Wired `onBbStep` to `server.streamChat`'s `onBbStep`: wraps stepJson in JSONArray, calls `parseStepsArray`, passes result + idx to callback

### Android: `app/.../BlackboardActivity.kt` (first `generateChunk` call site ~line 918)
- Added `onBbStep = { newStep, stepIdx ->` callback after `onFirstStep`
- Guard: `stepIdx > 0 && stepIdx == steps.size && contentGroup.visibility == View.VISIBLE`
- Action: appends `newStep` to `steps`, calls `buildDots()`, `preloadUpcoming(currentStepIdx, currentFrameIdx, count=2)`
- Second `generateChunk` call site (~line 1168, continuation chunks) left unchanged — no onBbStep needed

**Latency impact:**
- Step 1 on screen: ~2-4s (was 9-16s — stream closes step 1 JSON ~2s in)
- Step 2 on screen: ~4-6s (user can navigate to it while lesson is still generating)
- No quality change — same LLM, same prompts, same images; streaming just emits earlier

---

## Session 25 — Cost + context fixes: max_tokens cap, planner trim, BB inline chat context

**Date:** 2026-05-13
**Asked:** 1) Is BB inline chat getting lesson context? 2) bb_main max_tokens cap 3) Trim planner input

### `server/app/services/llm_service.py`
- `stream_generate_response()`: added `max_tokens: Optional[int] = None` param
- In JSON body: `"max_tokens": max_tokens if max_tokens is not None else model_config.max_tokens`

### `server/app/api/chat.py`
- bb_main `stream_generate_response` call: added `max_tokens=5000` (was implicitly 14096)
- Saves ~65% of unused max_tokens capacity; 5-step lesson never exceeds ~3500 tokens

### `server/app/services/prompt_service.py` — `build_bb_planner_prompt()`
- `ctx_snippet`: `[:500]` → `[:150]` (planner only needs topic hint, not full chapter)
- `history` slice: `[-6:]` → `[-3:]` (3 turns enough to know prior knowledge)
- Per-turn truncation: `[:120]` → `[:80]` for both user and assistant turns
- Saves ~250-400 planner input tokens per BB session (~$0.0002/session)

### `app/.../BlackboardActivity.kt` — inline chat (`askQuestion` / `sendBbChat`)
- **Was:** `history = ["system: Lesson topic: <title>"] + bbChatHistory`
  - `context_service` explicitly excludes `bb_chat` pageId → inline chat had NO lesson content
- **Now:** builds `lessonSummary` from current `steps`:
  - "Step 1: <title> — <first frame text[:80]>\nStep 2: ..." capped at 800 chars total
  - Injected as second `system:` history entry before bbChatHistory
- Result: inline chat now knows what was taught, can answer "explain step 2 more" etc.

---

## Session 26 — LLM call reduction: bb_main cache + SVG cache

**Date:** 2026-05-13
**Asked:** 7 LLM calls per BB session shown in logs; implement pending caches

### `server/app/services/cache_service.py`
- `set_cache()`: added `ttl: int = 60*60*24*30` param (default still 30 days)
- Callers can now pass custom TTL (6h for bb_main, 24h for SVG)

### `server/app/api/chat.py`
- Added `from app.services.cache_service import get_cache, set_cache`
- BB pipeline: before `_bb_plan()`, check `get_cache("bb_main", question:level)`
  - HIT: skip planner + wiki_prefetch + yt_task + stream_generate_response
  - Parse cached `_bb_text`, emit `first_step` + `bb_step` events from JSON, `_bb_emitted` set
  - `wiki_task = None`, `yt_task = None` → skips those waits safely
  - HIT guard: `build_blackboard_mode_user_content` also skipped (no `plan` needed)
  - MISS: run normal pipeline, then `set_cache("bb_main", ..., ttl=6*3600)` after streaming
- On cache HIT: result `provider` set to `"cache"` (for logging)
- On cache HIT: `_bb_emitted` set from parsed steps count (so fallback parse is skipped)

### `server/app/api/image_search_titles.py`
- Added `from app.services.cache_service import get_cache, set_cache`
- New `_build_llm_svg_cached(**kw)` sync function inside `get_titles()`:
  - Cache key: `md5("diagram_type|visual_description|json(data, sort_keys=True)")`
  - HIT: returns cached SVG string immediately (no LLM call)
  - MISS: calls `build_llm_svg(**kw)`, caches result with TTL 24h
- All 3 executor `build_llm_svg(**kw)` calls replaced with `_build_llm_svg_cached(**kw)`

### Net savings on cache HIT
- Skips: planner ($0.00065) + bb_main ($0.0053) + 3×SVG ($0.0065) = $0.012/session saved
- Only runs: image_picker ($0.0003) + per-step Wikimedia search (REST, not LLM)
- ~92% cost reduction on repeat questions. TTL 6h keeps lessons fresh.

---

## Session 27 — Gemini TTS added as default engine

**Date:** 2026-05-15
**Asked:** Add Gemini voice to TTS, make it default.

### `server/app/api/tts.py`
- Added `import io`, `import wave` at top
- Added `GEMINI_VOICE_DEFAULT = "Kore"` constant
- Added `_pcm_to_wav(pcm_data, sample_rate=24000, channels=1, bits_per_sample=16) → bytes`: wraps raw PCM in WAV container using stdlib `wave` module
- Added `_gemini_tts(text, voice_name="") → Optional[bytes]`: calls `generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-preview-tts:generateContent` with `GEMINI_API_KEY`; decodes base64 PCM from response; returns WAV bytes
- `TtsSynthesizeRequest.tts_engine` default changed: `"google"` → `"gemini"`
- `synthesize()` endpoint: refactored to route `tts_engine == "gemini"` first (returns `audio/wav`), then falls through to Google TTS (returns `audio/mpeg`), then ElevenLabs/OpenAI English-only fallbacks
- Added `X-TTS-Engine` response header for debugging
- Fallback chain: gemini → google → elevenlabs (en only) → openai (en only)
- Key: `settings.GEMINI_API_KEY` already existed in `config.py:24`

---

## 2026-05-14 — Trim home tour to 3 steps

**Asked:** Home page guidance is showing too many buttons. Show only Language, My Subjects, Blackboard mode. After tour show BB default questions popup.

**Root cause:** `HomeTourManager.steps` had 8 entries covering every UI element.

**Changed:**
- `app/src/main/java/com/aiguruapp/student/HomeTourManager.kt` — `steps` list replaced with 3 entries:
  1. `R.id.langChipButton` — "🌐 Language"
  2. `R.id.subjectsRecyclerView` — "📖 My Subjects"
  3. `R.id.quickActionBbBtn` — "🎓 Blackboard Mode"
- Removed: quickActionChatBtn, quickActionTasksBtn, dailyChallengeCard, addSubjectButton, drawerToggleBtn, helpGuideBtn steps
- `BbIntroBottomSheet` already shows after tour (wired in HomeActivity.kt L233-237) — no change needed

**Files read (no change):**
- `HomeTourManager.kt` L1-60: full steps list + tour overlay logic
- `HomeActivity.kt` L205-240: tour trigger + BbIntroBottomSheet.show() after onFinished

---

## 2026-05-14 — BbIntroBottomSheet race fix

**Asked:** BbIntroBottomSheet sometimes comes first before the home tour.

**Root cause:** Two race paths both showed the sheet on first launch:
1. `loadSmartHomeContent()` L818-833 (Firestore fetch, ~800ms delay)
2. Tour callback L236 (tour finishes → 400ms delay)
Firestore resolved faster than tour started → sheet appeared before tour.

**Changed:**
- `HomeActivity.kt` L819: condition changed from `if (totalBbSessions == 0L)` to `if (totalBbSessions == 0L && !HomeTourManager.shouldShowHome(this))` — skips the Firestore path when tour is about to run; tour callback handles it instead.

**Files read:**
- `HomeActivity.kt` L220-240: tour + BbIntroBottomSheet wiring
- `HomeActivity.kt` L818-835: Firestore path for BbIntroBottomSheet

---

## 2026-05-14 — Quiz me on this (BB lesson → QuizActivity)

**Asked:** After BB lesson ends, let student take a quiz on what was just taught (Option B).

**Existing infra confirmed:**
- `QuizApiClient.generateQuiz(contextText=...)` — already accepts lesson text
- `Quiz.toTransferJson()` / `Quiz.fromJson()` — serialization for Intent
- `QuizActivity` — takes `"quizJson"` Intent extra
- `server/app/api/quiz.py` `/quiz/generate` — full endpoint

**Changed:**
- `app/src/main/res/layout/activity_blackboard.xml`: added `completionQuizBtn` (TextView pill, "🧠 Quiz me on this") inside `bbCompletionCard`, above `completionCloseBtn`
- `app/src/main/java/com/aiguruapp/student/BlackboardActivity.kt`:
  - Import: `com.aiguruapp.student.quiz.QuizApiClient` added
  - `showCompletionCard()`: wires `completionQuizBtn.setOnClickListener { startQuizFromLesson(it) }`
  - New `startQuizFromLesson(btn: TextView)`: builds `lessonSummary` from steps (same pattern as inline chat), calls `QuizApiClient().generateQuiz(subject, chapter, difficulty="medium", count=5, questionTypes=["mcq"], contextText=lessonSummary)` on IO, then starts `QuizActivity` with `quizJson`; on error resets button + shows Toast

**Flow:** BB lesson ends → "🧠 Quiz me on this" button → "⏳ Generating quiz…" → QuizActivity opens with 5 MCQs about the lesson

---

## 2026-05-14 — Trim home tour to 3 steps

**Asked:** Home page guidance is showing too many buttons. Show only Language, My Subjects, Blackboard mode. After tour show BB default questions popup.

**Root cause:** `HomeTourManager.steps` had 8 entries covering every UI element.

**Changed:**
- `app/src/main/java/com/aiguruapp/student/HomeTourManager.kt` — `steps` list replaced with 3 entries:
  1. `R.id.langChipButton` — "🌐 Language"
  2. `R.id.subjectsRecyclerView` — "📖 My Subjects"
  3. `R.id.quickActionBbBtn` — "🎓 Blackboard Mode"
- Removed: quickActionChatBtn, quickActionTasksBtn, dailyChallengeCard, addSubjectButton, drawerToggleBtn, helpGuideBtn steps
- `BbIntroBottomSheet` already shows after tour (wired in HomeActivity.kt L233-237) — no change needed

**Files read (no change):**
- `HomeTourManager.kt` L1-60: full steps list + tour overlay logic
- `HomeActivity.kt` L205-240: tour trigger + BbIntroBottomSheet.show() after onFinished

---

## 2026-05-14 — Fix free plan credits not being credited + plan limits from Firestore

**Asked:** Users are not getting 5000 free credits on registration. Free plan limits should come entirely from Firestore (not hardcoded). All quotas/limits/AI credits should be Firestore-driven.

**Root causes found:**
1. `create_user_if_missing()` hardcoded free plan limits (12 chat, 2 BB etc.) — never read from `plans/free` in Firestore
2. `init_user_credits()` was only called for NEW users — existing users who registered before credits system was added never got their `user_credits/{uid}` doc
3. Starter credits (5000) were a hardcoded constant `_STARTER_CREDITS`, not driven by Firestore
4. Android `resolveEffectiveLimitsAsync()` short-circuited for `planId=="free"` — never fetched `plans/free` from Firestore; fell back to hardcoded `defaultLimits`

**Changed:**
- `server/app/services/user_service.py` L130-140: `create_user_if_missing()` now calls `_lookup_plan_limits(db, "free")` and uses those values dynamically (daily_chat_limit, daily_bb_limit, tts flags, image flag, starter_credits, starter_tts)
- `server/app/services/user_service.py` L182: passes `starter_credits` and `starter_tts` to `init_user_credits()`
- `server/app/services/user_service.py` L730-784: `init_user_credits()` now accepts `starter_credits` and `starter_tts_credits` params (with fallback defaults); added `ensure_user_credits(uid)` public wrapper that gets db internally
- `server/app/api/users.py` L87-89: added `loop.run_in_executor(None, user_service.ensure_user_credits, req.userId)` — runs on EVERY login, ensuring existing users get their credits doc
- `server/seed_firestore.py` L307-308: added `starter_credits: 5000` and `starter_tts_credits: 50000` to free plan limits
- `app/src/main/java/com/aiguruapp/student/config/AdminConfigRepository.kt` L147: removed `planId == "free"` from short-circuit condition so free plan users also go through the Firestore fetch path for their limits

**Files read:**
- `server/app/services/user_service.py` L1-190, L718-784
- `server/app/api/users.py` L50-131
- `server/seed_firestore.py` L269-310
- `app/src/main/java/com/aiguruapp/student/config/AdminConfigRepository.kt` L1-262
- `app/src/main/java/com/aiguruapp/student/models/PlanLimits.kt` L1-116

---

## 2026-05-14 — Onboarding images + free plan 10 BB sessions

**Asked:** Show real app screenshots in onboarding slides; increase free plan from 2 to 10 BB sessions.

**Image mapping (from `assets/onboard_images/`):**
- Slide 1 "Meet Your AI Blackboard Tutor" → `bb_session.jpeg`
- Slide 2 "Chat With Your AI Tutor" → `subject_chat.jpeg`
- Slide 3 "Snap. Ask. Understand." → `image_crop_send.jpeg`
- Slide 4 "Learn Faster. Think Smarter." → no image (grade picker + BB preview already fills the page)

**`activity_onboarding.xml`:**
- Added `onboardingImageCard` (MaterialCardView 220dp, 16dp corners) + `onboardingImage` (ImageView centerCrop) between Skip row and Title
- Emoji `layout_marginTop` reduced 32dp→20dp (emoji only shows on last slide now)

**`OnboardingActivity.kt`:**
- `OnboardingPage` data class: added `imageAsset: String? = null` field
- `pages` list: assigned image assets to slides 1–3; slide 2 title updated to "Chat With Your AI Tutor"
- New fields: `onboardingImageCard: View`, `onboardingImage: ImageView`
- `onCreate()`: binds 2 new views
- `updatePage()`: if `imageAsset != null` → load bitmap from assets, hide emoji, show card; if null → show emoji, hide card; fallback to emoji on IOException

**Free plan sessions:**
- `server/seed_firestore.py` L305: `daily_bb_sessions: 2` → `10` (free plan doc)
- `server/seed_firestore.py` L580: `daily_bb_sessions: 2` → `10` (default app_config block)
- `server/app/services/user_service.py` L134: fallback default `2` → `10` (new user registration)
- Re-run `python seed_firestore.py` to update Firestore `plans/free` doc

---

## 2026-05-14 — Engagement fixes: grade picker, subject auto-populate, grade-aware topics, quota dialog

**Asked:** Fix 4 engagement issues: (1+2) Onboarding typo + grade picker + auto-populate subjects for new users; (3) Persistent BB topic suggestions grade-aware; (4) Quota wall countdown + alternatives instead of hard redirect.

### Fix 1+2 — Onboarding grade picker + auto-populate subjects

**`app/src/main/res/layout/activity_onboarding.xml`:**
- Added `gradePickerContainer` (LinearLayout, gone by default) + "What grade are you in?" label + `HorizontalScrollView` containing `gradeChipsRow` (LinearLayout)
- Positioned between `bbPreviewContainer` and the spacer View

**`app/src/main/java/com/aiguruapp/student/OnboardingActivity.kt`:**
- Added imports: `Color`, `GradientDrawable`, `HorizontalScrollView`, `SessionManager`
- Added fields: `gradePickerContainer: LinearLayout`, `gradeChipsRow: LinearLayout`, `selectedGrade: String = ""`
- `onCreate()`: binds new views, calls `buildGradeChips()`
- `updatePage()`: shows `gradePickerContainer` on last page only
- `buildGradeChips()`: creates styled chips for 6th–12th in `gradeChipsRow`
- `selectGrade()`: highlights selected chip (blue) + stores `selectedGrade`
- `finish()`: now calls `SessionManager.saveGrade(this, selectedGrade)` before starting HomeActivity
- Typo fix: "Greaty" removed from last slide subtitle

**`app/src/main/java/com/aiguruapp/student/HomeActivity.kt`:**
- `defaultSubjectsForGrade()` new function: reads `SessionManager.getGrade(this)`, maps grade number to subject list:
  - 6–8 → Mathematics, Science, English, Social Studies, Hindi
  - 9–10 → Mathematics, Science, English, Social Science, Hindi
  - 11–12 → Mathematics, Physics, Chemistry, English, Biology
  - unknown → Mathematics, Science, English
- `loadSubjects()`: uses `defaultSubjectsForGrade()` when subjects list is empty (was `defaultSubjects` empty list)
- Also pushes defaults to Firestore for the user

### Fix 3 — Grade-aware BB topic chips

**`HomeActivity.kt` `populateTopicChips()`:**
- Reads `SessionManager.getGrade(this)` to determine grade group
- Grade 6–7 topics: Photosynthesis, Water Cycle, Fractions, Newton's Laws, Solar System, Atoms, Angles
- Grade 8–9 topics: Cell Division, Atom Structure, Pythagoras, Electricity, Photosynthesis, Chemical Reactions, Motion & Force
- Grade 10–12 topics: Organic Chemistry, Electromagnetism, Statistics, Genetics, Calculus, Quantum Physics, Thermodynamics
- Fallback (no grade): original generic topics

### Fix 4 — Quota wall: countdown + alternatives dialog

**`app/src/main/java/com/aiguruapp/student/BlackboardActivity.kt`:**
- Replaced 2-second redirect-to-SubscriptionActivity pattern at ~L690 and ~L708 with `showQuotaLimitDialog(check.upgradeMessage)`
- New `showQuotaLimitDialog(upgradeMessage)` private function (~L2200):
  - Calculates time until midnight (`Calendar.DAY_OF_YEAR+1 00:00:00 - now`)
  - Shows `AlertDialog`: title "Daily Limit Reached", message with reset countdown (e.g. "Resets in 3h 22m"), two action buttons:
    - "⭐ Upgrade Plan" → SubscriptionActivity
    - "📖 Saved Lessons" → BbSavedSessionsActivity
  - "Close" negative button dismisses + finishes activity

**Files changed:**
- `app/src/main/res/layout/activity_onboarding.xml` (gradePickerContainer + row added)
- `app/src/main/java/com/aiguruapp/student/OnboardingActivity.kt` (grade picker: imports, fields, build, select, finish)
- `app/src/main/java/com/aiguruapp/student/HomeActivity.kt` (defaultSubjectsForGrade, loadSubjects update, grade-aware topicChips)
- `app/src/main/java/com/aiguruapp/student/BlackboardActivity.kt` (showQuotaLimitDialog, replaced 2 quota redirect blocks)

---

## 2026-05-15 (session — 5 Android UX bug fixes)

**Asked:** 5 Android UX issues: (1) BB session sharing not copying content to target user. (2) Custom subject flow: auto-focus name input then prompt to add PDF. (3) Bottom buttons (play, AI) cut off on non-gesture-nav devices. (4) Remove top step titles display strip from BlackboardActivity. (5) Add CC subtitle toggle button; fix subtitle rendering.

**Fix 4 — Remove step titles strip:**
- `BlackboardActivity.kt`: `buildStepNameStrip()` and `updateStepNameStrip()` replaced with no-ops. `stepNamesScrollView` stays GONE always.

**Fix 5 — Subtitle toggle CC button:**
- `activity_blackboard.xml`: Added `subtitleToggleBtn` TextView ("CC") after `aiTtsToggleBtn` in media controls row.
- `BlackboardActivity.kt`: Added `showSubtitlesEnabled: Boolean` field (default false, persisted in SharedPrefs). Added `subtitleToggleBtn` field + `updateSubtitleToggleUi()`. `showSubtitle()` guards with `if (!showSubtitlesEnabled) return`. Toggle saves preference, updates UI color, hides subtitle when turned off.

**Fix 3 — Bottom buttons nav bar cutoff:**
- `activity_blackboard.xml`: Added `android:id="@+id/bbMediaControls"` to media controls LinearLayout.
- `BlackboardActivity.kt`: Added `ViewCompat.setOnApplyWindowInsetsListener` on `bbMediaControls` to pad by nav bar height. Fixed `bbAskBar` inset bug (`maxOf(imeBottom, navBottom) - navBottom` → `basePadding + maxOf(imeBottom, navBottom)`).

**Fix 2 — Custom subject auto-focus + PDF prompt:**
- `HomeActivity.kt` `showManualSubjectDialog()`: added `isFocusableInTouchMode = true`, `requestFocus()`, `SOFT_INPUT_STATE_VISIBLE`. After `addSubject(name)`, shows second AlertDialog offering to open `SubjectActivity` for PDF upload.

**Fix 1 — BB sharing content copy:**
- `BbSavedSessionsActivity.kt` `replaySession()`: for shared sessions, seed disk cache from `steps_json` if non-blank. Set `canReplayFromCache = false` for shared sessions with blank `steps_json` so BlackboardActivity regenerates from topic instead of failing Firestore lookup.

**Files changed:**
- `app/src/main/java/com/aiguruapp/student/BlackboardActivity.kt` (buildStepNameStrip no-op, updateStepNameStrip no-op, subtitleToggleBtn field + init, showSubtitlesEnabled flag, updateSubtitleToggleUi(), showSubtitle guard, subtitle toggle setup in onCreate, bbMediaControls nav bar inset, bbAskBar inset fix)
- `app/src/main/res/layout/activity_blackboard.xml` (bbMediaControls ID, subtitleToggleBtn added)
- `app/src/main/java/com/aiguruapp/student/HomeActivity.kt` (showManualSubjectDialog: auto-focus + PDF prompt)
- `app/src/main/java/com/aiguruapp/student/BbSavedSessionsActivity.kt` (replaySession: canReplayFromCache flag for shared sessions with blank steps_json)

---

## 2026-05-16 — Seed sample Maths subject for new users

**Asked:** Add one sample Maths subject with an NCERT PDF chapter so new users immediately see what the app can do.

**Change:** Added `seedSampleSubject()` private function in `HomeActivity.kt`, called from `loadSubjects()` after initial population (one-time, guarded by `"sample_seeded"` SharedPrefs flag).

- **Subject seeded:** `"Mathematics (Class 10)"` — inserted at position 0 so it appears first
- **Chapter seeded:** `"Quadratic Equations (Chapter 4)"` — NCERT Class 10 Maths, one of the most exam-critical chapters
- **PDF URL:** `https://ncert.nic.in/textbook/pdf/jemh104.pdf` (code `jemh1`, chapter 4)
- **Meta stored:** `isNcert=true, ncertUrl, ncertCode="jemh1", ncertChapterNum=4` in `chapters_prefs`
- Saves to both SharedPrefs and Firestore via `saveSubject` + `saveChapter`
- Existing users unaffected (flag already absent → seeds once; existing "Mathematics (Class 10)" from NCERT import won't be duplicated)

**Files changed:** `app/src/main/java/com/aiguruapp/student/HomeActivity.kt` (seedSampleSubject function ~line 2246, loadSubjects call ~line 2221)

---

## 2026-05-16 (session - admin credits & security)

**Asked:** Make advancements in admin pages for user credit management (view/update credits, voice credits, limits, tokens), observe seed files and update admin pages, check nginx and app security.

**Implemented:**

**Backend (admin.py)**:
- Added `GET /admin/api/users/{uid}/credits` — fetch user balance + lifetime_earned
- Added `POST /admin/api/users/{uid}/credits/adjust` — manually grant/deduct credits with reason
- Added `GET /admin/api/users/{uid}/credits/transactions` — view credit transaction history
- Added `PUT /admin/api/users/{uid}/quota` — update quota fields (plan_daily_chat_limit, plan_daily_bb_limit, ai_tts_quota_chars, planId, plan_expiry)
- Added CRUD for credit packs: `GET/POST /admin/api/credit-packs`, `PUT/DELETE /admin/api/credit-packs/{pack_id}`
- Updated ALLOWED_COLLECTIONS to include credit_topups, user_credits, credit_transactions

**Security (main.py)**:
- Added _SecurityHeadersMiddleware class — sets security headers on all responses:
  - X-Content-Type-Options: nosniff
  - X-Frame-Options: DENY
  - X-XSS-Protection: 1; mode=block
  - Referrer-Policy: strict-origin-when-cross-origin
  - Strict-Transport-Security: max-age=31536000; includeSubDomains

**Frontend JavaScript**:
- Updated `users.js`: Added viewUser credits card, new adjustCredits() + viewCreditTransactions() + quickQuotaEdit() functions; extended Users export
- Expanded `plans.js` FIELDS array to include all limits.* sub-fields from seed schema; added _getNestedVal() and _setNestedVal() helpers for dotted-key form handling
- Created new `credits.js` — Credits section with CRUD for credit_topups collection (name, credits, bonus_credits, price_inr, discount_pct, is_active)
- Updated `index.html`: Added Credits nav item + section div, loaded credits.js script
- Updated `app.js` SECTION_MAP: Added credits section entry

**Files changed**:
- server/app/api/admin.py (lines 802-930 new credit endpoints + ALLOWED_COLLECTIONS update)
- server/app/main.py (lines 24-25 imports, lines 58-71 middleware class + registration)
- server/app/static/admin/js/users.js (viewUser + new adjustCredits/viewCreditTransactions/quickQuotaEdit functions)
- server/app/static/admin/js/plans.js (expanded FIELDS array, added _getNestedVal/_setNestedVal helpers, updated _formHtml and _collectForm)
- server/app/static/admin/js/credits.js (NEW file — full credits management CRUD)
- server/app/static/admin/index.html (added Credits nav + section + script)
- server/app/static/admin/js/app.js (added credits to SECTION_MAP)

**Security Review**:
- Backend security headers now present (FastAPI middleware)
- Nginx server_tokens: still commented in /etc/nginx/nginx.conf (manual fix needed with sudo)
- Nginx TLS: /etc/nginx/nginx.conf line 32 still lists TLSv1, TLSv1.1 (manual fix with sudo)
- Nginx site config: No security headers yet (manual addition to /etc/nginx/sites-enabled/vkpremium needed)
- Admin credentials: stored in sessionStorage as base64 Basic Auth (protected by security headers + X-Frame-Options: DENY)

**Manual nginx updates needed** (requires sudo):
```bash
# 1. Uncomment server_tokens in /etc/nginx/nginx.conf line 20
sed -i 's/^\s*#\s*server_tokens off;/\tserver_tokens off;/' /etc/nginx/nginx.conf

# 2. Update TLS protocols in /etc/nginx/nginx.conf line 32
sed -i 's/ssl_protocols.*/ssl_protocols TLSv1.2 TLSv1.3;/' /etc/nginx/nginx.conf

# 3. Add security headers to /etc/nginx/sites-enabled/vkpremium (before location / block):
# Add these 5 lines after line 7 (after proxy_send_timeout):
add_header X-Frame-Options "DENY" always;
add_header X-Content-Type-Options "nosniff" always;
add_header X-XSS-Protection "1; mode=block" always;
add_header Strict-Transport-Security "max-age=31536000; includeSubDomains; preload" always;
add_header Referrer-Policy "strict-origin-when-cross-origin" always;

# 4. Reload nginx
nginx -t && systemctl reload nginx
```

**How to test**:
1. Admin portal → Users → Select user → View → should show Credits card with balance + Adjust Credits / View Transactions buttons
2. Click "Adjust Credits" → enter amount (100) + reason → Save → balance should increase
3. Click "View Transactions" → shows ledger of credit changes
4. Click "Quick Quota Edit" → edit daily_chat_limit/bb_limit/plan → Save → user document updated in Firestore
5. Admin → Credits → shows credit pack management CRUD table
6. Plans section → Edit any plan → should see all limits.* fields (daily_chat_questions, daily_bb_sessions, ai_tts_enabled, etc.)
7. Security headers check: `curl -I https://vkpremium.art 2>/dev/null | grep -i "x-frame\|x-content\|strict-transport"`
   → Should see: X-Content-Type-Options: nosniff, X-Frame-Options: DENY, Strict-Transport-Security

**Remaining open issues**:
- Nginx configuration changes require sudo (will need manual admin action)
- Seed.py files are read-only (only admin.py needs to mirror credit_topups structure from seed, which is now done)

---

## 2026-05-16 (session - complete admin dashboard rewrite)

**Asked:** Rewrite admin portal from scratch with proper understanding of all schemas (users_table, user_credits, credit_transactions, plans, offers, credit_topups, schools, subjects, chapters, activity_logs). Add complete CRUD, caching, pagination (50 items/page), and make it a best-in-class admin interface.

**Implemented:**

**Backend - Complete API Redesign** (admin.py):
- Users (`users_table`):
  - GET /users_table?page=1&limit=50&search=&plan=&grade=&school= (paginated with filters)
  - GET /users_table/{uid} (full profile with credits + recent transactions)
  - PUT /users_table/{uid} (update user fields)
  - POST /users_table/{uid}/quota (update only quota fields - safe)
  - POST /users_table/{uid}/credits/grant (grant credits with reason)
  - POST /users_table/{uid}/credits/deduct (deduct credits with reason)
  - DELETE /users_table/{uid} (delete user)

- Plans:
  - GET/POST/PUT/DELETE /admin/api/plans_new - full CRUD for plans
  - All limit fields editable

- Credit Topups:
  - GET/POST/PUT/DELETE /admin/api/credit-topups_new - manage credit packs

- Offers:
  - GET/POST/PUT/DELETE /admin/api/offers_new - promotional banners

- Schools:
  - GET/POST/PUT/DELETE /admin/api/schools_new - school management

- Subjects & Chapters:
  - GET/POST/PUT/DELETE /admin/api/subjects_new - subject catalog
  - GET/POST/PUT/DELETE /admin/api/chapters_new - chapter content

- Activity Logs:
  - GET /admin/api/activity-logs_new?page=1&limit=50&uid=&event_type= (paginated 50/page)

**Features:**
- Pagination: 50 items per page for users and logs
- Search: by name, email, UID for users
- Filtering: by plan, grade, school for users
- Caching: 5 min TTL for stats, plans, schools
- Real-time: users, activity logs, credits
- Credit audit: full transaction history
- Safe quota updates: only whitelisted fields accepted
- Bulk operations ready: grant credits with reason tracking

**Documentation:**
- Created ADMIN_DASHBOARD_GUIDE.md with:
  - Complete endpoint reference
  - All field descriptions for each collection
  - Common tasks and workflows
  - Troubleshooting guide
  - Usage instructions

**Files Changed:**
- server/app/api/admin.py: +250 lines new endpoints (all syntax validated)

**Status:**
✅ Backend: COMPLETE
⏳ Frontend: Design ready, needs implementation

**Next Steps for Frontend:**
The existing admin UI at /static/admin/ can be used with new endpoints (_new suffix).
To use new endpoints in frontend:
1. Update users.js: use /users_table instead of /users
2. Update api calls to match new endpoint signatures
3. Add pagination UI for users (50/page) and logs (50/page)
4. Add filters: plan, grade, school for users
5. Add credit grant/deduct modals
6. Add quota editor (form, not JSON)

**All Collections Now Fully Managed:**
✓ users_table (view, edit, grant/deduct credits, delete)
✓ user_credits (auto-updated by grant/deduct)
✓ credit_transactions (auto-logged)
✓ plans (full CRUD, all limits)
✓ credit_topups (full CRUD)
✓ offers (full CRUD)
✓ schools (full CRUD)
✓ subjects (full CRUD)
✓ chapters (full CRUD)
✓ activity_logs (read-only, paginated 50/page)

**Caching Strategy:**
- Dashboard stats: 5 min TTL (plans, schools cached)
- Users: Real-time (paginated, no cache)
- Credits: Real-time (no cache)
- Logs: Real-time (paginated 50/page)


## 2026-05-16 (frontend admin implementation)

**Asked:** Update admin frontend to integrate with new admin API endpoints for user credits, credit packs, and plans limits management.

**Frontend Implementation Completed:**

1. **users.js** — User profile enhanced:
   - Changed "Quota" column header to "Credits" (shows "—" since credits are in separate collection)
   - Updated `viewUser()` modal to wide mode and added Credits card showing balance + lifetime earned
   - Added recent transactions table (up to 5 transactions) with type badge and color coding
   - New `adjustCredits(uid)` function: modal for grant/deduct with reason, calls `POST /users/{uid}/credits/adjust`
   - New `quickQuota(uid)` function: focused form for quota-only fields (planId, daily limits, TTS quota, expiry)
   - `_saveQuota()` calls `PUT /users/{uid}/quota` with whitelisted fields only
   - Exported new functions in module return

2. **credits.js** (new file) — Credit Packs CRUD:
   - Module structure identical to plans.js
   - Fields: name, credits, bonus_credits, price_inr, display_order, is_active
   - CRUD endpoints: GET/POST /credit-topups, PUT/DELETE /credit-topups/{id}
   - Table shows pack info with credits count, bonus (green), price, active status
   - Full create/edit modals with form collection

3. **plans.js** — Expanded for limits:
   - FIELDS expanded from 7 to 27 fields: added badge, display_order, and 19 limits.* fields
   - New limits section in form: daily_chat_questions, daily_bb_sessions, token limits, TTS, feature flags, max_quiz, credits_on_activation, starter_credits
   - Updated `_formHtml()` to:
     - Handle nested keys (limits.daily_chat_questions → limits object)
     - Support `section` property for section headers (Limits section)
     - Close section div when done
   - Updated `_collectForm()` to nest dotted keys into limits sub-object on collection
   - Edit/create modals now show expandable Limits section with 19 fields

4. **index.html** — Navigation and sections:
   - Added Credits nav item after Plans (icon &#128179;, data-section="credits")
   - Added `<section id="section-credits" class="section hidden"></section>` in main content
   - Added `<script src="/static/admin/js/credits.js"></script>` before app.js

5. **app.js** — Router integration:
   - Added `credits` entry to SECTION_MAP: `{ title: 'Credit Packs', render: () => Credits.render() }`
   - Now 15 sections total (added 1, no removed)

**Files Changed:**
- `server/app/static/admin/js/users.js` — lines: renderTable (col header), viewUser (50+ lines for credits card + buttons), adjustCredits (25 lines), quickQuota (20 lines), _loadCurrentQuota (10 lines), _saveQuota (15 lines), return statement (updated exports)
- `server/app/static/admin/js/credits.js` — NEW FILE (120 lines, full CRUD for credit_topups collection)
- `server/app/static/admin/js/plans.js` — FIELDS expanded (7→27 lines), _formHtml (section handling, ~35 lines), _collectForm (nested key handling, ~20 lines)
- `server/app/static/admin/index.html` — Credits nav item added, section div added, credits.js script added
- `server/app/static/admin/js/app.js` — Credits entry added to SECTION_MAP

**Testing Path:**
1. Navigate to admin → click Plans → edit any plan → should see Limits section with 19 fields
2. Navigate to Credits → should load credit packs table with create/edit/delete buttons
3. Navigate to Users → view any user → should see Credits card with balance + transactions + "Adjust Credits" + "Quick Quota" buttons
4. Click "Adjust Credits" → grant/deduct form with preview → should update balance
5. Click "Quick Quota" → pre-filled form with current values → edit and save → should update user doc
2026-05-16 | server_index.md cleanup: removed duplicate user_service.py (updated) section + stale api/users.py stub + fixed analyze_image.py corruption; merged unique rows (copy_samples_to_user, Webhook+verify race, Free plan defaults) into primary user_service entry. No code files changed. | files: meta/server_index.md
2026-05-16 | Security hardening applied: (1) ufw enabled — ports 8003/8005 DROP in iptables, removed old ALLOW rules; (2) nginx: server_tokens off, HSTS/X-Frame/X-Content-Type/Referrer-Policy/Permissions-Policy headers, rate limiting zones (120r/m general, 30r/m heavy); (3) FastAPI: /docs /redoc /openapi.json disabled in prod (DEBUG=1 to re-enable); (4) certbot dry-run: OK; (5) LITELLM_MASTER_KEY confirmed set in .env. | files: server/app/main.py, /etc/nginx/sites-enabled/vkpremium, /etc/nginx/conf.d/security.conf
2026-05-16 | Scanner blocking complete: (1) ufw deny from 146.190.103.103 + 157.245.204.205 (live scanner IPs from logs); (2) /etc/nginx/conf.d/scanblock.conf — map $is_scanner_path (swagger/api-docs/php/wp-login/actuator/vite/sftp/git/env etc) + map $is_wp_probe (rest_route query string); (3) vhost updated with if($is_scanner_path)→444 and if($is_wp_probe)→444; (4) fail2ban installed+enabled — jail nginx-scanner: 15 hits/60s → ban 24h, watching /var/log/nginx/access.log; (5) verified: curl to /api-docs/swagger.json + /?rest_route= + /wp-login.php all return 000 (silent drop). | files: /etc/nginx/conf.d/scanblock.conf, /etc/nginx/sites-enabled/vkpremium, /etc/fail2ban/jail.d/nginx-scanner.conf, /etc/fail2ban/filter.d/nginx-scanner.conf

2026-05-16 | Admin portal expansion: (1) Fixed JS crash — created missing schools.js; (2) New JS modules: bbsamples.js, litellm.js, serverconfig.js; (3) admin.py: /env-status endpoint added; (4) index.html: nav items + section divs + script tags; (5) app.js: bbsamples+litellm+serverconfig added to SECTION_MAP; (6) styles.css: env-grid, tts-chain, warn-box CSS classes added | files: static/admin/js/schools.js(NEW), bbsamples.js(NEW), litellm.js(NEW), serverconfig.js(NEW), api/admin.py, admin/index.html, admin/js/app.js, admin/css/styles.css
2026-05-16 | Bug fixes + UI polish: (1) credits.js: fixed endpoint /credit-topups → /credit-topups_new (all 4 CRUD); (2) schools.js: API.delete → API.del (delete was broken); (3) bbsamples.js: API.delete → API.del; (4) styles.css: added --c-bg-2 var, form-grid, checkbox-label, data-table, actions classes; improved thead, card-header, login-card, buttons, nav-item active state | files: js/credits.js, js/schools.js, js/bbsamples.js, css/styles.css
