
---

## 2025-07-10 (session 12)

**Asked:** (1) Fix LiteLLM dashboard showing default_user_id instead of real uid. (2) Fix board jumping from step 1 to step 2 mid-playback when full chunk arrives.

**Fix 1 — LiteLLM user attribution:**
- server/app/services/llm_service.py _call_litellm_proxy() JSON payload: added top-level "user": uid or "guest" field. LiteLLM reads this field to tag calls with the real user ID in the dashboard. Previously only metadata.uid was set (not read by LiteLLM for user tracking).

**Fix 2 — Board jump mid-playback:**
- Root cause: onSuccess called setupBoard() which removed all board views, then when auto-advance fired (continueAfterFrame), advanceFrame navigated to step 2 on a blank board.
- Fix: In onSuccess, snapshot resumeStep/resumeFrame BEFORE replacing steps. When wasAlreadyPlaying=true: skip setupBoard() entirely, call preloadUpcoming(resumeStep, resumeFrame), do NOT call showFrame(). Existing animation/TTS continues naturally; continueAfterFrame guard (currentStepIdx!=stepIdx check) remains valid since indices unchanged.
- When wasAlreadyPlaying=false: still calls setupBoard() + showFrame(0,0) as before.

**Files:** server/app/services/llm_service.py (line ~237), BlackboardActivity.kt (onSuccess handler in generateChunk call, ~line 935)

---

## Session 16 — BB Shared Session Replay Bug Fix

**Date:** 2025-01-31
**Asked:** BB shared sessions not replaying correctly — message_id / blackboard session data missing for recipient

**Root Cause Found:**
- `BbSavedSessionsActivity.replaySession()` calls `loadFromSavedSession(sessionId, userId)`
- `loadFromSavedSession` looks in `users/{recipientUid}/saved_bb_sessions_flat/{sessionId}` 
- But shared sessions live in `users/{recipientUid}/shared_with_me/{senderUid}_{sessionId}` (written by server)
- Result: "Saved session not found" → falls back to LLM regeneration (wrong topic, wrong content)
- `loadSharedWithMe()` does NOT add `id` field (unlike other loaders) but `session_id` IS set as explicit field — so `session["session_id"]` is correct

**Fix Applied:**
- `BbSavedSessionsActivity.kt` `replaySession()` (~line 266): added block — when `showingShared == true` and `sessionId` is non-blank, reads `session["steps_json"]` from the loaded shared_with_me doc and writes it to disk cache via `BlackboardGenerator.writeSessionCache(applicationContext, sessionId, stepsJson)`
- Cache hit in `loadFromSavedSession` then bypasses Firestore entirely — correct content plays

**Files changed:** app/src/main/java/com/aiguruapp/student/BbSavedSessionsActivity.kt (~line 266, added 9 lines before intent creation)
