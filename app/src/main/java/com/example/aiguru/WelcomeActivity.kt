package com.example.aiguru

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.aiguru.utils.SessionManager
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Entry point of the app. Displays a brief welcome/splash screen, then routes the user:
 *  - Firebase logged-in + school session active  → HomeActivity
 *  - Firebase logged-in but no school session    → SchoolLoginActivity
 *  - Not authenticated                           → LoginActivity (Google Sign-In)
 */
class WelcomeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_welcome)

        lifecycleScope.launch {
            delay(1500L)
            navigate()
        }
    }

    private fun navigate() {
        val firebaseUser = FirebaseAuth.getInstance().currentUser
        val destination = when {
            firebaseUser != null && SessionManager.isLoggedIn(this) ->
                Intent(this, HomeActivity::class.java)
            firebaseUser != null ->
                Intent(this, SchoolLoginActivity::class.java)
            else ->
                Intent(this, LoginActivity::class.java)
        }
        startActivity(destination)
        finish()
    }
}
