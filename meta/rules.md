# Full Rules Reference
> Only read this when CLAUDE.md pointers are insufficient.
> Last updated: 2026-04-26

## Session Management
- Suggest `/clear` when switching to an unrelated topic (different files/feature).
- Do NOT suggest `/clear` if continuing the same feature area.
- After `/clear`, start from `meta/tracker.md` tail + relevant meta index — never re-read source files from scratch.
- Broad "how does X work?" questions → answer from meta index first. Only open source if meta says `?`.

## Read Routing (detailed)
- `app_context/` files are legacy — do NOT read them. `meta/server_index.md` and `meta/android_index.md` replace them entirely.
- Only fall back to `app_context/backend_svg_pipeline.py` if SVG pipeline details are missing from meta index.

## Editing Rules
- Smallest viable patch. No full-file rewrites unless necessary.
- Do not touch unrelated files during a focused fix.
- After any change: update meta index line numbers + append to tracker.
- Mark removed symbols in meta index with ~~strikethrough~~.

## Validation Rules
- Android XML/resource fix: run smallest Gradle task that confirms compile.
- Kotlin fix: compile affected module only.
- Backend fix: focused import or endpoint-level check.
- Avoid full builds unless narrow check fails.

## Response Style
- Lead with fix/decision, not explanation.
- Short bullets only when needed.
- Reference file:line instead of pasting code blocks.
- No trailing summaries of what was just done.

## Task-Specific
- Android UI/resource error: start from the exact file + resource named in build error only.
- BB Blackboard rendering ≠ standalone `/diagram/generate` — different pipelines.
- Do not assume diagram type works everywhere; check JS engine vs raw SVG vs `svg_builder._RENDERERS`.
- LiteLLM proxy: check localhost:8006 admin UI if BB costs spike unexpectedly.
