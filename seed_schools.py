#!/usr/bin/env python3
"""
seed_schools.py
---------------
Seeds sample schools and their student rosters into Firestore.

Collections written:
  schools/{schoolId}                      – school doc (branding, plans, code …)
  schools/{schoolId}/students/{username}  – student login credentials

Firestore document layout mirrors the Android School / SchoolBranding / SchoolPlan models.

Run:
    python seed_schools.py

Service-account JSON is resolved in this order:
  1. FIREBASE_SERVICE_ACCOUNT env var
  2. firebase_serviceaccount.json  (relative to repo root)
"""

import os
import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).parent

import firebase_admin
from firebase_admin import credentials, firestore

# ── Credentials ───────────────────────────────────────────────────────────────
SA_PATH = os.environ.get(
    "FIREBASE_SERVICE_ACCOUNT",
    str(SCRIPT_DIR / "firebase_serviceaccount.json"),
)

if not os.path.exists(SA_PATH):
    print(f"ERROR: Service-account file not found at:\n  {SA_PATH}")
    sys.exit(1)

if not firebase_admin._apps:
    cred = credentials.Certificate(SA_PATH)
    firebase_admin.initialize_app(cred)

db = firestore.client()

# ══════════════════════════════════════════════════════════════════════════════
# SCHOOL DEFINITIONS
# Each school has:
#   - top-level document fields  (id, name, code, branding, plans …)
#   - a "students" sub-list used only by this script to seed the subcollection
#     (not stored in the school doc itself)
# ══════════════════════════════════════════════════════════════════════════════

SCHOOLS = [
    {
        # ── Identity ──────────────────────────────────────────────────────────
        "id":           "svps_mumbai",
        "name":         "Swami Vivekananda Public School",
        "shortName":    "SVPS",
        "city":         "Mumbai",
        "state":        "Maharashtra",
        "code":         "SVPS",          # 4-letter code students type in the app
        "contactEmail": "admin@svps.edu.in",

        # ── Branding ──────────────────────────────────────────────────────────
        "branding": {
            "primaryColor":        "#1565C0",
            "primaryDarkColor":    "#003c8f",
            "accentColor":         "#FFD600",
            "backgroundColor":     "#E3F2FD",
            "headerTextColor":     "#FFFFFF",
            "headerSubtextColor":  "#BBDEFB",
            "bodyTextPrimaryColor": "#0D47A1",
            "logoText":            "SVPS",
            "logoEmoji":           "🏫",
            "logoUrl":             "",
        },

        # ── Plans ─────────────────────────────────────────────────────────────
        "plans": [
            {
                "id":       "svps_free",
                "name":     "SVPS Free",
                "badge":    "🏫 School",
                "priceINR": 0,
                "duration": "yearly",
                "features": [
                    "Unlimited questions",
                    "Blackboard mode",
                    "All subjects",
                    "School branding",
                ],
            }
        ],

        # ── Test student IDs (offline fallback — not stored in school doc) ────
        "testStudentIds": ["testuser1", "demo"],

        # ── Students subcollection ────────────────────────────────────────────
        # Stored under schools/svps_mumbai/students/{username}
        "students": [
            {"username": "ravi.sharma",  "password": "svps2024", "name": "Ravi Sharma",   "student_id": "SVPS001", "grade": "9",  "section": "A"},
            {"username": "priya.nair",   "password": "svps2024", "name": "Priya Nair",    "student_id": "SVPS002", "grade": "9",  "section": "A"},
            {"username": "arjun.mehta",  "password": "svps2024", "name": "Arjun Mehta",   "student_id": "SVPS003", "grade": "10", "section": "B"},
            {"username": "sneha.patil",  "password": "svps2024", "name": "Sneha Patil",   "student_id": "SVPS004", "grade": "10", "section": "B"},
            {"username": "rohit.verma",  "password": "svps2024", "name": "Rohit Verma",   "student_id": "SVPS005", "grade": "8",  "section": "C"},
            {"username": "ananya.iyer",  "password": "svps2024", "name": "Ananya Iyer",   "student_id": "SVPS006", "grade": "8",  "section": "C"},
            {"username": "demo",         "password": "demo1234", "name": "Demo Student",  "student_id": "DEMO",    "grade": "9",  "section": "A"},
            {"username": "testuser1",    "password": "test1234", "name": "Test User",     "student_id": "TEST001", "grade": "9",  "section": "A"},
        ],

        # ── Teachers subcollection ─────────────────────────────────────────────
        # Stored under schools/svps_mumbai/teachers/{username}
        "teachers": [
            {"username": "meera.sharma",  "password": "teach2024", "name": "Meera Sharma",  "teacher_id": "TSVPS01", "subjects": ["Physics", "Chemistry"]},
            {"username": "james.dsouza",  "password": "teach2024", "name": "James D'Souza",  "teacher_id": "TSVPS02", "subjects": ["Mathematics"]},
            {"username": "demo.teacher",  "password": "demo1234",  "name": "Demo Teacher",  "teacher_id": "TDEMO",   "subjects": []},
        ],
    },

    {
        "id":           "dps_delhi",
        "name":         "Delhi Public School",
        "shortName":    "DPS",
        "city":         "New Delhi",
        "state":        "Delhi",
        "code":         "DPSD",
        "contactEmail": "principal@dps-delhi.edu.in",

        "branding": {
            "primaryColor":        "#B71C1C",
            "primaryDarkColor":    "#7F0000",
            "accentColor":         "#FFEB3B",
            "backgroundColor":     "#FFEBEE",
            "headerTextColor":     "#FFFFFF",
            "headerSubtextColor":  "#FFCDD2",
            "bodyTextPrimaryColor": "#B71C1C",
            "logoText":            "DPS",
            "logoEmoji":           "🎓",
            "logoUrl":             "",
        },

        "plans": [
            {
                "id":       "dps_free",
                "name":     "DPS Free",
                "badge":    "🎓 DPS",
                "priceINR": 0,
                "duration": "yearly",
                "features": [
                    "Unlimited questions",
                    "Blackboard lessons",
                    "All NCERT subjects",
                    "School branding",
                ],
            },
            {
                "id":       "dps_premium",
                "name":     "DPS Premium",
                "badge":    "⭐ DPS Pro",
                "priceINR": 499,
                "duration": "yearly",
                "features": [
                    "Everything in Free",
                    "Priority AI responses",
                    "Detailed performance report",
                    "Exam prep mode",
                ],
            },
        ],

        "testStudentIds": ["dpsdemo"],

        "students": [
            {"username": "aditya.kumar",  "password": "dps@2024", "name": "Aditya Kumar",  "student_id": "DPS101", "grade": "10", "section": "A"},
            {"username": "kavya.singh",   "password": "dps@2024", "name": "Kavya Singh",   "student_id": "DPS102", "grade": "10", "section": "A"},
            {"username": "vikram.joshi",  "password": "dps@2024", "name": "Vikram Joshi",  "student_id": "DPS103", "grade": "11", "section": "B"},
            {"username": "meera.gupta",   "password": "dps@2024", "name": "Meera Gupta",   "student_id": "DPS104", "grade": "11", "section": "B"},
            {"username": "rahul.bose",    "password": "dps@2024", "name": "Rahul Bose",    "student_id": "DPS105", "grade": "12", "section": "C"},
            {"username": "pooja.reddy",   "password": "dps@2024", "name": "Pooja Reddy",   "student_id": "DPS106", "grade": "12", "section": "C"},
            {"username": "dpsdemo",       "password": "demo1234", "name": "DPS Demo",      "student_id": "DEMO",   "grade": "10", "section": "A"},
        ],

        "teachers": [
            {"username": "anita.khanna",   "password": "teach2024", "name": "Anita Khanna",  "teacher_id": "TDPS01", "subjects": ["Biology", "Chemistry"]},
            {"username": "sunil.mathur",   "password": "teach2024", "name": "Sunil Mathur",  "teacher_id": "TDPS02", "subjects": ["History", "Civics"]},
        ],
    },

    {
        "id":           "kendriya_pune",
        "name":         "Kendriya Vidyalaya Pune",
        "shortName":    "KV Pune",
        "city":         "Pune",
        "state":        "Maharashtra",
        "code":         "KVPN",
        "contactEmail": "kv.pune@kvs.gov.in",

        "branding": {
            "primaryColor":        "#1B5E20",
            "primaryDarkColor":    "#003300",
            "accentColor":         "#FFC107",
            "backgroundColor":     "#E8F5E9",
            "headerTextColor":     "#FFFFFF",
            "headerSubtextColor":  "#C8E6C9",
            "bodyTextPrimaryColor": "#1B5E20",
            "logoText":            "KV",
            "logoEmoji":           "🌿",
            "logoUrl":             "",
        },

        "plans": [
            {
                "id":       "kv_free",
                "name":     "KV Free",
                "badge":    "🌿 KV",
                "priceINR": 0,
                "duration": "yearly",
                "features": [
                    "Unlimited questions",
                    "Blackboard mode",
                    "NCERT syllabus focus",
                    "School branding",
                ],
            }
        ],

        "testStudentIds": ["kvdemo"],

        "students": [
            {"username": "suresh.rao",     "password": "kv@pune24", "name": "Suresh Rao",     "student_id": "KV201", "grade": "9",  "section": "A"},
            {"username": "divya.sharma",   "password": "kv@pune24", "name": "Divya Sharma",   "student_id": "KV202", "grade": "9",  "section": "B"},
            {"username": "tarun.mishra",   "password": "kv@pune24", "name": "Tarun Mishra",   "student_id": "KV203", "grade": "10", "section": "A"},
            {"username": "radha.patel",    "password": "kv@pune24", "name": "Radha Patel",    "student_id": "KV204", "grade": "10", "section": "B"},
            {"username": "kvdemo",         "password": "demo1234",  "name": "KV Demo",        "student_id": "DEMO",  "grade": "9",  "section": "A"},
        ],

        "teachers": [
            {"username": "priya.nambiar",   "password": "teach2024", "name": "Priya Nambiar",  "teacher_id": "TKV01", "subjects": ["Mathematics", "Physics"]},
        ],
    },
]


# ══════════════════════════════════════════════════════════════════════════════
# SEED FUNCTIONS
# ══════════════════════════════════════════════════════════════════════════════

def seed_school(school_data: dict) -> None:
    school_id = school_data["id"]
    students  = school_data.pop("students", [])       # pull out before writing school doc
    teachers  = school_data.pop("teachers", [])       # pull out teachers too

    print(f"\n\U0001f4da Seeding school: {school_data['name']} ({school_id})")

    # Write the school document (all fields except students/teachers lists)
    db.collection("schools").document(school_id).set(school_data, merge=True)
    print(f"   ✅ School doc written")

    # Write each student into the subcollection
    students_ref = (
        db.collection("schools")
          .document(school_id)
          .collection("students")
    )

    for student in students:
        username = student["username"].lower()
        students_ref.document(username).set(student, merge=True)
        print(f"   👤 Student: {username} ({student.get('name', '')})")

    print(f"   └── {len(students)} students seeded")

    # Write each teacher into the teachers subcollection
    if teachers:
        teachers_ref = (
            db.collection("schools")
              .document(school_id)
              .collection("teachers")
        )
        for teacher in teachers:
            username = teacher["username"].lower()
            teachers_ref.document(username).set(teacher, merge=True)
            print(f"   👩‍🏫 Teacher: {username} ({teacher.get('name', '')})")
        print(f"   └── {len(teachers)} teachers seeded")


def main() -> None:
    print("=" * 60)
    print("AI Guru — School & Student Seeder")
    print("=" * 60)

    for school in SCHOOLS:
        seed_school(school)

    print("\n" + "=" * 60)
    print(f"✅ Done — {len(SCHOOLS)} schools seeded")
    print("=" * 60)
    print()
    print("Firestore structure written:")
    print("  schools/{id}                    ← school doc")
    print("  schools/{id}/students/{user}    ← student login doc")
    print()
    print("Student doc fields:")
    print("  username   — used as Firestore document ID (lowercase)")
    print("  password   — matched in SchoolJoinActivity")
    print("  name       — displayed in the app after login")
    print("  student_id — optional school-assigned roll number")
    print("  grade      — class (e.g. '9', '10')")
    print("  section    — class section (e.g. 'A', 'B')")


if __name__ == "__main__":
    main()
