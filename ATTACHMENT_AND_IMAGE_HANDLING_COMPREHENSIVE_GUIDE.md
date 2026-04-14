# Image/PDF Attachment Handling - Comprehensive Technical Guide

## Overview
This guide documents the complete flow of image/PDF attachment handling in aiGuru, from capture/upload through LLM processing to Firestore storage.

---

## 1. ANDROID IMAGE/PDF ATTACHMENT HANDLING

### 1.1 Image Capture & Selection

**File:** [app/src/main/java/com/aiguruapp/student/FullChatFragment.kt](app/src/main/java/com/aiguruapp/student/FullChatFragment.kt#L200)

#### Activity Result Launchers
```kotlin
// Image picker launcher
private val pickImageLauncher =
    registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) launchCrop(uri, isPdf = false)
        else saveNextPickedImageToChapter = false
    }

// Camera launcher
private val cameraLauncher =
    registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && cameraImageUri != null) launchCrop(cameraImageUri!!, isPdf = false)
        else saveNextPickedImageToChapter = false
    }

// Crop result handler
private val cropLauncher =
    registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        when (result.resultCode) {
            android.app.Activity.RESULT_OK -> {
                val croppedUri = UCrop.getOutput(result.data) ?: return@registerForActivityResult
                // Handle crop result...
            }
        }
    }

// PDF page viewer launcher
private val pageViewerLauncher =
    registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val filePath = result.data?.getStringExtra("pdfPageFilePath") ?: return@registerForActivityResult
            val pageNum = result.data?.getIntExtra("pdfPageNumber", 1) ?: 1
            tutorSession.currentPage = pageNum
            preloadPdfPage(File(filePath), pageNum)
        }
    }
```

### 1.2 Image Cropping & PDF Attachment
**Function:** `launchCrop()` [FullChatFragment.kt](app/src/main/java/com/aiguruapp/student/FullChatFragment.kt#L1066) 

When user crops an image or PDF page:
- **Purpose:** Crop region is encoded to base64 and stored for sending to LLM
- **Flags:**
  - `pendingCropForNote` - True if user wants to save as note instead of chat
  - `pendingCropIsPdf` - True if cropped region is from PDF
  - `pendingCropPdfPageNumber` - Page number for reference

**PDF Crop Result Processing:**
```kotlin
if (pendingCropIsPdf) {
    // Decode croppedUri to Bitmap
    val bmp: Bitmap = requireContext().contentResolver.openInputStream(croppedUri)
        ?.use { stream -> BitmapFactory.decodeStream(stream) }
        ?: return
    
    // Compress to JPEG
    val baos = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.JPEG, 85, baos)
    
    // Encode to base64 (NO_WRAP = no newlines)
    val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    
    // Store in memory
    pdfPageBase64 = b64
    
    // Show preview
    Glide.with(requireContext()).load(croppedUri).centerCrop()
        .into(imagePreviewThumbnail)
    
    imagePreviewLabel.text = "Page $pendingCropPdfPageNumber ✂️ cropped"
    messageInput.setText("Explain this Page")
}
```

### 1.3 Base64 Encoding - MediaManager
**File:** [app/src/main/java/com/aiguruapp/student/utils/MediaManager.kt](app/src/main/java/com/aiguruapp/student/utils/MediaManager.kt#L115)

**Function:** `uriToBase64(uri: Uri, maxSizeKb: Int = 500): String?`

```kotlin
fun uriToBase64(uri: Uri, maxSizeKb: Int = 500): String? {
    return try {
        // Two-pass decoding to avoid OOM:
        // Pass 1: Decode bounds only to calculate inSampleSize
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        inputStream.use { BitmapFactory.decodeStream(it, null, opts) }
        
        // Calculate downsampling factor
        val maxDim = 1920  // Max dimension
        var sample = 1
        while (opts.outWidth / sample > maxDim || opts.outHeight / sample > maxDim) sample *= 2
        
        // Pass 2: Decode actual pixels at reduced sample size
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = decodeStream.use { BitmapFactory.decodeStream(it, null, decodeOpts) }
            ?: return null
        
        // Quality reduction loop to fit size constraint
        var quality = 90
        var outputStream: ByteArrayOutputStream
        do {
            outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            quality -= 10
        } while (outputStream.size() / 1024 > maxSizeKb && quality > 10)
        
        // Return base64 (NO_WRAP = no newline characters)
        Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    } catch (oom: OutOfMemoryError) {
        Log.e(TAG, "OOM encoding image", oom)
        null
    }
}
```

**Key Features:**
- Two-pass bitmap decoding to prevent OOM
- Dynamic quality reduction (90 → 10) to fit maxSizeKb (default 500KB)
- Sample rate calculation to avoid exceeding 1920px dimensions
- NO_WRAP encoding (no newlines in base64 string)

---

## 2. PDF PAGE HANDLING

### 2.1 PDF Page Manager
**File:** [app/src/main/java/com/aiguruapp/student/utils/PdfPageManager.kt](app/src/main/java/com/aiguruapp/student/utils/PdfPageManager.kt)

**Purpose:** Cache PdfRenderer output to JPEG files to avoid repeated rendering and OOM

#### Core Functions:

**`getPageCount(pdfId: String, assetPath: String): Int`**
```kotlin
fun getPageCount(pdfId: String, assetPath: String): Int {
    val file = ensurePdfCached(pdfId, assetPath)  // Copy PDF asset to cache
    val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    return PdfRenderer(pfd).use { renderer -> renderer.pageCount }
}
```

**`getPage(pdfId: String, assetPath: String, pageIndex: Int, widthPx: Int = 1080): File`**
```kotlin
fun getPage(pdfId: String, assetPath: String, pageIndex: Int, widthPx: Int = 1080): File {
    val pageFile = File(pageCacheDir(pdfId), "page_$pageIndex.jpg")
    if (pageFile.exists()) return pageFile  // Cache hit
    
    val pdf = ensurePdfCached(pdfId, assetPath)
    val pfd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)
    
    PdfRenderer(pfd).use { renderer ->
        renderer.openPage(pageIndex).use { page ->
            // Calculate height proportional to page width/height ratio
            val heightPx = (widthPx * page.height.toFloat() / page.width.toFloat()).toInt()
            
            // Attempt full resolution; fall back to half-res on OOM
            val bmp: Bitmap = try {
                Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            } catch (oom: OutOfMemoryError) {
                Bitmap.createBitmap(widthPx / 2, heightPx / 2, Bitmap.Config.ARGB_8888)
            }
            
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            
            // Save to JPEG with quality 85
            FileOutputStream(pageFile).use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            bmp.recycle()
        }
    }
    return pageFile
}
```

**Cache Locations:**
- Source PDFs: `cacheDir/pdf_cache/{pdfId}.pdf`
- Rendered pages: `cacheDir/pdf_pages/{pdfId}/page_N.jpg`

### 2.2 PDF Attachment Function
**File:** [app/src/main/java/com/aiguruapp/student/FullChatFragment.kt](app/src/main/java/com/aiguruapp/student/FullChatFragment.kt#L445)

```kotlin
fun attachPdfPage(pdfPageFilePath: String, pageNumber: Int) {
    if (isAdded && view != null) {
        tutorSession.currentPage = pageNumber
        preloadPdfPage(File(pdfPageFilePath), pageNumber)
    } else {
        pendingPdfPage = Pair(pdfPageFilePath, pageNumber)
    }
}
```

---

## 3. SERVER-SIDE IMAGE ANALYSIS

### 3.1 Image Analysis Endpoint
**File:** [server/app/api/analyze_image.py](server/app/api/analyze_image.py)

**Endpoint:** `POST /analyze-image`

**Request Model:**
```python
class AnalyzeImageRequest(BaseModel):
    image_base64: str              # JPEG/PNG base64 (with or without data: prefix)
    subject: str = "General"       # Subject context
    chapter: str = "Study Session" # Chapter context
    page_number: int = 0           # PDF page number (0 = not a PDF)
    source_type: str = "image"     # "image" | "camera" | "pdf"
```

**Response Model:**
```python
class AnalyzeImageResponse(BaseModel):
    transcript: str = ""           # Full text extracted from image
    paragraphs_json: str = "[]"    # Array of paragraph objects with text/summary
    diagrams_json: str = "[]"      # Array of diagram objects with heading/description
    key_terms: List[str] = []      # Key terms identified
    cached: bool = False           # True if result came from Redis cache
```

### 3.2 Image Analysis Prompt Template
**System Prompt:**
```
You are an expert educational content extractor.
Analyze this image of a textbook or classroom material.
Return ONLY a valid JSON object — no markdown fences, no explanation.
```

**User Prompt:**
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

### 3.3 Image Analysis Implementation
**Function:** `analyze_image()` [server/app/api/analyze_image.py](server/app/api/analyze_image.py#L126)

```python
@router.post("/analyze-image", response_model=AnalyzeImageResponse)
async def analyze_image(req: AnalyzeImageRequest, auth: AuthUser = Depends(require_auth)) -> AnalyzeImageResponse:
    """
    Analyse an educational image with the vision LLM and return structured
    content (transcript, paragraphs, diagrams, key terms).
    
    Camera images are NOT cached (real-time capture, likely unique).
    Gallery / PDF images ARE cached for 7 days.
    """
    
    # ── Cache decision ────────────────────────────────────────────────────
    use_cache = req.source_type != "camera"
    cache_key = _cache_key(req.image_base64) if use_cache else None
    
    # ── Cache read ────────────────────────────────────────────────────────
    if cache_key:
        cached = cache_service.get_cache(page_id="img_analysis", question=cache_key)
        if cached:
            logger.info("analyze-image cache HIT")
            return _to_response(cached, cached=True)
    
    # ── Build prompt with context ─────────────────────────────────────────
    system_prompt = f"{_SYSTEM}\nSubject: {req.subject}. Chapter: {req.chapter}."
    full_prompt = system_prompt + "\n\n" + _USER
    
    # ── Normalize image URI ───────────────────────────────────────────────
    image_uri = (
        req.image_base64
        if req.image_base64.startswith("data:")
        else f"data:image/jpeg;base64,{req.image_base64}"
    )
    
    # ── LLM call with tier fallback ───────────────────────────────────────
    last_exc: Exception | None = None
    for tier in ("power", "cheaper"):
        try:
            result = generate_response(full_prompt, [image_uri], tier=tier)
            raw_text: str = result.get("text", "")
            if not raw_text.strip():
                raise ValueError("LLM returned empty response")
            
            # ── Parse JSON response ───────────────────────────────────────
            data = _extract_json(raw_text)
            
            # ── Cache write ───────────────────────────────────────────────
            if cache_key:
                try:
                    cache_service.set_cache(
                        page_id="img_analysis",
                        question=cache_key,
                        value=data,
                    )
                except Exception as ce:
                    logger.warning("Cache write failed: %s", ce)
            
            logger.info(
                "analyze-image OK | tier=%s | subject=%s | transcript=%d chars",
                tier, req.subject, len(data.get("transcript", "")),
            )
            return _to_response(data)
        
        except Exception as exc:
            logger.warning("analyze-image tier=%s failed: %s", tier, exc)
            last_exc = exc
    
    # ── Fallback ──────────────────────────────────────────────────────────
    logger.error("analyze-image all tiers failed: %s", last_exc)
    raise HTTPException(status_code=502, detail=f"Image analysis failed: {last_exc}")
```

**Cache Key Function:**
```python
def _cache_key(image_base64: str) -> str:
    """Collision-resistant SHA-256 key: length + first 512 chars + last 256 chars."""
    head = image_base64[:512]
    tail = image_base64[-256:] if len(image_base64) > 512 else ""
    fingerprint = f"{len(image_base64)}:{head}:{tail}".encode()
    return hashlib.sha256(fingerprint).hexdigest()
```

---

## 4. MAIN CHAT ENDPOINT WITH IMAGE SUPPORT

### 4.1 Chat Request Model
**File:** [server/app/api/chat.py](server/app/api/chat.py#L20)

```python
class ChatRequest(BaseModel):
    question: str                                    # User's question
    page_id: str                                     # Chapter/lesson context ID
    student_level: Optional[int] = 5                # Grade level (1-12)
    history: List[str] = Field(default_factory=list)  # Previous Q&A pairs
    
    # Image handling
    images: List[str] = Field(default_factory=list)        # Legacy: multiple images
    image_base64: Optional[str] = None              # New: single image base64
    image_data: Optional[Dict[str, Any]] = None     # Pre-analyzed image data
    
    # Chat mode
    mode: Optional[str] = "normal"                  # "normal" | "blackboard" | "quiz"
    language: Optional[str] = "en-US"
    
    # User info
    user_plan: Optional[str] = "premium"            # "free" | "premium" | "pro"
    user_id: Optional[str] = None                   # Firebase Auth UID
```

### 4.2 Image Normalization
**Function:** `_normalize_images()` [server/app/api/chat.py](server/app/api/chat.py#L49)

```python
def _normalize_images(req: ChatRequest) -> List[str]:
    """Normalize images from various formats to consistent data-URI format."""
    if req.images:
        return req.images
    if req.image_base64:
        raw = req.image_base64.strip()
        if raw.startswith("data:image/"):
            return [raw]
        return [f"data:image/jpeg;base64,{raw}"]
    return []
```

### 4.3 Chat Stream Endpoint
**File:** [server/app/api/chat.py](server/app/api/chat.py#L445)

**Endpoint:** `POST /chat-stream`

**Flow:**
1. Receive request with optional image
2. Normalize images to data-URIs
3. Classify user intent (greet, quiz, practice, explain, etc.)
4. Build context-aware prompt
5. Select model tier based on user plan
6. Stream LLM response as SSE frames
7. Extract and return image transcription for storage

---

## 5. LLM IMAGE PROCESSING

### 5.1 LLM Service - Image Conversion
**File:** [server/app/services/llm_service.py](server/app/services/llm_service.py#L77)

#### Gemini Image Conversion
**Function:** `_images_to_gemini_parts(images: List[str]) -> list`

```python
def _images_to_gemini_parts(images: List[str]) -> list:
    """
    Convert images to Gemini Part objects with validation.
    Supports base64 data URIs and HTTPS URLs.
    Invalid images are skipped with warning logs.
    """
    parts = []
    for idx, img in enumerate(images):
        try:
            if img.startswith("data:"):
                # data:image/jpeg;base64,<data>
                header, data = img.split(",", 1)
                mime_type = header.split(":")[1].split(";")[0]
                
                # Validate MIME type
                if not mime_type.startswith("image/"):
                    logger.warning(f"Image {idx}: Invalid MIME type '{mime_type}'")
                    continue
                
                # Validate base64 is decodable
                decoded = base64.b64decode(data)
                if len(decoded) == 0:
                    logger.warning(f"Image {idx}: Empty base64 data")
                    continue
                
                parts.append(
                    genai_types.Part.from_bytes(data=decoded, mime_type=mime_type)
                )
            else:
                # Remote URL — download and inline
                resp = httpx.get(img, timeout=15, follow_redirects=True)
                resp.raise_for_status()
                mime_type = resp.headers.get("content-type", "image/jpeg").split(";")[0]
                
                if not mime_type.startswith("image/"):
                    logger.warning(f"Image {idx}: URL returned non-image MIME type")
                    continue
                
                parts.append(
                    genai_types.Part.from_bytes(data=resp.content, mime_type=mime_type)
                )
        except Exception as e:
            logger.warning(f"Image {idx}: Unexpected error: {e}")
            continue
    
    return parts
```

#### Bedrock (Claude) Image Conversion
**Function:** `_images_to_bedrock_content(images: List[str]) -> list`

```python
def _images_to_bedrock_content(images: List[str]) -> list:
    """
    Convert images to Bedrock converse() API content format.
    Returns list of content blocks for Claude models.
    """
    content_blocks = []
    
    for idx, img in enumerate(images):
        try:
            if img.startswith("data:"):
                header, data = img.split(",", 1)
                mime_type = header.split(":")[1].split(";")[0]
                
                if not mime_type.startswith("image/"):
                    logger.warning(f"Image {idx}: Invalid MIME type '{mime_type}'")
                    continue
                
                decoded = base64.b64decode(data)
                if len(decoded) == 0:
                    logger.warning(f"Image {idx}: Empty base64 data")
                    continue
                
                # Map MIME types to Bedrock formats
                format_map = {
                    "image/jpeg": "jpeg",
                    "image/jpg": "jpeg",
                    "image/png": "png",
                    "image/gif": "gif",
                    "image/webp": "webp",
                }
                image_format = format_map.get(mime_type.lower())
                if not image_format:
                    logger.warning(f"Image {idx}: Unsupported format '{mime_type}'")
                    continue
                
                # Bedrock converse() expects raw binary bytes
                content_blocks.append({
                    "image": {
                        "format": image_format,
                        "source": {"bytes": decoded},  # Raw binary bytes
                    },
                })
                logger.info(f"Image {idx}: Valid base64 ({image_format}, {len(decoded)} bytes)")
            else:
                # Download URL
                resp = httpx.get(img, timeout=15, follow_redirects=True)
                resp.raise_for_status()
                mime_type = resp.headers.get("content-type", "image/jpeg").split(";")[0]
                
                if not mime_type.startswith("image/"):
                    logger.warning(f"Image {idx}: URL returned non-image MIME type")
                    continue
                
                format_map = {
                    "image/jpeg": "jpeg",
                    "image/jpg": "jpeg",
                    "image/png": "png",
                    "image/gif": "gif",
                    "image/webp": "webp",
                }
                image_format = format_map.get(mime_type.lower())
                if not image_format:
                    logger.warning(f"Image {idx}: Unsupported format")
                    continue
                
                content_blocks.append({
                    "image": {
                        "format": image_format,
                        "source": {"bytes": resp.content},  # Raw binary
                    },
                })
                logger.info(f"Image {idx}: Valid URL ({image_format}, {len(resp.content)} bytes)")
        except Exception as e:
            logger.warning(f"Image {idx}: Unexpected error: {e}")
            continue
    
    return content_blocks
```

### 5.2 LLM Calls with Images
**Function:** `generate_response()` [server/app/services/llm_service.py](server/app/services/llm_service.py#L479)

```python
def generate_response(
    prompt: str,
    images: Optional[List[str]] = None,
    tier: Literal["power", "cheaper", "faster"] = "cheaper",
) -> Dict[str, Any]:
    """
    Generate LLM response using LiteLLM proxy.
    
    Args:
        prompt: Text prompt for LLM
        images: Optional list of image URLs or base64 data URIs
        tier: Model tier selection
    
    Returns:
        Dict with 'text', 'tokens', 'provider', 'model' keys
    """
    logger.info(f"generate_response | tier={tier} | images={len(images) if images else 0}")
    
    model_config = settings.get_model_config(tier)
    model_config._tier_name = tier
    
    try:
        # Provider-specific calls (Gemini, Bedrock, etc.)
        if model_config.provider == "gemini":
            result = _call_gemini(prompt, model_config, images)
        elif model_config.provider == "bedrock":
            result = _call_bedrock(prompt, model_config, images)
        else:
            result = _call_litellm_proxy(prompt, model_config, images)
        
        logger.info(f"LLM success | provider={result.get('provider')} | tokens={result.get('tokens')}")
        return result
    except Exception as e:
        logger.error(f"LLM call failed: {e}")
        return {
            "text": f"[LLM SERVICE ERROR] {str(e)[:200]}",
            "tokens": {"inputTokens": 0, "outputTokens": 0, "totalTokens": 0},
            "provider": "error",
            "error": str(e),
        }
```

**Gemini Call:**
```python
def _call_gemini(
    prompt: str,
    model_config: ModelConfig,
    images: Optional[List[str]] = None
) -> Dict[str, Any]:
    """Call Google Gemini API with images."""
    if not _genai_client:
        raise ValueError("GEMINI_API_KEY not configured")
    
    # Build contents: images first, then text
    if images and model_config.supports_images:
        contents: list = _images_to_gemini_parts(images) + [
            genai_types.Part.from_text(text=prompt)
        ]
    else:
        contents = prompt
    
    response = _genai_client.models.generate_content(
        model=model_config.model_id,
        contents=contents,
        config=genai_types.GenerateContentConfig(
            temperature=model_config.temperature,
            max_output_tokens=model_config.max_tokens,
        ),
    )
    
    usage = response.usage_metadata
    return {
        "text": response.text,
        "tokens": {
            "inputTokens": usage.prompt_token_count,
            "outputTokens": usage.candidates_token_count,
            "totalTokens": usage.total_token_count,
        },
        "provider": "gemini",
        "model": model_config.model_id,
    }
```

**Bedrock Call:**
```python
def _call_bedrock(
    prompt: str,
    model_config: ModelConfig,
    images: Optional[List[str]] = None
) -> Dict[str, Any]:
    """Call AWS Bedrock (Claude) API with images using converse method."""
    if not _bedrock_client:
        raise ValueError("AWS Bedrock not configured")
    
    # Build content blocks
    content_blocks = []
    if images and model_config.supports_images:
        content_blocks.extend(_images_to_bedrock_content(images))
    
    # Add text prompt
    content_blocks.append({"text": prompt})
    
    # Build messages for converse API
    messages = [{
        "role": "user",
        "content": content_blocks,
    }]
    
    logger.info(f"Calling Bedrock with {len(content_blocks)} content blocks")
    
    response = _bedrock_client.converse(
        modelId=model_config.model_id,
        messages=messages,
        inferenceConfig={
            "maxTokens": model_config.max_tokens,
            "temperature": model_config.temperature,
        },
    )
    
    response_text = response["output"]["message"]["content"][0]["text"]
    usage = response.get("usage", {})
    
    return {
        "text": response_text,
        "tokens": {
            "inputTokens": usage.get("inputTokens", 0),
            "outputTokens": usage.get("outputTokens", 0),
            "totalTokens": usage.get("inputTokens", 0) + usage.get("outputTokens", 0),
        },
        "provider": "bedrock",
        "model": model_config.model_id,
    }
```

---

## 6. ATTACHMENT TRANSCRIPTION EXTRACTION

### 6.1 Transcription Extraction from LLM Response
**File:** [server/app/api/chat.py](server/app/api/chat.py#L194)

**Function:** `_extract_page_transcript()`

```python
def _extract_page_transcript(
    result: Dict[str, Any],
    images: List[str],
    image_data: Optional[Dict[str, Any]],
) -> Optional[str]:
    """
    Returns a transcript string to send back to the Android app so it can
    persist the image analysis into the Firestore chapter system context.

    Priority:
      1. LLM JSON "user_attachment_transcription" — parsed from LLM response
      2. result["page_transcript"] — if generate_response() extracts it from vision
      3. result["transcript"]      — alternate key
      4. image_data["transcript"]  — client already analysed; echo it back
      5. None                      — no image attached, skip the frame
    """
    # Try to extract transcription from the LLM's own JSON response
    text = result.get("text", "")
    try:
        parsed = extract_json_safe(text)
        transcription = parsed.get("user_attachment_transcription", "").strip()
        if transcription:
            return transcription
    except Exception:
        pass
    
    if not images and not image_data:
        return None
    
    transcript = (
        result.get("page_transcript")
        or result.get("transcript")
        or (image_data or {}).get("transcript")
    )
    return str(transcript).strip() or None if transcript else None
```

### 6.2 JSON Extraction from LLM Response
**Function:** `extract_json_safe(text: str) -> Dict`

```python
def extract_json_safe(text):
    """
    Robustly extract and parse the first top-level JSON object from text.
    Handles:
      - Bare JSON
      - ```json ... ``` fenced JSON
      - Prefix/suffix prose around JSON
      - Nested objects/arrays/LaTeX inside string values
    """
    import json
    import re
    
    stripped = text.strip()
    
    # Strip markdown fences if present
    if stripped.startswith("```"):
        stripped = re.sub(r"^```(?:json)?\s*", "", stripped)
        stripped = re.sub(r"\s*```\s*$", "", stripped)
    
    # Fast path: entire text is valid JSON
    try:
        return json.loads(stripped)
    except json.JSONDecodeError:
        pass
    
    # Scan for first '{' and walk with balanced-brace counting
    # (correctly skipping string contents with escaped chars)
    brace_depth = 0
    in_string = False
    escape_next = False
    start_idx = -1
    
    for i, ch in enumerate(stripped):
        if escape_next:
            escape_next = False
            continue
        
        if ch == '\\':
            escape_next = True
            continue
        
        if ch == '"' and not escape_next:
            in_string = not in_string
            continue
        
        if not in_string:
            if ch == '{':
                if brace_depth == 0:
                    start_idx = i
                brace_depth += 1
            elif ch == '}':
                brace_depth -= 1
                if brace_depth == 0 and start_idx != -1:
                    json_str = stripped[start_idx:i+1]
                    try:
                        return json.loads(json_str)
                    except json.JSONDecodeError:
                        pass
                    start_idx = -1
    
    raise ValueError(f"No valid JSON found in text")
```

---

## 7. FIRESTORE STORAGE OF ATTACHMENTS

### 7.1 Message Model
**File:** [app/src/main/java/com/aiguruapp/student/models/Message.kt](app/src/main/java/com/aiguruapp/student/models/Message.kt)

```kotlin
data class Message(
    val id: String = "",
    val content: String = "",
    val isUser: Boolean = true,
    val timestamp: Long = System.currentTimeMillis(),
    val imageUrl: String? = null,           // URL to stored image
    val imageBase64: String? = null,        // Inline base64 (rarely used)
    val voiceUrl: String? = null,
    val pdfUrl: String? = null,
    val messageType: MessageType = MessageType.TEXT,  // TEXT, IMAGE, VOICE, PDF, MIXED
    val transcription: String = "",         // Full transcription of image/PDF
    val extraSummary: String = ""           // Extra details from LLM JSON
) : Serializable {
    enum class MessageType {
        TEXT, IMAGE, VOICE, PDF, MIXED
    }
}
```

### 7.2 Chat History Repository
**File:** [app/src/main/java/com/aiguruapp/student/chat/ChatHistoryRepository.kt](app/src/main/java/com/aiguruapp/student/chat/ChatHistoryRepository.kt)

**Function:** `saveMessage()`

```kotlin
fun saveMessage(message: Message, tokens: Int? = null, inputTokens: Int? = null, outputTokens: Int? = null) {
    val role = if (message.isUser) "user" else "model"
    if (message.id.isBlank()) return
    
    FirestoreManager.saveMessage(
        userId       = userId,
        subject      = subject,
        chapter      = chapter,
        messageId    = message.id,
        text         = message.content,
        role         = role,
        timestamp    = message.timestamp,
        tokens       = tokens,
        inputTokens  = inputTokens,
        outputTokens = outputTokens,
        imageUrl     = message.imageUrl,
        transcription = message.transcription,    // Full text extracted from image
        extraSummary  = message.extraSummary      // Extra details from LLM JSON
    )
}
```

### 7.3 Firestore Schema
**Location:** `conversations/{userId}__{subject}__{chapter}/`

```
conversations/{userId}__{subject}__{chapter}/
  ├── userId: String
  ├── subject: String
  ├── chapter: String
  ├── createdAt: Timestamp
  ├── lastMessage: String
  ├── summary: String
  └── messages/{messageId}/
      ├── messageId: String            # Unique message ID
      ├── role: String                 # "user" | "model"
      ├── text: String                 # Main message content
      ├── timestamp: Long              # Milliseconds since epoch
      ├── tokens: Int?                 # Total tokens used (if available)
      ├── inputTokens: Int?            # Input tokens (if available)
      ├── outputTokens: Int?           # Output tokens (if available)
      ├── imageUrl: String?            # URL to attached image (if any)
      ├── transcription: String        # Full text extracted from attached image/PDF
      └── extraSummary: String         # Extra details/summary from LLM JSON response
```

**Transcription Storage Logic:**
```kotlin
// Message load:
val transcription = map["transcription"] as? String ?: ""

// Message save:
firestore.collection("conversations")
    .document(userId)
    .collection("messages")
    .document(messageId)
    .set(mapOf(
        "text" to message.content,
        "transcription" to message.transcription,  // Store extracted text
        "extraSummary" to message.extraSummary,
        // ... other fields
    ))
```

---

## 8. LLM PROMPTS FOR IMAGE ANALYSIS

### 8.1 Tutor Prompts (Android Assets)
**File:** [app/src/main/assets/tutor_prompts.json](app/src/main/assets/tutor_prompts.json)

**Image Explanation Mode Prompt:**
```json
{
  "main_prompt": "You are a highly attentive visual AI tutor for Class {level} students.\n\nCHAPTER CONTEXT:\n{context}\n\nCONVERSATION HISTORY:\n{history}\n\nSTUDENT'S QUESTION: {question}\n\nThe student has attached an image or textbook page. Your tasks IN ORDER:\n\n1. TRANSCRIBE: Read and write out ALL visible text word-for-word (headings, body text, labels, numbers, formulas, captions, questions). Preserve structure. Put everything in user_attachment_transcription.\n2. DESCRIBE VISUALS: Note all diagrams, figures, tables, arrows, graphs with their labels inside user_attachment_transcription.\n3. ANSWER: Answer the student's question based on what you see in the image combined with your knowledge.\n\nANSWER STRUCTURE:\nWhat I see -- 1 sentence: type of content\nKey content -- main points/formulas/steps from the image\nAnswer -- direct answer to the student's question with explanation\nTip -- 1 memory aid or common mistake to avoid\n\nTRANSCRIPTION IS CRITICAL: It will be saved as context for all follow-up questions. Transcribe EVERYTHING visible — do not summarise or skip any text.\n\nRESPONSE CALIBRATION: Prioritise extracting image content accurately before answering.",
  "image_attachment_note": "IMAGE/PDF ATTACHMENT:\n- Transcribe ALL visible text word-for-word in user_attachment_transcription.\n- Describe any diagrams, tables, or charts in detail.\n\nSTRICT OUTPUT — return ONLY valid JSON (no code fences, no extra text):\n{\"user_question\": \"<restate the student's question briefly>\", \"answer\": \"<your full engaging tutoring answer with all markdown formatting>\", \"user_attachment_transcription\": \"<if image/PDF attached: ALL visible text + diagram descriptions; empty string if no attachment>\", \"extra_details_or_summary\": \"<bonus formulas, fun facts, summary table; empty string if nothing extra>\"}"
}
```

### 8.2 Server Prompts
**File:** [server/app/services/prompt_service.py](server/app/services/prompt_service.py)

**Image Explanation Prompt:**
```python
def _image_explain_prompt(context: str, history: str, question: str, level: int) -> str:
    return (
        f"You are a highly attentive visual AI tutor for Class {level} students.\n\n"
        f"CHAPTER CONTEXT:\n{context}\n\n"
        f"CONVERSATION HISTORY:\n{history}\n\n"
        f"STUDENT'S QUESTION: {question}\n\n"
        "The student has attached an image or textbook page. Your tasks IN ORDER:\n\n"
        "1. TRANSCRIBE: Read and write out ALL visible text word-for-word "
        "(headings, body text, labels, numbers, formulas, captions, questions). "
        "Preserve structure. Put everything in user_attachment_transcription.\n"
        "2. DESCRIBE VISUALS: Note all diagrams, figures, tables, arrows, graphs "
        "with their labels inside user_attachment_transcription.\n"
        "3. ANSWER: Answer the student's question based on what you see in the image "
        "combined with your knowledge.\n\n"
        "ANSWER STRUCTURE:\n"
        "What I see -- 1 sentence: type of content\n"
        "Key content -- main points/formulas/steps from the image\n"
        "Answer -- direct answer to the student's question with explanation\n"
        "Tip -- 1 memory aid or common mistake to avoid\n\n"
        "TRANSCRIPTION IS CRITICAL: It will be saved as context for all follow-up questions. "
        "Transcribe EVERYTHING visible — do not summarise or skip any text.\n\n"
        "RESPONSE CALIBRATION: Prioritise extracting image content accurately before answering."
        + _JSON_FOOTER
    )
```

**JSON Output Footer (appended to all prompts):**
```python
_JSON_FOOTER = (
    "\n\nSTRICT OUTPUT FORMAT:\n"
    "Return ONLY valid JSON (no markdown fences, no explanation):\n"
    "{\n"
    '  "user_question": "<restate briefly>",\n'
    '  "answer": "<full answer with **bold**, LaTeX $...$, markdown>",\n'
    '  "user_attachment_transcription": "<ALL visible text + diagram desc if image attached; else empty>",\n'
    '  "extra_details_or_summary": "<bonus facts/formulas/table; else empty>"\n'
    "}\n"
)
```

---

## 9. COMPLETE FLOW DIAGRAM

### Image/PDF Attachment Processing Flow

1. **Android Capture/Select**
   - User picks image/PDF or takes photo
   - `pickImageLauncher` / `cameraLauncher` triggered
   - Image sent to UCrop for cropping

2. **Cropping**
   - User defines crop region in UCrop
   - Cropped image decoded to Bitmap
   - Bitmap compressed to JPEG (quality 85)
   - JPEG encoded to base64 (NO_WRAP)
   - Base64 stored in `pdfPageBase64` / local variable

3. **Send to Server**
   - User completes question + sends message
   - Base64 embedded in `ChatRequest.image_base64`
   - Sent to `/chat-stream` endpoint

4. **Server: Image Normalization**
   - `_normalize_images()` converts to data-URI format
   - Result: `["data:image/jpeg;base64,..."]`

5. **Server: Intent & Prompt Building**
   - `_classify_intent()` determines if image analysis needed
   - `build_prompt()` selects appropriate prompt template
   - Prompt includes image-specific instructions for transcription

6. **LLM Processing**
   - `generate_response()` called with prompt + images
   - Images converted to provider format:
     - **Gemini:** `genai_types.Part.from_bytes(data=binary, mime_type="image/jpeg")`
     - **Bedrock:** `{"image": {"format": "jpeg", "source": {"bytes": binary}}}`
   - LLM returns JSON with `user_attachment_transcription` field

7. **Transcription Extraction**
   - `_extract_page_transcript()` extracts transcription from LLM JSON
   - Priority: LLM JSON > fallback fields

8. **Android Storage**
   - Transcription received from server in response
   - `Message` object created with:
     - `transcription = <extracted text>`
     - `extraSummary = <extra details from LLM>`
   - `ChatHistoryRepository.saveMessage()` called

9. **Firestore Persistence**
   - `FirestoreManager.saveMessage()` writes to:
     - `conversations/{userId}__{subject}__{chapter}/messages/{messageId}/`
   - Fields saved:
     - `text` - main answer
     - `transcription` - full extracted text
     - `extraSummary` - bonus details
     - `timestamp`, `role`, etc.

10. **Context for Follow-ups**
    - Previous messages loaded with transcription field
    - In follow-up questions without new image:
      - `_merge_context_with_image_data()` injects transcription
      - Prompt includes: `[attachment_transcription: <first 500 chars>]`
    - In follow-up questions WITH new image:
      - Old transcription skipped (to avoid conflict)
      - Fresh image analysis takes priority

---

## 10. KEY DATA STRUCTURES SUMMARY

| Component | Type | Purpose |
|-----------|------|---------|
| `pdfPageBase64` | String | In-memory base64 of cropped PDF page |
| `pendingDisplayUri` | Uri | Display URI for image preview thumbnail |
| `Message.transcription` | String | Full text extracted from attached image/PDF |
| `Message.extraSummary` | String | Extra details/summary from LLM JSON |
| `AnalyzeImageRequest.image_base64` | String | Client sends image for dedicated analysis |
| `AnalyzeImageResponse.transcript` | String | Extracted text from `/analyze-image` endpoint |
| `ChatRequest.image_base64` | String | Image sent with chat question |
| `ChatRequest.image_data` | Dict | Pre-analyzed image data (transcript, diagrams) |
| `user_attachment_transcription` | String | Field in LLM JSON response for image transcription |

---

## 11. SUPPORTED MIME TYPES

- **Gemini:** Any valid MIME type in `image/*`
- **Bedrock/Claude:** `image/jpeg`, `image/jpg`, `image/png`, `image/gif`, `image/webp`
- **Android:** Typically JPEG or PNG from camera/gallery

---

## 12. ERROR HANDLING & EDGE CASES

### Image Validation
- Empty base64 → Warning, skip image
- Invalid MIME type → Warning, skip image
- Corrupted base64 → Exception caught, image skipped
- OOM during bitmap decode → Retry at half resolution
- OOM during base64 encoding → Return null, show error

### Transcription Handling
- No LLM response → `_extract_page_transcript()` returns None
- No transcription in LLM JSON → Check fallback keys
- Empty transcription → Store empty string
- User deletes attachment → Old transcription remains in history

### Caching
- Camera images NOT cached (real-time, likely unique)
- Gallery/PDF images cached for 7 days
- Cache key: SHA-256(length + first 512 chars + last 256 chars)
- Cache miss → Fall back to "cheaper" tier model

---

## 13. CONFIGURATION & SETTINGS

**Model Tier Selection by User Plan:**
```python
plan_to_tier = {
    "free": "power",      # Best models
    "premium": "power",   # Best models
    "pro": "power",       # Best models
}
```

**Image Size Constraints:**
- Max image dimension: 1920px
- Max encoded size: 500KB (default, adjustable)
- Compression starts at quality 90, reduces to 10 if needed

**LLM Model Configs:**
- `power` tier: Gemini 2.0 Pro or Claude 3.5 Sonnet (best for images)
- `cheaper` tier: Gemini 2.0 Flash or Claude 3 Haiku
- `faster` tier: Gemini 2.0 Flash Lite

