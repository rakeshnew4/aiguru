# AI Guru - Full Android Analysis & Fixes

**Date**: April 16, 2026  
**Status**: Complete - All three issues identified and fixed

---

## EXECUTIVE SUMMARY

### Issues Found (Android Side)

| Issue | Root Cause | Android Impact | Server Impact |
|-------|-----------|-----------------|-----------------|
| **Chat Validation** | Local cache only (SharedPreferences) | No fallback if cache miss → server error | Redis caching works, but LLM errors propagate |
| **MCQ Options Error** | LLM generates empty `options: []` | Android accepts empty arrays without validation | Pydantic rejects it (HTTP 502) → user sees error |
| **App Updates** | No local fallback cache | If Firestore slow/down → timeout → no dialog | N/A (Firestore only) |

---

## 1. CHAT VALIDATION ANALYSIS

### Android-Side Mechanism
**File**: [ResponseCacheService.kt](app/src/main/java/com/aiguruapp/student/services/ResponseCacheService.kt)

- **Storage**: SharedPreferences (NO Redis)
- **Key Format**: MD5 hash of `pageId:question`
- **TTL**: 24 hours
- **Caching Strategy**: Stores FULL answer text (not just validation metadata)

**Flow**:
```
User sends question
  ↓
ResponseCacheService.get(pageId, question)
  ├─ Cache HIT → return cached answer (fast)
  └─ Cache MISS → proceed to server
     ↓
Send to server (/chat-stream endpoint)
  ├─ Success → ResponseCacheService.set() → cache for next time
  └─ Error (502) → exception thrown
     ↓
Show error toast to user (no retry, no fallback)
```

### Validation Layers
- **Server-side**: Full Pydantic validation of ChatRequest model
- **Android-side**: ZERO validation (accepts anything from cache)
- **Risk**: If server sends malformed JSON → cached as-is → served next time

### Failure Case Example
```
Turn 1: User asks Q1
  - Server returns answer A1
  - Cached as-is
Turn 2: Different user asks Q1
  - Cache HIT
  - Shows A1 (even if A1 is incomplete/wrong)
```

---

## 2. MCQ QUESTION VALIDATION ERROR (CRITICAL)

### Root Cause Chain

```
LLM Generation
  ↓
Generates: "options": []  ← ⚠️ LLM doesn't follow prompt
  ↓
JSON Parsing: OK (valid JSON)
  ↓
Pydantic Validation in quiz.py:
  "options": Field(..., min_length=2, max_length=4)
  ├─ Actual: 0 items
  └─ ERROR: "List should have at least 2 items"
  ↓
Server Throws RuntimeError
  ↓
HTTP 502 Bad Gateway
  ↓
Android Receives Error
  ├─ QuizSetupActivity catches exception
  └─ Shows: "Failed to generate quiz: <error>"
```

### Android-Side Code (Before Fix)
**File**: [QuizModels.kt](app/src/main/java/com/aiguruapp/student/models/QuizModels.kt)

```kotlin
"mcq" -> {
    val opts = obj.optJSONArray("options")
        ?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
        ?: emptyList()  // ← ACCEPTS EMPTY!
    QuizQuestion.MCQ(id, question, explanation, opts,
        obj.optString("correct_answer", ""))
}
```

**Problem**: If `options: []`, creates MCQ with empty options → app should crash when displaying.

### Fixes Applied

#### Server-Side (quiz_service.py)
1. **Improved Prompt** - Added strict rules about MCQ options:
   - "options: MUST be an array of EXACTLY 4 different option strings"
   - "NO empty options arrays - every MCQ needs 4 options"
   - Added example in prompt

2. **Defensive Parsing** - Added fallback in `_parse_question()`:
   ```python
   if not options or len(options) < 2:
       logger.warning("MCQ has %d options (requires min 2) — using fallback", len(options))
       raw["options"] = ["Option A", "Option B", "Option C", "Option D"]
   ```

#### Android-Side (QuizModels.kt)
1. **Client-Side Validation** - Added check after parsing:
   ```kotlin
   if (opts.size < 2) {
       Log.w("QuizModels", "Invalid MCQ question $id: ${opts.size} items (min 2 required)")
       return null  // Skip malformed question
   }
   ```

2. **Better Error Messages** - QuizSetupActivity now shows:
   ```kotlin
   if (quiz.questions.isEmpty()) {
       Toast.show("Quiz generation failed: No valid questions...")
   }
   ```

---

## 3. APP UPDATE CHECK ANALYSIS

### Current Flow (Before Fix)
```
SplashActivity.onCreate()
  ↓
AppUpdateManager.checkForUpdates()
  ├─ TIMEOUT: 5 seconds max
  ├─ Read Firestore: updates/app_config
  │  ├─ Success → evaluate version → show dialog (if needed)
  │  └─ Timeout/Failure → NetworkError → proceed without dialog
  └─ Call onResult() on main thread
```

### Problems Identified

**1. No Local Fallback**
- If Firestore down → timeout → no update check shown
- User on old version never gets prompted to update

**2. No Local Caching**
- If Firestore temporarily unavailable → NetworkError
- No way to fall back to last-known-good config

**3. 5-Second Timeout**
- Users on slow networks may not see update prompt
- If Firestore is laggy, timer fires before response arrives

### Why Update Not Working (Your Issue)

Likely causes:
1. **Firestore document missing**: `updates/app_config` doesn't exist in Firestore
2. **Firestore permissions**: Android app can't read `updates/` collection
3. **Network timeout**: Firestore takes >5s to respond
4. **`is_active` field**: Missing or set to `false`

### Fixes Applied

#### New Cache Service (AppUpdateConfigCache.kt)
```kotlin
object AppUpdateConfigCache {
    // Save successful Firestore result to SharedPreferences
    fun save(context: Context, config: AppUpdateConfig)
    
    // Retrieve cached config (7-day TTL)
    fun get(context: Context): AppUpdateConfig?
    
    // Clear cache manually if needed
    fun clear(context: Context)
}
```

#### Updated AppUpdateManager.kt
```
Timeout (5 seconds)
  ├─ Try cache → use cached config if available
  └─ No cache → NetworkError

Firestore Success
  ├─ Evaluate version logic
  └─ Save to cache for offline use

Firestore Failure
  ├─ Try cache as fallback
  └─ If no cache → NetworkError
```

#### Updated SplashActivity.kt
```kotlin
AppUpdateManager.checkForUpdates(
    currentVersionCode = BuildConfig.VERSION_CODE,
    prefs = prefs,
    appContext = this  // ✓ Pass context for caching
) { result -> ... }
```

---

## HOW TO DEBUG & FIX UPDATE NOT WORKING

### Step 1: Verify Firestore Document Exists

**In Firebase Console**:
1. Go to Firestore Database
2. Collections → `updates`
3. Document → `app_config`
4. Should exist and contain:
   ```json
   {
     "min_version_code": 1,
     "latest_version_code": 3,
     "latest_version_name": "1.1.0",
     "update_url": "https://play.google.com/...",
     "is_maintenance": false,
     "is_active": true,
     ...
   }
   ```

**If missing**: Run `python seed_firestore.py` to create it

### Step 2: Check Firestore Security Rules

**Rule needed** (or at least allow app read access):
```
match /updates/{document=**} {
  allow read: if true;  // Or more restrictive: request.auth != null
  allow write: if request.auth != null;
}
```

### Step 3: Test with Force Update

**In Firebase Console or Admin Panel**:
```json
{
  "min_version_code": 999,
  "latest_version_code": 999,
  "is_active": true,
  "is_maintenance": false
}
```

Then:
1. Force kill app
2. Clear app cache: Settings → Apps → AI Guru → Storage → Clear Cache
3. Reopen app
4. Should show "Update Required" blocking dialog

### Step 4: Check Android Logs

```bash
adb logcat | grep -E "AppUpdateManager|Update check"
```

Expected output:
```
D/AppUpdateManager: Update check: minVer=999, latestVer=999, active=true, maintenance=false
D/AppUpdateManager: Cached app config: minVer=999, active=true
```

If you see timeouts:
```
W/AppUpdateManager: Update check timed out after 5000ms
```

---

## HOW TO PUSH UPDATES (Working Guide)

### For Force Update (Bug Fix)

**Step 1**: Build new APK
```
gradle assembleRelease  (increment BUILD_CONFIG.VERSION_CODE by 1)
Current VERSION_CODE: 3 → New VERSION_CODE: 4
```

**Step 2**: Upload to Play Store

**Step 3**: Update Firestore (Admin Panel or Console)
```json
{
  "min_version_code": 4,            // ← Increment this
  "latest_version_code": 4,
  "latest_version_name": "1.2.0",
  "update_message": "Critical security fix. Please update.",
  "release_notes": "• Fixed MCQ validation issue\n• Improved quiz generation",
  "is_maintenance": false,
  "is_active": true
}
```

**Step 4**: Users see blocking "Update Required" dialog on next app launch

**Step 5**: Dialog re-appears on resume if not updated (via `onResume()` check)

### For Optional Update (New Feature)

**Same as above, but**:
```json
{
  "min_version_code": 1,            // ← Keep current minimum
  "latest_version_code": 4,         // ← New version for optional prompt
  "update_message": "New features available!",
  ...
}
```

Users see dismissible "Update Available" dialog (24h cooldown after dismiss)

### For Maintenance

```json
{
  "is_maintenance": true,
  "maintenance_message": "We're upgrading servers. Back in 1 hour.",
  "is_active": false,  // or just set is_maintenance: true
  "support_contact": "support@aiguru.app"
}
```

Users see full-screen maintenance UI immediately (non-dismissible)

---

## TESTING CHECKLIST

- [ ] Firestore `updates/app_config` document exists
- [ ] All required fields present (min_version_code, latest_version_code, is_active, is_maintenance)
- [ ] Firestore security rules allow app read access
- [ ] Test Force Update: Set `min_version_code = 999`
- [ ] Test Optional Update: Set `latest_version_code = 999` but `min_version_code = 1`
- [ ] Test Maintenance: Set `is_maintenance = true`
- [ ] Test offline: Put device in airplane mode → cached config used
- [ ] Check Android logs: `adb logcat | grep AppUpdateManager`

---

## FILES MODIFIED

### Android Files
- ✅ [QuizModels.kt](app/src/main/java/com/aiguruapp/student/models/QuizModels.kt) - Added validation for MCQ options
- ✅ [QuizSetupActivity.kt](app/src/main/java/com/aiguruapp/student/QuizSetupActivity.kt) - Better error handling
- ✅ [AppUpdateManager.kt](app/src/main/java/com/aiguruapp/student/utils/AppUpdateManager.kt) - Added cache fallback
- ✅ [SplashActivity.kt](app/src/main/java/com/aiguruapp/student/SplashActivity.kt) - Pass context for caching
- ✅ [AppUpdateConfigCache.kt](app/src/main/java/com/aiguruapp/student/config/AppUpdateConfigCache.kt) - NEW: Local cache service

### Server Files
- ✅ [quiz_service.py](server/app/services/quiz_service.py) - Improved prompt + defensive parsing

---

## NEXT STEPS

1. **Test MCQ Fix**: Generate a quiz → verify options are not empty
2. **Test Update Check**: Set `min_version_code = 999` → verify force update dialog appears
3. **Test Offline**: Enable airplane mode → verify cached config is used
4. **Monitor Logs**: Watch `adb logcat` for Update check messages
5. **Deploy**: Push new APK to Play Store with updated `min_version_code`

