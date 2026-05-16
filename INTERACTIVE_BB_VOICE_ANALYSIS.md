# Interactive Voice Activity for Blackboard (BB) Sessions
## Architectural Analysis & Implementation Options

**Date:** 2026-05-16  
**Scope:** Adding always-listening wake word detection + interruption capability during BB sessions  
**Current Status:** BB has manual mic tap (ask bar) but no continuous voice activity detection (VAD)

---

## Current BB Architecture

### Session Flow (Frontend)
```
BlackboardActivity.kt:
├─ onCreate() → loadLesson()
├─ setupBoard() → loads frames from Firestore (step-by-step content)
├─ speakFrame() → TTS playback (Gemini AI or device voices)
├─ pauseBtn → togglePause() → suspends TTS, persists state
├─ Ask Bar (manual interaction)
│  ├─ User taps 🎤 → startBbVoiceInput()
│  ├─ startListening() → Google Speech-to-Text API
│  ├─ Results → bbAskInput (text field)
│  └─ User taps send → API call to backend with question + context
└─ frames iterate: frame[0] → frame[4], each with audio preloading
```

### Session Flow (Backend)
```
chat.py (POST /chat-stream):
├─ mode="blackboard" → _bb_plan() → fast lesson plan (150ms)
├─ Step loop:
│  ├─ build_blackboard_mode_user_content() → enriched prompt
│  ├─ generate_response() → POWER tier LLM (claude-3.5-sonnet)
│  ├─ Response → JSON: frames[] with SVG, TTS, images, quizzes
│  ├─ Enrichment: wikimedia images, diagram building
│  └─ Stream to client as NDJSON (one frame/line)
└─ Full session: ~15–30 frames over 10–15 minutes
```

### Current Voice Interaction (Ask Bar)
- **Trigger:** Manual tap on 🎤 button in ask bar
- **Tech:** Android's `SpeechRecognizer` + Google Speech-to-Text
- **Duration:** Listens for ~5–10 seconds, then returns transcript
- **Integration:** Question → text input field → user taps send → new API call
- **UX Issue:** Requires user to explicitly tap mic; not hands-free or interruption-based

---

## What's Missing: Interactive Voice Activity

### Requirements
1. **Always-Listening Microphone** — Device listens in background without explicit tap
2. **Wake Word Detection** — Recognize trigger words: "Hey", "Stop", "Hold on", "Wait", "Question"
3. **Interruption Capability** — Pause TTS when wake word detected
4. **Context Merging** — Take user question mid-session and intelligently continue
5. **Resume Logic** — After answering, resume from where it paused (or restart step)
6. **Low Latency** — <500ms from speech to TTS pause (must feel responsive)
7. **Low Power** — VAD consumes <5% CPU when idle on device
8. **Graceful Fallback** — If VAD fails, fall back to manual mic tap

---

## Architectural Options

### Option 1: **Local VAD + Cloud Speech-to-Text (Recommended for MVP)**

**Approach:**
- Device runs on-device VAD engine (detects silence/speech locally, never sends audio to cloud)
- When VAD detects speech, start cloud speech-to-text (Google Speech-to-Text API)
- Detect hardcoded wake words in transcript on device (substring match or regex)
- If wake word detected: immediately pause BB session → inject question into context
- Resume lesson after backend processes interruption

**Tech Stack:**
- **VAD Engine:** 
  - Option A: Google's WebRTC VAD (compiled for Android) — ~50KB, no dependencies
  - Option B: NVIDIA Silero VAD (lighter, no cloud calls, free) — ~100KB
  - Option C: Local on-device TensorFlow Lite model (~200KB) — more flexible
- **Wake Word:** Simple substring match on speech transcript (case-insensitive)
  - Words: ["hey", "stop", "pause", "wait", "question", "hold on"]
  - Could upgrade to local keyword spotting (KWS) model later

**Flow:**
```
┌─ VAD.startListening() → runs continuously
├─ Frame plays: TTS audio ▶️ (user hearing lesson)
├─ User says "Hey, what about..."
├─ VAD detects: speech detected ✓
├─ Speech-to-Text: "What about photosynthesis?"
├─ Wake word check: contains "what about" → match! (flexible matching)
├─ ACTION: pauseBtn.performClick() → togglePause()
├─ TTS stops ⏸, UI shows "Question detected: What about..."
├─ POST /bb-interrupt {session_id, step, question, frame_context}
├─ Backend: processes interruption, generates inline answer
├─ Response: {answer, resume_step_idx} → show answer inline
├─ Optional: Auto-resume after 10s or manual tap ▶️
└─ Continue from resume_step_idx
```

**Pros:**
- ✅ No latency — VAD is local, sub-50ms
- ✅ Privacy — only speech-to-text results sent to cloud (never raw audio to VAD)
- ✅ Low power — WebRTC VAD is <2% CPU idle
- ✅ Simple to implement — 200–300 lines in Kotlin
- ✅ Works offline for VAD part (speech-to-text still needs network)
- ✅ Easy to test: can add debug UI showing VAD state

**Cons:**
- ❌ Wake word detection is basic (substring match, not ML-based)
- ❌ Speech-to-Text still has 2–3 sec latency
- ❌ Backend needs new `/bb-interrupt` endpoint (moderate work)
- ❌ Resume logic is complex: where to resume? how to inject answer?
- ❌ May need to tweak VAD sensitivity per device/environment

**Implementation Effort:** 5–7 days (VAD library integration + interrupt endpoint + resume logic)

---

### Option 2: **Google Cloud Speech-to-Text Streaming + Keyword Matching**

**Approach:**
- Use Google Cloud Speech-to-Text **streaming API** (bi-directional stream)
- Opens persistent gRPC connection; sends audio chunks (100ms) continuously
- Backend processes streaming transcript in real-time
- Client watches for wake words in each transcript chunk
- On match: pause TTS, merge question, call interrupt endpoint

**Flow:**
```
├─ BiDirectionalStream(Speech-to-Text) opened at lesson start
├─ Device: chunks audio every 100ms → sends to Google
├─ Google: streams back partial results: "hey" → "hey what" → "hey what about..."
├─ Client watches each partial result for wake words
├─ Detects: "hey" → pause TTS immediately
├─ Full results arrive next: "hey what about photosynthesis"
├─ POST /bb-interrupt {session_id, question}
└─ Resume as above
```

**Pros:**
- ✅ Real-time: see partial results while user is still speaking
- ✅ Accurate: Google's streaming API has higher accuracy than batch
- ✅ Wake word detection is ML-backed (Google's model)
- ✅ Lower latency than batch: ~300–500ms for first partial result

**Cons:**
- ❌ Consumes bandwidth: constant streaming even during silence/music
- ❌ Cost: $0.024 per 15 seconds audio; if always-on for 10 min lesson = ~$0.016 per session
- ❌ Complexity: need to manage bidirectional stream lifecycle
- ❌ Privacy: audio constantly sent to Google Cloud
- ❌ May wake up on random words ("hey" is common)

**Implementation Effort:** 6–8 days (stream management + partial result matching + cost monitoring)

---

### Option 3: **On-Device Keyword Spotting (KWS) Model + Manual Confirm**

**Approach:**
- Run lightweight TensorFlow Lite keyword spotting model on-device
- Recognizes specific wake words with >95% accuracy (no false positives)
- When wake word detected: auto-enable Google Speech-to-Text (not always-on)
- User speaks full question; speech-to-text captures it
- Send to backend interrupt endpoint

**Tech:**
- Model: Google's own KWS model or custom trained on ["hey", "stop", "wait"]
- Size: ~200–500 KB
- Latency: ~50ms per frame (10ms audio chunks)

**Flow:**
```
├─ On-device KWS model listening continuously (very low power)
├─ 99% of time: no match (silent, music, other speech)
├─ User: "Hey, I don't understand"
├─ KWS detects: "hey" → confidence 98%
├─ ACTION: enable streaming Speech-to-Text
├─ "I don't understand photosynthesis"
├─ Full transcript → POST /bb-interrupt
└─ Resume
```

**Pros:**
- ✅ Extremely low power: KWS is <1% CPU idle
- ✅ Privacy: only speech-to-text **after** wake word (not always-on)
- ✅ Accuracy: can achieve >99% wake word accuracy with proper model
- ✅ Cost: only pay for speech-to-text after wake word (maybe 30s per 10min lesson)
- ✅ Feels natural: like smart speakers (Alexa, Google Home)
- ✅ No ambient mic noise issues: speech-to-text only starts when wake word heard

**Cons:**
- ❌ Model training: need to train/fine-tune KWS model (1–2 weeks)
- ❌ Or: use third-party service (Google Lookout, custom Picovoice)
- ❌ Complexity: TensorFlow Lite in production (debugging, updates)
- ❌ Model versioning: if model changes, app must handle it

**Implementation Effort:** 8–12 days (KWS integration + speech-to-text fallback) + model training (weeks)

---

### Option 4: **Paid Third-Party Wake Word Service (Picovoice)**

**Approach:**
- Use Picovoice's **Porcupine** wake word engine (production-grade)
- Runs entirely on-device; no cloud dependency for wake detection
- 99.5% accuracy; <1MB; <1% CPU
- Integrates with Speech Recognition for full sentence capture

**Tech:**
- Picovoice Porcupine: $10–50/month or one-time license
- Pre-trained models for common wake words; custom training available

**Pros:**
- ✅ Production-ready: trusted by millions of apps
- ✅ Excellent accuracy: 99.5%
- ✅ Multiple wake words: can have 3–5 concurrent
- ✅ Custom training: can add app-specific words
- ✅ Very light: <1MB binary
- ✅ No cloud calls unless speech-to-text needed

**Cons:**
- ❌ Cost: licensing per app/user
- ❌ Dependency on third party
- ❌ Overkill if only 5 hardcoded wake words

**Implementation Effort:** 3–4 days (integration only; library handles VAD + KWS)

---

## Recommended Path (My Thinking)

### Phase 1: MVP (Week 1–2)
**Goal:** Get feedback on UX quickly; validate if always-listening is actually wanted

**Implementation:** Option 1 (Local VAD + Cloud Speech-to-Text)
- Use **Google WebRTC VAD** (free, open-source, <50KB)
- Simple substring wake word matching: ["hey", "stop", "wait", "what", "question"]
- New backend endpoint: `POST /bb-interrupt` (see spec below)
- Frontend: Add VAD manager singleton (runs in background during BB)
- UX: Show toast "Question detected" + pause TTS + show inline response area

**Why this first:**
- Validates user pain point: does always-listening actually improve learning?
- Lowest risk: VAD logic is local-only, no privacy/cost issues
- Fast iteration: can tune wake words, sensitivity from backend
- Clear fallback: if VAD fails, manual mic tap still works

**Expected Effort:** 5–6 days
- WebRTC VAD library integration: 1 day
- VAD manager + lifecycle: 1 day
- `/bb-interrupt` endpoint: 2 days
- Resume/injection logic: 1 day
- Testing + UX polish: 1 day

---

### Phase 2: Refinement (Week 3–4)
**Based on Phase 1 feedback:**

**If UX is good:** Upgrade to Option 3 (KWS model) for better accuracy
- Train custom KWS model on [hey, stop, wait, hold on, question]
- Picovoice as backup (paid)
- Reduce false positive rate

**If UX needs work:** Hybrid approach
- Use Picovoice for wake words (production-ready)
- Keep streaming speech-to-text (lower latency)
- Add context-aware resume (smarter injection point)

---

## Backend Spec: `/bb-interrupt` Endpoint

```python
POST /bb-interrupt

Request:
{
  "session_id": "sess_abc123",          # Current BB session ID
  "step_idx": 2,                        # Which step were we on
  "frame_idx": 1,                       # Which frame in step
  "question": "What about photosynthesis?",
  "transcript": "what about photosynthesis",
  "wake_word": "what",                  # Which word triggered interrupt
  "context": {...}                      # Serialized current lesson context
}

Response:
{
  "answer": "Photosynthesis is the process where plants...",
  "follow_up_frames": [...],            # Optional: new frames to show
  "resume_step_idx": 2,                 # Resume from here after answer
  "resume_frame_idx": 2,                # Or restart this frame
  "interrupt_id": "int_123",            # Track this interruption
  "suggested_action": "resume|new_step" # UX hint
}
```

### Backend Logic:
1. Validate session (user, lesson, permission)
2. Build context string: lesson topic + current step + user's plan/level
3. Prompt: "User asked during step X: 'question'. Provide a 1–2 sentence answer that directly addresses the question without interrupting the lesson flow. Then suggest: should we resume the current step or move to the next step?"
4. LLM call (tier=faster, 100 tokens max) → answer + suggestion
5. Optionally: generate follow-up frames if suggestion="new_step"
6. Log interrupt for analytics (interruption rate, common questions, etc.)

---

## Data Model: Activity Logs + Interrupts

```python
# In Firestore:
activity_logs/{doc_id}:
{
  "uid": "user123",
  "session_id": "sess_abc123",
  "event_type": "bb_interrupt",        # or "bb_start", "bb_complete", "bb_question"
  "timestamp": 1715849200000,
  "lesson_topic": "Photosynthesis",
  "step_idx": 2,
  "question": "What about photosynthesis?",
  "wake_word_matched": "what",
  "response_latency_ms": 850,           # Time to answer
  "vad_accuracy": 0.95,                 # If known
  "user_satisfaction": null,            # User can rate interruption
}
```

---

## Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|-----------|
| VAD false positives (dog bark, doorbell) | UX: pauses lesson on noise | Require 300ms+ speech duration, adjust sensitivity threshold per device |
| Speech-to-Text latency (3–5s) | UX: feels sluggish | Use streaming API; show "Processing..." feedback |
| Resume logic breaks (loses context, wrong injection point) | Data: lesson content misaligned | Test resume with all frame types (quiz, video, text). Log resumption points. |
| Always-listening drains battery | Perf: 10–20% extra battery drain | Only enable VAD during BB sessions, not globally. Profile on real devices. Disable if battery <20%. |
| Wake word triggers on lesson audio ("Wait, what about...") | UX: interrupts constantly | Filter speech from speaker (detect TTS playback via audio focus) vs mic input. |
| User privacy concern ("Is it always listening?") | Adoption: low uptake | Clear UI: "Listening" indicator shows VAD state. Document: VAD is local; only question text sent to server. |

---

## Analytics to Collect

```python
{
  "bb_session_starts": int,
  "bb_interruptions": int,
  "interruptions_per_session": float,  # Avg
  "avg_interruption_latency": int,     # ms
  "top_wake_words": dict,              # "what": 45%, "wait": 20%, ...
  "top_questions_topics": list,        # Most common interruption topics
  "vad_accuracy_per_device": dict,     # Samsung S24: 98%, iPhone 15: 95%
  "user_satisfaction_rating": float,   # 1–5 scale after lesson
}
```

---

## Summary: My Recommendation

**Start with Option 1 (Local VAD + Cloud STT) for MVP:**
- Validates the UX quickly (1–2 weeks)
- Lowest risk: privacy, cost, complexity
- Easy to iterate on wake words, sensitivity
- Clear fallback to manual mic tap

**Success Criteria:**
1. VAD runs <1% CPU when idle ✓
2. Wake word latency <500ms ✓
3. User can interrupt and get answer in <10 seconds ✓
4. Resume logic doesn't lose context ✓
5. Users rate experience >4/5 on survey ✓

**If Phase 1 is successful, upgrade to Picovoice (Option 4) for production stability and accuracy.**

---

Would you like me to implement Phase 1? Or would you prefer a different approach?

**Next Steps If You Approve:**
1. Design `/bb-interrupt` endpoint in detail
2. Build VAD manager in Kotlin
3. Update BlackboardActivity to integrate VAD lifecycle
4. Implement resume logic (most complex part)
5. Add activity logging for analytics
6. Test on real devices (phone, tablet)
