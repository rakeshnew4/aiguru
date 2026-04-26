# Android File Index
> Auto-maintained. Update this file every time you read an Android file.
> Format: file path → key symbols with line numbers and one-line purpose.
> If line numbers are unknown, mark as `?` and fill on next read.

---

## bb/BbInteractivePopup.kt
**Path:** `app/src/main/java/com/aiguruapp/student/bb/BbInteractivePopup.kt` | **Size:** ~935 lines

| Symbol | Lines | What it does |
|--------|-------|--------------|
| `quiz_mcq` popup builder | ~146–232 | 4-option MCQ quiz popup |
| `quiz_typed` / `quiz_voice` popup | ~275–318 | Text/voice input quiz |
| `quiz_fill` popup | ~368–548 | Fill-in-the-blank quiz |
| `quiz_order` popup | ~612–738 | Tap-to-order quiz |
| skip button — all 5 call sites | 218, 298, 413, 576, 732 | `dialog.dismiss(); onResult(QuizResult(false, 0, "Skipped"))` — fixed Apr 2026 |
| `skipButton()` helper | ~926–935 | Creates skip TextView; onClick is passed in |
| `buildDialog()` | ~885–930 | Creates Dialog with `setCancelable(false)` |
| Confidence picker | ~105–142 | Shows before every quiz: "I know / Not sure / Guessing" |

---

## BlackboardActivity.kt
**Path:** `app/src/main/java/com/aiguruapp/student/BlackboardActivity.kt` | **⚠️ Expensive**

| Symbol | Lines | What it does |
|--------|-------|--------------|
| (unread — use app_context/android_architecture.py first) | ? | Full-screen BB lesson system |

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
| (unread) | ? | True app launcher (not MainActivity) |

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
