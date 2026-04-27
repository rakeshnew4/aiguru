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
| `sendBbChat()` | 3748–3870 | Sends student question to server; builds question/response cards on board |
| `buildChatCard()` | 4007–4060 | Creates card view; decodes base64 image thumbnail if present |
| `launchBbCamera()` / `openBbCamera()` | 3323–3355 | Camera picker dialog → ContentValues insert → bbCameraLauncher |
| `launchBbCrop()` | 3357–3400 | UCrop launch; fallback `encodeBbImage()` on exception |
| `encodeBbImage()` | 3403–3425 | Background thread base64 encode; shows bbImgPreviewRow |
| `clearBbImage()` | 3422–3426 | Clears `bbPendingImageUri/Base64`, hides row |

---

## FullChatFragment.kt
**Path:** `app/src/main/java/com/aiguruapp/student/FullChatFragment.kt` | **⚠️ Expensive**

| Symbol | Lines | What it does |
|--------|-------|--------------|
| (unread — use app_context/android_index.py first) | ? | Main chat UI implementation |

---

## chat/BlackboardGenerator.kt
**Path:** `app/src/main/java/com/aiguruapp/student/chat/BlackboardGenerator.kt`

| Symbol | Lines | What it does |
|--------|-------|--------------|
| (unread) | ? | Client-side BB request builder / response parser |

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
| (unread) | ? | Proxies requests to backend server |

---

## tts/BbAiTtsEngine.kt
**Path:** `app/src/main/java/com/aiguruapp/student/tts/BbAiTtsEngine.kt`

| Symbol | Lines | What it does |
|--------|-------|--------------|
| TTS cache + fetch | ~36–266 | Fetches MP3 from /api/tts/synthesize; falls back to Android native TTS |
| Server call | ~227–235 | Calls `/api/tts/synthesize` with tts_engine param |
| No LLM calls | — | Confirmed: zero LLM calls in TTS path |

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
| (unread) | ? | All Firestore read/write operations |

---

## HomeActivity.kt | ChapterActivity.kt | ChatActivity.kt
**⚠️ Expensive** — unread. Use `app_context/android_architecture.py` first.

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

## res/values/themes.xml
**Path:** `app/src/main/res/values/themes.xml`

| Symbol | Lines | What it does |
|--------|-------|--------------|
| `Theme.AIGuru` | 11–39 | Main app theme and Material color roles |
| `Theme.AIGuru.Splash` | 62–68 | Splash window styling |
| `Theme.AIGuru.Fullscreen` | 70–76 | Fullscreen image viewer theme |

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
| `MediaManager.kt` | Audio/video media session management |
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
