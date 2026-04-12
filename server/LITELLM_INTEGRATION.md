# LiteLLM Integration Guide

Complete guide to integrating LiteLLM (v1.79.3-stable) with the AI Teacher FastAPI backend for per-user API key management and usage tracking.

## Overview

**What is LiteLLM?**

LiteLLM is an OpenAI-compatible proxy that acts as a unified interface to multiple LLM providers (Gemini, Claude, Groq, OpenAI, etc.). It provides:

- **Per-user API key management** — Isolate each user's usage and costs
- **Usage tracking** — Automatic token counting, cost calculation, and logging to PostgreSQL
- **Route optimization** — Direct requests to different models based on cost/performance tier
- **Retry logic** — Automatic fallback between providers
- **Admin dashboard** — View usage, costs, and manage keys (localhost:8006)

**Why integrate LiteLLM?**

1. **Cost attribution** — Know exactly how much each user costs
2. **Usage limits** — Enforce per-user rate limits and quotas
3. **Provider switching** — Seamlessly switch between Gemini, Claude, Groq without app changes
4. **Monitoring** — Real-time usage dashboard and alerts
5. **Security** — Isolate provider API keys from client apps

## Architecture

```
┌─────────────────────┐
│   Android/Web App   │
│  (User's LLM Key)   │
└──────────┬──────────┘
           │ HTTP/gRPC
           │
┌──────────▼──────────┐
│   FastAPI Server    │
│   /chat-stream      │
│   /users/register   │
└──────────┬──────────┘
           │ HTTP (Bearer: user-key)
           │
┌──────────▼──────────────────────┐
│   LiteLLM Proxy                  │
│   (localhost:8005)               │
│   • Request routing              │
│   • Usage tracking               │
│   • PostgreSQL logging           │
└──────────┬──────────────────────┘
           │ API calls
           │
     ┌─────┴─────┬────────┬─────────┐
     │           │        │         │
  ┌──▼──┐   ┌───▼──┐ ┌──▼──┐ ┌───▼────┐
  │Gemini│   │Claude│ │Groq │ │ OpenAI │
  └──────┘   └──────┘ └─────┘ └────────┘
```

**Key Components:**

1. **FastAPI Server:** Routes user requests to LiteLLM proxy with per-user API key
2. **LiteLLM Proxy:** Accepts OpenAI-compatible requests, logs usage to PostgreSQL, routes to providers
3. **PostgreSQL:** Stores per-user API keys, usage logs, cost tracking
4. **litellm_config.yaml:** Model definitions and routing rules
5. **Docker Compose:** Orchestrates PostgreSQL + LiteLLM services

## Setup Instructions

### 1. Start the Docker Stack

In the `litellm/` directory:

```bash
cd server/litellm

# Configure API keys
cp .env.example .env
nano .env  # Fill in your GEMINI_API_KEY, ANTHROPIC_API_KEY, etc.

# Start services
docker-compose up -d

# Wait for PostgreSQL to initialize (10-15 seconds)
sleep 15

# Verify health
docker-compose logs litellm-proxy | grep "started"
curl http://localhost:8005/health
```

Expected output:
```
{
  "status": "healthy",
  "timestamp": "2024-12-20T10:00:00Z"
}
```

### 2. Update Server Configuration

In `.env` (server root):

```bash
USE_LITELLM_PROXY=True
LITELLM_PROXY_URL=http://localhost:8005
LITELLM_MASTER_KEY=sk-1234567890abcdefghijklmnopqrstuvwxyz  # Match docker-compose
LITELLM_ADMIN_URL=http://localhost:8006
```

### 3. Install Dependencies

```bash
pip install -r requirements.txt
```

New packages added:
- `litellm>=1.79.3` — LiteLLM client SDK
- `openai>=1.3.0` — OpenAI-compatible interface
- `psycopg2-binary>=2.9.9` — PostgreSQL driver

### 4. Run Server

```bash
python -m uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
```

## Usage Flows

### A. User Registration (Android App)

```
1. User signs up with Firebase Auth (client-side)
2. Client gets Firebase ID token
3. Client calls POST /users/register
   {
     "userId": "firebase_uid_12345",
     "name": "John Doe",
     "email": "john@school.com",
     "grade": "10",
     "schoolId": "school_5432",
     "schoolName": "Central High"
   }

4. Server creates:
   - users_table/{uid} in Firestore (with plan limits)
   - LiteLLM API key in PostgreSQL
   
5. Server returns:
   {
     "success": true,
     "message": "User registered",
     "litellm_key": "sk-user-firebase_uid_12345-xyz..."
   }

6. Client stores LiteLLM key in secure storage (for LLM calls)
```

### B. Chat with LLM (Android → FastAPI → LiteLLM)

```
1. Client calls POST /chat-stream
   {
     "userId": "firebase_uid_12345",
     "litellm_key": "sk-user-firebase_uid_12345-xyz...",
     "model": "cheaper",
     "messages": [{"role": "user", "content": "What's 2+2?"}]
   }

2. Server router calls litellm_service.stream_litellm(
     messages=...,
     model="cheaper",
     user_litellm_key="sk-user-firebase_uid_12345-xyz..."
   )

3. LiteLLM proxy:
   - Validates key against PostgreSQL
   - Routes request to Gemini API
   - Logs tokens used to PostgreSQL  ← usage tracking
   - Streams response back

4. Server extracts tokens and calls user_service.record_tokens()
   - Updates users_table/{uid}.tokens_today
   - Updates users_table/{uid}.tokens_this_month

5. Client displays response to user
```

### C. Admin Monitoring

```
Admin logs in to localhost:8006 with LiteLLM master key

Can view:
- Total API calls per user
- Token counts (input+output)
- Cost per user (calculated from token usage)
- Usage trends (daily/weekly)
- Models used
- Last activity

Can also use FastAPI admin endpoints:
GET  /admin/api/litellm/usage/{user_id}     — Get user's usage stats
GET  /admin/api/litellm/usage/all            — All users' stats
POST /admin/api/litellm/keys/create?user_id=... — Create new key
GET  /admin/api/litellm/keys/{user_id}       — List user's keys
DELETE /admin/api/litellm/keys/revoke?key=... — Revoke key
GET  /admin/api/litellm/health                — Health check
```

## Model Tiers

Defined in `litellm/litellm_config.yaml`:

### Tier: `power`
**Use:** Premium users, analytical tasks, high-quality content generation
```yaml
Model: Claude 3.5 Sonnet (Anthropic)
Cost:  ~$0.003/1K input, ~$0.015/1K output
Tokens: 200K context, 4K output
```

### Tier: `cheaper`
**Use:** Standard users, balanced quality/cost
```yaml
Model: Gemini 2.0 Flash (Google)
Cost:  ~$0.075/1M input, FREE output
Tokens: 1M context, unlimited output
```

### Tier: `faster`
**Use:** Real-time chat, low latency
```yaml
Model: Gemini 2.0 Flash Lite (Google)
Cost:  ~$0.03/1M input, FREE output
Tokens: 1M context, unlimited output
```

**Routing by Tier:**

```python
# In FastAPI chat endpoint
if user_plan == "power":
    model = "power"  # Claude
elif user_plan == "free":
    model = "faster"  # Lite model for cost savings
else:
    model = "cheaper"  # Default
```

## Per-User API Key Lifecycle

### 1. Key Creation
```bash
POST /admin/api/litellm/keys/create?user_id=firebase_uid_12345&name=John%20Doe

Response:
{
  "key": "sk-user-firebase_uid_12345-abcdef123456...",
  "user_id": "firebase_uid_12345",
  "created_at": "2024-12-20T10:00:00Z"
}
```

Stored in PostgreSQL `api_keys` table:
```
user_id            | key                                | created_at            | is_valid
firebase_uid_12345 | sk-user-firebase_uid_12345-abc... | 2024-12-20 10:00:00Z | true
```

### 2. Key Usage (Automatic)
Every LLM call with user's key logs to PostgreSQL `request_logs`:
```
timestamp          | user_id             | model    | model_name | input_tokens | output_tokens | cost
2024-12-20 10:05   | firebase_uid_12345  | cheaper  | Gemini...  | 125          | 45            | 0.009375
2024-12-20 10:15   | firebase_uid_12345  | power    | Claude...  | 200          | 150           | 0.003500
```

### 3. Usage Aggregation
LiteLLM auto-calculates `usage_logs`:
```
user_id             | total_requests | input_tokens | output_tokens | total_cost
firebase_uid_12345  | 250            | 125000       | 45000         | 12.50
```

### 4. Key Revocation (Admin)
```bash
DELETE /admin/api/litellm/keys/revoke?key=sk-user-firebase_uid_12345-abc...

# Future calls with this key will be rejected
# PostgreSQL marks: is_valid = false
```

## Database Schema

### PostgreSQL — LiteLLM Tables

**api_keys** — Per-user API keys
```sql
CREATE TABLE api_keys (
  key_id SERIAL PRIMARY KEY,
  user_id VARCHAR(255) NOT NULL UNIQUE,  -- Firebase UID
  key VARCHAR(255) NOT NULL UNIQUE,       -- "sk-user-..."
  created_at TIMESTAMP DEFAULT NOW(),
  is_valid BOOLEAN DEFAULT TRUE,
  metadata JSONB                          -- {"name": "John", "tier": "power"}
);
```

**request_logs** — Every API call
```sql
CREATE TABLE request_logs (
  request_id SERIAL PRIMARY KEY,
  key_id INTEGER REFERENCES api_keys(key_id),
  user_id VARCHAR(255),
  model VARCHAR(255),                    -- "gemini-2.0-flash", "claude-3-5-sonnet"
  input_tokens INTEGER,
  output_tokens INTEGER,
  cost DECIMAL(10, 6),
  timestamp TIMESTAMP DEFAULT NOW()
);
```

**usage_logs** — Aggregated per user
```sql
CREATE TABLE usage_logs (
  user_id VARCHAR(255) PRIMARY KEY,
  total_requests INTEGER,
  input_tokens INTEGER,
  output_tokens INTEGER,
  total_cost DECIMAL(10, 2),
  last_request_at TIMESTAMP,
  created_at TIMESTAMP DEFAULT NOW(),
  updated_at TIMESTAMP DEFAULT NOW()
);
```

### Firestore — users_table Fields (Server-Managed)

After user registration, these fields are added to `users_table/{uid}`:

```javascript
{
  userId: "firebase_uid_12345",
  name: "John Doe",
  email: "john@school.com",
  grade: "10",
  
  // Plan
  planId: "free",
  planName: "Free",
  plan_expiry_date: 0,
  
  // Token counters (updated by server after each chat)
  tokens_today: 245,
  tokens_this_month: 5000,
  input_tokens_today: 120,
  output_tokens_today: 125,
  
  // Metadata
  litellm_key: "sk-user-firebase_uid_12345-xyz...",  // Optional
  created_at: 1703071200000,
  updated_at: 1703071200000
}
```

## Admin Endpoints

### List Stats
```
GET /admin/api/stats
Response: {users: 1200, payments: 450, ...}
```

### LiteLLM Key Management
```
POST   /admin/api/litellm/keys/create?user_id=...&name=...
GET    /admin/api/litellm/keys/{user_id}
DELETE /admin/api/litellm/keys/revoke?key=...
```

### Usage Monitoring
```
GET /admin/api/litellm/usage/{user_id}
GET /admin/api/litellm/usage/all
GET /admin/api/litellm/health
```

## Cost Calculation

LiteLLM auto-calculates costs based on model pricing:

**Gemini 2.0 Flash:**
- Input: $0.075 / 1M tokens = $0.000000075 per token
- Output: FREE

**Gemini 2.0 Flash Lite:**
- Input: $0.03 / 1M tokens = $0.00000003 per token
- Output: FREE

**Claude 3.5 Sonnet:**
- Input: $3 / 1K tokens = $0.003 per token
- Output: $15 / 1K tokens = $0.015 per token

**Groq (Free):**
- Input: FREE
- Output: FREE

**Example Calculation:**
```
User called:
  1. Gemini (250 input, 100 output)   = 250 * $0.000000075 = $0.01875
  2. Claude (200 input, 150 output)   = 200 * $0.003 + 150 * $0.015 = $2.85

Total cost for this user: $2.86875
```

## Troubleshooting

### LiteLLM Not Starting
```bash
# Check logs
docker-compose logs litellm-proxy

# Common issues:
# 1. PostgreSQL not ready — wait 15 seconds
# 2. Port 8005 already in use — change in docker-compose.yml
# 3. Config file path wrong — verify litellm_config.yaml exists
```

### Keys Not Being Created
```bash
# Check if LiteLLM is healthy
curl http://localhost:8006/health -H "Authorization: Bearer sk-..."

# Verify database connection
docker-compose exec litellm-postgres psql -U litellm -d litellm_db -c "SELECT COUNT(*) FROM api_keys;"
```

### Usage Not Tracking
```bash
# Verify request logs exist
docker-compose exec litellm-postgres psql -U litellm -d litellm_db -c "SELECT * FROM request_logs LIMIT 5;"

# Check proxy logs for errors
docker-compose logs litellm-proxy | tail -50
```

### Out of Memory
```bash
# Reduce workers in litellm_config.yaml
num_workers: 2

# Restart LiteLLM
docker-compose restart litellm-proxy
```

## Production Deployment

### Recommended Changes

1. **Use Managed PostgreSQL**
   ```yaml
   DATABASE_URL: postgresql://user:pass@cloud-instance.com:5432/litellm_db
   ```

2. **Update LITELLM_MASTER_KEY**
   ```bash
   # Generate secure key
   python -c "import secrets; print('sk-' + secrets.token_hex(32))"
   ```

3. **Configure Firewall**
   - Only expose port 8000 (FastAPI)
   - LiteLLM proxy (8005) is internal-only
   - Admin dashboard (8006) requires VPN/firewall

4. **Add Health Checks**
   ```yaml
   GET /health every 30 seconds
   Restart if unhealthy
   ```

5. **Monitor Costs**
   ```bash
   # Weekly cost report
   SELECT user_id, SUM(total_cost) as weekly_cost
   FROM usage_logs
   WHERE updated_at > NOW() - INTERVAL '7 days'
   GROUP BY user_id
   ORDER BY weekly_cost DESC;
   ```

6. **Set Rate Limits**
   ```yaml
   # In litellm_config.yaml
   rate_limit_calls: 100
   rate_limit_interval: 3600  # per hour
   ```

## References

- [LiteLLM Docs](https://docs.litellm.ai)
- [LiteLLM GitHub](https://github.com/BerriAI/litellm)
- [Proxy Deployment](https://docs.litellm.ai/docs/proxy/deploy)
- [Usage Tracking](https://docs.litellm.ai/docs/proxy/api_with_fallbacks)
