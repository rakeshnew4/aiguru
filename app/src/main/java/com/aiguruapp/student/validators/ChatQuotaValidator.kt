package com.aiguruapp.student.validators

import com.aiguruapp.student.config.PlanEnforcer
import com.aiguruapp.student.models.PlanLimits
import com.aiguruapp.student.models.UserMetadata

/**
 * Pre-request validator for daily chat question quota.
 *
 * Use [check] for logged-in users and [checkGuest] for guest (device-level) quota.
 * Call [questionsLeft] to get the current remaining count for UI display.
 *
 * All decision logic delegates to [PlanEnforcer] so quota rules stay in one place.
 * This class is the single call-site that fragments/activities should use instead
 * of calling PlanEnforcer directly for chat quota decisions.
 */
object ChatQuotaValidator {

    /**
     * Check whether a logged-in user has chat questions remaining today.
     *
     * @param metadata     Cached UserMetadata (refreshed before each send).
     * @param limits       Effective PlanLimits resolved via AdminConfigRepository.
     * @return [PlanEnforcer.CheckResult] — inspect [PlanEnforcer.CheckResult.allowed]
     *         and [PlanEnforcer.CheckResult.upgradeMessage] to react in UI.
     */
    fun check(metadata: UserMetadata, limits: PlanLimits): PlanEnforcer.CheckResult =
        PlanEnforcer.checkQuestionsQuota(metadata, limits, isBlackboard = false)

    /**
     * Async check for guest users (reads device quota from Firestore).
     *
     * @param deviceId  Unique device identifier from SessionManager.
     * @param callback  Called on the Firestore callback thread; switch to main if needed.
     */
    fun checkGuest(deviceId: String, callback: (PlanEnforcer.CheckResult) -> Unit) =
        PlanEnforcer.checkGuestQuota(deviceId, isBlackboard = false, callback = callback)

    /**
     * Returns the number of chat questions remaining today.
     * Returns -1 if the plan has no limit (unlimited).
     *
     * Use this for UI banners or quota-strip display in HomeActivity / FullChatFragment.
     */
    fun questionsLeft(metadata: UserMetadata, limits: PlanLimits): Int =
        PlanEnforcer.getQuestionsLeft(metadata, limits, isBlackboard = false)
}
