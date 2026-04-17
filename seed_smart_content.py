"""
seed_smart_content.py
─────────────────────
Seeds the  home_smart_content  Firestore collection.

These cards are shown on the home screen to guide users toward
Blackboard Mode and other features they haven't discovered yet.

  type            | targetCondition  | behaviour
  --------------- | ---------------- | -----------------------------------------
  bb_intro        | bb_not_used      | Shown until user completes 1 BB session
  tip             | new_user         | Welcome card for fresh installs
  tip             | streak_3         | Celebrate a 3+ day streak
  bb_intro        | all              | Always visible (lower priority)

Run:
    python seed_smart_content.py
"""

import firebase_admin
from firebase_admin import credentials, firestore

cred = credentials.Certificate("firebase_serviceaccount.json")
firebase_admin.initialize_app(cred)
db = firestore.client()

COLLECTION = "home_smart_content"

cards = [
    # ── BB-mode intro cards (shown when user has never used BB) ──────────────
    {
        "id": "bb_intro_math_algebra",
        "type": "bb_intro",
        "emoji": "📐",
        "title": "Solve Math Problems with AI",
        "subtitle": "BB Mode explains quadratic equations step-by-step with voice & diagrams",
        "cta_label": "Try Blackboard →",
        "subject": "Mathematics",
        "chapter": "Algebra",
        "bb_message": (
            "Explain how to solve quadratic equations ax² + bx + c = 0 step by step. "
            "Show 2 clear examples: one using the quadratic formula and one using factoring. "
            "Include a tip on when to use each method."
        ),
        "target_condition": "bb_not_used",
        "card_color": "#1565C0",
        "priority": 1,
        "active": True,
    },
    {
        "id": "bb_intro_science_photosynthesis",
        "type": "bb_intro",
        "emoji": "🔬",
        "title": "Understand Science Visually",
        "subtitle": "BB Mode teaches photosynthesis with animated diagrams and narration",
        "cta_label": "Try Blackboard →",
        "subject": "Science",
        "chapter": "Plant Biology",
        "bb_message": (
            "Explain photosynthesis: how do plants make food from sunlight? "
            "Include the chemical equation (6CO₂ + 6H₂O → C₆H₁₂O₆ + 6O₂), "
            "the role of chlorophyll, chloroplasts, and what happens during light "
            "and dark (Calvin cycle) reactions. Keep it clear for a Class 9 student."
        ),
        "target_condition": "bb_not_used",
        "card_color": "#2E7D32",
        "priority": 2,
        "active": True,
    },
    {
        "id": "bb_intro_english_grammar",
        "type": "bb_intro",
        "emoji": "📝",
        "title": "Master English Grammar",
        "subtitle": "BB Mode teaches tenses and writing rules with clear examples",
        "cta_label": "Try Blackboard →",
        "subject": "English",
        "chapter": "Grammar",
        "bb_message": (
            "Explain the 12 English tenses: present simple, present continuous, "
            "present perfect, present perfect continuous — and their past and future equivalents. "
            "For each tense give the structure (formula), 2 example sentences, "
            "and a memory tip. Make it easy to understand for a Class 8–10 student."
        ),
        "target_condition": "bb_not_used",
        "card_color": "#6A1B9A",
        "priority": 3,
        "active": True,
    },
    {
        "id": "bb_intro_chemistry_bonds",
        "type": "bb_intro",
        "emoji": "⚗️",
        "title": "Chemistry Made Easy",
        "subtitle": "BB Mode explains ionic and covalent bonds with molecule diagrams",
        "cta_label": "Try Blackboard →",
        "subject": "Chemistry",
        "chapter": "Chemical Bonding",
        "bb_message": (
            "Explain ionic bonds and covalent bonds in chemistry. "
            "Show how NaCl (ionic) and H₂O (covalent) form, the difference between "
            "the two bond types, dot-cross diagrams, and when each type occurs. "
            "Suitable for Class 10–11."
        ),
        "target_condition": "bb_not_used",
        "card_color": "#00695C",
        "priority": 4,
        "active": True,
    },
    {
        "id": "bb_intro_history_ww2",
        "type": "bb_intro",
        "emoji": "🏛️",
        "title": "Learn History Like a Story",
        "subtitle": "BB Mode narrates key events with timelines you can follow",
        "cta_label": "Try Blackboard →",
        "subject": "History",
        "chapter": "World Wars",
        "bb_message": (
            "Explain the causes, major turning points, and outcomes of World War II "
            "(1939–1945). Cover: the rise of fascism, key battles (Dunkirk, Stalingrad, "
            "D-Day), the Holocaust, the role of the USA, and the post-war world order. "
            "Make it engaging for a Class 9–10 history student."
        ),
        "target_condition": "bb_not_used",
        "card_color": "#BF360C",
        "priority": 5,
        "active": True,
    },
    {
        "id": "bb_intro_physics_laws",
        "type": "bb_intro",
        "emoji": "⚡",
        "title": "Physics — Laws of Motion",
        "subtitle": "BB Mode explains Newton's laws with real-life examples and diagrams",
        "cta_label": "Try Blackboard →",
        "subject": "Physics",
        "chapter": "Laws of Motion",
        "bb_message": (
            "Explain Newton's three laws of motion with real-life examples for each. "
            "For the first law: why a book stays on the table. "
            "For the second law: F = ma with a car acceleration example. "
            "For the third law: rocket propulsion. "
            "Include practice problems at the end."
        ),
        "target_condition": "bb_not_used",
        "card_color": "#283593",
        "priority": 6,
        "active": True,
    },

    # ── Tip cards (shown based on behaviour) ─────────────────────────────────
    {
        "id": "tip_new_user_welcome",
        "type": "tip",
        "emoji": "👋",
        "title": "Welcome to AI Guru!",
        "subtitle": "Add a subject from the list below and ask your first question in chat",
        "cta_label": "Add Subject →",
        "subject": "",
        "chapter": "",
        "bb_message": "",
        "target_condition": "new_user",
        "card_color": "#00838F",
        "priority": 1,
        "active": True,
    },
    {
        "id": "tip_streak_celebration",
        "type": "tip",
        "emoji": "🔥",
        "title": "You're on a learning streak!",
        "subtitle": "Keep going — consistent learners score 40% better in exams",
        "cta_label": "Keep Studying →",
        "subject": "General",
        "chapter": "General Chat",
        "bb_message": "",
        "target_condition": "streak_3",
        "card_color": "#E65100",
        "priority": 2,
        "active": True,
    },
    {
        "id": "tip_try_bb_after_chat",
        "type": "tip",
        "emoji": "💡",
        "title": "Upgrade your understanding",
        "subtitle": "After asking in chat, tap '🎓 Blackboard' on any AI answer for a full lesson",
        "cta_label": "Got it!",
        "subject": "",
        "chapter": "",
        "bb_message": "",
        "target_condition": "high_activity",
        "card_color": "#4527A0",
        "priority": 3,
        "active": True,
    },
]

def seed():
    col = db.collection(COLLECTION)
    for card in cards:
        doc_id = card.pop("id")
        col.document(doc_id).set(card)
        print(f"  ✅ {doc_id}")
    print(f"\nSeeded {len(cards)} smart content cards into '{COLLECTION}'")

if __name__ == "__main__":
    seed()
