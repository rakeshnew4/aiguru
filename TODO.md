# AI Guru — Production TODO List
> Last updated: 2026-04-28 (session 7)
> Format: [status] Priority — Description — Effort — File(s)
> Status: ✅ Done | 🔄 In Progress | ⏳ Pending | ❌ Won't Fix

---

## 🔴 Stability / Correctness (Ship Blockers)

| # | Status | Issue | Effort | File(s) |
|---|--------|-------|--------|---------|
| S1 | ✅ Done | `activate_plan` fallback always awards credits even if plan already activated — race between `/verify` + webhook double-awards credits | 3 lines | `server/app/services/user_service.py` |
| S2 | ✅ Done | `LITELLM_MASTER_KEY` default removed — config.py now uses `""` (empty); startup warns if empty or insecure; `.env` must set the key | 3 lines | `server/app/core/config.py`, `server/app/main.py` |
| S3 | ✅ Done | `activate_plan` fallback write missing `free_chat_remaining` + `free_bb_remaining` — daily quota drifts after transaction failure | 2 lines | `server/app/services/user_service.py` |
| S4 | ✅ Done | CORS hardcoded IP — warn at startup when `ALLOWED_ORIGINS` env var is missing | 3 lines | `server/app/main.py` |
| S5 | ⏳ Pending | LiteLLM proxy single point of failure — set up systemd/supervisor restart + uptime alert | Medium | Deploy config |
| S6 | ⏳ Pending | `check_and_record_quota` Firestore failure silently allows free request — decide: fail open or fail closed | Low | `server/app/services/user_service.py:493` |

---

## 🟠 Quiz Improvements

| # | Status | Issue | Effort | File(s) |
|---|--------|-------|--------|---------|
| Q1 | ✅ Done | Quiz cache key is global per chapter — all students get same questions, enabling answer sharing | 3 lines | `server/app/services/quiz_service.py`, `server/app/api/quiz.py` |
| Q2 | ⏳ Pending | Adaptive difficulty — track per-chapter wrong-answer rate in Firestore; skew next quiz toward weak topics | High | `quiz_service.py`, `users_table` |
| Q3 | ⏳ Pending | Timed quiz mode — optional countdown per question (CBSE exam prep) | Medium | Android `QuizActivity.kt` |
| Q4 | ⏳ Pending | Diagram-in-quiz — embed mini SVG in science/math questions | High | `quiz_service.py`, Android quiz renderer |
| Q5 | ✅ Done | `submitQuiz` in Android is fire-and-forget with silent fail — quiz scores lost on bad network | 10 lines | `app/.../quiz/QuizApiClient.kt` |
| Q6 | ⏳ Pending | Short-answer evaluation: if LLM returns unparseable JSON on evaluate, client gets no feedback | Low | `server/app/services/evaluation_service.py` |

---

## 🟠 Teacher-Student Flow

| # | Status | Issue | Effort | File(s) |
|---|--------|-------|--------|---------|
| T1 | ✅ Done | No server-side due-date enforcement — students can submit overdue tasks and still earn credits | 5 lines | `server/app/api/tasks.py` |
| T2 | ⏳ Pending | No FCM push on task assignment — students only see tasks when they open the app | High | Server + Android `FirebaseMessagingService` |
| T3 | ✅ Done | Teacher task completions view — `GET /tasks/{task_id}/completions` returns who submitted, when, with answer preview | Medium | `server/app/api/tasks.py` |
| T4 | ⏳ Pending | Student weakness heatmap on teacher dashboard (subject × chapter grid) | Medium | Android `TeacherDashboardActivity.kt` |
| T5 | ⏳ Pending | Teacher can attach a quiz to a BB lesson as one compound task (watch → quiz) | High | Android + server tasks model |
| T6 | ⏳ Pending | Class leaderboard per school (opt-in, shown to teacher + students) | Medium | New Firestore collection + Android UI |

---

## 🟡 Retention & Growth

| # | Status | Issue | Effort | File(s) |
|---|--------|-------|--------|---------|
| R1 | ⏳ Pending | No FCM push for daily question streak reminder | High | Server FCM + Android |
| R2 | ✅ Done | Referral reward wired — 20 credits to both parties via Firestore transaction in `ReferralManager.claimReferralCode` | Medium | `app/.../config/ReferralManager.kt`, `app/.../UserProfileActivity.kt` |
| R3 | ✅ Done | Notes Firestore write-through sync — `ChapterNotesRepository` writes to `notes/{uid}/chapters/` on every save; `restoreFromFirestoreIfEmpty` on `NotesActivity` open | Medium | `app/.../notes/ChapterNotesRepository.kt`, `app/.../notes/NotesActivity.kt` |
| R4 | ⏳ Pending | Parent read-only view (weekly digest of child's progress) | High | New Android activity + server endpoint |
| R5 | ⏳ Pending | BB session share card for WhatsApp (summary image with topic + key points) | Medium | Android `BlackboardActivity.kt` |
| R6 | ⏳ Pending | Streak + gamification prominently on Home screen (currently buried in Progress) | Low | `HomeActivity.kt` |

---

## 🟡 UX / Polish

| # | Status | Issue | Effort | File(s) |
|---|--------|-------|--------|---------|
| U1 | ⏳ Pending | Quota limit UI — HTTP 429 shows no user-facing explanation in some flows | Low | Android error handling |
| U2 | ⏳ Pending | Guest / email login — only Google Sign-In available; locks out school students | High | Android auth + server |
| U3 | ⏳ Pending | NCERT chapter completion badge — viewer exists but completion not rewarded visibly | Medium | Android + Firestore progress |
| U4 | ✅ Done | BB mid-chunk failure shows indefinite Snackbar with "Retry" action calling `triggerNextChunk()` | Medium | `BlackboardActivity.kt` |

---

## ✅ Already Fixed (validated 2026-04-28)

| # | What | When |
|---|------|------|
| - | `context_service.get_context()` is fully implemented (Firestore index + ES retrieval) — NOT a stub | Pre-existing |
| - | `bar_chart` renderer IS registered in `_RENDERERS` in `svg_builder.py` | Pre-existing |
| - | POWER model already updated to `gemini-2.5-flash` (stable) | Pre-existing |
| - | Teacher role gating (`require_teacher`) is server-side and secure | Pre-existing |
| - | Quiz wrong-answer → BB remediation is implemented in `QuizResultActivity.kt` | Pre-existing |
| - | `activate_plan` uses Firestore transaction with 120s grace window | Pre-existing |
| - | Free sessions truly free (no credit deduction) — `check_and_record_quota` returns `credit_mode` flag | Session 4 |
| - | All LLM calls auto-charge credits via daemon thread in `generate_response()` | Session 3 |
| - | BB animations gate covers ALL render paths | Session 4 |
