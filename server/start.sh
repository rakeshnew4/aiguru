#!/usr/bin/env bash
# Production startup script for AI Guru backend.
#
# Uses Gunicorn with Uvicorn workers:
#   - Each worker is a full OS process → true parallelism, no GIL contention
#   - Uvicorn worker class handles async I/O (SSE streams, concurrent requests)
#   - Workers = (2 × CPU cores) + 1  is the standard formula
#
# Usage:
#   ./start.sh                     # production (auto worker count)
#   WORKERS=2 ./start.sh           # override worker count
#   ./start.sh --reload            # dev mode (single worker + hot reload)

set -e

cd "$(dirname "$0")"

PORT=${PORT:-8003}
HOST=${HOST:-0.0.0.0}
WORKERS=${WORKERS:-$(( $(nproc) * 2 + 1 ))}

# Dev / reload mode: use plain uvicorn (gunicorn doesn't support --reload well)
if [[ "$1" == "--reload" ]]; then
    echo "[start.sh] Dev mode — uvicorn with --reload (1 worker)"
    exec uvicorn app.main:app \
        --host "$HOST" \
        --port "$PORT" \
        --reload \
        --log-level info
fi

echo "[start.sh] Starting production server — host=$HOST port=$PORT workers=$WORKERS"

exec gunicorn app.main:app \
    --worker-class uvicorn.workers.UvicornWorker \
    --workers "$WORKERS" \
    --bind "$HOST:$PORT" \
    --timeout 120 \
    --graceful-timeout 30 \
    --keep-alive 5 \
    --access-logfile - \
    --error-logfile - \
    --log-level info \
    --forwarded-allow-ips "*"
