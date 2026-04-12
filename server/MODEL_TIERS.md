# 3-Tier Model System

## Overview

The LLM service now supports **three model tiers** for different use cases and user plans:

### 🚀 **POWER** (Premium/Pro Users)
- **Use Case**: Most capable models for premium users
- **Default Provider**: AWS Bedrock (Claude Sonnet 3.7)
- **Config**:
  - Model: `us.anthropic.claude-3-7-sonnet-20250219-v1:0`
  - Temperature: 0.7
  - Max Tokens: 8192
  - Supports Images: ✅

### 💰 **CHEAPER** (Free Users)
- **Use Case**: Balanced cost/performance for standard users
- **Default Provider**: Google Gemini
- **Config**:
  - Model: `gemini-2.0-flash-exp`
  - Temperature: 0.7
  - Max Tokens: 4096
  - Supports Images: ✅

### ⚡ **FASTER** (Simple Tasks)
- **Use Case**: Quick operations like formatting, image search
- **Default Provider**: Groq (Llama 3.3 70B)
- **Config**:
  - Model: `llama-3.3-70b-versatile`
  - Temperature: 0.5
  - Max Tokens: 2048
  - Supports Images: ❌

## Usage

### In Code

```python
from app.services.llm_service import generate_response

# Use specific tier
response = generate_response(
    prompt="Explain photosynthesis",
    images=["data:image/jpeg;base64,..."],
    tier="power"  # or "cheaper" or "faster"
)

print(response["text"])
print(response["tokens"])
print(response["provider"])  # "bedrock", "gemini", or "groq"
print(response["model"])     # actual model ID used
```

### Via API

```bash
curl -X POST http://localhost:8003/chat-stream \
  -H "Content-Type: application/json" \
  -d '{
    "question": "Explain quadratic equations",
    "page_id": "math_chapter_1",
    "user_plan": "premium"
  }'
```

**User Plan → Tier Mapping:**
- `free` → `cheaper` (Gemini Flash)
- `premium` → `power` (Claude Sonnet)
- `pro` → `power` (Claude Sonnet)

## Configuration

All model tiers are configured in `.env`:

```env
# POWER tier (premium users)
POWER_PROVIDER=bedrock
POWER_MODEL_ID=us.anthropic.claude-3-7-sonnet-20250219-v1:0
POWER_TEMPERATURE=0.7
POWER_MAX_TOKENS=8192

# CHEAPER tier (free users)
CHEAPER_PROVIDER=gemini
CHEAPER_MODEL_ID=gemini-2.0-flash-exp
CHEAPER_TEMPERATURE=0.7
CHEAPER_MAX_TOKENS=4096

# FASTER tier (simple tasks)
FASTER_PROVIDER=groq
FASTER_MODEL_ID=llama-3.3-70b-versatile
FASTER_TEMPERATURE=0.5
FASTER_MAX_TOKENS=2048

# Provider API Keys
GEMINI_API_KEY=your_key_here
GROQ_API_KEY=your_key_here
AWS_ACCESS_KEY_ID=your_key_here
AWS_SECRET_ACCESS_KEY=your_secret_here
AWS_REGION=us-east-1
```

## Supported Providers

### 1. **Google Gemini**
- Models: `gemini-2.5-flash`, `gemini-2.0-flash-exp`, `gemini-1.5-pro`
- Image Support: ✅
- API Key: `GEMINI_API_KEY`

### 2. **Groq**
- Models: `llama-3.3-70b-versatile`, `mixtral-8x7b-32768`
- Image Support: ❌
- API Key: `GROQ_API_KEY`
- Best for: Fast inference on text-only tasks

### 3. **AWS Bedrock**
- Models: `claude-3-7-sonnet`, `claude-3-5-sonnet`, `claude-3-opus`
- Image Support: ✅
- Config: `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_REGION`
- Best for: Enterprise deployments, advanced reasoning

## Automatic Fallback

If a provider fails, the system **automatically falls back to Groq**:

```
Primary Provider Fails → Groq Fallback → Return Response
```

Example log:
```
ERROR: bedrock call failed: InvalidAccessKeyId
WARNING: Falling back to Groq...
INFO: LLM fallback | model=llama-3.3-70b-versatile | provider=groq
```

## Where Each Tier is Used

| Component | Tier | Reason |
|-----------|------|--------|
| Main chat endpoint | `power`/`cheaper` | Based on user plan |
| Image title search | `faster` | Simple JSON formatting task |
| System prompts | `cheaper` | Default for utilities |

## Migration from Old System

The old `USE_AGENT` flag is maintained for backward compatibility:

```python
# Old way (still works)
USE_AGENT=false
MODEL_ID=gemini-2.5-flash

# New way (recommended)
CHEAPER_PROVIDER=gemini
CHEAPER_MODEL_ID=gemini-2.5-flash
```

## Testing Different Providers

```python
# Test each provider
tiers = ["power", "cheaper", "faster"]
prompt = "What is 2+2?"

for tier in tiers:
    response = generate_response(prompt, tier=tier)
    print(f"{tier}: {response['provider']} - {response['model']}")
```

## Cost Optimization Tips

1. **Use FASTER tier** for:
   - JSON formatting
   - Simple string transformations
   - Image title matching
   - Basic completions

2. **Use CHEAPER tier** for:
   - Free user queries
   - Standard educational content
   - General Q&A

3. **Use POWER tier** for:
   - Premium user queries
   - Complex reasoning
   - Multimodal tasks (vision + text)
   - Long-form content generation

## Troubleshooting

### Provider not working?

Check logs for specific error messages:
```bash
tail -f server.log | grep "LLM call"
```

### Want to change a tier's provider?

Just update `.env`:
```env
# Switch POWER from Bedrock to Gemini
POWER_PROVIDER=gemini
POWER_MODEL_ID=gemini-2.0-flash-exp
```

Restart server to apply changes.
