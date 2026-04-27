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
| `onCreate()` | 291–? | Binds full BB UI, input controls, TTS + session flow |
| `enableEdgeToEdge()` | 293 | Enables edge-to-edge compatibility for pre-Android-15 devices |

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

## adapters/ | models/ | utils/ | streaming/ | calculator/
> Unread. Update on first access.

---

## Key Android Architecture Facts
- Launcher: `SplashActivity` (not MainActivity)
- Main chat: `FullChatFragment`  
- BB lesson: `BlackboardActivity` (separate full-screen)
- Quiz popups: `bb/BbInteractivePopup.kt`
- All HTTP: via `AiClient` or `ServerProxyClient`
- TTS: `tts/BbAiTtsEngine.kt` → `/api/tts/synthesize` (no LLM)
