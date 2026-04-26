# CLAUDE.md
Goal: minimum token spend per task. Details in `meta/rules.md`.

## Every Session Start — Do This Once
1. Check if a keep-alive cron job is running (`/loop` every 4 min). If not, create it:
   - Prompt: `Say "thank you" — keep-alive ping to maintain context cache.`
   - Cron: `*/4 * * * *`, recurring=true
2. Read last 50 lines of `meta/tracker.md`
3. Compare new question topic to last tracker entries:
   - Same area (same files/feature) → continue, no clear needed
   - Different/unrelated area → suggest `/clear` to user before proceeding
4. Decide what to read next:
   - Task touches same file as last tracker entry → use those line numbers directly, skip meta index
   - New backend area → read `meta/server_index.md` (jump to exact lines)
   - New Android area → read `meta/android_index.md` (jump to exact lines)
   - Skip both if tracker already has the answer

## Every Task — Do This After (NO EXCEPTIONS)
- Append to `meta/tracker.md`: date | asked | files:lines changed
- This applies to ALL tasks — code changes, questions, clarifications, tiny fixes. Every single one.
- Update `meta/server_index.md` or `meta/android_index.md` with any new/changed line numbers
Whatever You read in the repo, even  mistakenly also, just need to update the meta data, so that in future , it can be useful for future questions. you can enter like i have read sample.py , there are 3 functions named .... and 1 function lin numbers 4-60, this function does to read the data and do process and retruns this format, it calls other two functions lie etc etc, and then collects something and returns, like that you need to enter, whenever you read something. 
there is no Exception, everything you have to read and update, you can create new files for meta data md files if the file size becomes too big if more thatn some 500 lines, name it accordingly on your comfort to read


## Hard Rules
- Never read a file top-to-bottom. Search first, then read only the matching slice.
- Never read Android + backend together unless the task crosses the API contract.
- Never open: `build/`, `.gradle/`, `.idea/`, `__pycache__/`, `svg_builder_original_backup.py`
- Expensive files (read slices only): `FullChatFragment.kt`, `BlackboardActivity.kt`, `HomeActivity.kt`, `FirestoreManager.kt`, `chat.py`, `image_search_titles.py`, `prompt_service.py`, `diagrams.js`

## Key Facts (avoids re-reading)
- Launcher: `SplashActivity`. Main chat: `FullChatFragment`. BB lesson: `BlackboardActivity`.
- All LLM calls → `generate_response()` → `_call_litellm_proxy()` in `llm_service.py`
- BB post-processing (enrichment + SVG + wikimedia) → `get_titles()` in `image_search_titles.py`
- TTS = zero LLM calls. Only calls Google/ElevenLabs/OpenAI audio APIs.
- Quiz validator removed. `build_enrichment_tasks()` returns 2-tuple now.
- LiteLLM proxy body includes `thinking={budget_tokens:32}` + `extra_body={cache_control:ephemeral}`
- `context_service.get_context()` is a stub. `blackboard_prompt` is ~400 tokens — do not expand.

## Full Rules Reference
See `meta/rules.md` — read only if you need details on session management, validation, or response style.
