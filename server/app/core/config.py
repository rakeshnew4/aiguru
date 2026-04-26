from pydantic_settings import BaseSettings
from typing import Literal


class ModelConfig:
    """Configuration for a specific model tier."""
    def __init__(
        self,
        provider: str,
        model_id: str,
        temperature: float = 0.7,
        max_tokens: int = 4096,
        supports_images: bool = False,
    ):
        self.provider = provider
        self.model_id = model_id
        self.temperature = temperature
        self.max_tokens = max_tokens
        self.supports_images = supports_images


class Settings(BaseSettings):
    # ── Legacy settings (for backward compatibility) ──────────────────────────
    GEMINI_API_KEY: str = ""
    MODEL_ID: str = "gemini-3.1-flash-lite-preview"
    TEMPERATURE: float = 0.7
    REDIS_URL: str = "redis://localhost:6379"
    USE_AGENT: bool = False
    
    
    # ── Model Tier Configuration ──────────────────────────────────────────────
    # POWER: Most capable models for premium users
    POWER_PROVIDER: str = "gemini"
    POWER_MODEL_ID: str = "gemini-3.1-flash-lite-preview"
    POWER_TEMPERATURE: float = 0.7
    POWER_MAX_TOKENS: int = 18192
    
    # CHEAPER: Balanced cost/performance for standard users
    CHEAPER_PROVIDER: str = "gemini"  # bedrock, gemini, groq
    CHEAPER_MODEL_ID: str = "gemini-3.1-flash-lite-preview"
    CHEAPER_TEMPERATURE: float = 0.7
    CHEAPER_MAX_TOKENS: int = 14096
    
    # FASTER: Quickest tier for small structured tasks (intent classifier, BB planner, grading)
    FASTER_PROVIDER: str = "gemini"
    FASTER_MODEL_ID: str = "gemini-3.1-flash-lite-preview"
    FASTER_TEMPERATURE: float = 0.3
    FASTER_MAX_TOKENS: int = 20000
    
    # ── Provider API Keys ──────────────────────────────────────────────────────
    GROQ_API_KEY: str = ""

    # AWS Bedrock Configuration
    AWS_ACCESS_KEY_ID: str = ""
    AWS_SECRET_ACCESS_KEY: str = ""
    AWS_REGION: str = "us-east-1"
    AWS_BEARER_TOKEN_BEDROCK: str = ""  # Alternative auth method (if not using access keys)

    # ── Payments Configuration ────────────────────────────────────────────────
    RAZORPAY_KEY_ID: str = ""
    RAZORPAY_KEY_SECRET: str = ""
    RAZORPAY_WEBHOOK_SECRET: str = ""
    SERVER_API_KEY: str = ""
    FIREBASE_SERVICE_ACCOUNT: str = ""
    # Comma-separated list of allowed CORS origins. Empty = own server IP only.
    ALLOWED_ORIGINS: str = ""

    # ── TTS (Text-to-Speech) ──────────────────────────────────────────
    # Google Cloud TTS now uses OAuth2 Service Account authentication (see main.py).
    # The GOOGLE_APPLICATION_CREDENTIALS env var points to firebase_serviceaccount.json.
    # GOOGLE_TTS_API_KEY is deprecated and no longer used (left for backward compatibility).
    GOOGLE_TTS_API_KEY: str = ""  # DEPRECATED — use Service Account instead
    # Optional: ElevenLabs or OpenAI TTS fallback
    ELEVENLABS_API_KEY: str = ""
    OPENAI_TTS_API_KEY: str = ""

    # ── LiteLLM Proxy Configuration ──────────────────────────────────────────
    # URL for LiteLLM proxy (per-user API key management)
    LITELLM_PROXY_URL: str = "http://localhost:8005"
    LITELLM_MASTER_KEY: str = "sk-1234567890abcdefghijklmnopqrstuvwxyz"
    LITELLM_ADMIN_URL: str = "http://localhost:8006"
    # Enable LiteLLM proxy instead of direct provider calls
    USE_LITELLM_PROXY: bool = True
    # PostgreSQL database URL for LiteLLM (same as docker-compose)
    LITELLM_DATABASE_URL: str = "postgresql://litellm:litellm_secure_password_2024@localhost:5432/litellm_db"

    # ── Authentication & Security ────────────────────────────────────────────
    # Set to True to enforce Firebase ID token authentication (required in production)
    # Set to False only for local development/testing without tokens
    AUTH_REQUIRED: bool = True

    # ── YouTube Extractor (BB mode video enrichment) ──────────────────────────
    YOUTUBE_API_KEY: str = ""           # Google Cloud Console → YouTube Data API v3
    ES_HOST: str = "http://localhost:9200"
    YT_SCORE_THRESHOLD: float = 0.65    # Min cosine similarity to attach a clip
    YT_ENRICHMENT_TIMEOUT: float = 2.5  # Seconds; clip skipped if exceeded


    class Config:
        env_file = ".env"
        extra = "ignore"  # ignore unrelated .env keys
    
    def get_model_config(self, tier: Literal["power", "cheaper", "faster"]) -> ModelConfig:
        """Get model configuration for a specific tier."""
        if tier == "power":
            return ModelConfig(
                provider=self.POWER_PROVIDER,
                model_id=self.POWER_MODEL_ID,
                temperature=self.POWER_TEMPERATURE,
                max_tokens=self.POWER_MAX_TOKENS,
                supports_images=self.POWER_PROVIDER in [ "bedrock"],
            )
        elif tier == "cheaper":
            return ModelConfig(
                provider=self.CHEAPER_PROVIDER,
                model_id=self.CHEAPER_MODEL_ID,
                temperature=self.CHEAPER_TEMPERATURE,
                max_tokens=self.CHEAPER_MAX_TOKENS,
                supports_images=self.CHEAPER_PROVIDER in ["gemini", "bedrock"],
            )
        else:  # faster
            return ModelConfig(
                provider=self.FASTER_PROVIDER,
                model_id=self.FASTER_MODEL_ID,
                temperature=self.FASTER_TEMPERATURE,
                max_tokens=self.FASTER_MAX_TOKENS,
                supports_images=self.FASTER_PROVIDER in ["gemini", "bedrock"],
            )


settings = Settings()
