package com.example.aiguru

import android.os.Bundle

/**
 * DEPRECATED — all chat UI now lives in [FullChatFragment].
 *
 * Kept only as a redirect shell so old shortcuts / deep-links still work.
 * All launches must use [ChatHostActivity] instead.
 */
@Deprecated("Use ChatHostActivity + FullChatFragment instead.")
class ChatActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            android.content.Intent(this, ChatHostActivity::class.java)
                .also { it.putExtras(intent.extras ?: android.os.Bundle.EMPTY) }
        )
        finish()
    }
}
