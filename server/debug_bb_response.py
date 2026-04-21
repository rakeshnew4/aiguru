#!/usr/bin/env python3
"""
debug_bb_response.py - Diagnose BB response parsing issues.

Reads the last response.json, runs it through the sanitizer step by step,
then makes a live API call to reproduce the issue.
"""

import json, sys, os, re

sys.path.insert(0, os.path.dirname(__file__))

# ─────────────────────────────────────────────────────────────────────────────
# Step 1: Replicate the sanitizer logic inline so we can trace each step
# ─────────────────────────────────────────────────────────────────────────────

def _remove_trailing_commas(text: str) -> str:
    return re.sub(r",\s*([}\]])", r"\1", text)

_VALID_AFTER = frozenset('"\\\/bfnrtu')
def _fix_invalid_escapes(text: str) -> str:
    def _replacer(m):
        pair = m.group(0)
        return pair if pair[1] in _VALID_AFTER else ('\\\\' + pair[1:])
    return re.sub(r'\\(.)', _replacer, text, flags=re.DOTALL)

def extract_json_safe_traced(text):
    stripped = text.strip()
    if stripped.startswith("```"):
        stripped = re.sub(r"^```(?:json)?\s*", "", stripped)
        stripped = re.sub(r"\s*```\s*$", "", stripped)

    print(f"  text length: {len(stripped)}")
    print(f"  first 80 chars: {repr(stripped[:80])}")
    print(f"  last  80 chars: {repr(stripped[-80:])}")

    # Fast path
    try:
        r = json.loads(stripped)
        print("  ✅ Fast path succeeded")
        return r
    except json.JSONDecodeError as e:
        print(f"  ❌ Fast path failed: {e}")

    # Trailing comma path
    try:
        r = json.loads(_remove_trailing_commas(stripped))
        print("  ✅ Trailing-comma path succeeded")
        return r
    except json.JSONDecodeError as e:
        print(f"  ❌ Trailing-comma path failed: {e}")

    # Fix escapes + trailing commas
    fixed = _fix_invalid_escapes(_remove_trailing_commas(stripped))
    try:
        r = json.loads(fixed)
        print("  ✅ Fix-escapes path succeeded")
        return r
    except json.JSONDecodeError as e:
        print(f"  ❌ Fix-escapes path failed: {e}")

    # Brace scanner
    start = stripped.find('{')
    if start < 0:
        raise ValueError("No JSON object found in text")
    print(f"  Brace scanner: starting at index {start}")

    depth = 0
    in_string = False
    i = start
    while i < len(stripped):
        ch = stripped[i]
        if in_string:
            if ch == '\\':
                i += 2
                continue
            if ch == '"':
                in_string = False
        else:
            if ch == '"':
                in_string = True
            elif ch == '{':
                depth += 1
            elif ch == '}':
                depth -= 1
                if depth == 0:
                    candidate = stripped[start:i + 1]
                    print(f"  Brace scanner found candidate: len={len(candidate)}, last 40: {repr(candidate[-40:])}")
                    for attempt_name, attempt in [
                        ("direct", candidate),
                        ("trailing-commas", _remove_trailing_commas(candidate)),
                        ("fix-escapes", _fix_invalid_escapes(_remove_trailing_commas(candidate))),
                    ]:
                        try:
                            r = json.loads(attempt)
                            print(f"  ✅ Brace scanner {attempt_name} succeeded")
                            return r
                        except json.JSONDecodeError as e:
                            print(f"  ❌ Brace scanner {attempt_name} failed: {e}")
                    raise json.JSONDecodeError("All repair strategies failed", candidate, 0)
        i += 1

    raise ValueError("Unmatched braces — no complete JSON object found")

# ─────────────────────────────────────────────────────────────────────────────
# Step 2: Analyse response.json
# ─────────────────────────────────────────────────────────────────────────────

RESPONSE_JSON = os.path.join(os.path.dirname(__file__), "response.json")

print("=" * 70)
print("STEP 1: Analyse response.json")
print("=" * 70)

with open(RESPONSE_JSON) as f:
    result = json.load(f)

text_content = result.get("text", "")
print(f"result['text'] length: {len(text_content)}")
print(f"result['text'] first 120: {repr(text_content[:120])}")
print(f"result['text'] last  120: {repr(text_content[-120:])}")

# ─────────────────────────────────────────────────────────────────────────────
# Step 3: Run extract_json_safe_traced on it
# ─────────────────────────────────────────────────────────────────────────────

print()
print("=" * 70)
print("STEP 2: Running extract_json_safe on result['text']")
print("=" * 70)

try:
    parsed = extract_json_safe_traced(text_content)
    print(f"\nParsed OK. Top-level keys: {list(parsed.keys())}")
    steps = parsed.get("steps", [])
    print(f"Number of steps: {len(steps)}")
    for i, step in enumerate(steps):
        frames = step.get("frames", [])
        print(f"  Step {i}: keys={list(step.keys())}, frames={len(frames)}")
        for j, frame in enumerate(frames[:3]):
            nested_frames = frame.get("frames", [])
            print(f"    Frame {j}: frame_type={frame.get('frame_type')}, has_nested_frames={bool(nested_frames)}, nested_count={len(nested_frames)}")
except Exception as e:
    print(f"FAILED: {e}")
    parsed = None

# ─────────────────────────────────────────────────────────────────────────────
# Step 4: Run _sanitize_bb_response logic and measure output
# ─────────────────────────────────────────────────────────────────────────────

print()
print("=" * 70)
print("STEP 3: Run sanitizer and check output size")
print("=" * 70)

if parsed is not None:
    steps = parsed.get("steps")
    if isinstance(steps, list):
        _VALID_TTS = {"android", "gemini", "google"}
        _VALID_ROLES = {"teacher", "assistant", "quiz", "feedback"}
        coercions = 0
        for step in steps:
            for frame in step.get("frames", []):
                dm = frame.get("duration_ms")
                if dm is not None and not isinstance(dm, int):
                    frame["duration_ms"] = int(dm) if str(dm).isdigit() else 2500
                    coercions += 1
                qci = frame.get("quiz_correct_index")
                if qci is not None and not isinstance(qci, int):
                    try:
                        frame["quiz_correct_index"] = int(qci)
                    except:
                        frame["quiz_correct_index"] = -1
                    coercions += 1
                if frame.get("tts_engine") not in _VALID_TTS:
                    frame["tts_engine"] = "gemini"
                    coercions += 1
                if frame.get("voice_role") not in _VALID_ROLES:
                    frame["voice_role"] = "teacher"
                    coercions += 1
        sanitized = json.dumps(parsed, ensure_ascii=False)
        print(f"Coercions applied: {coercions}")
        print(f"Output length: {len(sanitized)} (input was {len(text_content)})")
        print(f"Output last 80: {repr(sanitized[-80:])}")
        if len(sanitized) < len(text_content) * 0.5:
            print("⚠️  WARNING: Output is significantly smaller than input!")
            print("   Possible cause: parsed dict missing fields vs raw text")

# ─────────────────────────────────────────────────────────────────────────────
# Step 5: Check Android-expected structure
# ─────────────────────────────────────────────────────────────────────────────

print()
print("=" * 70)
print("STEP 4: Check structure for Android compatibility")
print("=" * 70)

if parsed:
    steps = parsed.get("steps", [])
    issues = []
    for i, step in enumerate(steps):
        if "title" not in step:
            issues.append(f"Step {i}: missing 'title' field (has: {list(step.keys())[:5]})")
        if "lang" not in step:
            issues.append(f"Step {i}: missing 'lang' field")
        for j, frame in enumerate(step.get("frames", [])[:5]):
            if "frames" in frame:
                issues.append(f"Step {i} Frame {j}: has NESTED 'frames' sub-array (wrong structure!)")
            for required in ["frame_type", "text", "speech", "tts_engine", "voice_role", "duration_ms"]:
                if required not in frame:
                    issues.append(f"Step {i} Frame {j}: missing '{required}'")
    if issues:
        print("❌ Structure issues found:")
        for issue in issues:
            print(f"   {issue}")
    else:
        print("✅ Structure looks correct")

# ─────────────────────────────────────────────────────────────────────────────
# Step 6: Live API call
# ─────────────────────────────────────────────────────────────────────────────

print()
print("=" * 70)
print("STEP 5: Live API call (blackboard mode)")
print("=" * 70)

try:
    import firebase_admin
    from firebase_admin import credentials, auth as fb_auth
    import requests

    sa_path = os.path.join(os.path.dirname(__file__), "firebase_serviceaccount.json")
    if not firebase_admin._apps:
        cred = credentials.Certificate(sa_path)
        firebase_admin.initialize_app(cred)

    TARGET_UID = "RFQp1HbSRDOvE8xeC0YSP5bdBBi2"
    token = fb_auth.create_custom_token(TARGET_UID).decode("utf-8")

    # Exchange custom token for ID token via Firebase REST API
    with open(sa_path) as f:
        sa = json.load(f)
    project_id = sa.get("project_id", "aiguru-61bd1")

    exchange_url = f"https://identitytoolkit.googleapis.com/v1/accounts:signInWithCustomToken?key="
    # Need the Web API key — get from environment or skip
    web_api_key = os.getenv("FIREBASE_WEB_API_KEY", "")
    if not web_api_key:
        print("⚠️  FIREBASE_WEB_API_KEY not set, skipping live API call.")
        print("   Set it with: export FIREBASE_WEB_API_KEY=<your key>")
    else:
        resp = requests.post(
            f"https://identitytoolkit.googleapis.com/v1/accounts:signInWithCustomToken?key={web_api_key}",
            json={"token": token, "returnSecureToken": True},
        )
        id_token = resp.json().get("idToken")
        if not id_token:
            print(f"❌ Could not get ID token: {resp.text}")
        else:
            print(f"✅ Got ID token (first 30): {id_token[:30]}...")
            payload = {
                "user_id": TARGET_UID,
                "question": "What is photosynthesis?",
                "page_id": "blackboard__chunk",
                "mode": "blackboard",
                "student_level": 7,
                "user_plan": "premium",
                "language_tag": "en-US",
                "history": [],
            }
            print(f"POST /chat-stream → {payload['question']}")
            with requests.post(
                "http://localhost:8000/chat-stream",
                json=payload,
                headers={"Authorization": f"Bearer {id_token}"},
                stream=True,
                timeout=60,
            ) as r:
                frames_received = 0
                has_text_frame = False
                for raw_line in r.iter_lines():
                    if not raw_line:
                        continue
                    line = raw_line.decode("utf-8")
                    if not line.startswith("data:"):
                        print(f"  keepalive: {line}")
                        continue
                    data_str = line[5:].strip()
                    try:
                        frame = json.loads(data_str)
                    except Exception as e:
                        print(f"  ❌ Non-JSON frame: {repr(data_str[:100])}")
                        continue
                    frames_received += 1
                    if "status" in frame:
                        print(f"  [{frames_received}] status={frame['status']} progress={frame.get('progress')}")
                    elif "text" in frame:
                        has_text_frame = True
                        txt = frame["text"]
                        print(f"  [{frames_received}] TEXT frame: len={len(txt)}, cached={frame.get('cached')}")
                        print(f"    first 200: {repr(txt[:200])}")
                        # Try to parse it
                        try:
                            parsed_text = json.loads(txt)
                            steps = parsed_text.get("steps", [])
                            print(f"    ✅ Valid JSON — steps={len(steps)}")
                            for si, step in enumerate(steps):
                                print(f"       Step {si}: title={repr(step.get('title','?'))[:40]}, frames={len(step.get('frames',[]))}")
                        except Exception as pe:
                            print(f"    ❌ text is NOT valid JSON: {pe}")
                    elif "done" in frame:
                        print(f"  [{frames_received}] DONE: {frame}")
                    elif "error" in frame:
                        print(f"  [{frames_received}] ❌ ERROR: {frame['error']}")
                    else:
                        print(f"  [{frames_received}] other: {list(frame.keys())}")

                print(f"\nTotal frames: {frames_received}, has_text_frame={has_text_frame}")

except ImportError:
    print("⚠️  firebase_admin or requests not installed — skipping live call")
except Exception as e:
    print(f"❌ Live API call failed: {e}")
    import traceback; traceback.print_exc()
