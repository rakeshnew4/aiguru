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

**Asked:** Add rule: on every new session check/create the 4-min keep-alive cron. Also auto-suggest /clear when new question is unrelated to previous tracker context.
**Changed:**
- `CLAUDE.md` — added "Every Session Start" section with: (1) keep-alive cron check/create, (2) tracker read, (3) topic comparison → suggest /clear if unrelated

---

**Asked:** Keep-alive ping.
**Changed:** No code changes.
