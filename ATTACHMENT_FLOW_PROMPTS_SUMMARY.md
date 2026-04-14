# PDF/Image Attachment Processing - Prompts & Flow Summary

## SYSTEM ARCHITECTURE OVERVIEW

**Three main LLM endpoints handle attachments:**
1. **`/analyze-image`** - Extracts text/diagrams from educational images (Vision API)
2. **`/chat-stream`** - Main chat with image context (MultiModal LLM)
3. **`/chat-stream` Image Frame** - Extracts transcription for Firestore storage

---

## PROMPT 1: Image Analysis Prompt (`/analyze-image`)

### Location
- [server/app/api/analyze_image.py](server/app/api/analyze_image.py#L45)

### System Prompt
```
You are an expert educational content extractor.
Analyze this image of a textbook or classroom material.
Return ONLY a valid JSON object — no markdown fences, no explanation.
```

### User Prompt
```
Analyze this educational page image in detail. Return a JSON object with EXACTLY this structure:

{
  "transcript": "Full verbatim text extracted from the page — every word, equation, caption",
  "paragraphs": [
    {
      "number": 1,
      "text": "Exact text of this paragraph",
      "summary": "One-sentence summary of what this paragraph explains"
    }
  ],
  "diagrams": [
    {
      "heading": "Figure label/title visible in the image, e.g. 'Figure 2.1: Mitosis'",
      "context": "Which topic or concept this diagram relates to",
      "description": "All visible elements — labels, arrows, components, colours, numbers",
      "depiction": "What the diagram is illustrating, proving, or teaching the student",
      "position": "Where on the page — e.g. top-right, center, bottom-left",
      "labelled_parts": ["Label A", "Label B"]
    }
  ],
  "key_terms": ["term1", "term2", "term3"]
}

Rules:
- Include ALL text, ALL diagrams/figures/tables visible in the image.
- If there are no diagrams, set "diagrams" to [].
- Return ONLY the JSON — no extra text before or after.
```

### Response Fields
| Field | Type | Description |
|-------|------|-------------|
| `transcript` | String | Full verbatim text extracted (used in Firestore) |
| `paragraphs` | Array | Structured text with summaries |
| `diagrams` | Array | Detected diagrams with descriptions |
| `key_terms` | Array | Important terminology |

### Caching
- **Enabled for:** Gallery images, PDF screenshots
- **Cache key:** SHA-256(length + first 512 chars + last 256 chars)
- **Duration:** 7 days in Redis
- **Skipped for:** Camera captures (always fresh)

---

## PROMPT 2: Chat with Image Context (`/chat-stream`)

### Location
- [server/app/api/chat.py](server/app/api/chat.py#L445)

### System Prompt (Dynamic)
Built from [prompt_service.py](server/app/services/prompt_service.py#L120):

```
You are an expert {SUBJECT} tutor helping a {GRADE_LEVEL} student.

[Based on chat mode - injected here]

INSTRUCTIONS:
- Explain clearly, using exact terminology
- Cite specific figures/tables from materials
- Provide step-by-step reasoning
- End with a summary question to check understanding

IMPORTANT: If the student attached an image/PDF page:
- Use the page_transcript field to understand what they shared
- Reference specific content: "Looking at your page, I see..."
- Build your answer directly from the visual content

Always return a valid JSON object with this structure:
{
  "explanation": "Your detailed explanation...",
  "key_points": ["Point 1", "Point 2", "Point 3"],
  "user_attachment_transcription": "The text/content from their image",
  "visual_analysis": "How the image helps understand the concept",
  "follow_up": "A follow-up question for the student"
}
```

### User Prompt (Dynamic)
Constructed from question + context:

```
Subject: {SUBJECT}
Chapter: {CHAPTER}
Page: {PAGE_CONTEXT}

{PAGE_TRANSCRIPT (if image attached)}

Student Question: {QUESTION}

Previous Context:
{CHAT_HISTORY}

Please help the student understand this concept.
```

### Response Fields
| Field | Type | Description |
|-------|------|-------------|
| `explanation` | String | Main tutoring response |
| `key_points` | Array | Key takeaways |
| `user_attachment_transcription` | String | Echo of image text (→ Firestore) |
| `visual_analysis` | String | How image connects to answer |
| `follow_up` | String | Student engagement question |

### Tier Selection Logic
```
User Plan → Model Tier → LLM Provider:
├── free        → cheaper  → LiteLLM (GPT-3.5 or Claude Haiku)
├── premium     → cheaper  → Gemini 1.5 Flash or Claude 3.5 Haiku
└── pro         → power    → Gemini 2.0 or Claude 3.5 Sonnet
```

---

## PROMPT 3: Chat Mode Variants

### 3A: Normal Mode
```
Focus: Explain the concept clearly with examples.
Help the student understand why this matters.
```

### 3B: Blackboard Mode
```
You're teaching on a blackboard. Use ASCII diagrams where helpful.
Create step-by-step visual explanations.
Break down complex concepts into parts.
```

### 3C: Quiz Mode
```
You're creating a quiz for the student.
First ask a question to test understanding.
After they answer, provide feedback and explain the correct answer.
```

### 3D: Practice Mode
```
Help the student solve similar problems.
Provide hints first, then full solutions if needed.
```

---

## FLOW: Image Transcription Extraction

### Priority Order
The system extracts the image transcription using this priority:

```python
Priority 1: LLM JSON "user_attachment_transcription"
↓ (if empty/missing)
Priority 2: result["page_transcript"] (vision API result)
↓ (if empty/missing)
Priority 3: result["transcript"] (alternate key)
↓ (if empty/missing)
Priority 4: image_data["transcript"] (pre-analyzed client data)
↓ (if all missing)
Priority 5: None (no transcription available)
```

### Transcription Frame Response
The server sends back an SSE frame with transcription:

```json
{
  "type": "attachment_transcription",
  "data": {
    "messageId": "msg_12345",
    "transcription": "Full text extracted from the image/PDF page",
    "imageUrl": "https://storage.url/image.jpg"
  }
}
```

The Android client receives this and stores it in Firestore.

---

## FIRESTORE STORAGE SCHEMA

### Document Path
```
conversations/{userId}__{subject}__{chapter}/messages/{messageId}
```

### Document Fields
```json
{
  "messageId": "msg_12345",
  "role": "user",
  "text": "User's question/message",
  "timestamp": 1704067200000,
  "tokens": 2150,
  "inputTokens": 1500,
  "outputTokens": 650,
  "imageUrl": "gs://storage.url/image.jpg",
  "transcription": "Full text from analyze-image: 'The cell membrane...'",
  "extraSummary": "Additional details from LLM JSON response"
}
```

### Transcription Usage
**For future requests, transcription is included in history:**

```
CONVERSATION HISTORY: > User Q1 (with PDF page): 
> Your transcription: "Photosynthesis is the process..."
> AI Reply: "Looking at your page..."

> User Q2: "What about the light reactions?"
> [System includes Q1 transcription in context for continuity]
```

---

## IMAGE PROCESSING FLOW DETAILS

### Android-Side Processing
1. **Capture/Select** → `pickImageLauncher` or `cameraLauncher`
2. **Crop** → `UCrop` library with user-defined region
3. **Encode** → `MediaManager.uriToBase64()`
   - Two-pass bitmap decoding (prevent OOM)
   - Quality reduction loop (fit 500KB limit)
   - NO_WRAP base64 (no newlines)
4. **Store** → `pdfPageBase64` memory variable
5. **Send** → `POST /chat-stream` with `image_base64`

### PDF Handling
- **Page Caching** → [PdfPageManager.kt](app/src/main/java/com/aiguruapp/student/utils/PdfPageManager.kt)
- **Render to JPEG** → `PdfRenderer.Page.render()` with quality 85
- **Cache Location** → `cacheDir/pdf_pages/{pdfId}/page_N.jpg`
- **On OOM** → Fallback to 50% resolution

### Server-Side Vision API
- **Providers:** Google Gemini, AWS Bedrock (Claude)
- **Image Format:** `data:image/jpeg;base64,{data}`
- **Validation:** MIME type check, non-empty base64
- **Fallback:** If tier fails, try next tier (power → cheaper)

---

## KEY CONFIGURATION

### Cache Settings
| Setting | Value | Purpose |
|---------|-------|---------|
| Redis Cache Duration | 7 days | How long to keep image analysis |
| Cache Key Collision | SHA-256(len+head+tail) | Identify duplicate images |
| Image Size Limit | 500 KB | Android upload constraint |
| Max Bitmap Dimension | 1920 px | Memory safety |

### LLM Tier Models
```yaml
power:
  - gemini: "gemini-2.0-flash-exp" (vision capable)
  - bedrock: "anthropic.claude-3-5-sonnet" (vision capable)

cheaper:
  - gemini: "gemini-1.5-flash" (vision capable)
  - bedrock: "anthropic.claude-3-5-haiku" (vision capable)
```

### Supported Image Formats
- JPEG ✓
- PNG ✓
- GIF ✓
- WebP ✓
- PDF (rendered to JPEG) ✓

---

## TROUBLESHOOTING CHECKLIST

| Issue | Check |
|-------|-------|
| Transcription not saved | Is `user_attachment_transcription` in LLM JSON? |
| Image not processed | Check Redis cache key matches image |
| OOM errors | Verify two-pass decoding in MediaManager |
| Blank transcript | Confirm LLM returned valid JSON |
| PDF page not rendering | Check PdfPageManager cache location writable |
| LLM call fails | Verify API keys (Gemini/Bedrock) are set |
| Tier fallback not working | Check model tier configurations in settings |

---

## COMPLETE REQUEST/RESPONSE CYCLE

### Client → Server: Image Upload
```json
POST /chat-stream
{
  "question": "What does this page teach about?",
  "page_id": "bio_ch3_cells",
  "student_level": 9,
  "history": ["Q: What is the cell?", "A: The cell is..."],
  "image_base64": "iVBORw0KGgoAAAANSUhEUgAAAA...==",
  "user_plan": "premium"
}
```

### Server Analysis Phase
```
1. Normalize image → data:image/jpeg;base64,...
2. Check if pre-analyzed → No
3. POST /analyze-image
4. Cache check → No cache hit
5. Build vision prompt + context
6. Call Gemini/Bedrock → Get JSON with transcript
7. Cache result for 7 days
```

### Server Chat Phase
```
8. Extract page_transcript from vision analysis
9. Build chat prompt: question + image context + history
10. Select tier (premium → cheaper)
11. Call LLM: Gemini 1.5 Flash
12. LLM returns JSON with user_attachment_transcription
13. Stream response as SSE frames
14. Final frame: attachment_transcription
```

### Client Storage Phase
```
15. Android receives transcription frame
16. Parse transcription string
17. Create Message object with:
    - content: LLM response text
    - transcription: Extracted image text
    - imageUrl: Reference to uploaded image
18. Call ChatHistoryRepository.saveMessage()
19. Message stored in Firestore with transcription
```

---

## Document References

- **Deep Implementation Guide:** [ATTACHMENT_AND_IMAGE_HANDLING_COMPREHENSIVE_GUIDE.md](ATTACHMENT_AND_IMAGE_HANDLING_COMPREHENSIVE_GUIDE.md)
- **Android Image Capture:** [FullChatFragment.kt](app/src/main/java/com/aiguruapp/student/FullChatFragment.kt)
- **Image Encoding:** [MediaManager.kt](app/src/main/java/com/aiguruapp/student/utils/MediaManager.kt)
- **PDF Handling:** [PdfPageManager.kt](app/src/main/java/com/aiguruapp/student/utils/PdfPageManager.kt)
- **Vision API Endpoint:** [analyze_image.py](server/app/api/analyze_image.py)
- **Chat Endpoint:** [chat.py](server/app/api/chat.py)
- **LLM Service:** [llm_service.py](server/app/services/llm_service.py)
- **Prompt Templates:** [prompt_service.py](server/app/services/prompt_service.py)
- **Firestore Schema:** [Message.kt](app/src/main/java/com/aiguruapp/student/models/Message.kt)
- **Chat History:** [ChatHistoryRepository.kt](app/src/main/java/com/aiguruapp/student/chat/ChatHistoryRepository.kt)
