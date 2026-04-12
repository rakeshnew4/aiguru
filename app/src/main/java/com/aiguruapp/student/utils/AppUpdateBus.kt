package com.aiguruapp.student.utils

import com.aiguruapp.student.utils.AppUpdateManager.UpdateResult

/**
 * One-shot main-thread bus that carries a background update-check result
 * from SplashActivity to HomeActivity when the check finishes after the
 * splash has already dismissed.
 *
 * All calls must be made on the **main thread**.
 */
object AppUpdateBus {

    private var pending: UpdateResult? = null
    private var consumer: ((UpdateResult) -> Unit)? = null

    /** Called by SplashActivity when the result arrives after we left splash. */
    fun post(result: UpdateResult) {
        val c = consumer
        if (c != null) {
            consumer = null
            c(result)
        } else {
            pending = result
        }
    }

    /**
     * Called by HomeActivity in [onResume].
     * If a result is already pending it fires the handler immediately;
     * otherwise the handler is held until [post] is called.
     */
    fun consume(handler: (UpdateResult) -> Unit) {
        val p = pending
        if (p != null) {
            pending = null
            handler(p)
        } else {
            consumer = handler
        }
    }

    /** Called by HomeActivity in [onPause] to avoid delivering to a stopped activity. */
    fun clearConsumer() {
        consumer = null
    }
}
