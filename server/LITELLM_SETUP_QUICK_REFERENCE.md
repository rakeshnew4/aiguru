# LiteLLM Setup — Quick Reference

## 📋 What Was Created

### Docker Infrastructure
- ✅ `litellm/docker-compose.yml` — PostgreSQL (5432) + LiteLLM proxy (8005, 8006)
- ✅ `litellm/litellm_config.yaml` — Model routing (power/cheaper/faster tiers)
- ✅ `litellm/.env` — Provider API keys (Gemini, Claude, Groq, OpenAI)
- ✅ `litellm/README.md` — Detailed setup + troubleshooting

### Server Integration
- ✅ `app/services/litellm_service.py` — Per-user key management + usage monitoring
- ✅ `app/api/admin.py` → Added 6 new endpoints for LiteLLM key/usage management
- ✅ `app/api/users.py` → Now creates LiteLLM key on user registration
- ✅ `app/core/config.py` → Added LITELLM_* configuration settings
- ✅ `requirements.txt` → Added litellm, openai, psycopg2-binary

### Documentation
- ✅ `.env.example` → Updated with LiteLLM configuration
- ✅ `LITELLM_INTEGRATION.md` → Complete integration guide (flows, schemas, troubleshooting)

## 🚀 Quick Start (5 minutes)

### Step 1: Configure LiteLLM Variables
```bash
cd server/litellm
cp .env.example .env
nano .env

# Fill in at minimum:
GEMINI_API_KEY=your-key-here
ANTHROPIC_API_KEY=your-key-here
LITELLM_MASTER_KEY=sk-1234567890abcdefghijklmnopqrstuvwxyz
```

### Step 2: Start Docker Compose
```bash
docker-compose up -d
sleep 15  # Wait for PostgreSQL initialization
docker-compose logs litellm-proxy | tail -5  # Verify started
```

### Step 3: Configure FastAPI Server
In `server/.env`:
```bash
USE_LITELLM_PROXY=True
LITELLM_PROXY_URL=http://localhost:8005
LITELLM_MASTER_KEY=sk-1234567890abcdefghijklmnopqrstuvwxyz  # Match docker-compose
LITELLM_ADMIN_URL=http://localhost:8006
```

### Step 4: Install Python Dependencies
```bash
cd ..
pip install -r requirements.txt
```

### Step 5: Start FastAPI Server
```bash
python -m uvicorn app.main:app --port 8000 --reload
```

### Step 6: Test Registration
```bash
curl -X POST http://localhost:8000/users/register \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "test_user_123",
    "name": "Test User",
    "email": "test@example.com",
    "grade": "10"
  }'

# Response should include:
# {
#   "success": true,
#   "litellm_key": "sk-user-test_user_123-..."
# }
```

## 📊 Monitoring

### Admin Portal
```
Open: http://localhost:8006
Login: (use LITELLM_MASTER_KEY from docker-compose)
View: API keys, usage stats, models, costs
```

### Admin API Endpoints

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/admin/api/litellm/keys/create?user_id=...` | POST | Create user API key |
| `/admin/api/litellm/keys/{user_id}` | GET | List user's keys |
| `/admin/api/litellm/keys/revoke?key=...` | DELETE | Revoke key |
| `/admin/api/litellm/usage/{user_id}` | GET | User's usage stats |
| `/admin/api/litellm/usage/all` | GET | All users' stats |
| `/admin/api/litellm/health` | GET | Proxy health check |

### Example: View User Costs
```bash
curl http://localhost:8000/admin/api/litellm/usage/test_user_123 \
  -H "Authorization: Basic admin:admin123"

# Response:
# {
#   "user_id": "test_user_123",
#   "total_requests": 5,
#   "total_input_tokens": 625,
#   "total_output_tokens": 225,
#   "total_cost": 0.05,
#   "models_used": ["cheaper", "power"]
# }
```

### Query PostgreSQL Directly
```bash
# Connect to database
docker-compose exec litellm-postgres psql -U litellm -d litellm_db

# View all users and their costs
SELECT user_id, total_requests, total_cost FROM usage_logs ORDER BY total_cost DESC;

# View recent API keys created
SELECT user_id, key, created_at FROM api_keys LIMIT 10;

# View request logs
SELECT user_id, model, input_tokens, output_tokens, cost FROM request_logs LIMIT 20;
```

## 🔄 User Journey

```
1. Android App: User signs up with Firebase Auth
2. Android App: POST /users/register
   └─→ Returns: litellm_key = "sk-user-firebase_uid-..."
3. Android App: Stores litellm_key in secure storage
4. Android App: POST /chat-stream with litellm_key
   └─→ Server routes to LiteLLM proxy
   └─→ LiteLLM logs usage to PostgreSQL
5. Admin: Views user cost in /admin/api/litellm/usage/{user_id}
   └─→ Can see: tokens used, cost, models used
```

## 🎯 Model Routing Strategy

**Current Setup:**
- `power` → Claude 3.5 Sonnet (best quality, highest cost) — Premium users
- `cheaper` → Gemini 2.0 Flash (good quality, FREE output) — Standard users
- `faster` → Gemini 2.0 Flash Lite (low latency, FREE output) — Budget users

**In chat endpoint, route based on user's plan:**
```python
if user_plan == "premium":
    model = "power"
elif user_plan == "free":
    model = "faster"  # Cost optimization for free users
else:
    model = "cheaper"
```

## 🔐 Security Checklist

- [ ] Change LITELLM_MASTER_KEY in docker-compose.yml (don't use default)
- [ ] Change ADMIN_PASSWORD in server/.env (don't use admin123)
- [ ] Update PostgreSQL password (don't use litellm_secure_password_2024)
- [ ] Restrict access to /admin endpoints (firewall/VPN)
- [ ] Use HTTPS in production (nginx reverse proxy)
- [ ] Store API keys securely (don't commit them!)
- [ ] Set up regular backups of PostgreSQL
- [ ] Monitor LiteLLM logs for errors/abuse

## ⚠️ Troubleshooting

### Docker won't start
```bash
# Check ports are free
lsof -i :5432
lsof -i :8005
lsof -i :8006

# Remove old containers/volumes if needed
docker-compose down -v
docker-compose up -d
```

### LiteLLM proxy unhealthy
```bash
# Check logs
docker-compose logs litellm-proxy --tail=50

# Common issues:
# - PostgreSQL not ready — wait 15-20 seconds
# - Missing API keys in .env — add them
# - Config file error — validate YAML syntax
```

### Users not getting API keys
```bash
# Check if USE_LITELLM_PROXY=True in server/.env
# Check if LiteLLM proxy is running
curl http://localhost:8006/health -H "Authorization: Bearer sk-..."

# See if key creation tried in logs
docker-compose logs litellm-proxy | grep "key"
```

### High memory usage
```bash
# Reduce number of workers in litellm_config.yaml
num_workers: 2  # Was 4

# Restart
docker-compose restart litellm-proxy
```

## 📚 Full Documentation

See **[LITELLM_INTEGRATION.md](LITELLM_INTEGRATION.md)** for:
- Complete architecture diagram
- Detailed usage flows
- Database schema
- Production deployment
- Cost calculation examples
- Advanced monitoring

## 📞 Next Steps

1. **Configure API keys** — Fill in all provider keys in `litellm/.env`
2. **Start services** — Run `docker-compose up -d`
3. **Test registration** — Create a test user and verify key creation
4. **Update Android app** — Make sure it sends litellm_key in chat requests
5. **Set up monitoring** — Create admin dashboard widgets for usage alerts

## 🎓 Key Architecture Points

- **Per-user keys** stored in PostgreSQL by LiteLLM, returned to client
- **Usage tracked automatically** by LiteLLM proxy on every request
- **Cost calculated** from token counts × provider pricing
- **Admin endpoints** in FastAPI for key management and reporting
- **Docker Compose** makes local dev easy; use managed PostgreSQL + K8s in production

---

**Status:** ✅ LiteLLM setup complete. Ready to deploy.
