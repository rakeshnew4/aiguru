# AIGuru Codebase Structure & Key Components

A comprehensive exploration of the AIGuru Android/Python codebase focusing on chat UI, PDF handling, custom subjects, TTS functionality, and BB (Blackboard) mode.

---

## 1. CHAT UI & BUTTON MANAGEMENT

### Primary Android Files

**[FullChatFragment.kt](app/src/main/java/com/aiguruapp/student/FullChatFragment.kt)** — The primary chat interface
- **Purpose**: Embedded chat tab inside ChapterActivity with full chat functionality
- **Key UI Views & Buttons**:
  - `sendButton` (MaterialButton) - Send message
  - `voiceButton` (MaterialButton) - Voice input toggle, shows listening state with animation
  - `imageButton` (MaterialButton) - Image picker (camera or gallery)
  - `saveNotesButton` (MaterialButton) - Save AI response as notes
  - `viewNotesButton` (MaterialButton) - View saved notes
  - `formulaButton` (MaterialButton) - Quick action for formulas
  - `practiceButton` (MaterialButton) - Quick action for practice problems
  - `autoExplainButton` (MaterialButton) - Toggle Blackboard/Auto-explain mode
  - `voiceChatButton` (MaterialButton) - Activate interactive voice mode
  - `plusButton` (MaterialButton) - Toggle quick actions panel
  - `bottomDescribeButton` (MaterialButton) - Describe image quick action
  - `clearChatButton` (MaterialButton) - Clear all messages
  - `removeImageButton` (MaterialButton) - Remove selected image

- **Key Methods**:
  - `initializeUI(view)` - Button initialization and click listeners
  - `setupButtons(view)` - Configure button callbacks (lines 668-765)
  - `setupQuickActions(view)` - Configure quick action buttons
  - `sendMessage(userText, autoSaveNotes)` - Send chat message with optional attachments
  - `proceedWithSendMessage()` - Handle message sending with quota checks
  - `sendAutoPrompt()` - Send automatic prompts from chapter activities

- **Message Display**: Uses `RecyclerView` with [MessageAdapter.kt](app/src/main/java/com/aiguruapp/student/adapters/MessageAdapter.kt) (lines 39-486)

### [MessageAdapter.kt](app/src/main/java/com/aiguruapp/student/adapters/MessageAdapter.kt) — Chat Message Display & Actions

**Button Callbacks on Messages**:
```kotlin
- 🔊 (Speak) → onVoiceClick()
  - Triggers TextToSpeechManager with message content
  - Supports multiple languages via ttsManager.setLocale()

- ■ (Stop) → onStopClick()
  - Calls ttsManager.stop()

- ⧉ (Copy) → Copy to clipboard

- 📌 (Save Note) → onSaveNoteClick()
  - Triggers showCategoryPickerAndSaveNote() in FullChatFragment

- BB (Explain/Blackboard) → onExplainClick()
  - Starts BlackboardActivity with message content
  - Checks plan limits before allowing (PlanEnforcer.FeatureType.BLACKBOARD)
  - Passes: EXTRA_MESSAGE, EXTRA_MESSAGE_ID, EXTRA_USER_ID, EXTRA_CONVERSATION_ID
```

**Message Types**:
- Text messages with Markdown + LaTeX rendering (via Markwon)
- Image messages (gallery, camera, or PDF pages)
- Messages with inline actions (speak, save, explain)

---

## 2. CHAPTER/PDF HANDLING & DOWNLOADS

### Android PDF/Chapter Management

**[ChapterActivity.kt](app/src/main/java/com/aiguruapp/student/ChapterActivity.kt)** — Tab-based chapter viewer
- **Purpose**: Manages chapter selection, PDF loading, and NCERT downloads
- **Features**:
  - Tabs for: Chat, Pages, Notes
  - NCERT PDF download with browser-like User-Agent
  - PDF page rendering and caching
  - Swipe gestures to switch tabs
  - Download overlay with progress tracking

- **Key Components**:
  - `pdfPageManager: PdfPageManager` - Handles PDF rendering and caching
  - `ncertHttpClient` - OkHttp client with TLS 1.2/1.3 fallback for NCERT downloads
  - PDF chapter detection: checks `isPdf` flag from Firestore metadata

**[PageViewerActivity.kt](app/src/main/java/com/aiguruapp/student/PageViewerActivity.kt)** — Full-screen PDF page viewer
- **Purpose**: Browse PDF pages, navigate prev/next, ask AI about pages
- **Functionality**:
  - Receives: `subjectName`, `chapterName`, `pdfId`, `pdfAssetPath`, `pageCount`, `startPage`
  - Page navigation with Prev/Next buttons
  - "Ask AI" button to attach current page to chat
  - `PdfPreloadManager` - Preloads next 5 pages in background
  - Returns result back to ChatFragment with page content

- **Key Methods**:
  - `loadPage()` - Render current page via `pdfPageManager.getPage()`
  - `openChatForCurrentPage()` - Attach page to chat and switch tabs

**[NcertViewerActivity.kt](app/src/main/java/com/aiguruapp/student/NcertViewerActivity.kt)** — NCERT PDF viewer
- **Purpose**: Render NCERT PDFs using Google Docs Viewer
- **Approach**: 
  - Uses `https://docs.google.com/gview?embedded=true&url=<PDF_URL>`
  - No native PDF plugin required
  - JavaScript disabled for security
  
- **Buttons**:
  - Back button
  - "Open in Browser" button - Opens PDF in external reader

**[SubjectActivity.kt](app/src/main/java/com/aiguruapp/student/SubjectActivity.kt)** — Chapter list for subject
- **Purpose**: Display all chapters in a subject with NCERT URLs
- **Features**:
  - `ncertUrlMap` - Maps chapter order to direct NCERT PDF URLs from Firestore
  - Long-press to delete custom chapters
  - "Add Chapter" button for custom chapters
  - Swipe-to-refresh chapter list

### PDF Page Management

**Utils: [PdfPageManager](app/src/main/java/com/aiguruapp/student/utils/PdfPageManager.kt)**
- `getPageCount(pdfId, assetPath)` - Get total pages in PDF
- `getPage(pdfId, assetPath, pageIndex)` - Render page to file (JPEG)
- Caches pages in app cache directory

**Utils: [PdfPreloadManager](app/src/main/java/com/aiguruapp/student/utils/PdfPreloadManager.kt)**
- Preloads 5+ pages ahead in background
- Reduces latency when user navigates

### Backend PDF/Chapter API

**[library.py](server/app/api/library.py)** — Library endpoints
```
GET /library/subjects
  → List all available subjects

GET /library/chapters?subject_id=<id>
  → Get chapters for a subject (ordered by index)

POST /library/select-chapters
  → Save user's selected chapter IDs to Firestore

GET /library/selected-chapters?user_id=<id>
  → Retrieve previously saved chapter selection

GET /library/progress?user_id=<id>
  → Get per-chapter progress for a user
```

---

## 3. CUSTOM SUBJECT & PDF UPLOAD FEATURES

### Android Implementation

**[SubjectActivity.kt](app/src/main/java/com/aiguruapp/student/SubjectActivity.kt)** — Custom chapter management
- **Features**:
  - `showManualChapterDialog()` - Dialog to create custom chapter
  - Long-press chapter to delete
  - Edit chapter name (in-app dialog)
  - Supported actions: view NCERT, start chat

**[FullChatFragment.kt](app/src/main/java/com/aiguruapp/student/FullChatFragment.kt)** — Image/PDF attachment to custom chapters
- **Image Workspace (lines 874-960)**:
  - Drag images into `FullChatFragment` from gallery or camera
  - `saveImagePageToChapter(uri)` - Save image to chapter's page list
  - Stored in SharedPreferences: key = `"imgpages_${subjectName}_${chapterName}"`
  - Format: JSON array of `{path, timestamp}` objects

- **PDF Workspace (lines 836-872)**:
  - For PDF chapters, show "Add Extra Image" button
  - Load PDF page count and render pages on demand
  - Attach individual pages to chat

- **Image Picker Flow**:
  1. User taps "Image" button or "Create Page" quick action
  2. Dialog: "Take Photo" or "Choose from Gallery"
  3. Crop image via UCrop library (lines 1107-1165)
  4. If `saveNextPickedImageToChapter = true`, save to chapter
  5. Otherwise, attach to current message

- **Image to Chat Workflow (lines 1183-1210)**:
  ```kotlin
  showImagePreview(uri) → 
    - Display thumbnail in message input area
    - Eagerly encode to Base64 in background
    - User taps Send → encoded image attached to chat
  ```

### Firestore Storage

**Chapter Metadata Storage** (`chapters_prefs` SharedPreferences):
```json
{
  "meta_${subjectName}_${chapterName}": {
    "isPdf": true/false,
    "pdfAssetPath": "library/9/math/chapter_1.pdf",
    "pdfId": "9_math_chapter_1"
  },
  "imgpages_${subjectName}_${chapterName}": [
    { "path": "uri://...", "timestamp": "15 Apr 2026 10:30" },
    { "path": "uri://...", "timestamp": "15 Apr 2026 10:45" }
  ]
}
```

### Upload Handling in Chat

**[FullChatFragment.kt](app/src/main/java/com/aiguruapp/student/FullChatFragment.kt)** — Message sending with attachments (lines 1283-1600)
- **Image Upload**:
  - User picks image → encoded to Base64 (JPEG, 85% quality)
  - Sent to server via `ServerProxyClient.streamChat()` with `image_base64` parameter
  - Server detects image, analyzes it, returns transcription

- **PDF Upload**:
  - User crops PDF page → encoded to Base64 (JPEG, 85% quality)
  - Sent to server with `image_base64` + `page_id`
  - Server performs OCR and analysis

- **Message Model** ([Message.kt](app/src/main/java/com/aiguruapp/student/models/Message.kt)):
  ```kotlin
  data class Message(
    val id: String,
    val content: String,           // Full JSON from server (with transcription)
    val isUser: Boolean,
    val timestamp: Long,
    val imageUrl: String?,         // Display URL
    val imageBase64: String?,      // Encoded image
    val messageType: MessageType,  // TEXT, IMAGE, VOICE, PDF, MIXED
    val transcription: String = "", // OCR/analysis from server
    val extraSummary: String = ""   // Extra context from server
  )
  ```

### Backend Image Processing

**[chat.py](server/app/api/chat.py)** — Chat endpoint with image support
```python
class ChatRequest(BaseModel):
    question: str
    page_id: str
    student_level: int
    history: List[str]
    
    # Image handling
    image_base64: Optional[str] = None
    image_data: Optional[Dict[str, Any]] = None
    
    mode: str = "normal"  # normal, bb, quiz
    language: str = "en-US"
    user_plan: str = "premium"
```

**Image Transcription** (server-side):
1. Receive Base64 image
2. Optional: Extract text via OCR (Google Vision API)
3. Build prompt with image analysis
4. Return transcription in response

---

## 4. TEXT-TO-SPEECH (TTS) FUNCTIONALITY

### Android TTS Implementation

**[TextToSpeechManager.kt](app/src/main/java/com/aiguruapp/student/utils/TextToSpeechManager.kt)** — Main TTS interface
- **Supported Backends**:
  - Local Android TTS (TextToSpeech API)
  - Google Cloud TTS (neural voices, cost-efficient)
  - Gemini 2.5 Flash TTS (premium, natural voice)
  - ElevenLabs TTS (fallback)
  - OpenAI TTS (fallback)

- **Methods**:
  - `speak(text, callback)` - Synthesize and play audio
  - `stop()` - Stop current playback
  - `setLocale(locale)` - Set language (e.g., Locale.forLanguageTag("hi-IN"))
  - `isSpeaking()` - Check if currently playing

**[AiTtsProvider.kt](app/src/main/java/com/aiguruapp/student/tts/AiTtsProvider.kt)** — AI TTS engine wrapper
- **Provider Functions**:
  - `googleTts()` - Call Google Cloud TTS API with API key
  - `buildGoogleVoice()` - Select best neural voice per language

- **Voice Selection Map**:
  ```kotlin
  "hi" → "hi-IN-Neural2-A"
  "en-in" → "en-IN-Neural2-A"
  "en" → "en-US-Neural2-F"
  "ta" → "ta-IN-Neural2-A"
  "te" → "te-IN-Standard-A"
  "kn" → "kn-IN-Wavenet-A"
  "ml" → "ml-IN-Wavenet-A"
  "mr" → "mr-IN-Wavenet-A"
  "bn" → "bn-IN-Wavenet-A"
  "gu" → "gu-IN-Wavenet-A"
  "pa" → "pa-Guru-IN-Wavenet-A"
  ```

**[BbAiTtsEngine.kt](app/src/main/java/com/aiguruapp/student/tts/BbAiTtsEngine.kt)** — Blackboard TTS engine
- Specialized for BB mode with caching and provider selection
- Supports switching between Google, Gemini, and Android TTS per frame

### Voice Selection in Chat

**[FullChatFragment.kt](app/src/main/java/com/aiguruapp/student/FullChatFragment.kt)** — Voice integration (lines 568-645)
- Language support: English, Hindi, Bengali, Telugu, Tamil, Marathi, Kannada, Gujarati
- Saved via SharedPreferences: `SessionManager.getPreferredLang(context)`
- Resume language when re-opening chat after home activity

- **Voice Input Callbacks**:
  - `onResults(text)` - Voice recognized, send as message
  - `onPartialResults(text)` - Show live transcription while speaking
  - `onError(error)` - Handle recognition errors

- **Voice Mode (Interactive Voice Chat)**:
  - `startVoiceMode()` - Continuous listening with TTS responses
  - `isVoiceLoopListening()` - Detect stop words to interrupt AI speech
  - Recognizes: "stop", "stop it", "wait", "wait wait", "hold on"
  - Wave animation shows listening state

### Backend TTS Service

**[tts.py](server/app/api/tts.py)** — Server-side TTS endpoint

**Request** (`POST /api/tts/synthesize`):
```python
class TtsSynthesizeRequest(BaseModel):
    text: str
    language_code: str = "en-US"    # BCP-47 format
    voice_name: str = ""            # Optional specific voice
    speaking_rate: float = 1.0      # 0.25 – 4.0
    tts_engine: str = ""            # android | gemini | google | "" (legacy)
    voice_role: str = "teacher"     # teacher | assistant | quiz | feedback
```

**TTS Engine Selection**:
- `android` → Return 204 No Content (client uses native TTS)
- `gemini` → Gemini 2.5 Flash TTS (premium, natural voice)
- `google` → Google Cloud TTS (neural, cost-efficient)
- `""` (legacy) → Fallback chain: Google → ElevenLabs → OpenAI → 503 error

**Response**: Raw MP3 bytes (`Content-Type: audio/mpeg`) or 204 No Content

**Google Cloud TTS Provider** (lines 72-119):
```python
async def _google_tts(text, language_code, voice_name, speaking_rate):
    """
    Uses OAuth2 Service Account (GOOGLE_APPLICATION_CREDENTIALS env var)
    Supports all Indian languages with Neural2 voices
    """
    client = texttospeech.TextToSpeechClient()
    voice = texttospeech.VoiceSelectionParams(
        language_code=language_code,
        name=voice_name,
        ssml_gender=NEUTRAL
    )
    audio_config = texttospeech.AudioConfig(
        audio_encoding=MP3,
        speaking_rate=speaking_rate
    )
    response = client.synthesize_speech(synthesis_input, voice, audio_config)
    return response.audio_content  # MP3 bytes
```

**Voice Pricing Model**:
- Gemini TTS: Premium/Pro plans → Best quality (used for concept/memory frames)
- Google TTS: All plans → Cost-efficient (used for summary/assistant frames)
- Android TTS: Free tier → Instant (used for quiz frames, first frame of lesson)

---

## 5. BLACKBOARD (BB) MODE IMPLEMENTATION

### Android Blackboard Activity

**[BlackboardActivity.kt](app/src/main/java/com/aiguruapp/student/BlackboardActivity.kt)** — Full-screen interactive lesson
- **Purpose**: Step-by-step visual explanation with audio
- **Extras Received**:
  - `EXTRA_MESSAGE` - Main answer text
  - `EXTRA_MESSAGE_ID` - Message ID for caching
  - `EXTRA_USER_ID` - User ID for tracking
  - `EXTRA_CONVERSATION_ID` - Conversation context
  - `EXTRA_LANGUAGE_TAG` - Preferred language (e.g., "hi-IN")
  - `EXTRA_SUBJECT` - Subject name
  - `EXTRA_CHAPTER` - Chapter name

- **UI Navigation**:
  - Scrollable view with all steps fading in sequentially
  - Step counter and navigation dots
  - Controls: ◀ Prev, ▶ Next, ↺ Replay, ⏸/▶ Pause, ✕ Close

- **TTS Integration**:
  - `TextToSpeechManager` - Local TTS with language support
  - `BbAiTtsEngine` - AI-powered TTS with caching
  - Toggle: "AI Voice" button to switch between Google/Gemini/Android

**Generated Lesson Structure** (from [BlackboardGenerator.kt](app/src/main/java/com/aiguruapp/student/chat/BlackboardGenerator.kt)):

```kotlin
data class BlackboardStep(
    val title: String,
    val frames: List<BlackboardFrame>,
    val languageTag: String = "en-US"
)

data class BlackboardFrame(
    val text: String,
    val speech: String,              // Text to read aloud
    val durationMs: Long = 2000,     // Display duration
    
    // Frame type determines layout and interaction
    val frameType: String,           // concept | quiz | memory | summary | quiz_mcq | quiz_typed | quiz_voice | quiz_fill | quiz_order
    
    // Voice engine assignment
    val ttsEngine: String,           // android | gemini | google
    val voiceRole: String,           // teacher | assistant | quiz | feedback
    
    // Quiz fields
    val quizOptions: List<String>,   // MCQ options or fill-in blanks
    val quizCorrectIndex: Int,       // Correct MCQ option (0-3)
    val quizModelAnswer: String,     // Reference answer for grading
    val quizKeywords: List<String>,  // Keywords student must mention
    val quizCorrectOrder: List<Int>, // For ordering/sequencing questions
    
    // Media
    val highlight: List<String> = emptyList()
)
```

### Blackboard Generation

**[BlackboardGenerator.kt](app/src/main/java/com/aiguruapp/student/chat/BlackboardGenerator.kt)** — Generate teaching steps

**Generation Process**:
1. Receives message content and lesson context
2. Calls LLM with system prompt from `assets/tutor_prompts.json`
3. LLM returns structured JSON with steps/frames
4. Caches result in Firestore: `users/{userId}/conversations/{conversationId}/blackboard_cache/{messageId}`

**TTS Engine Assignment** (lines 38-52):
```kotlin
fun smartAssignTts(frameType: String): Pair<String, String> = when {
    frameType == "concept"      → ("gemini",  "teacher")    // Premium voice
    frameType == "memory"       → ("gemini",  "teacher")    // Premium voice
    frameType == "summary"      → ("google",  "assistant")  // Neural, efficient
    frameType.startsWith("quiz") → ("android", "quiz")      // Instant
    else                        → ("android", "teacher")    // Safe default
}
```

**Important**: First frame is always overridden to `android`/`teacher` in BlackboardActivity for zero-latency playback.

### BB Mode in Chat

**[FullChatFragment.kt](app/src/main/java/com/aiguruapp/student/FullChatFragment.kt)** — BB mode integration

**"Explain" Button** (lines 560-571):
```kotlin
val bbLimits = AdminConfigRepository.resolveEffectiveLimits(...)
val bbCheck = PlanEnforcer.check(cachedMetadata, bbLimits, BLACKBOARD)
if (!bbCheck.allowed) {
    showError(bbCheck.upgradeMessage)
} else {
    startActivity(Intent(requireContext(), BlackboardActivity::class.java)
        .putExtra(BlackboardActivity.EXTRA_MESSAGE, extractAnswerForDisplay(msg.content))
        .putExtra(BlackboardActivity.EXTRA_MESSAGE_ID, msg.id)
        .putExtra(BlackboardActivity.EXTRA_USER_ID, userId)
        .putExtra(BlackboardActivity.EXTRA_CONVERSATION_ID, convId)
        .putExtra(BlackboardActivity.EXTRA_LANGUAGE_TAG, currentLang)
        .putExtra(BlackboardActivity.EXTRA_SUBJECT, subjectName)
        .putExtra(BlackboardActivity.EXTRA_CHAPTER, chapterName)
    )
}
```

**Blackboard Auto-Explain Mode** (lines 669-680):
- Toggle button in chat UI
- Default: ON (stored in SharedPreferences)
- When ON: Chat responses trigger BB nudge (visual prompt to tap "Explain")
- Feature limited by subscription plan

**Blackboard Nudge** (lines 1622-1642):
- Quietly highlights "Explain" button after response
- Single blue flash (no animation pop)
- Encourages user to try BB mode without being intrusive
- Disabled if user's plan doesn't support BB

### Backend Blackboard Services

**[bb.py](server/app/api/bb.py)** — Blackboard grading endpoint

**Endpoint**: `POST /bb/grade`

**Request**:
```python
class GradeRequest(BaseModel):
    question: str
    student_answer: str
    model_answer: str
    keywords: List[str] = []
```

**Response**:
```python
class GradeResponse(BaseModel):
    correct: bool
    score: int        # 0–100
    feedback: str     # 1-sentence encouragement/correction
```

**Grading Logic**:
1. Uses LLM to evaluate student's open-ended answer
2. Checks if main idea captured (≥70% accuracy = correct)
3. Returns score 0–100 and warm, specific feedback
4. Fallback: Keyword matching if LLM fails

### Backend Prompt Service

**[prompt_service.py](server/app/services/prompt_service.py)** — Prompt templates

**Key Prompts**:
- `INTENT_CLASSIFIER_PROMPT` - Classify user intent (explain, practice, quiz, etc.)
- `BB_PLANNER_PROMPT` - Plan lesson steps for BB generation
- `BB_MAIN_PROMPT` - Generate detailed teaching frames
- Intent-specific builders: `_greet_prompt()`, `_explain_prompt()`, etc.

---

## 6. KEY BACKEND SERVICES

### Chat Processing

**[chat.py](server/app/api/chat.py)** — Main chat endpoint
- Receives: question, page_id, images, history, language, user_plan, student_level
- Returns: Streaming response with answer + transcription + suggestions
- Supports: Text, images, conversation history
- Language: Any BCP-47 tag (en-US, hi-IN, ta-IN, etc.)

### Authentication & Config

**[config.py](server/app/core/config.py)** — Global settings
- API keys for Google Cloud TTS, ElevenLabs, OpenAI, Gemini
- Server URL for proxy clients
- Database connection strings

**[auth.py](server/app/core/auth.py)** — Firebase Auth verification
- Validates Firebase ID tokens
- Extracts user ID and scopes
- Rate limiting and quota tracking

### LLM Service

**[llm_service.py](server/app/services/llm_service.py)** — LLM interaction layer
- Supports: Gemini, OpenAI, Claude (via LiteLLM proxy)
- Streaming responses for low-latency chat
- Token usage tracking for billing

### Context & Evaluation

**[context_service.py](server/app/services/context_service.py)** — Extract chapter/page context
- Retrieves relevant text from Firestore documents
- Builds system prompts with subject-specific context

**[evaluation_service.py](server/app/services/evaluation_service.py)** — Quiz/BB answer grading
- Compare student answers against model answers
- LLM-based semantic matching
- Confidence scoring

---

## 7. KEY DATA MODELS

### Client Models

**Message** ([models/Message.kt](app/src/main/java/com/aiguruapp/student/models/Message.kt))
- Represents single chat message (user or AI)
- Stores: content, timestamp, image, transcription, extraSummary

**Flashcard** ([models/Flashcard.kt](app/src/main/java/com/aiguruapp/student/models/Flashcard.kt))
- Q&A pair for revision
- Used in RevisionActivity

**TutorSession** ([models/TutorSession.kt](app/src/main/java/com/aiguruapp/student/models/TutorSession.kt))
- Tracks: subject, chapter, mode, page number, conversation context
- Updated during chat to maintain lesson state

### Server Models

**Library Models** ([models/library.py](server/app/models/library.py))
- Subject, Chapter, Progress tracking
- Language: English, Hindi, Bengali, Telugu, Tamil, Marathi, Kannada, Gujarati

**Response Models** ([models/response.py](server/app/models/response.py))
- Structured chat responses with answer + intent + metadata
- JSON format with answer, transcription, suggestions, etc.

---

## 8. QUICK FILE REFERENCE TABLE

| Feature | Android File | Backend File |
|---------|--------------|--------------|
| **Chat UI** | [FullChatFragment.kt](app/src/main/java/com/aiguruapp/student/FullChatFragment.kt), [MessageAdapter.kt](app/src/main/java/com/aiguruapp/student/adapters/MessageAdapter.kt) | [chat.py](server/app/api/chat.py) |
| **PDF/Chapter** | [ChapterActivity.kt](app/src/main/java/com/aiguruapp/student/ChapterActivity.kt), [PageViewerActivity.kt](app/src/main/java/com/aiguruapp/student/PageViewerActivity.kt), [SubjectActivity.kt](app/src/main/java/com/aiguruapp/student/SubjectActivity.kt) | [library.py](server/app/api/library.py), [library_service.py](server/app/services/library_service.py) |
| **Custom Upload** | [FullChatFragment.kt](app/src/main/java/com/aiguruapp/student/FullChatFragment.kt) (lines 836-960) | [chat.py](server/app/api/chat.py) (image_base64 param) |
| **TTS** | [TextToSpeechManager.kt](app/src/main/java/com/aiguruapp/student/utils/TextToSpeechManager.kt), [AiTtsProvider.kt](app/src/main/java/com/aiguruapp/student/tts/AiTtsProvider.kt) | [tts.py](server/app/api/tts.py) |
| **Blackboard** | [BlackboardActivity.kt](app/src/main/java/com/aiguruapp/student/BlackboardActivity.kt), [BlackboardGenerator.kt](app/src/main/java/com/aiguruapp/student/chat/BlackboardGenerator.kt) | [bb.py](server/app/api/bb.py), [prompt_service.py](server/app/services/prompt_service.py) |
| **Voice Input** | [VoiceManager.kt](app/src/main/java/com/aiguruapp/student/utils/VoiceManager.kt) | N/A (client-side only) |
| **Configuration** | [AdminConfigRepository.kt](app/src/main/java/com/aiguruapp/student/config/AdminConfigRepository.kt), [PlanEnforcer.kt](app/src/main/java/com/aiguruapp/student/config/PlanEnforcer.kt) | [config.py](server/app/core/config.py) |

---

## 9. CONVERSATION FLOW EXAMPLES

### Example 1: User Sends Chat with Image

```
1. User taps 📷 in FullChatFragment
2. ImagePicker launched → user selects image from gallery
3. Crop dialog opens (UCrop)
4. FullChatFragment.showImagePreview(uri) called
5. Image encoded to Base64 in background (mediaManager.uriToBase64)
6. User taps Send → sendMessage(text) invoked
7. ServerProxyClient.streamChat() sends:
   - userText: "Explain this"
   - image_base64: base64-encoded JPEG
   - language: "en-US"
   - pageId: "math_9_chapter_2"
8. Server (chat.py endpoint):
   - Analyzes image (Google Vision API optional)
   - Builds context with image transcription
   - Calls LLM to generate answer
   - Streams response back with transcription field
9. FullChatFragment receives response:
   - Adds Message to adapter with imageUrl
   - Shows thumbnail in chat
   - Stores transcription for next turn's context
10. User taps 🔊 button on message → speak answer
11. TextToSpeechManager calls /api/tts/synthesize with language
12. Google TTS returns MP3 → played via MediaPlayer
```

### Example 2: User Opens Blackboard Mode

```
1. AI responds to chat question
2. If isAutoExplainActive = true, showBlackboardNudge() called
3. User taps "BB" button on message
4. Plan check: PlanEnforcer.check(BLACKBOARD feature)
5. If allowed:
   - BlackboardActivity launched with EXTRA_MESSAGE, EXTRA_MESSAGE_ID, userId
   - BlackboardGenerator.generate() called (async)
   - Server LLM called to create teaching steps
   - Firestore cache document created
   - Steps loaded into BlackboardActivity
6. Each step fades in sequentially:
   - Concept frame → Gemini TTS (natural voice)
   - Quiz frame → Android TTS (instant)
   - User taps Next → reveal next frame
7. User can:
   - Tap 🔊 to re-speak frame
   - Answer quiz question
   - Tap Replay to re-listen
8. On quiz answer:
   - /bb/grade endpoint called
   - LLM compares student vs model answer
   - Feedback returned and displayed
```

### Example 3: User Adds Custom Image Page to Chapter

```
1. User in ChapterActivity > Chat tab
2. Taps + (Plus) button → Quick Actions panel opens
3. Taps "Create Page" button
4. saveNextPickedImageToChapter = true
5. Image source dialog: Camera or Gallery
6. User takes photo or picks image
7. Crop dialog (UCrop)
8. saveImagePageToChapter(uri) called:
   - Image URI appended to SharedPreferences
   - Key: "imgpages_{subjectName}_{chapterName}"
   - Value: JSON array with {path, timestamp}
9. Chapter Workspace Drawer updated with new page
10. User can now:
    - Swipe to Pages tab → See new page in list
    - Tap page → Preview in chat
    - Long-press → Crop and save as note
11. Next time user opens chapter:
    - loadImageWorkspacePages() rebuilds list from SharedPreferences
    - Pages persist across sessions
```

---

## 10. CONFIGURATION & SETTINGS

### Shared Preferences Keys

```
user_prefs:
  - blackboard_mode_on: boolean (default: true)
  - preferred_lang: string (e.g., "hi-IN")

chapters_prefs:
  - meta_{subjectName}_{chapterName}: JSON metadata
  - imgpages_{subjectName}_{chapterName}: JSON array of image pages
```

### Environment Variables (Backend)

```
GOOGLE_APPLICATION_CREDENTIALS=/path/to/service-account.json
GOOGLE_TTS_API_KEY=<API key for Google TTS>
OPENAI_API_KEY=<OpenAI API key>
ELEVENLABS_API_KEY=<ElevenLabs API key>
GEMINI_API_KEY=<Google Gemini API key>
FIREBASE_CREDENTIALS=/path/to/firebase-service-account.json
```

### Plan-Based Feature Limits

- **Free**: Basic text chat, limited questions/day
- **Premium**: Text + images, custom subjects, audio explanations, some BB
- **Pro**: All features, unlimited BB, highest TTS quality (Gemini)

---

## 11. DATA FLOW DIAGRAMS

### Chat Message Processing
```
User Input
    ↓
[FullChatFragment.sendMessage]
    ↓
Encode image (if present) → Base64
    ↓
[ServerProxyClient.streamChat]
    ↓
Backend: chat.py endpoint
    ├→ Extract intent (intent_classifier)
    ├→ Build context (context_service)
    ├→ Call LLM (llm_service)
    ├→ Extract answer + transcription
    └→ Stream response back
    ↓
[FullChatFragment.onToken callback]
    ↓
Display message in [MessageAdapter]
    ↓
User can: Speak (TTS), Save (Notes), Explain (BB), Copy

```

### Blackboard Generation
```
User taps "Explain" on message
    ↓
Plan check (PlanEnforcer)
    ↓
[BlackboardActivity.onCreate]
    ↓
[BlackboardGenerator.generate]
    ↓
Check Firestore cache
    ├→ If cached: Load steps
    └→ If not cached:
        └→ Call LLM (bb_planner_prompt)
           → LLM generates teaching steps JSON
           → Save to Firestore cache
    ↓
Load steps into [BlackboardActivity]
    ↓
User navigates: Prev/Next/Replay
    ↓
Each frame: Display + Speak (via TTS)
    ↓
Quiz frames: Capture answer → /bb/grade
    ↓
Display feedback
```

### TTS Engine Selection
```
Message to speak
    ↓
Check language (en-US, hi-IN, etc)
    ↓
If interactive (BlackboardActivity):
    └→ smartAssignTts(frameType) determines:
       ├→ concept/memory → Gemini TTS (premium)
       ├→ summary → Google TTS (efficient)
       └→ quiz → Android TTS (instant)
    ↓
Call TextToSpeechManager.speak()
    ↓
POST /api/tts/synthesize
    ├→ tts_engine: android/gemini/google
    ├→ language_code: BCP-47
    └→ voice_role: teacher/assistant/quiz
    ↓
Server backend responds:
    ├→ android → 204 No Content (use device TTS)
    ├→ google → MP3 bytes (Google TTS)
    └→ gemini → MP3 bytes (Gemini TTS)
    ↓
MediaPlayer plays audio
```

---

**Last Updated**: April 15, 2026  
**Codebase Version**: Latest from main branch  
**Explored by**: AI Assistant

