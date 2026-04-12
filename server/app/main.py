import os
import sys
import types

# ── Bootstrap: register this directory as the 'app' package ──────────────────
# Allows `from app.X.Y import Z` imports when uvicorn is invoked as
# `uvicorn main:app` from inside this directory (cross-platform: no symlinks).
_HERE = os.path.dirname(os.path.abspath(__file__))
if _HERE not in sys.path:
    sys.path.insert(0, _HERE)

if "app" not in sys.modules:
    _app_pkg = types.ModuleType("app")
    _app_pkg.__path__ = [_HERE]           # type: ignore[attr-defined]
    _app_pkg.__package__ = "app"
    _app_pkg.__spec__ = None              # type: ignore[attr-defined]
    sys.modules["app"] = _app_pkg


from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from fastapi.responses import FileResponse

from app.api.chat import router as chat_router
from app.api.payments import router as payments_router
from app.api.library import router as library_router
from app.api.quiz import router as quiz_router
from app.api.analyze_image import router as analyze_image_router
from app.api.tts import router as tts_router
from app.api.admin import router as admin_router
from app.api.users import router as users_router
from app.core.logger import get_logger

logger = get_logger(__name__)

app = FastAPI(
    title="AI Teacher Backend",
    description="Production-grade AI backend — cache-enabled, structured, streamable.",
    version="2.0.0",
)

# ── CORS ──────────────────────────────────────────────────────────────────────
_allowed_origins_env = os.getenv("ALLOWED_ORIGINS", "")
_allowed_origins: list[str] = (
    [o.strip() for o in _allowed_origins_env.split(",") if o.strip()]
    if _allowed_origins_env
    else ["http://108.181.187.227:8003"]  # default: own server only
)
app.add_middleware(
    CORSMiddleware,
    allow_origins=_allowed_origins,
    allow_credentials=True,
    allow_methods=["GET", "POST", "PUT", "DELETE", "OPTIONS"],
    allow_headers=["Authorization", "Content-Type", "X-Request-ID"],
)

# ── Routers ───────────────────────────────────────────────────────────────────
app.include_router(chat_router)
app.include_router(payments_router)
app.include_router(library_router)
app.include_router(quiz_router)
app.include_router(analyze_image_router)
app.include_router(tts_router)
app.include_router(admin_router)
app.include_router(users_router)

# ── Static files & Admin portal ───────────────────────────────────────────────
_static_dir = os.path.join(os.path.dirname(__file__), "static")
if os.path.isdir(_static_dir):
    app.mount("/static", StaticFiles(directory=_static_dir), name="static")

@app.get("/admin", include_in_schema=False)
async def admin_portal():
    return FileResponse(os.path.join(os.path.dirname(__file__), "static", "admin", "index.html"))

@app.get("/health")
async def health():
    return {"status": "ok"}

# ── Run ───────────────────────────────────────────────────────────────────────
# uvicorn app.main:app --reload --port 8003 --host 0.0.0.0


