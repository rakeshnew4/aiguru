package com.aiguruapp.student

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aiguruapp.student.auth.TokenManager
import com.aiguruapp.student.config.AdminConfigRepository
import com.aiguruapp.student.firestore.FirestoreManager
import com.aiguruapp.student.http.HttpClientManager
import com.aiguruapp.student.utils.SessionManager
import com.google.android.material.button.MaterialButton
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Shows the user's friends list and lets them add friends by share code.
 *
 * Share mode: launched with EXTRA_SESSION_* extras from BbSavedSessionsActivity.
 *   Each friend card shows a "Share" button. Tapping it POSTs /users/share-session
 *   and then finishes the activity.
 *
 * Browse mode (no session extras): shows friends list with an "Add Friend" button.
 */
class FriendsActivity : BaseActivity() {

    companion object {
        const val EXTRA_SHARE_MODE       = "extra_share_mode"
        const val EXTRA_SESSION_ID       = "extra_session_id"
        const val EXTRA_SESSION_TOPIC    = "extra_session_topic"
        const val EXTRA_SESSION_STEPS    = "extra_session_steps"
        const val EXTRA_SESSION_STEP_CNT = "extra_session_step_cnt"
        const val EXTRA_SESSION_MSG_ID   = "extra_session_msg_id"
        const val EXTRA_SESSION_CONV_ID  = "extra_session_conv_id"
    }

    private lateinit var userId: String
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: View
    private lateinit var adapter: FriendsAdapter
    private val friends = mutableListOf<Map<String, Any>>()

    private var shareMode = false
    private var sessionId = ""
    private var sessionTopic = ""
    private var sessionStepsJson = ""
    private var sessionStepCount = 0
    private var sessionMsgId = ""
    private var sessionConvId = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_friends)

        userId     = SessionManager.getFirestoreUserId(this)
        shareMode  = intent.getBooleanExtra(EXTRA_SHARE_MODE, false)
        sessionId  = intent.getStringExtra(EXTRA_SESSION_ID) ?: ""
        sessionTopic     = intent.getStringExtra(EXTRA_SESSION_TOPIC) ?: ""
        sessionStepsJson = intent.getStringExtra(EXTRA_SESSION_STEPS) ?: ""
        sessionStepCount = intent.getIntExtra(EXTRA_SESSION_STEP_CNT, 0)
        sessionMsgId     = intent.getStringExtra(EXTRA_SESSION_MSG_ID) ?: ""
        sessionConvId    = intent.getStringExtra(EXTRA_SESSION_CONV_ID) ?: ""

        findViewById<ImageButton>(R.id.backBtn).setOnClickListener { finish() }

        val subtitle = if (shareMode) "Pick a friend to share \"$sessionTopic\""
                       else "Share lessons with friends"
        findViewById<TextView>(R.id.screenSubtitle).text = subtitle

        emptyState   = findViewById(R.id.emptyState)
        recyclerView = findViewById(R.id.friendsList)

        adapter = FriendsAdapter(
            friends    = friends,
            shareMode  = shareMode,
            onShare    = { friend -> doShareToFriend(friend) }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        findViewById<MaterialButton>(R.id.addFriendBtn).setOnClickListener {
            showAddFriendDialog()
        }

        loadFriends()
    }

    // ── Load ──────────────────────────────────────────────────────────────────

    private fun loadFriends() {
        FirestoreManager.loadFriends(
            userId    = userId,
            onSuccess = { list ->
                runOnUiThread {
                    friends.clear()
                    friends.addAll(list)
                    adapter.notifyDataSetChanged()
                    emptyState.visibility   = if (friends.isEmpty()) View.VISIBLE else View.GONE
                    recyclerView.visibility = if (friends.isEmpty()) View.GONE   else View.VISIBLE
                }
            },
            onFailure = {
                runOnUiThread {
                    Toast.makeText(this, "Failed to load friends", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    // ── Add Friend ────────────────────────────────────────────────────────────

    private fun showAddFriendDialog() {
        val input = EditText(this).apply {
            hint = "Enter 8-char share code"
            filters = arrayOf(InputFilter.AllCaps(), InputFilter.LengthFilter(8))
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFFAAABBCC.toInt())
        }

        AlertDialog.Builder(this)
            .setTitle("Add Friend")
            .setMessage("Enter your friend's 8-character share code")
            .setView(input)
            .setPositiveButton("Look Up") { _, _ ->
                val code = input.text.toString().trim().uppercase()
                if (code.length == 8) lookupAndAddFriend(code)
                else Toast.makeText(this, "Code must be 8 characters", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun lookupAndAddFriend(code: String) {
        val serverUrl = AdminConfigRepository.effectiveServerUrl().trimEnd('/')
        Thread {
            try {
                val authHeader = TokenManager.buildAuthHeader() ?: run {
                    runOnUiThread { Toast.makeText(this, "Auth error — try again", Toast.LENGTH_SHORT).show() }
                    return@Thread
                }
                val request = Request.Builder()
                    .url("$serverUrl/users/lookup?code=$code")
                    .get()
                    .header("Authorization", authHeader)
                    .build()

                HttpClientManager.standardClient.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    when (response.code) {
                        200 -> {
                            val json = JSONObject(body)
                            val uid  = json.optString("uid")
                            val name = json.optString("name", "Friend")
                            runOnUiThread { confirmAddFriend(uid, name, code) }
                        }
                        400 -> runOnUiThread { Toast.makeText(this, "That's your own code", Toast.LENGTH_SHORT).show() }
                        404 -> runOnUiThread { Toast.makeText(this, "No user found with that code", Toast.LENGTH_SHORT).show() }
                        else -> {
                            Log.w("FriendsActivity", "lookup HTTP ${response.code}: $body")
                            runOnUiThread { Toast.makeText(this, "Lookup failed, try again", Toast.LENGTH_SHORT).show() }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("FriendsActivity", "lookupAndAddFriend: ${e.message}", e)
                runOnUiThread { Toast.makeText(this, "Network error", Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    private fun confirmAddFriend(friendUid: String, friendName: String, friendCode: String) {
        // Already in friends?
        if (friends.any { it["friend_uid"] == friendUid }) {
            Toast.makeText(this, "$friendName is already your friend", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Add $friendName?")
            .setMessage("Add $friendName as a friend so you can share BB sessions with each other.")
            .setPositiveButton("Add") { _, _ ->
                FirestoreManager.addFriend(
                    userId     = userId,
                    friendUid  = friendUid,
                    friendName = friendName,
                    friendCode = friendCode,
                    onSuccess  = {
                        runOnUiThread {
                            val entry = mapOf(
                                "friend_uid" to friendUid,
                                "name"       to friendName,
                                "code"       to friendCode,
                                "added_at"   to System.currentTimeMillis()
                            )
                            friends.add(0, entry)
                            adapter.notifyItemInserted(0)
                            emptyState.visibility   = View.GONE
                            recyclerView.visibility = View.VISIBLE
                            Toast.makeText(this, "Added $friendName!", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onFailure = {
                        runOnUiThread { Toast.makeText(this, "Failed to add friend", Toast.LENGTH_SHORT).show() }
                    }
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Share session to friend ───────────────────────────────────────────────

    private fun doShareToFriend(friend: Map<String, Any>) {
        val toCode    = friend["code"] as? String ?: return
        val toName    = friend["name"] as? String ?: "Friend"
        val serverUrl = AdminConfigRepository.effectiveServerUrl().trimEnd('/')

        Thread {
            try {
                val authHeader = TokenManager.buildAuthHeader() ?: run {
                    runOnUiThread { Toast.makeText(this, "Auth error — try again", Toast.LENGTH_SHORT).show() }
                    return@Thread
                }
                val body = JSONObject().apply {
                    put("to_code",        toCode)
                    put("session_id",     sessionId)
                    put("topic",          sessionTopic)
                    put("step_count",     sessionStepCount)
                    put("steps_json",     sessionStepsJson)
                    put("message_id",     sessionMsgId)
                    put("conversation_id", sessionConvId)
                }.toString().toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url("$serverUrl/users/share-session")
                    .post(body)
                    .header("Authorization", authHeader)
                    .build()

                HttpClientManager.standardClient.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string() ?: ""
                    if (response.isSuccessful) {
                        runOnUiThread {
                            Toast.makeText(this, "Shared with $toName! 🎉", Toast.LENGTH_SHORT).show()
                            finish()
                        }
                    } else {
                        Log.w("FriendsActivity", "share HTTP ${response.code}: $responseBody")
                        runOnUiThread { Toast.makeText(this, "Share failed, try again", Toast.LENGTH_SHORT).show() }
                    }
                }
            } catch (e: Exception) {
                Log.e("FriendsActivity", "doShareToFriend: ${e.message}", e)
                runOnUiThread { Toast.makeText(this, "Network error", Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    private class FriendsAdapter(
        private val friends:   List<Map<String, Any>>,
        private val shareMode: Boolean,
        private val onShare:   (Map<String, Any>) -> Unit
    ) : RecyclerView.Adapter<FriendsAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val avatar:   TextView = view.findViewById(R.id.friendAvatar)
            val name:     TextView = view.findViewById(R.id.friendName)
            val code:     TextView = view.findViewById(R.id.friendCode)
            val shareBtn: TextView = view.findViewById(R.id.shareBtn)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_friend, parent, false)
            return VH(view)
        }

        override fun getItemCount() = friends.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val friend = friends[position]
            val name   = friend["name"] as? String ?: "Friend"
            val code   = friend["code"] as? String ?: ""
            holder.avatar.text   = name.firstOrNull()?.uppercase() ?: "F"
            holder.name.text     = name
            holder.code.text     = "Code: $code"
            if (shareMode) {
                holder.shareBtn.visibility = View.VISIBLE
                holder.shareBtn.setOnClickListener { onShare(friend) }
            } else {
                holder.shareBtn.visibility = View.GONE
            }
        }
    }
}
