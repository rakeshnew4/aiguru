# Google Token-Based LLM Server Authentication Implementation Guide

## Overview
Replace generic API key authentication with per-user Firebase ID token authentication for LLM server calls.

**Current Flow**: All users → same API key → server (no per-user auth)  
**New Flow**: User's Google Sign-In → Firebase ID token → server validates with Firebase Admin SDK

---

## Step 1: Update SessionManager to Store Google Tokens

**File**: `app/src/main/java/com/aiguruapp/student/utils/SessionManager.kt`

Add these constants and methods:

```kotlin
// Add to SessionManager companion object constants:
private const val KEY_GOOGLE_ID_TOKEN = "google_id_token"
private const val KEY_GOOGLE_REFRESH_TOKEN = "google_refresh_token"
private const val KEY_GOOGLE_TOKEN_EXPIRY = "google_token_expiry"

// Add these methods to SessionManager:

/** Save Google ID token (short-lived, ~1 hour) */
fun saveGoogleIdToken(context: Context, idToken: String) {
    prefs(context).edit().putString(KEY_GOOGLE_ID_TOKEN, idToken).apply()
}

/** Get the current Google ID token */
fun getGoogleIdToken(context: Context): String =
    prefs(context).getString(KEY_GOOGLE_ID_TOKEN, "") ?: ""

/** Save Google Refresh token (long-lived, months) */
fun saveGoogleRefreshToken(context: Context, refreshToken: String) {
    prefs(context).edit().putString(KEY_GOOGLE_REFRESH_TOKEN, refreshToken).apply()
}

/** Get the refresh token (used to get new ID tokens) */
fun getGoogleRefreshToken(context: Context): String =
    prefs(context).getString(KEY_GOOGLE_REFRESH_TOKEN, "") ?: ""

/** Cache when the ID token expires (System.currentTimeMillis() + 3600000) */
fun saveGoogleTokenExpiry(context: Context, expiryMs: Long) {
    prefs(context).edit().putLong(KEY_GOOGLE_TOKEN_EXPIRY, expiryMs).apply()
}

/** Get the expiry time of the cached ID token */
fun getGoogleTokenExpiry(context: Context): Long =
    prefs(context).getLong(KEY_GOOGLE_TOKEN_EXPIRY, 0L)

/** Check if ID token is expired (and needs refresh) */
fun isGoogleIdTokenExpired(context: Context): Boolean {
    val expiry = getGoogleTokenExpiry(context)
    if (expiry <= 0) return true  // No expiry stored = expired
    return System.currentTimeMillis() >= expiry
}
```

---

## Step 2: Modify LoginActivity to Capture & Store Tokens

**File**: `app/src/main/java/com/aiguruapp/student/LoginActivity.kt`

### Change 1: Capture refresh token in sign-in launcher

```kotlin
private val signInLauncher =
    registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            val account = task.getResult(ApiException::class.java)
            val idToken = account.idToken ?: run {
                setLoading(false)
                Toast.makeText(this, "Google sign in failed: no ID token", Toast.LENGTH_LONG).show()
                return@registerForActivityResult
            }
            
            // NEW: Get refresh token if available
            val serverAuthCode = account.serverAuthCode  // Can exchange for refresh token
            
            firebaseSignIn(idToken, serverAuthCode)  // Pass both to firebase sign-in
        } catch (e: ApiException) {
            Log.e(TAG, "Google sign-in failed, code=${e.statusCode}", e)
            setLoading(false)
            Toast.makeText(this, "Google sign in failed (code ${e.statusCode})", Toast.LENGTH_SHORT).show()
        }
    }
```

### Change 2: Update firebaseSignIn to save tokens

```kotlin
private fun firebaseSignIn(idToken: String, serverAuthCode: String? = null) {
    val credential = GoogleAuthProvider.getCredential(idToken, null)
    auth.signInWithCredential(credential)
        .addOnSuccessListener { authResult ->
            val user = authResult.user ?: run {
                setLoading(false)
                Toast.makeText(this, "Sign in failed. Please try again.", Toast.LENGTH_SHORT).show()
                return@addOnSuccessListener
            }
            Log.d(TAG, "Firebase sign-in success: uid=${user.uid}")
            
            // NEW: Save Google tokens to SessionManager
            SessionManager.saveFirebaseUid(this, user.uid)
            SessionManager.saveGoogleIdToken(this, idToken)
            // ID tokens expire in ~1 hour
            SessionManager.saveGoogleTokenExpiry(this, System.currentTimeMillis() + 3600000)
            
            // NEW: Store refresh token in Firestore for later use
            if (!serverAuthCode.isNullOrBlank()) {
                FirestoreManager.saveUserGoogleTokens(
                    userId = user.uid,
                    idToken = idToken,
                    serverAuthCode = serverAuthCode,
                    onSuccess = {
                        Log.d(TAG, "Google tokens saved to Firestore")
                    },
                    onError = { e ->
                        Log.e(TAG, "Failed to save tokens: ${e.message}", e)
                    }
                )
            }
            
            SessionManager.login(
                context     = this,
                schoolId    = "google",
                schoolName  = "Google Account",
                studentId   = user.email ?: user.uid,
                studentName = user.displayName ?: "Student"
            )
            
            // Rest of existing code...
            val meta = SessionManager.buildUserMetadata(this)
            FirestoreManager.saveUserMetadata(meta, onSuccess = {
                FirestoreManager.cleanupMangledFields(user.uid)
            })
            setLoading(false)
            goHome()
        }
        .addOnFailureListener { e ->
            Log.e(TAG, "Firebase credential sign-in failed", e)
            setLoading(false)
            Toast.makeText(this, "Sign in failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
}
```

---

## Step 3: Update FirestoreManager to Store Tokens

**File**: `app/src/main/java/com/aiguruapp/student/firestore/FirestoreManager.kt`

Add this method to save Google tokens to Firestore:

```kotlin
/**
 * Save Google OAuth tokens to Firestore for later refresh.
 * Stored at: users/{uid}/auth/google
 */
fun saveUserGoogleTokens(
    userId: String,
    idToken: String,
    serverAuthCode: String,
    onSuccess: () -> Unit = {},
    onError: (Exception) -> Unit = {}
) {
    val db = FirebaseFirestore.getInstance()
    val tokenData = hashMapOf(
        "idToken" to idToken,
        "serverAuthCode" to serverAuthCode,
        "savedAt" to System.currentTimeMillis(),
        "expiresIn" to 3600  // seconds until expiry
    )
    
    db.collection("users").document(userId)
        .collection("auth").document("google")
        .set(tokenData, SetOptions.merge())
        .addOnSuccessListener { onSuccess() }
        .addOnFailureListener { e -> onError(e) }
}

/**
 * Retrieve stored Google tokens from Firestore
 */
fun getUserGoogleTokens(
    userId: String,
    onSuccess: (Map<String, Any>) -> Unit = {},
    onError: (Exception) -> Unit = {}
) {
    val db = FirebaseFirestore.getInstance()
    db.collection("users").document(userId)
        .collection("auth").document("google")
        .get()
        .addOnSuccessListener { doc ->
            if (doc.exists()) {
                onSuccess(doc.data ?: emptyMap())
            } else {
                onError(Exception("No Google tokens found"))
            }
        }
        .addOnFailureListener { e -> onError(e) }
}
```

---

## Step 4: Update ServerProxyClient to Use Firebase ID Token

**File**: `app/src/main/java/com/aiguruapp/student/chat/ServerProxyClient.kt`

### Change 1: Accept Firebase token instead of API key

```kotlin
/**
 * Updated ServerProxyClient to use Firebase ID token for authentication.
 * 
 * Constructor changes:
 *  - REMOVED: apiKey parameter (generic API key)
 *  - ADDED: firebaseIdToken parameter (per-user token)
 */
class ServerProxyClient(
    private val serverUrl: String,
    private val modelName: String,        // kept for API compatibility
    private val firebaseIdToken: String = "",  // NEW: Firebase ID token
    private val userId: String = ""
) : AiClient {
    // ... existing code ...
}
```

### Change 2: Update executeStream to use token

```kotlin
private fun executeStream(
    json: JSONObject,
    onPageTranscript: ((String) -> Unit)? = null,
    onSuggestBlackboard: ((Boolean) -> Unit)? = null,
    onToken: (String) -> Unit,
    onDone: (inputTokens: Int, outputTokens: Int, totalTokens: Int) -> Unit,
    onError: (String) -> Unit
) {
    val reqBuilder = Request.Builder()
        .url(endpoint)
        .post(json.toString().toRequestBody("application/json".toMediaType()))
    
    // CHANGE: Use Firebase token instead of API key
    if (firebaseIdToken.isNotEmpty()) {
        reqBuilder.addHeader("Authorization", "Bearer $firebaseIdToken")
    }
    
    Log.d("ServerProxyClient", "→ POST $endpoint with user=$userId")
    try {
        val response = client.newCall(reqBuilder.build()).execute()
        Log.d("ServerProxyClient", "← HTTP ${response.code}")
        
        // NEW: Handle 401 Unauthorized (token expired/invalid)
        if (response.code == 401) {
            onError("Authentication failed (401). Token may have expired. Please re-login.")
            return
        }
        
        if (!response.isSuccessful) {
            onError("HTTP ${response.code}: ${response.message}")
            return
        }
        
        // ... rest of existing code ...
    } catch (e: IOException) {
        Log.e("ServerProxyClient", "IOException: ${e.message}", e)
        onError(e.message ?: "Network error")
    }
}
```

---

## Step 5: Update FullChatFragment to Pass Token to ServerProxyClient

**File**: `app/src/main/java/com/aiguruapp/student/FullChatFragment.kt`

Find the `buildAiClient()` function and update it:

```kotlin
private fun buildAiClient(): AiClient {
    val config = AdminConfigRepository.getConfig()

    return when {
        config.serverUrl.isNotBlank() -> {
            // NEW: Get Firebase ID token instead of API key
            val firebaseToken = SessionManager.getGoogleIdToken(requireContext())
            
            // ALTERNATIVE: If token is expired, mark for refresh
            if (SessionManager.isGoogleIdTokenExpired(requireContext())) {
                Log.w("FullChatFragment", "Google ID token expired - will need refresh on next call")
            }
            
            ServerProxyClient(
                serverUrl = config.serverUrl,
                modelName = config.modelName,
                firebaseIdToken = firebaseToken,  // CHANGED from apiKey
                userId = SessionManager.getFirebaseUid(requireContext())
            )
        }
        else -> {
            // Fallback to local LLM if server not configured
            LocalLLMClient()
        }
    }
}
```

---

## Step 6: FastAPI Server Validation

**File**: `fastapi_server/main.py` (or equivalent)

Add Firebase token validation:

```python
from firebase_admin import credentials, initialize_app, auth as firebase_auth
from fastapi import FastAPI, Header, HTTPException
from typing import Optional
import logging

# Initialize Firebase Admin SDK
cred = credentials.Certificate("path/to/serviceAccountKey.json")
initialize_app(cred)

app = FastAPI()

async def verify_firebase_token(authorization: Optional[str] = Header(None)) -> str:
    """
    Extract and validate Firebase ID token from Authorization header.
    Returns: Firebase UID if valid
    Raises: HTTPException(401/403) if invalid/expired
    """
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="Missing or invalid Authorization header")
    
    token = authorization.replace("Bearer ", "")
    
    try:
        decoded = firebase_auth.verify_id_token(token)
        return decoded['uid']
    except firebase_auth.ExpiredSignatureError:
        raise HTTPException(status_code=401, detail="Token expired - please re-login")
    except firebase_auth.InvalidIdTokenError:
        raise HTTPException(status_code=403, detail="Invalid authentication token")
    except Exception as e:
        logging.error(f"Token verification failed: {e}")
        raise HTTPException(status_code=403, detail="Authentication failed")

@app.post("/chat-stream")
async def chat_stream(
    request: ChatRequest,
    authorization: Optional[str] = Header(None)
):
    """
    LLM chat endpoint with Firebase authentication.
    """
    # Verify token and get user ID
    user_id = await verify_firebase_token(authorization)
    logging.info(f"Processing chat for user: {user_id}")
    
    # Rest of your chat logic...
    # You now know exactly who is making the request!
    return StreamingResponse(stream_chat(request, user_id), media_type="text/event-stream")

@app.post("/analyze-image")
async def analyze_image(
    request: ImageAnalysisRequest,
    authorization: Optional[str] = Header(None)
):
    """
    Image analysis endpoint with Firebase authentication.
    """
    user_id = await verify_firebase_token(authorization)
    logging.info(f"Analyzing image for user: {user_id}")
    
    # Rest of your image analysis logic...
    return {"user_id": user_id, "analysis": prepare_analysis(request)}
```

---

## Step 7: (Optional) Add Token Refresh Logic

For production, add automatic token refresh when expired:

**File**: `app/src/main/java/com/aiguruapp/student/http/HttpClientManager.kt`

Add interceptor for automatic token refresh:

```kotlin
private fun buildLongTimeoutClient(): OkHttpClient {
    val tokenRefreshInterceptor = object : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            var request = chain.request()
            val response = chain.proceed(request)
            
            // If 401 Unauthorized, try to refresh token
            if (response.code == 401 && request.header("Authorization") != null) {
                Log.w("HttpClientManager", "Got 401 - attempting token refresh")
                
                // Refresh token logic here (would need Context)
                // For now, just log - client app handles refresh
            }
            
            return response
        }
    }
    
    return OkHttpClient.Builder()
        .addInterceptor(tokenRefreshInterceptor)
        .connectTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
        .build()
}
```

---

## Testing Checklist

- [ ] Google Sign-In saves ID token to SessionManager
- [ ] Firebase ID token appears in Firestore `users/{uid}/auth/google`
- [ ] ServerProxyClient includes `Authorization: Bearer {token}` header
- [ ] FastAPI server successfully validates token and logs user ID
- [ ] LLM calls work with user-specific authentication
- [ ] Expired token returns 401 and prompts re-login
- [ ] Server rejects requests from invalid/missing tokens

---

## Benefits of This Approach

✅ **Per-user authentication**: Server knows exactly which user made each request  
✅ **Better security**: No shared API key; each user has unique token  
✅ **Audit trail**: Server can log all requests by user ID  
✅ **Plan enforcement**: Server can validate user's subscription plan before processing  
✅ **Rate limiting**: Can enforce per-user rate limits instead of global  
✅ **Firebase integration**: Leverage Firebase's security rules on your data

