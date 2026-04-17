package com.aiguruapp.student.validators

import com.aiguruapp.student.config.PlanEnforcer
import com.aiguruapp.student.models.PlanLimits
import com.aiguruapp.student.models.UserMetadata

/**
 * Pre-request validator for daily Blackboard session quota.
 *
 * Call [check] before launching a Blackboard generation to decide whether the
 * user is allowed to start a new session.  Call [sessionsLeft] to update the
 * quota chip shown in BlackboardActivity.
 *
 * All decision logic delegates to [PlanEnforcer]; this class is the single
 * entry-point for any BB-quota decision so nothing else touches isBlackboard=true
 * checkQuestionsQuota calls directly.
 */
object BlackboardQuotaValidator {

    /**
     * Check whether the user has Blackboard sessions remaining today.
     *
     * @param metadata  Cached UserMetadata for the current user.
     * @param limits    Effective PlanLimits resolved via AdminConfigRepository.
     * @return [PlanEnforcer.CheckResult] — inspect [PlanEnforcer.CheckResult.allowed]
     *         and [PlanEnforcer.CheckResult.upgradeMessage] to react in UI.
     */
    fun check(metadata: UserMetadata, limits: PlanLimits): PlanEnforcer.CheckResult =
        PlanEnforcer.checkQuestionsQuota(metadata, limits, isBlackboard = true)

    /**
     * Returns the number of Blackboard sessions remaining today.
     * Returns -1 if the plan has no limit (unlimited).
     *
     * Use this for the quota chip displayed inside BlackboardActivity.
     */
    fun sessionsLeft(metadata: UserMetadata, limits: PlanLimits): Int =
        PlanEnforcer.getQuestionsLeft(metadata, limits, isBlackboard = true)
}
