#!/usr/bin/env python3
"""
seed_firestore.py
-----------------
Populates Firestore with sample subjects and chapters so the app has data
to work with immediately. Run once:

    python seed_firestore.py

Requires FIREBASE_SERVICE_ACCOUNT to be set in .env (or the JSON file to be
present at fast_api server/firebase_serviceaccount.json).
"""

import os
import sys
import json
from pathlib import Path

# Allow running from repo root or fast_api server/ directory
SCRIPT_DIR = Path(__file__).parent
sys.path.insert(0, str(SCRIPT_DIR / "fast_api server"))

import firebase_admin
from firebase_admin import credentials, firestore

# ── Credentials ───────────────────────────────────────────────────────────────
SA_PATH = os.environ.get(
    "FIREBASE_SERVICE_ACCOUNT",
    str(SCRIPT_DIR / "fast_api server" / "firebase_serviceaccount.json"),
)

if not os.path.exists(SA_PATH):
    print(f"ERROR: Service-account file not found at {SA_PATH}")
    sys.exit(1)

cred = credentials.Certificate(SA_PATH)
firebase_admin.initialize_app(cred)
db = firestore.client()

# ── Seed data ─────────────────────────────────────────────────────────────────

SUBJECTS = [
    {
        "id": "math_9th",
        "name": "Mathematics",
        "grade": "9th",
        "icon_url": "",
        "description": "NCERT 9th standard mathematics",
        "chapter_count": 4,
    },
    {
        "id": "science_9th",
        "name": "Science",
        "grade": "9th",
        "icon_url": "",
        "description": "NCERT 9th standard science",
        "chapter_count": 4,
    },
]

CHAPTERS = [
    # Mathematics
    {
        "id": "math9_ch1",
        "subject_id": "math_9th",
        "title": "Number Systems",
        "order": 1,
        "description": "Rational and irrational numbers, real number line",
        "topic_tags": ["numbers", "rational", "irrational", "real numbers"],
        "estimated_minutes": 30,
    },
    {
        "id": "math9_ch2",
        "subject_id": "math_9th",
        "title": "Polynomials",
        "order": 2,
        "description": "Polynomials, zeroes, Remainder Theorem, Factor Theorem",
        "topic_tags": ["algebra", "polynomials", "factorisation"],
        "estimated_minutes": 35,
    },
    {
        "id": "math9_ch3",
        "subject_id": "math_9th",
        "title": "Coordinate Geometry",
        "order": 3,
        "description": "Cartesian plane, axes, plotting points",
        "topic_tags": ["geometry", "coordinates", "cartesian"],
        "estimated_minutes": 25,
    },
    {
        "id": "math9_ch4",
        "subject_id": "math_9th",
        "title": "Linear Equations in Two Variables",
        "order": 4,
        "description": "Lines, solutions, graph of linear equations",
        "topic_tags": ["algebra", "linear equations", "graphing"],
        "estimated_minutes": 30,
    },
    # Science
    {
        "id": "sci9_ch1",
        "subject_id": "science_9th",
        "title": "Matter in Our Surroundings",
        "order": 1,
        "description": "States of matter, evaporation, boiling, sublimation",
        "topic_tags": ["physics", "matter", "states"],
        "estimated_minutes": 30,
    },
    {
        "id": "sci9_ch2",
        "subject_id": "science_9th",
        "title": "Is Matter Around Us Pure?",
        "order": 2,
        "description": "Mixtures, solutions, colloids, separation techniques",
        "topic_tags": ["chemistry", "mixtures", "solutions"],
        "estimated_minutes": 35,
    },
    {
        "id": "sci9_ch3",
        "subject_id": "science_9th",
        "title": "Atoms and Molecules",
        "order": 3,
        "description": "Laws of chemical combination, atomic mass, molecules",
        "topic_tags": ["chemistry", "atoms", "molecules", "mole"],
        "estimated_minutes": 40,
    },
    {
        "id": "sci9_ch4",
        "subject_id": "science_9th",
        "title": "Structure of the Atom",
        "order": 4,
        "description": "Electrons, protons, neutrons, Bohr model, valency",
        "topic_tags": ["chemistry", "atomic structure", "electrons"],
        "estimated_minutes": 35,
    },
]


# ── Insert helpers ────────────────────────────────────────────────────────────

def seed_collection(col_name: str, docs: list, id_field: str = "id") -> None:
    col = db.collection(col_name)
    for doc in docs:
        doc_id = doc.pop(id_field)
        col.document(doc_id).set(doc)
        print(f"  ✓  {col_name}/{doc_id}")


# ── Run ───────────────────────────────────────────────────────────────────────

if __name__ == "__main__":
    print("Seeding subjects…")
    seed_collection("subjects", [s.copy() for s in SUBJECTS])

    print("Seeding chapters…")
    seed_collection("chapters", [c.copy() for c in CHAPTERS])

    print("\nDone! Firestore now has sample subjects and chapters.")
