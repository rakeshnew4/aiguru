# CLAUDE.md

Repository rules for Claude Code in this project.

Goal: spend fewer tokens per task so sessions last longer, while still making correct code changes.

## Core Rule

- Do not start by scanning the whole repo.
- Read the smallest useful context first, then inspect only the files needed for the current task.
- Keep answers short and implementation-focused.

## First Read Routing

Use the `app_context` summaries before opening large source files.

- Backend task: read `app_context/backend_index.py` first.
- Android task: read `app_context/android_index.py` first.
- Diagram / SVG / Blackboard visual task: read `app_context/backend_svg_pipeline.py` first.
- Chat / quiz / payments / TTS / users endpoint task: read `app_context/backend_chat_services.py` first.
- Auth / config / Firestore / model-routing task: read `app_context/backend_architecture.py` first.
- Android startup / screen map / navigation task: read `app_context/android_architecture.py` first.
- Android state / network / quota / storage task: read `app_context/android_state_and_network.py` first.

If the context file already answers the question, do not reopen the full source file.

## Token-Saving Search Rules

- Always search first with `rg` or filename search before opening files.
- Open only the matching line ranges, not entire large files.
- Do not read Android and backend code together unless the task truly crosses the client-server contract.
- Do not reread the same file in full if you already inspected the relevant region.
- Prefer path references, symbols, and short summaries over pasted code.
- Summarize logs and build output in a few lines instead of echoing them.

## Large File Hotspots

These files are expensive. Do not open them top-to-bottom unless there is no alternative.

- `app/src/main/java/com/aiguruapp/student/FullChatFragment.kt`
- `app/src/main/java/com/aiguruapp/student/BlackboardActivity.kt`
- `app/src/main/java/com/aiguruapp/student/HomeActivity.kt`
- `app/src/main/java/com/aiguruapp/student/ChapterActivity.kt`
- `app/src/main/java/com/aiguruapp/student/firestore/FirestoreManager.kt`
- `server/app/api/chat.py`
- `server/app/api/image_search_titles.py`
- `server/app/services/prompt_service.py`
- `server/app/static/engine/diagrams.js`

For these files:

- Search for the symbol, error string, endpoint, or method first.
- Read only the local slice around the match.
- Use `app_context/*.py` to avoid re-deriving architecture from scratch.

## Ignore By Default

Do not spend tokens on these unless the user explicitly asks or the task directly touches them.

- `build/`
- `.gradle/`
- `.idea/`
- `.git/`
- `.git-rewrite/`
- `__pycache__/`
- generated artifacts
- test HTML or one-off outputs
- inactive backup builders such as `server/app/utils/svg_builder_original_backup.py`

## Project-Specific Reality Shortcuts

Use these facts to avoid unnecessary exploration:

- `SplashActivity` is the true Android launcher.
- `FullChatFragment` is the main chat implementation.
- `BlackboardActivity` is a separate full-screen lesson system.
- Blackboard visual finalization happens in `server/app/api/image_search_titles.py`.
- Standalone diagram generation in `server/app/services/diagram_service.py` is narrower than the Blackboard visual pipeline.
- `generate_response()` currently routes through the LiteLLM proxy path.
- `context_service.get_context()` is still a stub.
- `blackboard_prompt` is ultra-compact (~400 tokens). `_ACCURACY_NOTES` was removed — key rules are folded into the main prompt. Do not expand it back.
- BB main LLM uses **sparse output** (only non-default fields) and **intent-only PATH 2** (`diagram_type="custom"`, `data={"intent":"..."}`). The engine renders — never the main LLM.
- `_normalize_frame()` in `image_search_titles.py` restores all default fields after sparse parse.
- `svg_llm_builder` uses Phase 1 animation only, max_tokens=4096; rejects output with `<script>`.
- **LiteLLM proxy**: ensure thinking is disabled and model alias `gemini-2.5-flash-lite` routes to the lite model (not full flash). Check localhost:8006 admin UI if BB costs spike.

## Task-Specific Rules

### Android UI / resource errors

- Start from the exact file and missing resource named in the build error.
- Open the referenced layout, drawable, color, style, or manifest entry only.
- Do not inspect backend files for Android resource-linking failures.

### Android feature work

- Use `app_context/android_*` files first.
- Only open the specific Activity, Fragment, adapter, or XML involved.
- Avoid reading unrelated screens.

### Backend endpoint work

- Use `app_context/backend_*` files first.
- Open only the router, service, and utility files on the active request path.
- Do not read prompt files or JS engine files unless the task actually touches them.

### Diagram / SVG / Blackboard work

- Read `app_context/backend_svg_pipeline.py` first.
- Treat Blackboard rendering and standalone `/diagram/generate` as different pipelines.
- Do not assume a diagram type works everywhere; check whether it is routed through JS engine, raw SVG, or `svg_builder._RENDERERS`.

## Editing Rules

- Make the smallest viable patch.
- Prefer local edits over full-file rewrites.
- Do not change unrelated files during focused bug fixes.
- If behavior changes, update the matching `app_context/*.py` summary in the same task.

## Validation Rules

- Run the narrowest useful verification.
- Android XML/resource fix: run the smallest Gradle task that confirms resources compile.
- Kotlin code fix: compile the affected module or app, not the entire workspace if avoidable.
- Backend change: run a focused import, test, or endpoint-level check.
- Avoid full builds unless the narrow check is insufficient.

## Response Style

- Be concise.
- Lead with the fix, bug, or decision.
- Use short bullets only when needed.
- Do not repeat architecture already captured in `app_context`.
- When explaining code, reference file paths and function names instead of pasting long code blocks.
