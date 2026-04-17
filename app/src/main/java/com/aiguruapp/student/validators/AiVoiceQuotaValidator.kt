package com.aiguruapp.student.validators

import com.aiguruapp.student.config.PlanEnforcer
import com.aiguruapp.student.models.PlanLimits
import com.aiguruapp.student.models.UserMetadata

/**
 * Pre-request validator for AI Voice (TTS) feature access and daily character quota.
 *
 * Two separate gates must both pass before AI TTS audio is played:
 *  1. [checkFeature] — is AI TTS enabled on this plan at all?
 *  2. [checkQuota]   — has the user exhausted their daily character budget?
 *
 * Call [charsLeft] to get the remaining character count for UI display
 * (e.g., the voice-credits row in the Home screen drawer).
 */
object AiVoiceQuotaValidator {

    /**
     * Check whether AI Voice synthesis is enabled on the user's current plan.
     *
     * This is a feature-gate check (not a usage-count check). If the plan does not
     * include AI TTS, this returns allowed=false before any usage is counted.
     *
     * @param metadata  Cached UserMetadata for the current user.
     * @param limits    Effective PlanLimits resolved via AdminConfigRepository.
     * @return [PlanEnforcer.CheckResult] — inspect [PlanEnforcer.CheckResult.allowed]
     *         and [PlanEnforcer.CheckResult.upgradeMessage] to react in UI.
     */
    fun checkFeature(metadata: UserMetadata, limits: PlanLimits): PlanEnforcer.CheckResult =
        PlanEnforcer.check(metadata, limits, PlanEnforcer.FeatureType.AI_TTS)

    /**
     * Check whether the user has enough AI Voice character budget remaining today.
     *
     * Call this immediately before each TTS playback, passing the character count of
     * the text about to be spoken.  If the quota would be exceeded, falls back to
     * Android TTS and shows an upgrade toast.
     *
     * @param metadata       Cached UserMetadata (should be fresh — re-read from Firestore
     *                       after each spoken frame so the counter stays accurate).
     * @param limits         Effective PlanLimits resolved via AdminConfigRepository.
     * @param charsToSpeak   Length of the text string about to be synthesised.
     * @return [PlanEnforcer.CheckResult] — inspect [PlanEnforcer.CheckResult.allowed]
     *         and [PlanEnforcer.CheckResult.upgradeMessage] to react in UI.
     */
    fun checkQuota(
        metadata: UserMetadata,
        limits: PlanLimits,
        charsToSpeak: Int
    ): PlanEnforcer.CheckResult =
        PlanEnforcer.checkAiTtsQuota(metadata, limits, charsToSpeak)

    /**
     * Returns the number of AI Voice characters remaining today.
     * Returns -1 if the plan has no character limit (unlimited).
     *
     * Use this for the voice-credits row in the Home screen navigation drawer.
     */
    fun charsLeft(metadata: UserMetadata, limits: PlanLimits): Int =
        PlanEnforcer.getAiTtsCharsRemaining(metadata, limits)
}
