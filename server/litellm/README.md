# LiteLLM Proxy Setup

This directory contains the Docker Compose stack for running LiteLLM proxy with PostgreSQL for per-user API key management and usage tracking.

## Architecture

```
FastAPI Server (localhost:8000)
         ↓
    LiteLLM Proxy (localhost:8005 — exposed)
         ↓
    PostgreSQL (localhost:5432)
         ↓
    Provider APIs (Gemini, Claude, Groq, OpenAI, etc.)
```

## Quick Start

### 1. Configure API Keys

Edit `.env` file with your provider API keys:

```bash
cp .env.example .env  # Already provided
nano .env
```

Required keys:
- `GEMINI_API_KEY` — Google Gemini / Vertex AI
- `ANTHROPIC_API_KEY` — Claude models
- `GROQ_API_KEY` — Groq (free tier)
- `OPENAI_API_KEY` — GPT-4/GPT-4o (optional)

### 2. Start Services

```bash
docker-compose up -d
```

Services started:
- **PostgreSQL:** `localhost:5432` (auto-initializes schema)
- **LiteLLM Proxy:** `localhost:8005` (API endpoint)
- **LiteLLM Admin:** `localhost:8006` (key management UI)

### 3. Health Check

```bash
# Test proxy health
curl http://localhost:8005/health

# Test model availability
curl -X POST http://localhost:8005/v1/chat/completions \
  -H "Authorization: Bearer sk-1234567890abcdefghijklmnopqrstuvwxyz" \
  -H "Content-Type: application/json" \
  -d '{"model": "cheaper", "messages": [{"role": "user", "content": "hi"}]}'
```

## Model Aliases

### Tier-Based Routing
- **`power`** → Claude 3.5 Sonnet (best quality, ~$0.003/1K input, ~$0.015/1K output)
- **`cheaper`** → Gemini 2.0 Flash (good quality, ~$0.075/1M input, free output)
- **`faster`** → Gemini 2.0 Flash Lite (lower latency, ~$0.03/1M input, free output)

### Direct Model Names
- **Gemini:** `gemini-2.0-flash`, `gemini-1.5-pro`, `gemini-2.0-flash-lite`
- **Claude:** `claude-3-5-sonnet`
- **Groq:** `groq-mixtral`, `groq-llama`
- **OpenAI:** `gpt-4`, `gpt-4o`, `gpt-4o-mini`

## Per-User API Key Management

### Create Key for User
```bash
curl -X POST http://localhost:8006/key/new \
  -H "Authorization: Bearer sk-1234567890abcdefghijklmnopqrstuvwxyz" \
  -d '{
    "user_id": "google_user_12345",
    "metadata": {"name": "John Doe", "tier": "power"}
  }'

# Response:
# {
#   "key": "sk-user-12345-abcdef...",
#   "user_id": "google_user_12345",
#   "created_at": "2024-12-20T10:00:00Z"
# }
```

### Get User Usage Stats
```bash
curl http://localhost:8006/usage \
  -H "Authorization: Bearer sk-1234567890abcdefghijklmnopqrstuvwxyz" \
  -d '{"user_id": "google_user_12345"}'

# Response:
# {
#   "user_id": "google_user_12345",
#   "total_requests": 250,
#   "total_input_tokens": 125000,
#   "total_output_tokens": 45000,
#   "total_cost": 12.50,
#   "daily_usage": {...},
#   "models_used": ["power", "cheaper"]
# }
```

### Revoke Key
```bash
curl -X POST http://localhost:8006/key/delete \
  -H "Authorization: Bearer sk-1234567890abcdefghijklmnopqrstuvwxyz" \
  -d '{"key": "sk-user-12345-abcdef..."}'
```

## Server Integration

### Update FastAPI Config

In `app/core/config.py`:
```python
LITELLM_PROXY_URL = "http://localhost:8005"  # Local dev
# or for Docker: "http://litellm-proxy:8001"  # Production

LITELLM_MASTER_KEY = "sk-1234567890abcdefghijklmnopqrstuvwxyz"
LITELLM_ADMIN_URL = "http://localhost:8006"
```

### Update LLM Service

In `app/services/llm_service.py`:
```python
from litellm import completion

async def call_llm(prompt, model="cheaper", user_id=None):
    response = await completion(
        model=model,
        messages=[{"role": "user", "content": prompt}],
        api_base=LITELLM_PROXY_URL,
        api_key=user_litellm_key,  # Per-user key created in LiteLLM
        timeout=120
    )
    return response
```

### Pass User Keys in Chat Requests

Each user gets their own LiteLLM API key (created once during registration):

```json
POST /chat-stream
{
  "user_id": "google_user_12345",
  "model": "cheaper",
  "litellm_key": "sk-user-12345-abcdef...",
  "messages": [...]
}
```

## Monitoring & Logs

### View Proxy Logs
```bash
docker-compose logs -f litellm-proxy
```

### PostgreSQL Queries

Connect to DB:
```bash
docker-compose exec litellm-postgres psql -U litellm -d litellm_db
```

View API keys:
```sql
SELECT * FROM api_keys;
SELECT user_id, key, created_at, is_valid FROM api_keys WHERE user_id = 'google_user_12345';
```

View usage logs:
```sql
SELECT model, total_input_tokens, total_output_tokens, total_cost FROM usage_logs WHERE user_id = 'google_user_12345';
```

View request logs:
```sql
SELECT timestamp, user_id, model, input_tokens, output_tokens FROM request_logs LIMIT 100;
```

## Troubleshooting

### LiteLLM Won't Start
```bash
# Check PostgreSQL is healthy
docker-compose ps

# View full logs
docker-compose logs litellm-proxy
```

### Connection Refused (8005)
- Wait 10-15 seconds for PostgreSQL to initialize
- Check: `docker-compose logs litellm-postgres`

### Out of Memory
Reduce `num_workers` in `litellm_config.yaml` and docker-compose.yml

### Model Not Available
- Verify API key in `.env` is valid
- Check: `curl http://localhost:8005/models`

## Scaling

### Production Deployment

```yaml
# Use environment-specific docker-compose
docker-compose -f docker-compose.prod.yml up -d
```

With:
- Multi-replica LiteLLM behind load balancer
- CloudSQL / managed PostgreSQL
- Network policies for API key isolation
- Prometheus/Grafana monitoring

### Environment Variables

```bash
# Use secrets manager instead of .env
export GEMINI_API_KEY=$(gcloud secrets versions access latest --secret=gemini-key)
docker-compose up -d
```

## Cost Optimization

### Model Selection by Use Case
- **Content generation:** Use `cheaper` (Gemini 2.0 Flash) — free output
- **Analysis/reasoning:** Use `power` (Claude 3.5) — highest quality
- **Real-time/chat:** Use `faster` (Flash Lite) — lowest latency
- **Fallback chain:** Set `retries` in litellm_config.yaml

### Rate Limiting by Tier

```yaml
# In litellm_config.yaml
rate_limits:
  free: 100_calls_per_hour
  power: 1000_calls_per_hour
  premium: unlimited
```

## Reference

- [LiteLLM Docs](https://docs.litellm.ai)
- [LiteLLM GitHub](https://github.com/BerriAI/litellm)
- [PostgreSQL LiteLLM Schema](https://docs.litellm.ai/docs/proxy/deploy)
