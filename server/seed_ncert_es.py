"""
"""seed_ncert_es.py — Seed all NCERT chapters (PDF → chunks → embeddings → Elasticsearch).

Usage (from server/ directory):
    python seed_ncert_es.py [--subject science_10th] [--batch 1.0] [--reset]
    python seed_ncert_es.py --pdf-only          # download & cache PDFs without indexing
    python seed_ncert_es.py --from-cache-only   # index from local cache, no HTTP requests

PDFs are cached in server/ncert_pdfs/<chapter_id>.pdf so the script can be re-run
without hitting ncert.nic.in again. Safe to re-run — already-indexed chapters are skipped.

Run after starting ES:
    docker compose -f server/youtube_extractor/docker-compose.yml up -d
    cd server && python seed_ncert_es.py --subject science_10th   # test one subject
    python seed_ncert_es.py                                        # all 454 chapters

Estimated time: ~60-90 min for 454 chapters (embed dominates; PDF download is fast once cached).
"""

import argparse
import asyncio
import hashlib
import io
import logging
import os
import sys
import time
from pathlib import Path
from typing import Optional

import requests

# ── Local PDF cache directory ─────────────────────────────────────────────────
# PDFs are stored as ncert_pdfs/<chapter_id>.pdf
# Re-runs skip the HTTP request if the file already exists.
PDF_CACHE_DIR = Path(os.path.dirname(os.path.abspath(__file__))) / "ncert_pdfs"
PDF_CACHE_DIR.mkdir(exist_ok=True)

# ── Ensure server/ is on path ─────────────────────────────────────────────────
_HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, _HERE)

os.environ.setdefault("GOOGLE_APPLICATION_CREDENTIALS",
                      os.path.join(_HERE, "google_tts_serviceaccount.json"))

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s  %(levelname)-7s  %(message)s",
    datefmt="%H:%M:%S",
)
logger = logging.getLogger("seed_ncert")

# ── Imports (after path setup) ────────────────────────────────────────────────
import firebase_admin
from firebase_admin import credentials, firestore

from ncert_extractor.indexer import (
    get_es, ensure_index, embed_texts, chunk_text
)
from ncert_extractor.config import ES_INDEX, EMBED_DIMS


# ── Firebase init ─────────────────────────────────────────────────────────────
_SA_PATH = os.path.join(os.path.dirname(_HERE), "firebase_serviceaccount.json")
if not firebase_admin._apps:
    cred = credentials.Certificate(_SA_PATH)
    firebase_admin.initialize_app(cred)
_db = firestore.client()


# ── PDF text extraction ───────────────────────────────────────────────────────
def _extract_pdf_text(pdf_bytes: bytes) -> str:
    """Extract all text from a PDF using pypdf (already in requirements.txt)."""
    try:
        from pypdf import PdfReader
        reader = PdfReader(io.BytesIO(pdf_bytes))
        pages = []
        for page in reader.pages:
            text = page.extract_text() or ""
            pages.append(text)
        return "\n\n".join(pages)
    except Exception as exc:
        logger.warning("PDF extraction failed: %s", exc)
        return ""


def _pdf_cache_path(chapter_id: str) -> Path:
    return PDF_CACHE_DIR / f"{chapter_id}.pdf"


def _get_pdf(chapter_id: str, url: str, from_cache_only: bool = False, timeout: int = 30) -> Optional[bytes]:
    """Return PDF bytes — from local cache if available, otherwise download and cache."""
    cache_path = _pdf_cache_path(chapter_id)

    # Hit local cache first
    if cache_path.exists():
        try:
            data = cache_path.read_bytes()
            if data:
                logger.info("  → PDF from cache (%d KB)", len(data) // 1024)
                return data
        except Exception as exc:
            logger.warning("  → cache read error: %s", exc)

    if from_cache_only:
        logger.warning("  → not in cache and --from-cache-only set, skipping")
        return None

    # Download
    try:
        headers = {"User-Agent": "Mozilla/5.0 (compatible; AiGuru-Edu/1.0; +https://aiguruapp.in)"}
        resp = requests.get(url, headers=headers, timeout=timeout)
        if resp.status_code == 200 and resp.content:
            # Save to local cache
            try:
                cache_path.write_bytes(resp.content)
                logger.info("  → downloaded & cached (%d KB) → %s", len(resp.content) // 1024, cache_path.name)
            except Exception as exc:
                logger.warning("  → cache write error: %s", exc)
            return resp.content
        logger.warning("  → download HTTP %d: %s", resp.status_code, url)
        return None
    except Exception as exc:
        logger.warning("  → download error %s: %s", url, exc)
        return None


# ── ES helpers ────────────────────────────────────────────────────────────────
async def _chapter_already_indexed(chapter_id: str) -> bool:
    """Return True if this chapter_id has any docs in ES."""
    es = get_es()
    try:
        result = await es.count(
            index=ES_INDEX,
            body={"query": {"term": {"chapter_id": chapter_id}}}
        )
        return result["count"] > 0
    except Exception:
        return False


async def _index_chapter(
    chapter_id: str,
    subject_id: str,
    grade: str,
    title: str,
    text: str,
) -> int:
    """Chunk, embed, and bulk-index one chapter. Returns number of docs indexed."""
    chunks = chunk_text(text)
    if not chunks:
        logger.warning("  → no chunks extracted for %s", chapter_id)
        return 0

    # Embed in batches of 10 (Vertex AI rate limit)
    BATCH = 10
    all_embeddings = []
    for i in range(0, len(chunks), BATCH):
        batch = chunks[i:i + BATCH]
        embeddings = await embed_texts(batch)
        all_embeddings.extend(embeddings)
        if i + BATCH < len(chunks):
            await asyncio.sleep(0.5)   # be kind to Vertex AI quota

    if len(all_embeddings) != len(chunks):
        logger.warning("  → embedding count mismatch for %s", chapter_id)
        return 0

    # Bulk index
    es = get_es()
    bulk_body = []
    for idx, (chunk, emb) in enumerate(zip(chunks, all_embeddings)):
        bulk_body.append({"index": {"_index": ES_INDEX}})
        bulk_body.append({
            "chapter_id":    chapter_id,
            "subject_id":    subject_id,
            "grade":         grade,
            "chapter_title": title,
            "chunk_index":   idx,
            "chunk_text":    chunk,
            "embedding":     emb,
        })

    resp = await es.bulk(body=bulk_body)
    errors = [item for item in resp.get("items", []) if item.get("index", {}).get("error")]
    if errors:
        logger.warning("  → %d bulk errors for %s", len(errors), chapter_id)
    indexed = len(chunks) - len(errors)
    return indexed


# ── Main seeding loop ─────────────────────────────────────────────────────────
async def seed(
    subject_filter: Optional[str] = None,
    batch_pause: float = 1.0,
    pdf_only: bool = False,
    from_cache_only: bool = False,
):
    """Fetch all chapters, cache PDFs locally, and index them into ES."""
    if not pdf_only:
        await ensure_index()
    else:
        logger.info("--pdf-only mode: skipping ES indexing")

    # Read chapters from Firestore
    query = _db.collection("chapters")
    if subject_filter:
        query = query.where("subject_id", "==", subject_filter)
    docs = list(query.stream())
    logger.info("Found %d chapters to process%s",
                len(docs), f" (subject: {subject_filter})" if subject_filter else "")

    total_indexed = 0
    total_skipped = 0
    total_failed  = 0

    for i, doc in enumerate(docs, 1):
        data       = doc.to_dict() or {}
        chapter_id = doc.id
        subject_id = data.get("subject_id", "")
        title      = data.get("title", "")
        pdf_url    = data.get("ncert_pdf_url", "")
        grade      = subject_id.rsplit("_", 1)[-1].replace("th", "").replace("st", "").replace("nd", "").replace("rd", "")

        prefix = f"[{i:3d}/{len(docs)}] {chapter_id}"

        if not pdf_url:
            logger.warning("%s  SKIP — no ncert_pdf_url", prefix)
            total_skipped += 1
            continue

        # Skip already-indexed chapters (skip this check in pdf_only mode)
        if not pdf_only and await _chapter_already_indexed(chapter_id):
            logger.info("%s  already indexed — skipping", prefix)
            total_skipped += 1
            continue

        # Skip if PDF already cached and we're in pdf_only mode
        if pdf_only and _pdf_cache_path(chapter_id).exists():
            logger.info("%s  PDF already cached — skipping", prefix)
            total_skipped += 1
            continue

        logger.info("%s  fetching PDF", prefix)
        pdf_bytes = _get_pdf(chapter_id, pdf_url, from_cache_only=from_cache_only)
        if not pdf_bytes:
            logger.warning("%s  FAIL — download error", prefix)
            total_failed += 1
            continue

        text = _extract_pdf_text(pdf_bytes)
        if not text.strip():
            logger.warning("%s  FAIL — no text extracted from PDF", prefix)
            total_failed += 1
            continue

        text_len = len(text)
        logger.info("%s  extracted %d chars", prefix, text_len)

        if pdf_only:
            # PDF download+cache only — no embedding or ES indexing
            continue

        try:
            n = await _index_chapter(chapter_id, subject_id, grade, title, text)
            logger.info("%s  ✓  indexed %d chunks", prefix, n)
            total_indexed += n
        except Exception as exc:
            logger.error("%s  FAIL — indexing error: %s", prefix, exc)
            total_failed += 1

        # Small pause to avoid Vertex AI quota exhaustion
        await asyncio.sleep(batch_pause)

    logger.info(
        "\n── Seeding complete ──\n"
        "  Chapters processed : %d\n"
        "  Chunks indexed     : %d\n"
        "  Chapters skipped   : %d\n"
        "  Chapters failed    : %d",
        len(docs) - total_skipped, total_indexed, total_skipped, total_failed,
    )


async def reset_index():
    """Drop and recreate the ncert_chunks index."""
    es = get_es()
    try:
        exists = await es.indices.exists(index=ES_INDEX)
        if exists:
            await es.indices.delete(index=ES_INDEX)
            logger.info("Deleted index '%s'", ES_INDEX)
    except Exception as exc:
        logger.warning("Could not delete index: %s", exc)
    await ensure_index()
    logger.info("Index '%s' recreated", ES_INDEX)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Seed NCERT chapters to Elasticsearch")
    parser.add_argument("--subject", default=None,
                        help="Seed only one subject (e.g. science_10th)")
    parser.add_argument("--batch", type=float, default=1.0,
                        help="Pause seconds between chapters (default: 1.0)")
    parser.add_argument("--reset", action="store_true",
                        help="Drop and recreate the ES index before seeding")
    parser.add_argument("--pdf-only", action="store_true",
                        help="Only download+cache PDFs locally, skip ES indexing")
    parser.add_argument("--from-cache-only", action="store_true",
                        help="Only index from local PDF cache, never hit ncert.nic.in")
    args = parser.parse_args()

    logger.info("PDF cache directory: %s", PDF_CACHE_DIR)

    async def main():
        if args.reset:
            await reset_index()
        await seed(
            subject_filter=args.subject,
            batch_pause=args.batch,
            pdf_only=args.pdf_only,
            from_cache_only=args.from_cache_only,
        )

    asyncio.run(main())
