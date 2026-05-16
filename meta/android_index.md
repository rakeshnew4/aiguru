# Android File Index
> Auto-maintained. Update this file every time you read an Android file.
> Format: file path → key symbols with line numbers and one-line purpose.
> If line numbers are unknown, mark as `?` and fill on next read.

---

## bb/BbInteractivePopup.kt
**Path:** `app/src/main/java/com/aiguruapp/student/bb/BbInteractivePopup.kt` | **Size:** ~935 lines

| Symbol | Lines | What it does |
|--------|-------|--------------|
| `BbInteractivePopup` object | 44 | Singleton object |
| `QuizResult` data class | 46–50 | Result: correct, score, feedback |
| `httpClient` | 56–61 | Shared OkHttp client (lazy) |
| `show()` | 63–97 | Entry point; routes to quiz type; wraps onResult with once-guard |
| `safeResult` guard | 81–87 | Ensures onResult called at most once (skip/timer/continue race) |
| `showConfidenceMeter()` | 105–142 | Pre-quiz confidence check: I know / Not sure / Guessing |
| `showMcq()` | 146–? | 4-option MCQ quiz popup |
| `showTyped()` / `quiz_voice` | ~275–318 | Text/voice input quiz, AI-graded via /bb/grade |
| `showFillBlank()` | ~368–548 | Fill-in-the-blank quiz |
| `showOrderSteps()` | ~612–738 | Tap-to-order quiz |
| skip button — all 5 call sites | 218, 298, 413, 576, 732 | `dialog.dismiss(); onResult(QuizResult(false, 0, "Skipped"))` — fixed Apr 2026 |
| `skipButton()` helper | ~926–935 | Creates skip TextView; onClick passed in |
| `buildDialog()` | ~885 | Creates Dialog with `setCancelable(false)` |

---

## BlackboardActivity.kt
**Path:** `app/src/main/java/com/aiguruapp/student/BlackboardActivity.kt` | **⚠️ Expensive**

| Symbol | Lines | What it does |
|--------|-------|--------------|
| `BlackboardActivity` class | 88 | Full-screen BB lesson activity |
| Key extras (consts) | 63–103 | EXTRA_MESSAGE, EXTRA_USER_ID, EXTRA_SUBJECT, EXTRA_CHAPTER, EXTRA_DURATION, EXTRA_BB_CACHE_ID, EXTRA_TTS_KEYS |
| TTS fields | 179–200 | `tts: TextToSpeechManager`, `aiTtsEngine: BbAiTtsEngine`, `isPaused`, `useAiTts` |
| `bbSubtitleTv` | 195 | lateinit TextView for subtitle overlay |
| `subtitleToggleBtn` | ~202 | lateinit TextView — the "CC" pill button in media controls |
| `showSubtitlesEnabled` | 203 | Boolean flag (default false); persisted in SharedPrefs `show_subtitles` |
| `bbLoadingWebView` | ~173 | WebView for SVG loading animations |
| Post-TTS advance logic | ~3139–3186 | `makeTtsCallback()` now uses shared `continueAfterSpeech()` for BOTH `onComplete` and `onError`; prevents frame stalls when TTS fails |
| `makeTtsCallback()` | 3553 | Factory: `(stepIdx, frameIdx, subtitle="")` → TTSCallback; `onStart()` calls `showSubtitle(subtitle)` if non-blank and enabled |
| `showSubtitle(text)` | 3636 | Guards with `if (!showSubtitlesEnabled) return`; fades in + starts pulse |
| `hideSubtitle()` | 3669 | Cancels pulse, fades out bbSubtitleTv |
| `startSubtitlePulse()` | 3658 | Infinite alpha 1.0↔0.65 pulse animation on bbSubtitleTv |
| `updateSubtitleToggleUi()` | ~860 | Green tint when enabled, gray when off; called on toggle |
| ~~`buildStepNameStrip()`~~ | — | No-op — stepNamesScrollView stays GONE always (removed 2026-05-15) |
| ~~`updateStepNameStrip()`~~ | — | No-op (removed 2026-05-15) |
| Diagram pre-speech delay | ~1525–1529 | `postDelayed(speakFrame, 200ms)` — minimal render delay, no subtitle; was 2500ms+showSubtitle (removed 2026-04-30) |
| BB ask-bar fields | 207–216 | `bbAskInput`, `bbAskSendBtn`, `bbCameraImageUri`, `bbPendingImageUri/Base64` |
| Launcher registrations | 227–250 | `bbCameraLauncher`, `bbGalleryLauncher`, `bbCropLauncher` |
| `onCreate()` | 291–430 | Binds full BB UI; TTS init at 375; AI TTS toggle at 392; lang set at 427 |
| `onPause()` | 594–600 | Calls `aiTtsEngine.stop()` + sets `isPaused = true` + resets pauseBtn to "▶" (fixed Apr 2026) |
| `onDestroy()` | 601–610 | Cancels typeAnimator, `aiTtsEngine.destroy()`, `bbVoiceManager.destroy()`, FeedbackManager |
| `isPaused` field | 188 | Controls whether TTS callbacks re-trigger next frame |
| `speakFrame()` | 1980–2050 | Speaks a frame; checks `isPaused`; calls `preloadUpcoming()` |
| `togglePause()` | 2298–2315 | Toggles `isPaused`; stops/resumes `aiTtsEngine` |
| `pauseBtn` | 169 | lateinit TextView; text "▶"/"⏸" |
| `fetchAndShowStepImage()` | ~1840–1935 | Wikimedia image fetch; `serverScore≥0.7` → inline img; caption hidden for raw URLs (fixed Apr 2026); `FIT_CENTER`+`maxHeight=280dp` (fixed Apr 2026) |
| `showImageDialog()` | 1936–1975 | AlertDialog with ImageView (FIT_CENTER, 300dp) or WebView for SVG |
| Silent-frame auto advance | ~1623–1720, ~2375–2410 | `showFrame()` now auto-continues frames with blank `speech` after direct render or typewriter end via `continueAfterFrame()` |
| `sendBbChat()` | 3906–4060 | Captures `bbPendingImageBase64`; builds question+response cards; streams `mode="normal"` with `imageBase64`; adds "▶ Explain in Blackboard Mode" button on done |
| `requestInlineBbLesson()` | 4072–4163 | Appends inline BB lesson to board; calls `BlackboardGenerator.generate()` with NO image |
| `buildChatCard()` | 4165–4217 | Creates card view; decodes raw base64 image thumbnail if present (try/catch safe) |
| `showAskBottomSheet()` | 3657–3901 | Full bottom sheet with multiline input + 📷/🎤/⛶ tiles + Send button; shows sheetImgCard if `bbPendingImageBase64 != null` |
| `sendBbQuestion` lambda | 333–336 | Sends if text non-blank only — ⚠️ image-only sends silently ignored |
| bbMediaControls nav inset | ~580 | `ViewCompat.setOnApplyWindowInsetsListener` on `bbMediaControls`; pads by `navBottom` so buttons clear 3-button nav bar |
| bbAskBar inset fix | ~600 | `basePadding + maxOf(imeBottom, navBottom)` (was `maxOf(...) - navBottom` which always = 0 when keyboard hidden) |
| `showQuotaLimitDialog()` | ~2200 | Shows countdown-to-midnight + "Upgrade" / "Saved Lessons" buttons; replaces hard redirect to SubscriptionActivity |
| `startQuizFromLesson()` | ~2800 | Builds lessonSummary from steps → calls QuizApiClient.generateQuiz → starts QuizActivity |
| `showCompletionCard()` | ~2769 | Wires `completionQuizBtn` → `startQuizFromLesson()`; wires completionSaveBtn, completionReplayBtn |
| `appendLocalWatchHistory()` | ~1260 | Prepends new entry to `bb_watch_history_<uid>.json`; caps at 50 entries |
| BbLoadingAnimator calls | 851, 1263, 1308 | `BbLoadingAnimator.start(bbLoadingWebView)` at 3 entry points; 9 stop sites at all `loadingGroup.visibility=GONE` calls |
| `launchBbCamera()` / `openBbCamera()` | 3481–3509 | Camera picker dialog → ContentValues insert → bbCameraLauncher |
| `launchBbCrop()` | 3515–3560 | UCrop launch with `Uri.fromFile(destFile)`; catches `FileUriExposedException` → fallback `encodeBbImage(sourceUri)` |
| `encodeBbImage()` | 3566–3578 | Background thread `bbMediaManager.uriToBase64(uri)` → sets `bbPendingImageBase64`; shows bbImgPreviewRow |
| `clearBbImage()` | 3580–3584 | Clears `bbPendingImageUri/Base64`; hides bbImgPreviewRow |
| BB image fields | 216–217 | `bbPendingImageUri: Uri?`, `bbPendingImageBase64: String?` |
| `bbImgPreviewRow/Thumb/Remove` | 223–225 | Preview strip views bound in `onCreate()` lines 326–328 |
| `bbCameraLauncher` / `bbGalleryLauncher` / `bbCropLauncher` | 228–250 | Activity result launchers for camera, gallery, and UCrop |
| ⚠️ Race: encode vs send | — | `encodeBbImage()` runs on raw Thread; if user sends before encode completes, `bbPendingImageBase64` is null → image silently dropped |

---

## FullChatFragment.kt
**Path:** `app/src/main/java/com/aiguruapp/student/FullChatFragment.kt` | **⚠️ Expensive**

| Symbol | Lines | What it does |
|--------|-------|--------------|
| `onViewCreated()` | 341–417 | Fragment view setup; loads BB preference, binds keyboard/insets handling, initializes managers and history load |
| `isAutoExplainActive` | 342 | Loaded from `user_prefs/blackboard_mode_on`; if true, nudges toward BB after response |
| Image fields | 157–180 | `cameraImageUri`, `pendingImageBase64`, `pendingImagePath`, `imageEncodeJob`, `selectedImageUri` |
| `initializeUI()` | ~503–580 | Binds RecyclerView, adapter, imageButton, imagePreviewStrip, message input |
| `onExplainClick` callback | ~534–545 | Plan check → `showBbDurationPickerAndLaunch(msg)` |
| `showBbDurationPickerAndLaunch()` | 718–751 | Captures `ctx=requireContext()` before dialog; cancels imageEncodeJob; clears pendingImage; duration picker → BlackboardActivity intent (fixed Apr 2026) |
| `showImageSourceDialog()` | 1121–1132 | Dialog: Take Photo / Gallery |
| `openCamera()` | 1133–1155 | Permission check → ContentValues → `cameraLauncher.launch()` |
| `sendMessage()` | 1203–1295 | Captures image/pdf/base64 state; checks plan; fires SERVER quota refresh then `proceedWithSendMessage` |
| `proceedWithSendMessage()` | 1295–1365 | Guest vs regular quota check; calls `proceedWithMessageSendAfterQuotaCheck()` |
| `proceedWithMessageSendAfterQuotaCheck()` | 1366–1660 | Builds user Message; launches IO coroutine; streams via `ServerProxyClient` or `AiClient`; on done → `showBlackboardNudge()` |
| `showBlackboardNudge()` | ~1695–1720 | Scrolls to message, highlights Explain button (no dialog) |
| `imageEncodeJob` | 180 | Job for background Base64 encode; always cancel before launching BB |

---

## chat/BlackboardGenerator.kt
**Path:** `app/src/main/java/com/aiguruapp/student/chat/BlackboardGenerator.kt`

| Symbol | Lines | What it does |
|--------|-------|--------------|
| `BlackboardIntent` data class | 55 | Structured lesson plan: `lessonTitle`, `stepTitles`, `useSvg`, `category`, `hookQuestion`, `continuationTopic` |
| `callIntent()` | 145–208 | Fast BB planner: sends topic + `imageBase64` to server with `mode="blackboard_intent"`; parses JSON → `BlackboardIntent`; fallback outline on parse failure |
| `generateChunk()` | 224–312 | Generates N steps for one chunk; takes `imageBase64`, `bbFeatures`, `previousContext`; sends `mode="blackboard"` to server; parses JSON steps array |
| `generateChunk()` result clamp | ~314–318 | After parsing `steps`, clamps to `chunkStepTitles.size` so over-returned model output cannot exceed requested 5-step batch |
| `generate()` | 323–510 | Cache-aware single-topic generation (teacher tasks / inline BB); checks Firestore cache first; falls back to `generateChunk()` with no image; saves result to Firestore |
| `CHUNK_SIZE` constant | ~30 | How many steps to generate per chunk (used by BlackboardActivity) |
| ⚠️ `generate()` no-image | 486–510 | `generate()` → `generateChunk()` called with NO `imageBase64` — inline BB lessons from ask-bar don't use the attached image |

---

## chat/AiClient.kt
**Path:** `app/src/main/java/com/aiguruapp/student/chat/AiClient.kt`

| Symbol | Lines | What it does |
|--------|-------|--------------|
| (unread) | ? | HTTP client for AI endpoints |

---

## chat/ServerProxyClient.kt
**Path:** `app/src/main/java/com/aiguruapp/student/chat/ServerProxyClient.kt`

| Symbol | Lines | What it does |
|--------|-------|--------------|
| `class ServerProxyClient` | 38–43 | Constructor: `serverUrl`, `modelName` (unused), `apiKey`, `userId=""` |
| `streamChat()` | 101–137 | Builds JSON body; puts `image_base64` if non-null; calls `executeStream()` |
| `executeStream()` | 153–174 | Calls `executeStreamInternal`; retries once on HTTP 401 with fresh Firebase token |
| `executeStreamInternal()` | 177–? | Actual OkHttp SSE stream; parses `{"text":"..."}` tokens → `onToken`; handles `{"done":true}` → `onDone` |
| `streamWithImage()` | 139–149 | ⚠️ NOT a real multimodal call — falls back to text-only `streamText()`; use `streamChat(imageBase64=...)` instead |
| `bbFeatures` param | 110 | Map<String,Boolean> key-value pairs appended to JSON body (e.g. `bb_images_enabled`) |
| `onBbStep` param | ~110 | `((String, Int) -> Unit)?` — callback fired per streamed BB step; parses `bb_step` SSE event |
| Token refresh | 277 | Skips refresh if `serverUrl` blank, `userId` blank, or `userId=="guest_user"` |

---

## tts/BbAiTtsEngine.kt
**Path:** `app/src/main/java/com/aiguruapp/student/tts/BbAiTtsEngine.kt`

| Symbol | Lines | What it does |
|--------|-------|--------------|
| `scope` | 41 | `CoroutineScope(SupervisorJob() + Dispatchers.IO)` — cancelled only in `destroy()`, NOT in `stop()` |
| `mediaPlayer` | 51 | `MediaPlayer?` — reset in `stop()`, released in `destroy()` |
| `play()` | 100–130 | Checks cache; if hit → `playFile()`; if miss → Android TTS + background preload |
| `stop()` | 130–136 | Stops MediaPlayer + `androidTts.stop()`; does NOT cancel scope (pending coroutines can still fire) |
| `destroy()` | 138–142 | Cancels scope, releases MediaPlayer; called from `BlackboardActivity.onDestroy()` |
| `playFile()` | 178–210 | Sets MediaPlayer datasource; `onCompletionListener` calls `callback.onComplete()` |
| `preload()` | 84 | `scope.launch { generateAndCache(...) }` — background MP3 download |
| Server call | ~227–235 | Calls `/api/tts/synthesize` with tts_engine param |
| ⚠️ Race risk | — | `stop()` resets player but scope coroutines survive until `destroy()` — fixed in `onPause` by setting `isPaused=true` |

---

## tts/AiTtsProvider.kt
**Path:** `app/src/main/java/com/aiguruapp/student/tts/AiTtsProvider.kt`

| Symbol | Lines | What it does |
|--------|-------|--------------|
| (unread) | ? | TTS provider abstraction |

---

## firestore/FirestoreManager.kt
**Path:** `app/src/main/java/com/aiguruapp/student/firestore/FirestoreManager.kt` | **⚠️ Expensive**

| Symbol | Lines | What it does |
|--------|-------|--------------|
| `loadSubjects()` | 375–390 | `users/{uid}/subjects/` orderBy createdAt → list of names |
| `saveChapter()` | 392–415 | Saves chapter meta; accepts `pdfStoragePath` + `pdfId` (added Apr 2026); uses `SetOptions.merge()` |
| `loadChapterMeta()` | 416–430 | NEW (Apr 2026): returns full chapter doc map including `pdfStoragePath`, `pdfId`, `isPdf` |
| `loadChapters()` | 440–460 | Returns list of chapter names (not full docs) |
| `loadTasksForSchool()` | ~1040–1070 | Queries `school_tasks` by `school_id` only (removed composite index req Apr 2026); filters `is_active` + grade/section in-memory |
| `saveTask()` | ~988–1040 | Writes `school_tasks/{docId}`; fields: task_id, teacher_id, school_id, grade, title, description, task_type, subject, chapter, bb_topic, bb_cache_id, quiz_id, is_active=true |
| `loadTasksByTeacher()` | ~1073–1090 | Queries by `teacher_id` only (removed orderBy Apr 2026); sorts in-memory |
| `addFriend()` | ~968 | `users/{uid}/friends/{friendUid}` — adds friend record |
| `loadFriends()` | ~985 | Ordered by `added_at` desc |
| `loadSharedWithMe()` | ~1000 | `users/{uid}/shared_with_me/` ordered by `shared_at` desc |
| `recordBbHistory()` | ~? | Writes to `users/{uid}/bb_watch_history` with `viewed_at` timestamp |
| `loadBbWatchHistory()` | ~? | Reads `bb_watch_history` ordered by `viewed_at` desc, limit 200 |
| `deleteBbHistoryEntry()` | ~? | Deletes from `bb_watch_history` |
| `deactivateTask()` | ~1093–1102 | Updates `is_active=false` on school_tasks doc |
| `loadTeacherBbLessons()` | 1489–1510 | `bb_cache` where `teacher_id` + orderBy created_at + limit 50 |
| `saveBbLesson()` / bb_cache | ~1425–1470 | Fields: bb_cache_id, teacher_id, school_id, subject, chapter, topic, preview(150), steps_json, language_tag, step_count, created_at |
| ⚠️ Composite index trap | — | Any `whereEqualTo + orderBy` on different fields needs explicit Firestore index; use single-field filter + in-memory sort |

---

## ChapterActivity.kt
**Path:** `app/src/main/java/com/aiguruapp/student/ChapterActivity.kt` | **⚠️ Expensive**

| Symbol | Lines | What it does |
|--------|-------|--------------|
| `isPdfChapter`, `pdfAssetPath`, `pdfId` | 75–77 | PDF chapter state flags |
| `ncertPdfId` | 92–94 | Computed: `ncert_{subject}_{chapter}` (safe, max 60 chars) |
| `ncertUrl`, `ncertCode`, `ncertChapterNum` | 89–91 | NCERT download state |
| Chapter meta load | 651–665 | Reads SharedPrefs JSON `meta_{subject}_{chapter}`; extracts isPdf, pdfAssetPath, pdfId, ncertUrl |
| `setupPdfChapter()` | ~874–920 | Checks `cacheDir/pdf_cache/{pdfId}.pdf`; if missing → calls `loadChapterMeta` → downloads from Firebase Storage (Apr 2026); falls back to "missing" message |
| `setupPdfChapterLoad()` | ~920–960 | Actual page-count load via PdfPageManager; shows overlay while loading |
| `setupNcertChapter()` | ~685–810 | Downloads NCERT PDF from URL; tries `ncertCandidateUrls()` variants |
| NCERT cached PDF path | 685 | `cacheDir/pdf_cache/{ncertPdfId}.pdf` |
| `pdfPageManager.getPageCount()` | ~929 | Returns int page count |
| Firebase Storage download | ~883–903 | `com.google.firebase.storage.FirebaseStorage.getInstance().reference.child(storagePath).getBytes(50MB)` |

---

## HomeActivity.kt
**Path:** `app/src/main/java/com/aiguruapp/student/HomeActivity.kt` | **⚠️ Expensive**

| Symbol | Lines | What it does |
|--------|-------|--------------|
| `defaultSubjectsForGrade()` | 2180 | Maps grade number to list of subject names (6-8, 9-10, 11-12, fallback) |
| `seedSampleSubject()` | ~2246 | One-time (flag `sample_seeded`) seeds "Mathematics (Class 10)" + "Quadratic Equations (Chapter 4)" chapter with NCERT URL `jemh104.pdf` |
| `loadSubjects()` | 2209 | Loads from SharedPrefs; if empty uses `defaultSubjectsForGrade()`; calls `seedSampleSubject()`; syncs with Firestore |
| `showManualSubjectDialog()` | ~2248 | Rewritten 2026-05-15: auto-focuses input, shows keyboard, after `addSubject()` offers to open SubjectActivity for PDF |
| `populateTopicChips()` | ~? | Grade-aware BB topic chips: grade 6-7 / 8-9 / 10-12 topic sets |
| `homePendingImageBase64` | 81 | Raw base64 string (no prefix); passed as `EXTRA_IMAGE_BASE64` to BlackboardActivity |
| `homePendingImageUri` | 80 | URI of selected/cropped image |
| `homeMediaManager` | 84 | `MediaManager(this)`; initialized in `onCreate()` line 133 |
| `homeCameraLauncher` / `homeGalleryLauncher` | 92–98 | Launchers; both call `launchHomeCrop(uri)` on result |
| `homeCropLauncher` | 100–112 | UCrop result; on OK → `applyHomeCroppedImage(cropped)` |
| BB launch with image | 1408–1416 | Puts `EXTRA_IMAGE_BASE64 = homePendingImageBase64` in intent if non-null |
| `launchHomeCrop()` | 1461–1505 | UCrop with `Uri.fromFile(destFile)`; ⚠️ NO fallback on exception (image silently dropped — unlike BlackboardActivity which has fallback) |
| `applyHomeCroppedImage()` | 1507–1520 | `lifecycleScope.launch(IO)` → `homeMediaManager.uriToBase64(uri)`; shows sheetImgCard |
| `openHomeCamera()` / `openHomeCameraCapture()` | 1432–1457 | Camera dialog → ContentValues insert |
| `updateQuotaStripUI()` | 342 | Updates chat/BB quota displays in drawer |

---

## SplashActivity.kt
| Symbol | Lines | What it does |
|--------|-------|--------------|
| `SplashActivity` class | 36 | True app launcher (not MainActivity) |
| `onCreate()` | 64–? | Runs update gate and routes to HomeActivity |
| `enableEdgeToEdge()` | 67 | Enables edge-to-edge compatibility |

---

## AndroidManifest.xml
**Path:** `app/src/main/AndroidManifest.xml`

| Symbol | Lines | What it does |
|--------|-------|--------------|
| `.SplashActivity` declaration | 63–71 | Launcher activity entry-point |
| `.OnboardingActivity` declaration | 79–80 | First-launch onboarding screen |
| `.BlackboardActivity` declaration | 138–139 | Blackboard lesson activity |
| `UCropActivity` declaration | 157–159 | Third-party crop activity |

---

## res/layout/activity_blackboard.xml
**Path:** `app/src/main/res/layout/activity_blackboard.xml`

| Symbol / ID | Notes |
|-------------|-------|
| `bbMediaControls` | LinearLayout — transport row container; has `android:id` added 2026-05-15 for nav bar inset |
| `subtitleToggleBtn` | "CC" pill TextView after `aiTtsToggleBtn` in transport row (added 2026-05-15) |
| `bbSubtitleTv` | Bottom subtitle overlay; `maxLines="4"` (was broken `maxLines="0"` — fixed 2026-05-16) |
| `bbLoadingWebView` | `200×220dp` WebView inside `loadingGroup` for SVG loading animations |
| `stepNamesScrollView` | `visibility="gone"` — always hidden (no-op in code since 2026-05-15) |
| `bbCompletionCard` | `0×0dp` overlay; contains `completionSaveBtn`, `completionReplayBtn`, `completionQuizBtn`, `completionDemoStartBtn`, `completionCloseBtn` |
| `saveSessionBtn` | Pill button `"💾 Save"` in top-right cluster; `wrap_content` width |
| `bbChatFab` | `52×52dp` floating chat button bottom-right |
| `bbAskBar` | Slide-up ask panel; contains `bbAskInput`, `bbCameraBtn`, `bbMicBtn`, `bbAskSend` |

---

## res/values/themes.xml
**Path:** `app/src/main/res/values/themes.xml`

| Symbol | Lines | What it does |
|--------|-------|--------------|
| `Theme.AIGuru` | 11–39 | Main app theme and Material color roles |
| `Theme.AIGuru.Splash` | 62–68 | Splash window styling |
| `Theme.AIGuru.Fullscreen` | 70–76 | Fullscreen image viewer theme |

---

## BbSavedSessionsActivity.kt
**Path:** `app/src/main/java/com/aiguruapp/student/BbSavedSessionsActivity.kt`

| Symbol | Lines | What it does |
|--------|-------|------|
| `showingShared` | ~30 | Boolean; true when showing "Shared with Me" tab |
| `sharedSessions` | ~32 | List of shared session maps from `shared_with_me` |
| `replaySession()` | ~255–295 | For shared sessions: seeds disk cache from `steps_json` if non-blank; sets `canReplayFromCache=false` for blank steps_json (falls back to topic regeneration) |
| `shareSession()` | ~300 | Opens `FriendsActivity` in share mode (passes session extras) |
| `loadSharedSessions()` | ~? | Loads from `users/{uid}/shared_with_me/` via `FirestoreManager.loadSharedWithMe()` |
| `switchTab()` | ~? | Switches between "My Sessions" and "Shared with Me" tabs |
| `confirmDelete()` | ~? | Uses `deleteBbHistoryEntry` in history mode, `deleteBbSession` in saved mode |
| `isAllHistory` | ~? | true = Watch History (from `bb_watch_history`), false = My Sessions |
| Cache file | — | `bb_watch_history_{uid}.json` (history mode) or `bb_sessions_{uid}.json` (saved mode) |

---

## FriendsActivity.kt
**Path:** `app/src/main/java/com/aiguruapp/student/FriendsActivity.kt`

| Symbol | Lines | What it does |
|--------|-------|------|
| `FriendsActivity` | 1 | Browse/add friends + share BB sessions to friends |
| Browse mode | ~60–140 | Lists friends; "Add Friend" button → referral-code lookup dialog |
| Add friend | ~110–140 | `GET /users/lookup?code=XXXX` → confirm → `FirestoreManager.addFriend()` |
| Share mode | ~150–200 | Launched from BbSavedSessionsActivity; shows friends list with "Share" buttons |
| `doShareToFriend()` | ~180 | `POST /users/share-session` with session data; shows toast on success |

---

## widget/BbLoadingAnimator.kt
**Path:** `app/src/main/java/com/aiguruapp/student/widget/BbLoadingAnimator.kt`

| Symbol | Lines | What it does |
|--------|-------|------|
| `BbLoadingAnimator` | 1 | Singleton; cycles SVG animations in WebView during BB lesson load |
| `ALL_ANIMATIONS` | ~10 | Array of 39 filenames (`01_water_glass.html` … `36_tetris.html`) from `loading_svgs/` |
| `SESSION_SIZE = 5` | ~20 | 5 random distinct animations per session |
| `WARMUP_ASSET` | ~22 | `22_bouncing_balls.html` — fast-rendering first frame |
| `WARMUP_MS = 4000L` | ~23 | Duration of warmup animation |
| `ROTATE_INTERVAL_MS = 4000L` | ~24 | Per-animation display time |
| `SLOW_FACTOR = 1.6` | ~25 | Applied to all CSS animation durations via injected script |
| `htmlCache` | ~30 | In-memory cache of all 39 HTML files (pre-loaded on first `start()`) |
| `start(webView)` | ~50 | Sets dark bg `#0D1117`, shows WARMUP_ASSET, starts rotation after warmup |
| `stop(webView)` | ~80 | Cancels rotation, blanks WebView, sets GONE |
| `injectSlowdown(html)` | ~100 | Appends `<script>` that multiplies all CSS animationDuration by SLOW_FACTOR |
| `ensureCached(ctx)` | ~115 | Pre-loads all assets into `htmlCache` via `assets.open()` |
| Assets location | — | `app/src/main/assets/loading_svgs/` — 39 `.html` files |

---

## ChatHostActivity.kt
**Path:** `app/src/main/java/com/aiguruapp/student/ChatHostActivity.kt`

| Symbol | Lines | What it does |
|--------|-------|--------------|
| `ChatHostActivity` class | 23 | Thin host around `FullChatFragment` |
| `onCreate()` | 25–? | Loads host layout and forwards launch extras |
| `enableEdgeToEdge()` | 27 | Enables edge-to-edge compatibility |

---

## TeacherChatHostActivity.kt
**Path:** `app/src/main/java/com/aiguruapp/student/TeacherChatHostActivity.kt`

| Symbol | Lines | What it does |
|--------|-------|--------------|
| `TeacherChatHostActivity` class | 29 | Teacher chat wrapper with header + defaults dialog |
| `onCreate()` | 42–? | Loads defaults then hosts `FullChatFragment` |
| `enableEdgeToEdge()` | 44 | Enables edge-to-edge compatibility |

---

## TeacherQuizValidationActivity.kt
**Path:** `app/src/main/java/com/aiguruapp/student/TeacherQuizValidationActivity.kt`

| Symbol | Lines | What it does |
|--------|-------|--------------|
| `TeacherQuizValidationActivity` class | 30 | Teacher-side generated-quiz filtering UI |
| `onCreate()` | 47–? | Parses quiz JSON and binds selection recycler |
| `enableEdgeToEdge()` | 49 | Enables edge-to-edge compatibility |

---

## OnboardingActivity.kt
**Path:** `app/src/main/java/com/aiguruapp/student/OnboardingActivity.kt`

| Symbol | Lines | What it does |
|--------|-------|--------------|
| `OnboardingActivity` class | 21 | 4-screen first-time walkthrough |
| `onCreate()` | 75–? | Binds onboarding controls and paging actions |
| `enableEdgeToEdge()` | 77 | Enables edge-to-edge compatibility |

---

## NcertViewerActivity.kt
**Path:** `app/src/main/java/com/aiguruapp/student/NcertViewerActivity.kt`

| Symbol | Lines | What it does |
|--------|-------|--------------|
| `NcertViewerActivity` class | 30 | Safe WebView-based NCERT PDF viewer |
| `onCreate()` | 47–? | Validates NCERT URL then loads docs viewer |
| `enableEdgeToEdge()` | 49 | Enables edge-to-edge compatibility |

---

## PageViewerActivity.kt
**Path:** `app/src/main/java/com/aiguruapp/student/PageViewerActivity.kt`

| Symbol | Lines | What it does |
|--------|-------|--------------|
| `PageViewerActivity` class | 27 | Full-screen PDF page browsing and Ask AI handoff |
| `onCreate()` | 46–? | Reads intent payload and initializes page navigation |
| `enableEdgeToEdge()` | 48 | Enables edge-to-edge compatibility |

---

## notes/NotesActivity.kt
**Path:** `app/src/main/java/com/aiguruapp/student/notes/NotesActivity.kt`

| Symbol | Lines | What it does |
|--------|-------|--------------|
| `NotesActivity` class | 36 | Chapter notes list with category chips + annotation editing |
| `onCreate()` | 62–? | Toolbar setup, list binding, and notes loading |
| `enableEdgeToEdge()` | 64 | Enables edge-to-edge compatibility |

---

## FullscreenImageActivity.kt
**Path:** `app/src/main/java/com/aiguruapp/student/FullscreenImageActivity.kt`

| Symbol | Lines | What it does |
|--------|-------|--------------|
| `FullscreenImageActivity` class | 25 | Pinch-to-zoom fullscreen image viewer |
| `onCreate()` | 43–? | Builds frame + close UI and gesture handlers |
| `enableEdgeToEdge()` | 47 | Enables edge-to-edge compatibility |

---

## utils/SchoolTheme.kt
**Path:** `app/src/main/java/com/aiguruapp/student/utils/SchoolTheme.kt`

| Symbol | Lines | What it does |
|--------|-------|--------------|
| `SchoolTheme` object | 33 | Runtime school-brand color registry |
| `applyStatusBar(window)` | 109–116 | Controls system bar icon appearance via insets controller |

---

## config/
| File | Purpose |
|------|---------|
| `AccessGate.kt` | Feature access control |
| `PlanEnforcer.kt` | Plan limits enforcement |
| `AdminConfigRepository.kt` | Remote admin config fetch |
| `AppStartRepository.kt` | Startup config loading |

---

## validators/
| File | Purpose |
|------|---------|
| `BlackboardQuotaValidator.kt` | BB session quota check |
| `ChatQuotaValidator.kt` | Chat message quota check |
| `AiVoiceQuotaValidator.kt` | Voice quota check |

---

## adapters/
| File | Purpose |
|------|---------|
| `ChapterAdapter.kt` | RecyclerView adapter for chapter list |
| `LibraryAdapter.kt` | Adapter for library book grid |
| `MessageAdapter.kt` | Chat message list adapter (renders AI + user bubbles, LaTeX, images) |
| `PageListAdapter.kt` | Adapter for PDF page list |
| `QuizValidationAdapter.kt` | Teacher quiz validation list adapter |
| `SavedBbMiniAdapter.kt` | Adapter for saved BB session mini-cards |
| `SubjectAdapter.kt` | Adapter for subject grid cards |

---

## models/
| File | Purpose |
|------|---------|
| `AdminConfig.kt` | App-wide admin config (maintenance mode, force update, etc.) |
| `AppConfig.kt` | App runtime config wrapper |
| `AppNotification.kt` | In-app notification data class |
| `AppUpdateConfig.kt` | Update check config (min version, store URL) |
| `FirestoreOffer.kt` | Promotional offer data class |
| `FirestorePlan.kt` | Subscription plan from Firestore |
| `Flashcard.kt` | Flashcard data class for revision |
| `LibraryBook.kt` | Library book/resource data class |
| `Message.kt` | Chat message data class (role, content, images, metadata) |
| `ModelConfig.kt` | LLM model config (name, tier, context window) |
| `PageContent.kt` | PDF page content + extracted text |
| `PlanLimits.kt` | Per-plan feature limits |
| `QuizModels.kt` | Quiz data classes (Question, Option, Submission, Result) |
| `School.kt` | School entity + branding config |
| `StudentStats.kt` | Student usage and progress stats |
| `SubscriptionPlan.kt` | In-app subscription plan entity |
| `TutorSession.kt` | BB/chat session metadata |
| `UserMetadata.kt` | User profile + subscription + school info |

---

## utils/
| File | Purpose |
|------|---------|
| `AppUpdateBus.kt` | LiveData/event bus for force-update state |
| `AppUpdateManager.kt` | Checks Firestore for force update / maintenance state |
| `ChapterMetricsTracker.kt` | Tracks per-chapter engagement (time, questions) |
| `ConfigManager.kt` | Loads + caches school and app config from Firestore |
| `FeedbackManager.kt` | Rating prompt logic |
| `ImageOptimizer.kt` | Compresses + resizes images before upload |
| `MasteryCalculator.kt` | Computes mastery % from quiz/flashcard data |
| `MediaManager.kt` | Image encoding utility: `uriToBase64(uri, maxSizeKb=500)` — 2-pass decode (bounds then pixels), compresses JPEG until ≤maxSizeKb, returns raw base64 (no data-URI prefix); handles `file://` + `content://` URIs; catches OOM |
| `PdfPageManager.kt` | PDF rendering and page extraction |
| `PdfPreloadManager.kt` | Background PDF page preloading |
| `PromptRepository.kt` | Stores + retrieves user-defined custom prompts |
| `SchoolTheme.kt` | Runtime school-brand color registry (see full entry above) |
| `SessionManager.kt` | SharedPreferences wrapper for session state (uid, schoolId, plan) |
| `StorageMigrationHelper.kt` | Migrates legacy local storage to new schema |
| `TextToSpeechManager.kt` | Android native TTS fallback wrapper |
| `VoiceManager.kt` | Mic recording + voice-input lifecycle |
| `WikimediaUtils.kt` | Wikimedia Commons image URL helpers |

---

## services/
| File | Purpose |
|------|---------|
| `CloudBackupService.kt` | Background service for cloud chat/notes backup |
| `LLMQueueManager.kt` | Queues and throttles concurrent LLM requests |
| `ResponseCacheService.kt` | In-memory + disk cache for LLM responses |
| `StorageService.kt` | Firebase Storage upload/download helpers |

---

## streaming/
| File | Purpose |
|------|---------|
| `AudioStreamer.kt` | Streams PCM audio chunks from server |
| `GeminiLiveClient.kt` | WebSocket client for Gemini Live real-time voice |
| `PcmAudioPlayer.kt` | Plays raw PCM audio buffers via AudioTrack |
| `StreamingAudioPlayer.kt` | Plays streamed MP3/audio from HTTP response |
| `StreamingVoiceClient.kt` | Manages mic-to-server streaming for voice input |

---

## http/
| File | Purpose |
|------|---------|
| `HttpClientManager.kt` | Singleton OkHttp client factory with auth interceptor |

---

## auth/
| File | Purpose |
|------|---------|
| `TokenManager.kt` | Firebase ID token fetch + refresh + caching |

---

## daily/
| File | Purpose |
|------|---------|
| `DailyQuestionsManager.kt` | Fetches daily questions, awards credits, tracks streak |

---

## quiz/
| File | Purpose |
|------|---------|
| `DonutChartView.kt` | Custom donut chart for quiz score visualization |
| `QuizApiClient.kt` | HTTP client for `/quiz/*` endpoints |

---

## payments/
| File | Purpose |
|------|---------|
| `PaymentApiClient.kt` | Razorpay + backend payment API integration |

---

## chat/ (additional files)
| File | Purpose |
|------|---------|
| `ChatHistoryRepository.kt` | Loads + persists conversation history from Firestore |
| `ConversationSummarizer.kt` | Summarizes long conversation context before sending to LLM |
| `NotesRepository.kt` | CRUD for student notes synced to Firestore |
| `PageAnalyzer.kt` | Sends PDF page image to `/analyze-image` for AI description |

---

## GeminiLiveActivity.kt
| Symbol | Lines | What it does |
|--------|-------|--------------|
| (unread) | ? | Real-time voice tutoring via Gemini Live WebSocket |

---

## RevisionActivity.kt
| Symbol | Lines | What it does |
|--------|-------|--------------|
| (unread) | ? | Flashcard revision mode |

---

## LibraryActivity.kt
| Symbol | Lines | What it does |
|--------|-------|--------------|
| (unread) | ? | In-app library / resource browser |

---

## SubscriptionActivity.kt
| Symbol | Lines | What it does |
|--------|-------|--------------|
| (unread) | ? | Subscription plans UI + Razorpay checkout |

---

## ProgressDashboardActivity.kt / ParentDashboardActivity.kt / TeacherDashboardActivity.kt
| Symbol | Lines | What it does |
|--------|-------|--------------|
| (all unread) | ? | Student / parent / teacher progress views |

---

## TutorController.kt
| Symbol | Lines | What it does |
|--------|-------|--------------|
| (unread) | ? | Orchestrates tutor session state + step-by-step flow |

---

## BaseActivity.kt
**Path:** `app/src/main/java/com/aiguruapp/student/BaseActivity.kt`

| Symbol | Lines | What it does |
|--------|-------|--------------|
| `BaseActivity` | 18 | Open base class all branded activities extend |
| `onCreate()` | 22–26 | Calls `enableEdgeToEdge()` + `SchoolTheme.applyStatusBar()` |
| `onPostCreate()` | 31–41 | Adds floating calculator overlay after layout inflated |

---

## TasksActivity.kt
**Path:** `app/src/main/java/com/aiguruapp/student/TasksActivity.kt`

| Symbol | Lines | What it does |
|--------|-------|--------------|
| `TasksActivity` class | ~20 | Student task list; calls `loadAllTaskProgress` then `loadTasksForSchool` |
| `loadData()` | ~60–85 | Short-circuits if `schoolId.isBlank()` ("Join a school first"); otherwise loads progress then tasks |
| `loadTasks()` | ~86–115 | Calls `FirestoreManager.loadTasksForSchool(schoolId, grade, section)`; shows "Couldn't load tasks" toast on failure |
| `launchLesson()` | ~117–145 | Starts `BlackboardActivity` with `EXTRA_BB_CACHE_ID` (preferred) or `EXTRA_MESSAGE` (legacy) |
| `launchQuiz()` | ~147–175 | Loads quiz by `quiz_id` from `quizzes/` collection, or uses embedded `quiz_json` |
| `TasksAdapter` | ~185–end | Shows bb/quiz/both badges; locks quiz until BB lesson read for "both" type; shows due-date countdown |

---

## TeacherTasksActivity.kt
**Path:** `app/src/main/java/com/aiguruapp/student/TeacherTasksActivity.kt`

| Symbol | Lines | What it does |
|--------|-------|--------------|
| Companion extras | 37–46 | EXTRA_PREFILL_QUIZ_JSON, EXTRA_PREFILL_SUBJECT, EXTRA_PREFILL_CHAPTER, EXTRA_PREFILL_BB_TOPIC, EXTRA_PREFILL_QUIZ_ID, EXTRA_PREFILL_BB_CACHE_ID |
| Dropdown state | ~80–85 | `subjectOptions`, `chapterOptions`, `bbSessions` (added Apr 2026) |
| `loadDropdownData()` | ~195–225 | Loads subjects + BB sessions from Firestore on startup (Apr 2026) |
| `loadChaptersForSubject()` | ~226–232 | Lazy-loads chapters for selected subject into `chapterOptions` map |
| `pickSubject()` | ~234–252 | Dialog: teacher's subjects + "Type manually…" fallback |
| `pickChapter()` | ~254–272 | Dialog: chapters for current subject + "Type manually…" fallback |
| `pickBbSession()` | ~274–325 | Dialog: teacher's bb_cache sessions; picking one fills `selectedBbCacheId` + subject + chapter (Apr 2026) |
| `onSaveTask()` | ~365–405 | Validates fields → `FirestoreManager.saveTask()` |
| `loadMyTasks()` | ~408–430 | `FirestoreManager.loadTasksByTeacher(userId)` |

---

## SubjectActivity.kt
**Path:** `app/src/main/java/com/aiguruapp/student/SubjectActivity.kt`

| Symbol | Lines | What it does |
|--------|-------|--------------|
| `importPdfChapter()` | ~310–365 | Copies PDF to `cacheDir/pdf_cache/`; uploads to Firebase Storage at `user_pdfs/{uid}/{pdfId}.pdf` using `Tasks.await()` (Apr 2026); calls `saveChapter` with `pdfStoragePath` + `pdfId` |
| Firestore restore callback | ~130–165 | After `loadChapters()` returns remote chapters, calls `loadChapterMeta()` for each missing-from-SharedPrefs chapter to restore `isPdf`/`pdfId` (Apr 2026) |
| `saveChapterMeta()` | ~227–235 | Writes `meta_{subject}_{chapter}` JSON to SharedPrefs with isPdf, pdfAssetPath, pdfId |
| PDF storage path | — | Firebase Storage: `user_pdfs/{userId}/{pdfId}.pdf`; Firestore field: `pdfStoragePath` |
| ⚠️ cacheDir vs persistence | — | `cacheDir/pdf_cache/` is cleared on reinstall; Firebase Storage is the source of truth |

---

## services/StorageService.kt
**Path:** `app/src/main/java/com/aiguruapp/student/services/StorageService.kt`

| Symbol | Lines | What it does |
|--------|-------|--------------|
| Storage root | ~47 | `Environment.DIRECTORY_DOCUMENTS/AI Guru/` — public external; survives reinstall IF permissions granted |
| `initialize()` | ~42–75 | Creates subdirs: pdfs/, images/, audio/tts/, cache/, metadata/ |
| `getPdfFile()` | ~88 | Returns `File` in pdfs/ subdir |
| ⚠️ Not used for custom PDFs | — | Custom PDFs use `cacheDir/pdf_cache/` (lost on reinstall); only Firebase Storage survives |

---

## services/CloudBackupService.kt
**Path:** `app/src/main/java/com/aiguruapp/student/services/CloudBackupService.kt`

| Symbol | Lines | What it does |
|--------|-------|--------------|
| `firebaseStorage` | 25 | `FirebaseStorage.getInstance()` |
| `uploadFile()` | ~171–190 | `fileRef.putFile(Uri.fromFile(file))` pattern |
| `downloadFile()` | ~241–260 | `sourceRef.getBytes(Long.MAX_VALUE)` → `destFile.writeBytes(bytes)` pattern |

---

## Key Android Architecture Facts
- **Launcher:** `SplashActivity` → update gate → `HomeActivity`
- **Main chat:** `FullChatFragment` (hosted in `ChatHostActivity` or `HomeActivity`)
- **BB lesson:** `BlackboardActivity` (singleTop, full-screen)
- **Quiz popups:** `bb/BbInteractivePopup.kt` (singleton object, 5 quiz types)
- **Real-time voice:** `GeminiLiveActivity` + `streaming/GeminiLiveClient.kt`
- **HTTP:** `chat/AiClient.kt` (AI endpoints), `chat/ServerProxyClient.kt` (backend proxy), `http/HttpClientManager.kt` (OkHttp factory)
- **Auth tokens:** `auth/TokenManager.kt` → Firebase ID token refresh
- **TTS:** `tts/BbAiTtsEngine.kt` → `/api/tts/synthesize` (zero LLM calls)
- **Branding:** `utils/SchoolTheme.kt` + `utils/ConfigManager.kt` (runtime school colors)
- **Session state:** `utils/SessionManager.kt` (SharedPrefs: uid, schoolId, plan)
- **Firestore:** `firestore/FirestoreManager.kt` (all reads/writes), `firestore/HomeSmartContentLoader.kt`, `firestore/StudentStatsManager.kt`
- **Quota gates:** `validators/BlackboardQuotaValidator.kt`, `ChatQuotaValidator.kt`, `AiVoiceQuotaValidator.kt`
- **Plan access:** `config/AccessGate.kt`, `config/PlanEnforcer.kt`
- **Build config:** `compileSdk=36`, `targetSdk=36`, `minSdk=26`, `versionCode=15`, `versionName=1.3.0`
- **Tasks flow:** Teacher creates in `TeacherTasksActivity` → `school_tasks/{id}` (global collection) → Student reads in `TasksActivity` via `loadTasksForSchool(schoolId, grade, section)` — match on school_id
- **PDF persistence:** Import → `cacheDir/pdf_cache/` (local, lost on reinstall) + `user_pdfs/{uid}/{pdfId}.pdf` (Firebase Storage, permanent) + Firestore chapter doc has `pdfStoragePath`/`pdfId`
- **Firebase Storage pattern:** Use fully-qualified `com.google.firebase.storage.FirebaseStorage.getInstance()` (no import) to survive offline-Gradle linter stripping
- **Composite index rule:** Never use `whereEqualTo(A) + orderBy(B)` in Firestore without creating index — use single-field filter + in-memory sort
