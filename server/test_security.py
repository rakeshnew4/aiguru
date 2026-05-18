#!/usr/bin/env python3
"""
Security sanity tests for the AI Guru backend (gunicorn/nginx).

Tests cover:
  1. Admin HTTP Basic auth
  2. Firebase Bearer auth enforcement
  3. Debug endpoints hidden in production
  4. Nginx security headers
  5. CORS lockdown
  6. HTTP → HTTPS redirect
  7. Path traversal
  8. Oversized payload rejection
  9. HTTP method enforcement
 10. Admin password strength warning

Run:
    python test_security.py [--base-url https://vkpremium.art]
"""

import argparse
import sys
import time
import requests
from requests.auth import HTTPBasicAuth

# ── Config ────────────────────────────────────────────────────────────────────

DEFAULT_BASE = "https://vkpremium.art"
ADMIN_USER = "admin"
ADMIN_PASS = "admin123"

GREEN  = "\033[32m"
RED    = "\033[31m"
YELLOW = "\033[33m"
RESET  = "\033[0m"
BOLD   = "\033[1m"

# ── Helpers ───────────────────────────────────────────────────────────────────

results = []

def ok(name: str, detail: str = ""):
    results.append(("PASS", name, detail))
    print(f"  {GREEN}✓ PASS{RESET}  {name}" + (f"  ({detail})" if detail else ""))

def fail(name: str, detail: str = ""):
    results.append(("FAIL", name, detail))
    print(f"  {RED}✗ FAIL{RESET}  {name}" + (f"  — {detail}" if detail else ""))

def warn(name: str, detail: str = ""):
    results.append(("WARN", name, detail))
    print(f"  {YELLOW}⚠ WARN{RESET}  {name}" + (f"  — {detail}" if detail else ""))

class _FakeResponse:
    """Returned when the server abruptly drops the connection (e.g. nginx blocking a bad request)."""
    def __init__(self):
        self.status_code = 444  # nginx non-standard 'connection closed'
        self.headers: dict = {}
        self.text = ""

def get(base: str, path: str, **kwargs) -> requests.Response:
    try:
        return requests.get(base + path, timeout=10, allow_redirects=False, **kwargs)
    except (requests.exceptions.ConnectionError, requests.exceptions.ChunkedEncodingError):
        return _FakeResponse()

def post(base: str, path: str, timeout: int = 10, **kwargs) -> requests.Response:
    try:
        return requests.post(base + path, timeout=timeout, allow_redirects=False, **kwargs)
    except (requests.exceptions.ConnectionError, requests.exceptions.ChunkedEncodingError):
        return _FakeResponse()

# ── Test groups ───────────────────────────────────────────────────────────────

def test_admin_auth(base: str):
    print(f"\n{BOLD}[1] Admin HTTP Basic auth{RESET}")

    r = get(base, "/admin/api/stats")
    if r.status_code == 401:
        ok("No credentials → 401", f"got {r.status_code}")
    else:
        fail("No credentials → 401", f"got {r.status_code}")

    r = get(base, "/admin/api/stats", auth=HTTPBasicAuth("admin", "wrongpass"))
    if r.status_code == 401:
        ok("Wrong password → 401", f"got {r.status_code}")
    else:
        fail("Wrong password → 401", f"got {r.status_code}")

    r = get(base, "/admin/api/stats", auth=HTTPBasicAuth("notadmin", ADMIN_PASS))
    if r.status_code == 401:
        ok("Wrong username → 401", f"got {r.status_code}")
    else:
        fail("Wrong username → 401", f"got {r.status_code}")

    r = get(base, "/admin/api/stats", auth=HTTPBasicAuth(ADMIN_USER, ADMIN_PASS))
    if r.status_code == 200:
        ok("Correct credentials → 200", f"got {r.status_code}")
    else:
        fail("Correct credentials → 200", f"got {r.status_code}")

    # Timing: wrong vs right — response times should be similar (constant-time compare)
    times_wrong = []
    times_right = []
    for _ in range(5):
        t0 = time.perf_counter()
        get(base, "/admin/api/stats", auth=HTTPBasicAuth("admin", "wrongpass"))
        times_wrong.append(time.perf_counter() - t0)
        t0 = time.perf_counter()
        get(base, "/admin/api/stats", auth=HTTPBasicAuth(ADMIN_USER, ADMIN_PASS))
        times_right.append(time.perf_counter() - t0)
    avg_wrong = sum(times_wrong) / len(times_wrong)
    avg_right = sum(times_right) / len(times_right)
    ratio = max(avg_wrong, avg_right) / (min(avg_wrong, avg_right) + 1e-9)
    if ratio < 5:
        ok("Timing difference between wrong/right creds is low", f"ratio={ratio:.1f}x")
    else:
        warn("Large timing difference between wrong/right creds", f"ratio={ratio:.1f}x — may allow timing oracle")

    if ADMIN_PASS in ("admin123", "admin", "password", "123456", "test"):
        warn("Admin password is weak", f"'{ADMIN_PASS}' is a commonly guessed value — change in .env")


def test_bearer_auth(base: str):
    print(f"\n{BOLD}[2] Firebase Bearer auth enforcement{RESET}")

    protected = "/users/quota"  # Firebase-protected GET endpoint

    r = get(base, protected)
    if r.status_code == 401:
        ok("No Authorization header → 401", f"got {r.status_code}")
    elif r.status_code == 444:
        ok("No Authorization header → connection dropped by server")
    else:
        fail("No Authorization header check", f"got {r.status_code} on {protected} — route may be unprotected")

    r = get(base, protected, headers={"Authorization": "Bearer notavalidtoken"})
    if r.status_code == 401:
        ok("Garbage Bearer token → 401", f"got {r.status_code}")
    elif r.status_code == 444:
        ok("Garbage Bearer token → connection dropped")
    else:
        fail("Garbage Bearer token not rejected", f"got {r.status_code}")

    r = get(base, protected, headers={"Authorization": "Bearer ' OR '1'='1"})
    if r.status_code in (401, 422, 444):
        ok("SQL-injection-style token → rejected", f"got {r.status_code}")
    else:
        fail("SQL-injection-style token not rejected", f"got {r.status_code}")

    r = get(base, protected, headers={"Authorization": "Basic dXNlcjpwYXNz"})
    if r.status_code in (401, 444):
        ok("Basic auth on Bearer-only route → rejected", f"got {r.status_code}")
    else:
        warn("Basic auth scheme on Bearer route", f"got {r.status_code}")


def test_debug_endpoints(base: str):
    print(f"\n{BOLD}[3] Debug endpoints hidden in production{RESET}")

    for path in ("/docs", "/redoc", "/openapi.json"):
        r = get(base, path)
        if r.status_code in (404, 444):
            ok(f"{path} not exposed", f"got {r.status_code}")
        else:
            fail(f"{path} is exposed", f"got {r.status_code} — set DEBUG=false in .env")


def test_security_headers(base: str):
    print(f"\n{BOLD}[4] Nginx security headers{RESET}")

    r = get(base, "/")
    h = {k.lower(): v for k, v in r.headers.items()}

    checks = {
        "x-frame-options":          ("present", None),
        "x-content-type-options":   ("present", None),
        "strict-transport-security":("present", None),
        "referrer-policy":          ("present", None),
    }

    for header, (check, expected) in checks.items():
        if header in h:
            ok(f"{header}: {h[header]}")
        else:
            fail(f"{header} missing")

    # Server header should not reveal version
    server = h.get("server", "")
    if server and any(c.isdigit() for c in server):
        warn("Server header reveals version", f"'{server}' — add 'server_tokens off' in nginx.conf")
    else:
        ok("Server header does not reveal version", f"'{server}'")

    # X-Powered-By should not be present
    if "x-powered-by" not in h:
        ok("X-Powered-By not exposed")
    else:
        warn("X-Powered-By exposed", f"'{h['x-powered-by']}'")


def test_cors(base: str):
    print(f"\n{BOLD}[5] CORS lockdown{RESET}")

    evil_origin = "https://evil.example.com"
    r = get(base, "/admin/api/stats",
            auth=HTTPBasicAuth(ADMIN_USER, ADMIN_PASS),
            headers={"Origin": evil_origin})
    acao = r.headers.get("access-control-allow-origin", "")
    if acao == "*":
        fail("Wildcard CORS on admin endpoint", "access-control-allow-origin: *")
    elif evil_origin in acao:
        fail("Arbitrary origin reflected on admin endpoint", f"got: {acao}")
    else:
        ok("Arbitrary origin not reflected on admin endpoint", f"acao='{acao or '(not set)'}'")

    r = get(base, "/",
            headers={"Origin": evil_origin})
    acao = r.headers.get("access-control-allow-origin", "")
    if acao == "*":
        warn("Wildcard CORS on root endpoint", "may be intentional if API is public")
    elif evil_origin in acao:
        warn("Arbitrary origin reflected on root", f"got: {acao}")
    else:
        ok("Arbitrary origin not reflected on root", f"acao='{acao or '(not set)'}'")


def test_http_redirect(base: str):
    print(f"\n{BOLD}[6] HTTP → HTTPS redirect{RESET}")

    http_base = base.replace("https://", "http://")
    try:
        r = requests.get(http_base + "/", timeout=5, allow_redirects=False)
        if r.status_code in (301, 302, 307, 308):
            loc = r.headers.get("location", "")
            if loc.startswith("https://"):
                ok("HTTP redirects to HTTPS", f"{r.status_code} → {loc}")
            else:
                warn("HTTP redirects but not to HTTPS", f"location: {loc}")
        else:
            fail("HTTP does not redirect", f"got {r.status_code}")
    except requests.exceptions.ConnectionError:
        warn("Port 80 not reachable", "may be firewalled — verify nginx listens on :80")


def test_path_traversal(base: str):
    print(f"\n{BOLD}[7] Path traversal{RESET}")

    payloads = [
        "/../../../../etc/passwd",
        "/%2e%2e/%2e%2e/etc/passwd",
        "/static/../../../etc/passwd",
    ]
    for path in payloads:
        r = get(base, path)
        body = r.text[:200].lower()
        if "root:" in body or "/bin/bash" in body:
            fail(f"Path traversal leaked /etc/passwd via {path}")
        elif r.status_code in (400, 403, 404):
            ok(f"Traversal attempt blocked", f"{path} → {r.status_code}")
        else:
            ok(f"Traversal attempt did not leak", f"{path} → {r.status_code}")


def test_large_payload(base: str):
    print(f"\n{BOLD}[8] Oversized payload rejection{RESET}")

    payload = "A" * (20 * 1024 * 1024)  # 20 MB
    try:
        r = post(base, "/chat-stream",
                 data=payload,
                 headers={"Content-Type": "application/json"},
                 timeout=15)
        if r.status_code in (413, 400, 401, 422):
            ok("20 MB body rejected", f"got {r.status_code}")
        else:
            warn("20 MB body not explicitly rejected", f"got {r.status_code}")
    except requests.exceptions.ConnectionError:
        ok("20 MB body caused connection reset (nginx client_max_body_size)")
    except requests.exceptions.Timeout:
        warn("Request timed out on large payload", "nginx may not have a body size limit")


def test_method_enforcement(base: str):
    print(f"\n{BOLD}[9] HTTP method enforcement{RESET}")

    r = requests.delete(base + "/admin/api/stats", timeout=10,
                        auth=HTTPBasicAuth(ADMIN_USER, ADMIN_PASS))
    if r.status_code == 405:
        ok("DELETE on GET-only admin/stats → 405")
    elif r.status_code in (401, 403):
        ok("DELETE on admin/stats rejected by auth", f"got {r.status_code}")
    else:
        warn("DELETE on GET-only route", f"got {r.status_code}")

    r = requests.options(base + "/admin/api/stats", timeout=10)
    sensitive_methods = {"DELETE", "PUT", "PATCH", "TRACE", "CONNECT"}
    allow_header = r.headers.get("allow", r.headers.get("Allow", ""))
    exposed = sensitive_methods & set(m.strip() for m in allow_header.split(","))
    if not exposed:
        ok("OPTIONS does not expose dangerous methods", f"Allow: {allow_header or '(not set)'}")
    else:
        warn("OPTIONS exposes potentially dangerous methods", f"{exposed}")


# ── Main ──────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default=DEFAULT_BASE)
    args = parser.parse_args()
    base = args.base_url.rstrip("/")

    print(f"\n{BOLD}Security sanity tests → {base}{RESET}")
    print("=" * 60)

    test_admin_auth(base)
    test_bearer_auth(base)
    test_debug_endpoints(base)
    test_security_headers(base)
    test_cors(base)
    test_http_redirect(base)
    test_path_traversal(base)
    test_large_payload(base)
    test_method_enforcement(base)

    # ── Summary ───────────────────────────────────────────────────────────────
    passed = sum(1 for s, _, _ in results if s == "PASS")
    failed = sum(1 for s, _, _ in results if s == "FAIL")
    warned = sum(1 for s, _, _ in results if s == "WARN")

    print(f"\n{'=' * 60}")
    print(f"{BOLD}Summary:{RESET}  "
          f"{GREEN}{passed} passed{RESET}  "
          f"{RED}{failed} failed{RESET}  "
          f"{YELLOW}{warned} warnings{RESET}")

    if failed:
        print(f"\n{RED}FAILED checks:{RESET}")
        for s, name, detail in results:
            if s == "FAIL":
                print(f"  • {name}" + (f": {detail}" if detail else ""))

    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
