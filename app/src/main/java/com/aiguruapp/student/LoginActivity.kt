package com.aiguruapp.student

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.aiguruapp.student.firestore.FirestoreManager
import com.aiguruapp.student.utils.SessionManager
import com.aiguruapp.student.chat.ServerProxyClient
import com.aiguruapp.student.config.AdminConfigRepository
import com.aiguruapp.student.widget.BoxSpinnerView
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider

class LoginActivity : BaseActivity() {

    companion object {
        private const val TAG = "LoginActivity"
        // Web client (type 3) from Google Cloud Console / Firebase Auth → Google Sign-In
        private const val WEB_CLIENT_ID =
            "162570108156-u53umd4n0se88sp5dumhg8ugu1uco89j.apps.googleusercontent.com"
    }

    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var signInButton: Button
    private lateinit var loadingBar: BoxSpinnerView
    private val auth = FirebaseAuth.getInstance()

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
                firebaseSignIn(idToken)
            } catch (e: ApiException) {
                Log.e(TAG, "Google sign-in failed, code=${e.statusCode}", e)
                setLoading(false)
                Toast.makeText(this, "Google sign in failed (code ${e.statusCode})", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        signInButton = findViewById(R.id.googleSignInButton)
        loadingBar   = findViewById(R.id.loginProgressBar)

        // Already logged in — go straight home
        if (SessionManager.isLoggedIn(this)) {
            goHome(); return
        }

        // If Firebase already has a real (non-anonymous) Google user, restore session
        val currentUser = auth.currentUser
        if (currentUser != null && !currentUser.isAnonymous) {
            Log.d(TAG, "Restoring existing Firebase session: uid=${currentUser.uid}")
            SessionManager.login(
                context     = this,
                schoolId    = "google",
                schoolName  = "Google Account",
                studentId   = currentUser.email ?: currentUser.uid,
                studentName = currentUser.displayName ?: "Student"
            )
            SessionManager.saveFirebaseUid(this, currentUser.uid)
            goHome()
            return
        }

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(WEB_CLIENT_ID)
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)

        signInButton.setOnClickListener {
            setLoading(true)
            googleSignInClient.signOut().addOnCompleteListener {
                signInLauncher.launch(googleSignInClient.signInIntent)
            }
        }

        // Email sign-in
        findViewById<Button>(R.id.emailSignInButton).setOnClickListener {
            startActivity(Intent(this, EmailAuthActivity::class.java))
        }
    }

    private fun firebaseSignIn(idToken: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential)
            .addOnSuccessListener { authResult ->
                val user = authResult.user ?: run {
                    setLoading(false)
                    Toast.makeText(this, "Sign in failed. Please try again.", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }
                Log.d(TAG, "Firebase sign-in success: uid=${user.uid}")
                SessionManager.login(
                    context     = this,
                    schoolId    = "google",
                    schoolName  = "Google Account",
                    studentId   = user.email ?: user.uid,
                    studentName = user.displayName ?: "Student"
                )
                SessionManager.saveFirebaseUid(this, user.uid)
                // Always write/refresh the Firestore user doc after Google sign-in.
                // This overwrites any mangled-field-name documents created by older builds
                // and ensures the doc exists for new users before HomeActivity reads it.
                val meta = SessionManager.buildUserMetadata(this)
                FirestoreManager.saveUserMetadata(meta, onSuccess = {
                    // Remove corrupted single-letter fields left by older builds
                    FirestoreManager.cleanupMangledFields(user.uid)
                })
                // Register with server (creates LiteLLM key, ensures server-side user record).
                // Fire-and-forget on background thread — login is not blocked by this call.
                Thread {
                    ServerProxyClient.registerWithServer(
                        serverUrl  = AdminConfigRepository.effectiveServerUrl(),
                        userId     = user.uid,
                        name       = user.displayName ?: "",
                        email      = user.email ?: "",
                        grade      = "",
                        schoolId   = "google",
                        schoolName = "Google Account"
                    )
                }.start()
                setLoading(false)
                goHome()
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Firebase credential sign-in failed", e)
                setLoading(false)
                Toast.makeText(this, "Sign in failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun goHome() {
        val nextActivity = if (SessionManager.isSignupComplete(this)) {
            HomeActivity::class.java
        } else {
            SignupActivity::class.java
        }
        startActivity(Intent(this, nextActivity).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
    }

    private fun setLoading(loading: Boolean) {
        signInButton.isEnabled = !loading
        if (loading) {
            loadingBar.visibility = View.VISIBLE
            loadingBar.start()
        } else {
            loadingBar.stop()
            loadingBar.visibility = View.GONE
        }
    }
}
