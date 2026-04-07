"""
NCERT PDF URL Scraper & Firestore Updater
==========================================
Builds a mapping of (grade, subject, chapter) → NCERT PDF direct URL
by decoding NCERT's textbook page URL scheme, then updates the
`chapters/` collection in Firestore with `ncert_pdf_url` field.

NCERT URL scheme:
  https://ncert.nic.in/textbook.php?{code}=0-15

  code format:  {subjectCode}{gradeCode}
    e.g.  "hemh1"  = Hindi Elective (he) · Math-Hist?  No — see table below.
    e.g.  "iemh1"  = not used
    Actual codes derived from NCERT textbook catalogue (see TEXTBOOKS dict).

PDF filename scheme:
  {code}{paddedChapter}.pdf
  e.g.  cesa102.pdf → code="cesa1", chapter=2
  e.g.  hemh101.pdf → code="hemh1", chapter=1

Direct PDF download URL:
  https://ncert.nic.in/textbook/pdf/{code}{paddedChapter}.pdf

Usage:
  python3 ncert_scraper.py               # dry run — prints mappings only
  python3 ncert_scraper.py --update      # updates Firestore chapters/ docs
  python3 ncert_scraper.py --update --grade 9  # only update grade 9
"""

import os
import sys
import argparse
import requests
from pathlib import Path

# ── Firebase setup ────────────────────────────────────────────────────────────
SCRIPT_DIR = Path(__file__).parent
sys.path.insert(0, str(SCRIPT_DIR / "fastapi server"))

import firebase_admin
from firebase_admin import credentials, firestore

SA_PATH = os.environ.get(
    "FIREBASE_SERVICE_ACCOUNT",
    str(SCRIPT_DIR / "fastapi server" / "firebase_serviceaccount.json"),
)

# ══════════════════════════════════════════════════════════════════════════════
# NCERT TEXTBOOK CATALOGUE
# Each entry:  Firestore subject_id → list of NCERT book codes with chapter ranges
#
# Code derivation:
#   First 2-4 chars = subject abbreviation
#   Last 1-2 digits = class/grade number (1=class1 ... 9=class9, 10=class10 etc)
#
# Chapter range: (start, end) inclusive — from NCERT website ?code=start-end param
# ══════════════════════════════════════════════════════════════════════════════

TEXTBOOKS = {
    # ── Class 9 ───────────────────────────────────────────────────────────────
    "math_9th": [
        {
            "code": "iemh1",          # Mathematics class 9
            "chapters": list(range(1, 16)),  # Ch 1–15
            "chapter_offset": 0,      # chapter N → file iemh1{N:02d}.pdf
        }
    ],
    "science_9th": [
        {
            "code": "iesc1",          # Science class 9
            "chapters": list(range(1, 16)),
            "chapter_offset": 0,
        }
    ],
    "english_9th": [
        {
            "code": "beeh1",          # Beehive (Main reader) class 9
            "chapters": list(range(1, 11)),
            "chapter_offset": 0,
        },
        {
            "code": "bmom1",          # Moments (Supplementary) class 9
            "chapters": list(range(1, 11)),
            "chapter_offset": 10,     # offset so chapters don't clash with beeh1
        },
    ],
    "social_9th": [
        {
            "code": "jess1",          # Social Science (History) class 9
            "chapters": list(range(1, 6)),
            "chapter_offset": 0,
        },
        {
            "code": "jevs1",          # Social Science (Geography) class 9
            "chapters": list(range(1, 7)),
            "chapter_offset": 5,
        },
        {
            "code": "jdcs1",          # Social Science (Civics) class 9
            "chapters": list(range(1, 7)),
            "chapter_offset": 11,
        },
        {
            "code": "jeec1",          # Social Science (Economics) class 9
            "chapters": list(range(1, 5)),
            "chapter_offset": 17,
        },
    ],
    "hindi_9th": [
        {
            "code": "hhvn1",          # Kshitij (Hindi-A prose) class 9
            "chapters": list(range(1, 18)),
            "chapter_offset": 0,
        },
    ],

    # ── Class 10 ──────────────────────────────────────────────────────────────
    "math_10th": [
        {
            "code": "jemh1",          # Mathematics class 10
            "chapters": list(range(1, 16)),
            "chapter_offset": 0,
        }
    ],
    "science_10th": [
        {
            "code": "jesc1",          # Science class 10
            "chapters": list(range(1, 17)),
            "chapter_offset": 0,
        }
    ],
    "english_10th": [
        {
            "code": "jfls1",          # First Flight (main) class 10
            "chapters": list(range(1, 12)),
            "chapter_offset": 0,
        },
        {
            "code": "jfcs1",          # Footprints (supplementary) class 10
            "chapters": list(range(1, 11)),
            "chapter_offset": 11,
        },
    ],
    "social_10th": [
        {
            "code": "jhss1",          # History class 10
            "chapters": list(range(1, 6)),
            "chapter_offset": 0,
        },
        {
            "code": "jhge1",          # Geography class 10
            "chapters": list(range(1, 8)),
            "chapter_offset": 5,
        },
        {
            "code": "jhcv1",          # Civics (Pol. Science) class 10
            "chapters": list(range(1, 9)),
            "chapter_offset": 12,
        },
        {
            "code": "jhec1",          # Economics class 10
            "chapters": list(range(1, 6)),
            "chapter_offset": 20,
        },
    ],
    "hindi_10th": [
        {
            "code": "khvn1",          # Kshitij 2 (Hindi-A) class 10
            "chapters": list(range(1, 18)),
            "chapter_offset": 0,
        },
    ],

    # ── Class 8 ───────────────────────────────────────────────────────────────
    "math_8th": [
        {
            "code": "hemh1",          # Mathematics class 8
            "chapters": list(range(1, 17)),
            "chapter_offset": 0,
        }
    ],
    "science_8th": [
        {
            "code": "hesc1",          # Science class 8
            "chapters": list(range(1, 19)),
            "chapter_offset": 0,
        }
    ],
    "english_8th": [
        {
            "code": "cesa1",          # English Supplementary (Honeydew) class 8
            "chapters": list(range(1, 13)),
            "chapter_offset": 0,
        },
    ],
    "social_8th": [
        {
            "code": "ghss1",          # History class 8
            "chapters": list(range(1, 11)),
            "chapter_offset": 0,
        },
        {
            "code": "ghge1",          # Geography class 8
            "chapters": list(range(1, 7)),
            "chapter_offset": 10,
        },
        {
            "code": "ghcs1",          # Civics class 8
            "chapters": list(range(1, 11)),
            "chapter_offset": 16,
        },
    ],
}

NCERT_PDF_BASE = "https://ncert.nic.in/textbook/pdf/"
NCERT_PAGE_BASE = "https://ncert.nic.in/textbook.php?"


def make_pdf_url(code: str, chapter: int) -> str:
    """Returns the direct PDF URL for a given NCERT book code and chapter number."""
    return f"{NCERT_PDF_BASE}{code}{chapter:02d}.pdf"


def verify_url(url: str, timeout: int = 8) -> bool:
    """HEAD-checks whether the PDF URL is reachable (non-destructive)."""
    try:
        r = requests.head(url, timeout=timeout, allow_redirects=True)
        return r.status_code == 200
    except Exception:
        return False


def build_chapter_url_map(subject_id: str, verify: bool = False) -> dict[int, str]:
    """
    Returns {chapter_number: pdf_url} for all chapters of a subject_id.
    chapter_number is 1-based, matching the Firestore `order` field.
    """
    books = TEXTBOOKS.get(subject_id, [])
    url_map: dict[int, str] = {}

    for book in books:
        code = book["code"]
        offset = book["chapter_offset"]

        for ch in book["chapters"]:
            order = ch + offset          # Firestore chapter order number
            url = make_pdf_url(code, ch)

            if verify:
                ok = verify_url(url)
                status = "✓" if ok else "✗"
                print(f"    [{status}] ch{order:02d} → {url}")
                if ok:
                    url_map[order] = url
            else:
                url_map[order] = url

    return url_map


def get_firestore_chapters(db, subject_id: str) -> list[dict]:
    """Fetch all Firestore chapter documents for a subject."""
    docs = db.collection("chapters").where("subject_id", "==", subject_id).stream()
    return [{"id": doc.id, **doc.to_dict()} for doc in docs]


def update_chapters_in_firestore(db, subject_id: str, url_map: dict[int, str]) -> int:
    """
    For each chapter doc in Firestore with matching subject_id,
    sets ncert_pdf_url based on chapter `order` field.
    Uses batch writes to avoid per-document gRPC round-trips.
    Returns number of documents updated.
    """
    chapters = get_firestore_chapters(db, subject_id)
    updated = 0
    batch = db.batch()

    for ch in chapters:
        order = ch.get("order", 0)
        url = url_map.get(order)
        if url:
            ref = db.collection("chapters").document(ch["id"])
            batch.update(ref, {"ncert_pdf_url": url})
            print(f"  ✓ chapters/{ch['id']}  (order={order}) → {url}")
            updated += 1
        else:
            print(f"  ○ chapters/{ch['id']}  (order={order}) — no NCERT URL mapped")

    if updated > 0:
        batch.commit()

    return updated


# ══════════════════════════════════════════════════════════════════════════════
# MAIN
# ══════════════════════════════════════════════════════════════════════════════

def main():
    parser = argparse.ArgumentParser(description="NCERT PDF URL scraper & Firestore updater")
    parser.add_argument("--update", action="store_true",
                        help="Write ncert_pdf_url to Firestore (default: dry run)")
    parser.add_argument("--verify", action="store_true",
                        help="HEAD-check each URL against NCERT server (slow)")
    parser.add_argument("--subject", default=None,
                        help="Only process this subject_id (e.g. math_9th)")
    args = parser.parse_args()

    subjects = [args.subject] if args.subject else list(TEXTBOOKS.keys())

    if args.update:
        if not os.path.exists(SA_PATH):
            print(f"ERROR: Service-account not found at {SA_PATH}")
            sys.exit(1)
        cred = credentials.Certificate(SA_PATH)
        firebase_admin.initialize_app(cred)
        db = firestore.client()
    else:
        db = None

    total_updated = 0

    for subject_id in subjects:
        print(f"\n{'─'*60}")
        print(f"Subject: {subject_id}")
        url_map = build_chapter_url_map(subject_id, verify=args.verify)

        if not args.update:
            # Dry run — just print the mapping
            for order, url in sorted(url_map.items()):
                print(f"  ch{order:02d} → {url}")
        else:
            n = update_chapters_in_firestore(db, subject_id, url_map)
            total_updated += n

    if args.update:
        print(f"\n✅ Done — {total_updated} chapter documents updated with ncert_pdf_url.")
    else:
        print(f"\n(Dry run — pass --update to write to Firestore)")


if __name__ == "__main__":
    main()
