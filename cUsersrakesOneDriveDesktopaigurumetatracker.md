
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
