# AI Guru – Streaming Voice Server

Local Python server that replaces on-device SpeechRecognizer with a full GPU-accelerated pipeline:

```
Android mic → PCM WebSocket stream → Whisper STT → Groq LLM (stream) → edge-tts → MP3 → Android speaker
```

## Requirements

- Python 3.10+
- NVIDIA GPU recommended (RTX 3080 Ti works great)
- CUDA 11.8+ with matching PyTorch

## Setup

```bash
# 1. Create virtualenv
python -m venv venv
source venv/bin/activate      # Windows: venv\Scripts\activate

# 2. Install dependencies
pip install -r requirements.txt

# 3. Configure
cp .env.example .env
# Edit .env and set your GROQ_API_KEY
```

## Configuration  (`server/.env`)

| Variable | Default | Description |
|---|---|---|
| `GROQ_API_KEY` | *(required)* | Get from console.groq.com |
| `WHISPER_MODEL` | `base` | tiny / base / small / medium / large-v3 |
| `WHISPER_DEVICE` | `cpu` | `cuda` for GPU |
| `WHISPER_COMPUTE` | `int8` | `float16` on GPU, `int8` on CPU |
| `LLM_MODEL` | `llama-3.3-70b-versatile` | Any Groq-supported model |
| `TTS_VOICE` | `en-US-AriaNeural` | Any edge-tts voice |
| `VAD_SILENCE_THRESHOLD_SEC` | `0.6` | Seconds of silence before STT fires |
| `HOST` | `0.0.0.0` | Bind address |
| `PORT` | `8765` | WebSocket port |

## Run

```bash
python main.py
# → Uvicorn listening on ws://0.0.0.0:8765
```

Health check: `http://localhost:8765/health`

## Android side

Add to `local.properties`:

```properties
# Replace with your PC's LAN IP (find with `ipconfig` on Windows)
STREAMING_SERVER_URL=ws://192.168.1.100:8765
```

Then rebuild the app.  When `STREAMING_SERVER_URL` is set the 🎙️ Live button will use the
WebSocket pipeline.  Leave it blank to fall back to on-device SpeechRecognizer.

## WebSocket Protocol

### Client → Server

```json
{ "type": "init",        "session_id": "uuid", "system_prompt": "...", "language": "en-US" }
{ "type": "audio_chunk", "data": "<base64 PCM 16kHz 16-bit mono>" }
{ "type": "interrupt" }
{ "type": "end" }
```

### Server → Client

```json
{ "type": "partial_text", "text": "..." }          // live STT
{ "type": "token",        "text": "..." }          // LLM token stream
{ "type": "audio_chunk",  "data": "<base64 MP3>" } // TTS audio chunk
{ "type": "final_text",   "text": "..." }          // complete AI turn
{ "type": "interrupted" }
{ "type": "error",        "message": "..." }
```

## Latency Profile (expected on 3080 Ti)

| Stage | Latency |
|---|---|
| STT (Whisper base, ~3 s speech) | ~200 ms |
| First LLM token (Groq) | ~100 ms |
| First TTS chunk (edge-tts) | ~300 ms |
| **Total first audio to user** | **~600 ms** |

## Troubleshooting

**`torch.hub.load` fails on first run** – it needs internet to download Silero VAD weights (~3 MB).

**`faster-whisper` CUDA error** – set `WHISPER_DEVICE=cpu` and `WHISPER_COMPUTE=int8` to run on CPU.

**Android can't connect** – make sure both devices are on the same WiFi network and Windows Firewall
allows inbound TCP on port 8765.
