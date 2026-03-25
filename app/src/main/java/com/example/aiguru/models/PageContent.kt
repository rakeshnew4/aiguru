package com.example.aiguru.models

import org.json.JSONArray
import org.json.JSONObject

/**
 * One extracted paragraph from a scanned page or image.
 */
data class PageParagraph(
    val number: Int = 0,
    val text: String = "",
    val summary: String = ""
)

/**
 * One diagram, figure, or illustration found on a page.
 *
 * Fields:
 *  - heading      : Figure label/title (e.g. "Figure 1.3: Cell Structure")
 *  - context      : Which concept or topic this diagram illustrates
 *  - description  : All visible elements — labels, arrows, components, colours
 *  - depiction    : What the diagram proves or explains (the learning takeaway)
 *  - position     : Where on the page ("top-right", "center", etc.)
 *  - labelledParts: Named parts visible inside the image
 */
data class DiagramInfo(
    val heading: String = "",
    val context: String = "",
    val description: String = "",
    val depiction: String = "",
    val position: String = "",
    val labelledParts: List<String> = emptyList()
)

/**
 * Structured content extracted from one image/page attached in chat.
 *
 * Stored in Firestore at:
 *   users/{uid}/subjects/{subject}/chapters/{chapter}/pages/{pageId}
 *
 * [paragraphsJson] and [diagramsJson] are JSON array strings rather than
 * nested objects to avoid Firestore nested-collection serialization limitations.
 *
 * Use [parsedParagraphs] / [parsedDiagrams] to get typed objects in-memory.
 * Use [toContextSummary] to get a compact string to include as a history entry
 * when sending the request to the AI server.
 */
data class PageContent(
    /** Unique ID — format: "{safeSubject}__{safeChapter}__p{epochMs}" */
    val pageId: String = "",
    val subject: String = "",
    val chapter: String = "",
    /** 0 if unknown (ad-hoc image), ≥1 for PDF pages */
    val pageNumber: Int = 0,
    /** "image" | "camera" | "pdf" */
    val sourceType: String = "image",
    /** Full verbatim text extracted from the image */
    val transcript: String = "",
    /** JSON array string — see [parsedParagraphs] */
    val paragraphsJson: String = "[]",
    /** JSON array string — see [parsedDiagrams] */
    val diagramsJson: String = "[]",
    val keyTerms: List<String> = emptyList(),
    val analyzedAt: Long = 0L
) {

    // ── Typed accessors (in-memory only) ─────────────────────────────────────

    fun parsedParagraphs(): List<PageParagraph> = runCatching {
        val arr = JSONArray(paragraphsJson)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            PageParagraph(
                number  = o.optInt("number", i + 1),
                text    = o.optString("text", ""),
                summary = o.optString("summary", "")
            )
        }
    }.getOrDefault(emptyList())

    fun parsedDiagrams(): List<DiagramInfo> = runCatching {
        val arr = JSONArray(diagramsJson)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val parts = o.optJSONArray("labelled_parts")
                ?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            DiagramInfo(
                heading       = o.optString("heading", ""),
                context       = o.optString("context", ""),
                description   = o.optString("description", ""),
                depiction     = o.optString("depiction", ""),
                position      = o.optString("position", ""),
                labelledParts = parts
            )
        }
    }.getOrDefault(emptyList())

    /**
     * Compact summary (≤600 chars) suitable for injection into the
     * server's history list as a "system_context: ..." entry.
     */
    fun toContextSummary(): String {
        val excerpt = if (transcript.length > 400) "${transcript.take(400)}…" else transcript

        val diagramLines = parsedDiagrams().joinToString("; ") { d ->
            listOfNotNull(
                d.heading.takeIf { it.isNotBlank() },
                d.depiction.takeIf { it.isNotBlank() }
                    ?.let { "depicts $it" },
                d.labelledParts.takeIf { it.isNotEmpty() }
                    ?.let { "parts: ${it.joinToString()}" }
            ).joinToString(", ")
        }.let { if (it.isNotBlank()) "Diagrams — $it." else "" }

        val termLine = if (keyTerms.isNotEmpty())
            "Key terms: ${keyTerms.joinToString()}." else ""

        return "Page transcript: $excerpt $diagramLines $termLine".trim()
    }

    /**
     * Full structured representation for detailed server context or saving as notes.
     */
    fun toDetailedContext(): String = buildString {
        if (transcript.isNotBlank()) {
            appendLine("=== PAGE TRANSCRIPT ===")
            appendLine(transcript)
        }
        val paras = parsedParagraphs()
        if (paras.isNotEmpty()) {
            appendLine("\n=== PARAGRAPHS ===")
            paras.forEach { p ->
                appendLine("§${p.number}: ${p.summary}")
            }
        }
        val diags = parsedDiagrams()
        if (diags.isNotEmpty()) {
            appendLine("\n=== DIAGRAMS / FIGURES ===")
            diags.forEach { d ->
                appendLine("• ${d.heading}")
                if (d.context.isNotBlank())      appendLine("  Context   : ${d.context}")
                if (d.description.isNotBlank())   appendLine("  Elements  : ${d.description}")
                if (d.depiction.isNotBlank())      appendLine("  Depicts   : ${d.depiction}")
                if (d.position.isNotBlank())       appendLine("  Position  : ${d.position}")
                if (d.labelledParts.isNotEmpty())  appendLine("  Labels    : ${d.labelledParts.joinToString()}")
            }
        }
        if (keyTerms.isNotEmpty()) {
            appendLine("\n=== KEY TERMS ===")
            appendLine(keyTerms.joinToString())
        }
    }

    /**
     * Structured JSON object to send as `image_data` in the server API payload.
     * Shape:
     *   {
     *     "transcript": "...",
     *     "paragraphs": [{"number":1, "text":"...", "summary":"..."}],
     *     "diagrams":   [{"heading":"...", "context":"...", "description":"...",
     *                     "depiction":"...", "position":"...", "labelled_parts":[...]}],
     *     "key_terms":  ["term1", "term2"]
     *   }
     */
    fun toImageDataJson(): JSONObject {
        val paragraphsArr = JSONArray().apply {
            parsedParagraphs().forEach { p ->
                put(JSONObject().apply {
                    put("number",  p.number)
                    put("text",    p.text)
                    put("summary", p.summary)
                })
            }
        }
        val diagramsArr = JSONArray().apply {
            parsedDiagrams().forEach { d ->
                put(JSONObject().apply {
                    put("heading",        d.heading)
                    put("context",        d.context)
                    put("description",    d.description)
                    put("depiction",      d.depiction)
                    put("position",       d.position)
                    put("labelled_parts", JSONArray(d.labelledParts))
                })
            }
        }
        return JSONObject().apply {
            put("transcript", transcript)
            put("paragraphs", paragraphsArr)
            put("diagrams",   diagramsArr)
            put("key_terms",  JSONArray(keyTerms))
        }
    }
}
