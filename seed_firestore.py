#!/usr/bin/env python3
"""
seed_firestore.py
-----------------
Populates Firestore with all bootstrap data for AI Guru.

Collections written (all at root level):
  subjects/          – subject catalogue
  chapters/          – per-subject chapters
  plans/             – subscription plan definitions
  offers/            – promotional banner cards
  updates/           – app version / maintenance config (doc: app_config)
  notifications/     – admin broadcast notifications
  admin_config/      – server routing + global limits (doc: global)

Run once (idempotent – uses set() so safe to re-run):

    python seed_firestore.py

Service-account JSON is resolved in this order:
  1. FIREBASE_SERVICE_ACCOUNT env var
  2. firebase_serviceaccount.json  (relative to repo root)
"""

import os
import sys
from pathlib import Path
from datetime import datetime, timezone

SCRIPT_DIR = Path(__file__).parent
sys.path.insert(0, str(SCRIPT_DIR / "fastapi server"))

import firebase_admin
from firebase_admin import credentials, firestore

# ── Credentials ───────────────────────────────────────────────────────────────
SA_PATH = os.environ.get(
    "FIREBASE_SERVICE_ACCOUNT",
    str(SCRIPT_DIR  /"firebase_serviceaccount.json"),
)

if not os.path.exists(SA_PATH):
    print(f"ERROR: Service-account file not found at:\n  {SA_PATH}")
    sys.exit(1)

cred = credentials.Certificate(SA_PATH)
firebase_admin.initialize_app(cred)
db = firestore.client()

# ══════════════════════════════════════════════════════════════════════════════
# SEED DATA
# ══════════════════════════════════════════════════════════════════════════════

# ── subjects/ ─────────────────────────────────────────────────────────────────
SUBJECTS = [
    {
        "id": "math_9th",
        "name": "Mathematics",
        "grade": "9th",
        "icon_url": "",
        "description": "NCERT 9th standard mathematics",
        "chapter_count": 15,
        "display_order": 1,
    },
    {
        "id": "science_9th",
        "name": "Science",
        "grade": "9th",
        "icon_url": "",
        "description": "NCERT 9th standard science",
        "chapter_count": 15,
        "display_order": 2,
    },
    {
        "id": "english_9th",
        "name": "English",
        "grade": "9th",
        "icon_url": "",
        "description": "NCERT 9th standard English",
        "chapter_count": 10,
        "display_order": 3,
    },
    {
        "id": "social_9th",
        "name": "Social Science",
        "grade": "9th",
        "icon_url": "",
        "description": "NCERT 9th standard Social Science (History, Geography, Civics, Economics)",
        "chapter_count": 20,
        "display_order": 4,
    },
    {
        "id": "hindi_9th",
        "name": "Hindi",
        "grade": "9th",
        "icon_url": "",
        "description": "NCERT 9th standard Hindi (Kshitij & Kritika)",
        "chapter_count": 17,
        "display_order": 5,
    },
]

# ── chapters/ ─────────────────────────────────────────────────────────────────
CHAPTERS = [
    # ─ Mathematics 9th ─
    {"id": "math9_ch1",  "subject_id": "math_9th", "title": "Number Systems",               "order": 1,  "topic_tags": ["numbers", "rational", "irrational", "real numbers"],           "estimated_minutes": 30},
    {"id": "math9_ch2",  "subject_id": "math_9th", "title": "Polynomials",                  "order": 2,  "topic_tags": ["algebra", "polynomials", "factorisation"],                     "estimated_minutes": 35},
    {"id": "math9_ch3",  "subject_id": "math_9th", "title": "Coordinate Geometry",          "order": 3,  "topic_tags": ["geometry", "coordinates", "cartesian"],                        "estimated_minutes": 25},
    {"id": "math9_ch4",  "subject_id": "math_9th", "title": "Linear Equations in Two Variables", "order": 4, "topic_tags": ["algebra", "linear equations", "graphing"],              "estimated_minutes": 30},
    {"id": "math9_ch5",  "subject_id": "math_9th", "title": "Introduction to Euclid's Geometry", "order": 5, "topic_tags": ["geometry", "euclid", "axioms", "theorems"],            "estimated_minutes": 25},
    {"id": "math9_ch6",  "subject_id": "math_9th", "title": "Lines and Angles",             "order": 6,  "topic_tags": ["geometry", "lines", "angles", "parallel lines"],              "estimated_minutes": 30},
    {"id": "math9_ch7",  "subject_id": "math_9th", "title": "Triangles",                    "order": 7,  "topic_tags": ["geometry", "triangles", "congruence", "similarity"],          "estimated_minutes": 40},
    {"id": "math9_ch8",  "subject_id": "math_9th", "title": "Quadrilaterals",               "order": 8,  "topic_tags": ["geometry", "quadrilaterals", "parallelogram"],                "estimated_minutes": 30},
    {"id": "math9_ch9",  "subject_id": "math_9th", "title": "Circles",                      "order": 9,  "topic_tags": ["geometry", "circles", "chords", "arcs"],                      "estimated_minutes": 35},
    {"id": "math9_ch10", "subject_id": "math_9th", "title": "Heron's Formula",              "order": 10, "topic_tags": ["mensuration", "area", "heron"],                               "estimated_minutes": 20},
    {"id": "math9_ch11", "subject_id": "math_9th", "title": "Surface Areas and Volumes",    "order": 11, "topic_tags": ["mensuration", "surface area", "volume"],                      "estimated_minutes": 45},
    {"id": "math9_ch12", "subject_id": "math_9th", "title": "Statistics",                   "order": 12, "topic_tags": ["statistics", "mean", "median", "mode"],                       "estimated_minutes": 35},
    # ─ Science 9th ─
    {"id": "sci9_ch1",  "subject_id": "science_9th", "title": "Matter in Our Surroundings",    "order": 1,  "topic_tags": ["physics", "matter", "states"],                             "estimated_minutes": 30},
    {"id": "sci9_ch2",  "subject_id": "science_9th", "title": "Is Matter Around Us Pure?",     "order": 2,  "topic_tags": ["chemistry", "mixtures", "solutions"],                      "estimated_minutes": 35},
    {"id": "sci9_ch3",  "subject_id": "science_9th", "title": "Atoms and Molecules",           "order": 3,  "topic_tags": ["chemistry", "atoms", "molecules", "mole"],                 "estimated_minutes": 40},
    {"id": "sci9_ch4",  "subject_id": "science_9th", "title": "Structure of the Atom",         "order": 4,  "topic_tags": ["chemistry", "atomic structure", "electrons"],               "estimated_minutes": 35},
    {"id": "sci9_ch5",  "subject_id": "science_9th", "title": "The Fundamental Unit of Life",  "order": 5,  "topic_tags": ["biology", "cell", "organelles"],                            "estimated_minutes": 35},
    {"id": "sci9_ch6",  "subject_id": "science_9th", "title": "Tissues",                       "order": 6,  "topic_tags": ["biology", "tissues", "plant", "animal"],                   "estimated_minutes": 30},
    {"id": "sci9_ch7",  "subject_id": "science_9th", "title": "Motion",                        "order": 7,  "topic_tags": ["physics", "motion", "velocity", "acceleration"],             "estimated_minutes": 40},
    {"id": "sci9_ch8",  "subject_id": "science_9th", "title": "Force and Laws of Motion",      "order": 8,  "topic_tags": ["physics", "force", "newton", "momentum"],                  "estimated_minutes": 40},
    {"id": "sci9_ch9",  "subject_id": "science_9th", "title": "Gravitation",                   "order": 9,  "topic_tags": ["physics", "gravity", "weight", "pressure"],                 "estimated_minutes": 35},
    {"id": "sci9_ch10", "subject_id": "science_9th", "title": "Work and Energy",               "order": 10, "topic_tags": ["physics", "work", "energy", "power"],                       "estimated_minutes": 35},
    {"id": "sci9_ch11", "subject_id": "science_9th", "title": "Sound",                         "order": 11, "topic_tags": ["physics", "sound", "waves", "echo"],                        "estimated_minutes": 30},
    {"id": "sci9_ch12", "subject_id": "science_9th", "title": "Improvement in Food Resources", "order": 12, "topic_tags": ["biology", "agriculture", "food"],                           "estimated_minutes": 25},
]

# ── schools/ ──────────────────────────────────────────────────────────────────
SCHOOLS = [
    {
        "id": "SCH001",
        "name": "Delhi Public School",
        "shortName": "DPS",
        "city": "New Delhi",
        "state": "Delhi",
        "code": "DPS-ND",
        "contactEmail": "admin@dpsnd.edu.in",
        "branding": {
            "primaryColor": "#0D47A1",
            "primaryDarkColor": "#002171",
            "accentColor": "#FF6F00",
            "backgroundColor": "#E3F2FD",
            "headerTextColor": "#FFFFFF",
            "headerSubtextColor": "#90CAF9",
            "bodyTextPrimaryColor": "#0D47A1",
            "logoText": "DPS",
            "logoEmoji": "🏫",
        },
        "plans": [
            {"id": "FREE",    "name": "Free Trial", "badge": "",           "priceINR": 0,   "duration": "7 days",     "features": ["AI Chat (10 messages/day)", "Up to 2 Subjects", "Basic Library Access", "Voice Input"]},
            {"id": "BASIC",   "name": "Basic",      "badge": "Popular",    "priceINR": 99,  "duration": "per month",  "features": ["AI Chat (unlimited)", "Up to 5 Subjects", "Full Library Access", "Voice Teacher", "Chapter Notes"]},
            {"id": "PREMIUM", "name": "Premium",    "badge": "Best Value", "priceINR": 249, "duration": "per month",  "features": ["Everything in Basic", "PDF Analysis & Upload", "Revision Tests", "Flashcards", "Priority Support"]},
        ],
        "testStudentIds": ["DPS001", "DPS002", "DPS003", "STUDENT1"],
    },
    {
        "id": "SCH002",
        "name": "Kendriya Vidyalaya",
        "shortName": "KV",
        "city": "Mumbai",
        "state": "Maharashtra",
        "code": "KV-MUM",
        "contactEmail": "admin@kvmumbai.edu.in",
        "branding": {
            "primaryColor": "#880E4F",
            "primaryDarkColor": "#560027",
            "accentColor": "#F57F17",
            "backgroundColor": "#FCE4EC",
            "headerTextColor": "#FFFFFF",
            "headerSubtextColor": "#F48FB1",
            "bodyTextPrimaryColor": "#880E4F",
            "logoText": "KV",
            "logoEmoji": "🎓",
        },
        "plans": [
            {"id": "FREE",    "name": "Free Trial", "badge": "",           "priceINR": 0,   "duration": "7 days",    "features": ["AI Chat (10 messages/day)", "Up to 2 Subjects", "Basic Library Access"]},
            {"id": "BASIC",   "name": "Standard",   "badge": "Popular",    "priceINR": 79,  "duration": "per month", "features": ["AI Chat (unlimited)", "Up to 5 Subjects", "Full Library Access", "Voice Teacher"]},
            {"id": "PREMIUM", "name": "Pro",        "badge": "Best Value", "priceINR": 199, "duration": "per month", "features": ["Everything in Standard", "PDF Analysis", "Revision Tests", "Analytics Dashboard"]},
        ],
        "testStudentIds": ["KV001", "KV002", "STUDENT1"],
    },
    {
        "id": "SCH003",
        "name": "DAV Public School",
        "shortName": "DAV",
        "city": "Bengaluru",
        "state": "Karnataka",
        "code": "DAV-BLR",
        "contactEmail": "principal@davblr.edu.in",
        "branding": {
            "primaryColor": "#1B5E20",
            "primaryDarkColor": "#003300",
            "accentColor": "#E65100",
            "backgroundColor": "#E8F5E9",
            "headerTextColor": "#FFFFFF",
            "headerSubtextColor": "#A5D6A7",
            "bodyTextPrimaryColor": "#1B5E20",
            "logoText": "DAV",
            "logoEmoji": "📚",
        },
        "plans": [
            {"id": "FREE",    "name": "Free Trial", "badge": "",           "priceINR": 0,   "duration": "14 days",   "features": ["AI Chat (10 messages/day)", "Up to 2 Subjects", "Basic Library Access", "Voice Input"]},
            {"id": "BASIC",   "name": "Standard",   "badge": "Popular",    "priceINR": 129, "duration": "per month", "features": ["AI Chat (unlimited)", "All Subjects", "Full Library Access", "Voice Teacher", "Chapter Notes"]},
            {"id": "PREMIUM", "name": "Pro",        "badge": "Best Value", "priceINR": 299, "duration": "per month", "features": ["Everything in Standard", "PDF Analysis", "Tests & Flashcards", "Performance Analytics", "Parent Reports"]},
        ],
        "testStudentIds": ["DAV001", "DAV002", "STUDENT1"],
    },
    {
        "id": "SCH004",
        "name": "Ryan International School",
        "shortName": "Ryan",
        "city": "Hyderabad",
        "state": "Telangana",
        "code": "RYAN-HYD",
        "contactEmail": "info@ryanhyd.edu.in",
        "branding": {
            "primaryColor": "#4A148C",
            "primaryDarkColor": "#12005E",
            "accentColor": "#FFD600",
            "backgroundColor": "#F3E5F5",
            "headerTextColor": "#FFFFFF",
            "headerSubtextColor": "#CE93D8",
            "bodyTextPrimaryColor": "#4A148C",
            "logoText": "Ryan",
            "logoEmoji": "⭐",
        },
        "plans": [
            {"id": "FREE",    "name": "Free Trial", "badge": "",           "priceINR": 0,   "duration": "7 days",    "features": ["AI Chat (10 messages/day)", "Up to 2 Subjects", "Basic Library Access"]},
            {"id": "BASIC",   "name": "Scholar",    "badge": "Popular",    "priceINR": 149, "duration": "per month", "features": ["AI Chat (unlimited)", "Up to 6 Subjects", "Full Library", "Voice Teacher", "Notes"]},
            {"id": "PREMIUM", "name": "Elite",      "badge": "Best Value", "priceINR": 349, "duration": "per month", "features": ["Everything in Scholar", "PDF Analysis", "Mock Tests", "Doubt Clearing Sessions", "Priority Support"]},
        ],
        "testStudentIds": ["RYAN001", "RYAN002", "STUDENT1"],
    },
    {
        "id": "SCH005",
        "name": "St. Xavier's High School",
        "shortName": "Xavier's",
        "city": "Kolkata",
        "state": "West Bengal",
        "code": "SXS-KOL",
        "contactEmail": "office@sxskol.edu.in",
        "branding": {
            "primaryColor": "#B71C1C",
            "primaryDarkColor": "#7F0000",
            "accentColor": "#1565C0",
            "backgroundColor": "#FFEBEE",
            "headerTextColor": "#FFFFFF",
            "headerSubtextColor": "#EF9A9A",
            "bodyTextPrimaryColor": "#B71C1C",
            "logoText": "SXS",
            "logoEmoji": "✝️",
        },
        "plans": [
            {"id": "FREE",    "name": "Free Trial", "badge": "",           "priceINR": 0,   "duration": "7 days",    "features": ["AI Chat (10 messages/day)", "Up to 2 Subjects", "Basic Library"]},
            {"id": "BASIC",   "name": "Basic",      "badge": "Popular",    "priceINR": 89,  "duration": "per month", "features": ["AI Chat (unlimited)", "5 Subjects", "Full Library", "Voice Teacher"]},
            {"id": "PREMIUM", "name": "Premium",    "badge": "Best Value", "priceINR": 229, "duration": "per month", "features": ["Everything in Basic", "PDF Upload & Analysis", "Revision Tests", "Performance Reports"]},
        ],
        "testStudentIds": ["SXS001", "SXS002", "STUDENT1"],
    },
]


# Fields must match FirestorePlan (UI display) + SubscriptionPlan (access control)
PLANS = [
    {
        "id": "free",
        # FirestorePlan fields (used by SubscriptionActivity UI)
        # Keys are camelCase to match Kotlin property names directly (no @PropertyName needed)
        "name": "Free",
        "badge": "",
        "priceInr": 0,
        "duration": "Forever",
        "validityDays": 0,
        "isActive": True,
        "displayOrder": 0,
        "accentColor": "#455A64",
        "features": [
            "12 AI sessions per day",
            "Basic quiz generation",
            "Access to all subjects",
            "Text chat only",
        ],
        # SubscriptionPlan fields (used by AdminConfigRepository for rate limiting)
        "tagline": "Get started for free",
        "priceDisplay": "₹0/month",
        "isPublic": True,
        "limits": {
            "daily_token_limit": 20000,
            "monthly_token_limit": 200000,
            "context_window_messages": 15,
            "context_window_chars": 6000,
            "image_upload_enabled": False,
            "voice_mode_enabled": False,
            "pdf_enabled": False,
            "blackboard_enabled": True,
            "flashcards_enabled": False,
            "tts_enabled": True,
            "ai_tts_enabled": False,
            "ai_tts_quota_chars": 0,
            "daily_chat_questions": 12,
            "daily_bb_sessions": 2,
            "max_quiz_questions": 10,
        },
    },
    {
        "id": "basic",
        "name": "Basic",
        "badge": "Popular",
        "priceInr": 99,
        "duration": "1 Month",
        "validityDays": 30,
        "isActive": True,
        "displayOrder": 1,
        "accentColor": "#1565C0",
        "features": [
            "50 AI sessions per day",
            "Image upload for problem solving",
            "Voice mode",
            "Advanced quiz generation",
            "Flashcards",
            "Priority support",
        ],
        "tagline": "Perfect for everyday studying",
        "priceDisplay": "₹99/month",
        "isPublic": True,
        "limits": {
            "daily_token_limit": 80000,
            "monthly_token_limit": 800000,
            "context_window_messages": 30,
            "context_window_chars": 12000,
            "image_upload_enabled": True,
            "voice_mode_enabled": True,
            "pdf_enabled": True,
            "blackboard_enabled": True,
            "flashcards_enabled": True,
            "tts_enabled": True,
            "ai_tts_enabled": True,
            "ai_tts_quota_chars": 5000,
            "daily_chat_questions": 50,
            "daily_bb_sessions": 10,
            "max_quiz_questions": 20,
        },
    },
    {
        "id": "premium",
        "name": "Premium",
        "badge": "Best Value",
        "priceInr": 199,
        "duration": "1 Month",
        "validityDays": 30,
        "isActive": True,
        "displayOrder": 2,
        "accentColor": "#6A1B9A",
        "features": [
            "Unlimited AI sessions",
            "Image upload for problem solving",
            "Voice mode + live AI tutor",
            "Hard difficulty quizzes",
            "Unlimited flashcards",
            "Gemini 2.5 Pro model access",
            "Priority support",
        ],
        "tagline": "For serious learners",
        "priceDisplay": "₹199/month",
        "isPublic": True,
        "limits": {
            "daily_token_limit": 200000,
            "monthly_token_limit": 0,
            "context_window_messages": 50,
            "context_window_chars": 24000,
            "image_upload_enabled": True,
            "voice_mode_enabled": True,
            "pdf_enabled": True,
            "blackboard_enabled": True,
            "flashcards_enabled": True,
            "tts_enabled": True,
            "ai_tts_enabled": True,
            "ai_tts_quota_chars": 50000,
            "daily_chat_questions": 0,
            "daily_bb_sessions": 0,
            "max_quiz_questions": 30,
        },
    },
    {
        "id": "school_unlimited",
        "name": "School Unlimited",
        "badge": "Enterprise",
        "priceInr": 0,
        "duration": "Annual",
        "validityDays": 365,
        "isActive": False,          # hidden from public listing
        "displayOrder": 3,
        "accentColor": "#00695C",
        "features": [
            "All Student Pro features",
            "School-wide dashboard",
            "Custom branding",
            "Bulk student management",
            "Dedicated account manager",
        ],
        "tagline": "For institutions",
        "priceDisplay": "Contact us",
        "isPublic": False,
        "limits": {
            "daily_token_limit": 0,
            "monthly_token_limit": 0,
            "context_window_messages": 100,
            "context_window_chars": 48000,
            "image_upload_enabled": True,
            "voice_mode_enabled": True,
            "pdf_enabled": True,
            "blackboard_enabled": True,
            "flashcards_enabled": True,
            "tts_enabled": True,
            "ai_tts_enabled": True,
            "daily_chat_questions": 0,
            "daily_bb_sessions": 0,
            "max_quiz_questions": 50,
        },
    },
]

# ── app_offers/ ───────────────────────────────────────────────────────────────
# Collection name is app_offers/ — that is what FirestoreManager.fetchOffers() reads
OFFERS = [
    {
        "id": "offer_launch",
        "title": "🚀 Launch Offer!",
        "subtitle": "Get Student Basic at 50% off — first month",
        "emoji": "🎁",
        "background_color": "#1A237E",
        "display_order": 0,
        "is_active": True,
    },
    {
        "id": "offer_new_chapters",
        "title": "📚 New Chapters Added",
        "subtitle": "All 9th grade subjects now complete",
        "emoji": "✨",
        "background_color": "#1B5E20",
        "display_order": 1,
        "is_active": True,
    },
    {
        "id": "offer_quiz",
        "title": "🧠 Challenge Mode Live",
        "subtitle": "Test yourself with timed hard-mode quizzes",
        "emoji": "⚡",
        "background_color": "#4A148C",
        "display_order": 2,
        "is_active": True,
    },
]

# ── updates/app_config ────────────────────────────────────────────────────────
APP_UPDATE_CONFIG = {
    "min_version_code": 1,
    "latest_version_code": 3,
    "latest_version_name": "1.1.0",
    "update_url": "https://play.google.com/store/apps/details?id=com.example.aiguru",
    "update_message": "A new version of AI Guru is available with improvements and fixes.",
    "release_notes": "• Build stability fixes\n• Subscription plans added\n• New chapter content",
    "is_maintenance": False,
    "maintenance_message": "We're working on improvements. Please check back soon.",
    "is_active": True,
    "support_contact": "support@aiguru.app",
}

# ── notifications/ ────────────────────────────────────────────────────────────
NOW_ISO = datetime.now(timezone.utc).isoformat()

NOTIFICATIONS = [
    {
        "id": "notif_welcome",
        "title": "Welcome to AI Guru! 👋",
        "body": "Start exploring subjects and ask your AI tutor anything.",
        "type": "info",          # info | promo | update | alert
        "target": "all",         # all | plan:student_pro | uid:xyz
        "is_active": True,
        "created_at": NOW_ISO,
        "display_order": 0,
        "action_url": "",        # optional deep-link or URL
    },
    {
        "id": "notif_launch_promo",
        "title": "🎉 Launch Offer — 50% Off",
        "body": "Upgrade to Student Basic this month and get your first month at half price.",
        "type": "promo",
        "target": "plan:free",
        "is_active": True,
        "created_at": NOW_ISO,
        "display_order": 1,
        "action_url": "",
    },
    {
        "id": "notif_new_chapters",
        "title": "📖 New Content Available",
        "body": "All 9th grade Science and Maths chapters have been updated with detailed explanations.",
        "type": "update",
        "target": "all",
        "is_active": True,
        "created_at": NOW_ISO,
        "display_order": 2,
        "action_url": "",
    },
]

# ── admin_config/global ───────────────────────────────────────────────────────
ADMIN_CONFIG_GLOBAL = {
    "server_url": "http://108.181.187.227:8003",
    "server_api_key": "",
    "razorpay_key_id": "rzp_test_SWYJxX0vJpdv4i",  # Razorpay publishable key — safe to store here
    "model_tiers": {
        "standard": "gemini-2.0-flash",
        "advanced": "gemini-2.5-pro",
    },
    "global_daily_token_hard_cap": 200000,
    "global_monthly_token_hard_cap": 0,
    "maintenance_mode": False,
    "maintenance_message": "Service is under maintenance. Please try again later.",
    "default_limits": {
        "daily_token_limit": 20000,
        "monthly_token_limit": 200000,
        "context_window_messages": 15,
        "context_window_chars": 6000,
        "image_upload_enabled": False,
        "voice_mode_enabled": False,
        "pdf_enabled": False,
        "blackboard_enabled": True,
        "flashcards_enabled": False,
        "tts_enabled": True,
        "ai_tts_enabled": False,
        "daily_chat_questions": 12,
        "daily_bb_sessions": 2,
        "max_quiz_questions": 10,
    },
    "gemini_live_enabled": True,
    "flashcards_globally_enabled": True,
    "cache_max_age_ms": 3600000,

    # ── AI TTS credentials (never embed in APK — fetched from Firestore) ──────
    # Set the provider and paste the real API key here, then re-run this script.
    # Options: "android" (no AI TTS), "google", "elevenlabs", "openai", "self_hosted"
    "tts_provider": "android",
    "tts_google_api_key": "",          # Google Cloud TTS API key
    "tts_elevenlabs_api_key": "",     # ElevenLabs secret key
    "tts_openai_api_key": "",         # OpenAI sk-...
    "tts_server_url": "",             # Self-hosted TTS server URL (leave blank to reuse server_url)
}

# ══════════════════════════════════════════════════════════════════════════════
# HELPERS
# ══════════════════════════════════════════════════════════════════════════════

def seed_collection(col_name: str, docs: list, id_field: str = "id") -> None:
    col = db.collection(col_name)
    batch = db.batch()
    ids = []
    for i, doc in enumerate(docs):
        doc_copy = dict(doc)
        doc_id = doc_copy.pop(id_field)
        ids.append(doc_id)
        batch.set(col.document(doc_id), doc_copy)
        if (i + 1) % 400 == 0:
            batch.commit()
            batch = db.batch()
    batch.commit()
    for doc_id in ids:
        print(f"  ✓  {col_name}/{doc_id}")


def seed_single(col_name: str, doc_id: str, data: dict) -> None:
    db.collection(col_name).document(doc_id).set(data)
    print(f"  ✓  {col_name}/{doc_id}")


# ══════════════════════════════════════════════════════════════════════════════
# RUN
# ══════════════════════════════════════════════════════════════════════════════

if __name__ == "__main__":
    print("\n── schools/ ──────────────────────────────────────────")
    seed_collection("schools", SCHOOLS)

    print("\n── subjects/ ─────────────────────────────────────────")
    seed_collection("subjects", SUBJECTS)

    print("\n── chapters/ ─────────────────────────────────────────")
    seed_collection("chapters", CHAPTERS)

    print("\n── plans/ ────────────────────────────────────────────")
    seed_collection("plans", PLANS)

    print("\n── offers/ ───────────────────────────────────────────")
    seed_collection("app_offers", OFFERS)

    print("\n── updates/app_config ────────────────────────────────")
    seed_single("updates", "app_config", APP_UPDATE_CONFIG)

    print("\n── notifications/ ────────────────────────────────────")
    seed_collection("notifications", NOTIFICATIONS)

    print("\n── admin_config/global ───────────────────────────────")
    seed_single("admin_config", "global", ADMIN_CONFIG_GLOBAL)

    print("\n✅  Done — all collections seeded successfully.")
    print("""
NOTE: /users_table collection is server-managed (not seeded here).
Server writes plan activations to users_table/{userId} after payment.
Android reads plan info from users_table/{userId} but CANNOT write plan fields.
Firestore rules must allow Android READ but only server (admin SDK) WRITE.
""")
