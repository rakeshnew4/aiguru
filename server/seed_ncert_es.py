"""seed_ncert_es.py - Seed all NCERT chapters (PDF -> chunks -> embeddings -> Elasticsearch).

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
import collections
import hashlib
import io
import logging
import os
import sys
import time
from pathlib import Path
from typing import Optional

import httpx

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
    get_es, ensure_index, embed_texts, chunk_pages
)
from ncert_extractor.config import ES_INDEX, EMBED_DIMS, PAGES_PER_CHUNK


# ── Firebase init ─────────────────────────────────────────────────────────────
_SA_PATH = os.path.join(_HERE, "firebase_serviceaccount.json")
if not firebase_admin._apps:
    cred = credentials.Certificate(_SA_PATH)
    firebase_admin.initialize_app(cred)
_db = firestore.client()


# ── PDF text extraction ───────────────────────────────────────────────────────
def _extract_pdf_pages(pdf_bytes: bytes) -> list[str]:
    """Return list of page texts (one entry per PDF page)."""
    try:
        from pypdf import PdfReader
        reader = PdfReader(io.BytesIO(pdf_bytes))
        return [page.extract_text() or "" for page in reader.pages]
    except Exception as exc:
        logger.warning("PDF extraction failed: %s", exc)
        return []


def _pdf_cache_path(chapter_id: str) -> Path:
    return PDF_CACHE_DIR / f"{chapter_id}.pdf"


_CHROME_UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/124.0.0.0 Safari/537.36"
)
_DOWNLOAD_HEADERS = {
    "User-Agent": _CHROME_UA,
    "Accept": "application/pdf,*/*",
    "Accept-Language": "en-US,en;q=0.9",
}


async def _get_pdf(
    chapter_id: str,
    url: str,
    client: httpx.AsyncClient,
    from_cache_only: bool = False,
) -> Optional[bytes]:
    """Return PDF bytes from local cache (instant) or async HTTP download."""
    cache_path = _pdf_cache_path(chapter_id)

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

    try:
        resp = await client.get(url, headers=_DOWNLOAD_HEADERS, timeout=40.0)
        if resp.status_code == 200 and resp.content:
            try:
                cache_path.write_bytes(resp.content)
                logger.info("  → downloaded & cached (%d KB) → %s",
                            len(resp.content) // 1024, cache_path.name)
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
    pages: list[str],
) -> int:
    """Group pages into chunks, embed, and bulk-index one chapter. Returns docs indexed."""
    page_chunks = chunk_pages(pages, PAGES_PER_CHUNK)
    if not page_chunks:
        logger.warning("  → no page chunks for %s", chapter_id)
        return 0

    texts = [text for _, _, text in page_chunks]

    # Embed in batches of 10 (Vertex AI rate limit)
    BATCH = 10
    all_embeddings: list = []
    for i in range(0, len(texts), BATCH):
        batch = texts[i:i + BATCH]
        embeddings = await embed_texts(batch)
        all_embeddings.extend(embeddings)
        if i + BATCH < len(texts):
            await asyncio.sleep(0.5)

    if len(all_embeddings) != len(page_chunks):
        logger.warning("  → embedding count mismatch for %s", chapter_id)
        return 0

    # Bulk index
    es = get_es()
    bulk_body = []
    for idx, ((pg_start, pg_end, text), emb) in enumerate(zip(page_chunks, all_embeddings)):
        bulk_body.append({"index": {"_index": ES_INDEX}})
        bulk_body.append({
            "chapter_id":    chapter_id,
            "subject_id":    subject_id,
            "grade":         grade,
            "chapter_title": title,
            "chunk_index":   idx,
            "page_start":    pg_start,
            "page_end":      pg_end,
            "chunk_text":    text,
            "embedding":     emb,
        })

    resp = await es.bulk(body=bulk_body)
    errors = [item for item in resp.get("items", []) if item.get("index", {}).get("error")]
    if errors:
        logger.warning("  → %d bulk errors for %s", len(errors), chapter_id)
    return len(page_chunks) - len(errors)


# ── Per-subject worker (runs sequentially within one subject) ─────────────────
async def _seed_subject(
    subject_id: str,
    docs: list,
    total_docs: int,
    client: httpx.AsyncClient,
    sem: asyncio.Semaphore,
    counters: dict,
    batch_pause: float,
    pdf_only: bool,
    from_cache_only: bool,
) -> None:
    """Download + index all chapters for one subject. Runs under [sem]."""
    async with sem:
        logger.info("── Subject %s: %d chapters", subject_id, len(docs))
        for doc in docs:
            data       = doc.to_dict() or {}
            chapter_id = doc.id
            s_id       = data.get("subject_id", "")
            title      = data.get("title", "")
            pdf_url    = data.get("ncert_pdf_url", "")
            grade      = s_id.rsplit("_", 1)[-1].replace("th","").replace("st","").replace("nd","").replace("rd","")
            prefix     = f"[{subject_id}] {chapter_id}"

            if not pdf_url:
                logger.warning("%s  SKIP — no ncert_pdf_url", prefix)
                counters["skipped"] += 1
                continue

            if not pdf_only and await _chapter_already_indexed(chapter_id):
                logger.info("%s  already indexed — skipping", prefix)
                counters["skipped"] += 1
                continue

            if pdf_only and _pdf_cache_path(chapter_id).exists():
                logger.info("%s  PDF already cached — skipping", prefix)
                counters["skipped"] += 1
                continue

            logger.info("%s  fetching PDF", prefix)
            pdf_bytes = await _get_pdf(chapter_id, pdf_url, client, from_cache_only)
            if not pdf_bytes:
                logger.warning("%s  FAIL — download error", prefix)
                counters["failed"] += 1
                continue

            pdf_pages = _extract_pdf_pages(pdf_bytes)
            if not pdf_pages or not any(p.strip() for p in pdf_pages):
                logger.warning("%s  FAIL — no text extracted", prefix)
                counters["failed"] += 1
                continue

            logger.info("%s  %d pages", prefix, len(pdf_pages))
            if pdf_only:
                continue

            try:
                n = await _index_chapter(chapter_id, s_id, grade, title, pdf_pages)
                logger.info("%s  ✓  %d page-chunks", prefix, n)
                counters["indexed"] += n
            except Exception as exc:
                logger.error("%s  FAIL — %s", prefix, exc)
                counters["failed"] += 1

            await asyncio.sleep(batch_pause)


# ── Main seeding loop ─────────────────────────────────────────────────────────
async def seed(
    subject_filter: Optional[str] = None,
    batch_pause: float = 1.0,
    pdf_only: bool = False,
    from_cache_only: bool = False,
    parallel: int = 3,
):
    """Download PDFs + index into ES.

    Subjects run in parallel (up to [parallel] at once).
    Chapters within each subject run sequentially to avoid Vertex AI rate limits.
    """
    if not pdf_only:
        await ensure_index()
    else:
        logger.info("--pdf-only mode: skipping ES indexing")

    query = _db.collection("chapters")
    if subject_filter:
        query = query.where("subject_id", "==", subject_filter)
    docs = list(query.stream())
    logger.info("Found %d chapters%s",
                len(docs), f" (subject={subject_filter})" if subject_filter else "")

    # Group by subject so each subject is one parallel task
    by_subject: dict = collections.defaultdict(list)
    for doc in docs:
        sid = (doc.to_dict() or {}).get("subject_id", "unknown")
        by_subject[sid].append(doc)

    logger.info("%d subjects → running %d in parallel", len(by_subject), min(parallel, len(by_subject)))

    counters = {"indexed": 0, "skipped": 0, "failed": 0}
    sem = asyncio.Semaphore(parallel)

    async with httpx.AsyncClient(
        follow_redirects=True,
        limits=httpx.Limits(max_connections=parallel * 2, max_keepalive_connections=parallel),
    ) as client:
        tasks = [
            _seed_subject(sid, sdocs, len(docs), client, sem, counters,
                          batch_pause, pdf_only, from_cache_only)
            for sid, sdocs in sorted(by_subject.items())
        ]
        await asyncio.gather(*tasks)

    logger.info(
        "\n── Seeding complete ──\n"
        "  Chunks indexed   : %d\n"
        "  Chapters skipped : %d\n"
        "  Chapters failed  : %d",
        counters["indexed"], counters["skipped"], counters["failed"],
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
    parser.add_argument("--parallel", type=int, default=3,
                        help="Number of subjects to process in parallel (default: 3)")
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
            parallel=args.parallel,
        )

    asyncio.run(main())
