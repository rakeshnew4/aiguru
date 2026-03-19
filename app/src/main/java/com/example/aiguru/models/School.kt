package com.example.aiguru.models

data class SchoolBranding(
    val primaryColor: String,
    val primaryDarkColor: String,
    val accentColor: String,
    val backgroundColor: String,
    val headerTextColor: String,
    val headerSubtextColor: String,
    val bodyTextPrimaryColor: String,
    val logoText: String,
    val logoEmoji: String
)

data class SubscriptionPlan(
    val id: String,
    val name: String,
    val badge: String,
    val priceINR: Int,
    val duration: String,
    val features: List<String>
) {
    val isFree: Boolean get() = priceINR == 0
    val displayPrice: String get() = if (isFree) "FREE" else "₹$priceINR"
}

data class School(
    val id: String,
    val name: String,
    val shortName: String,
    val city: String,
    val state: String,
    val code: String,
    val contactEmail: String,
    val branding: SchoolBranding,
    val plans: List<SubscriptionPlan>,
    val testStudentIds: List<String>
) {
    val displayName: String get() = "$name, $city"

    fun getPlan(planId: String): SubscriptionPlan? = plans.find { it.id == planId }
}
