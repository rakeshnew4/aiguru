package com.example.aiguru

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.aiguru.config.AdminConfigRepository
import com.example.aiguru.firestore.FirestoreManager
import com.example.aiguru.models.School
import com.example.aiguru.models.SchoolPlan
import com.example.aiguru.payments.CreateOrderRequest
import com.example.aiguru.payments.PaymentApiClient
import com.example.aiguru.payments.VerifyPaymentRequest
import com.example.aiguru.utils.ConfigManager
import com.example.aiguru.utils.SessionManager
import com.google.android.material.button.MaterialButton
import com.razorpay.Checkout
import com.razorpay.PaymentData
import com.razorpay.PaymentResultWithDataListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class SubscriptionActivity : AppCompatActivity(), PaymentResultWithDataListener {

    companion object {
        private const val TAG = "SubscriptionActivity"
    }

    private lateinit var school: School
    private lateinit var paymentClient: PaymentApiClient
    private var selectedPaidPlan: SchoolPlan? = null
    private var selectedPaidButton: MaterialButton? = null
    private var isPaymentInFlight = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_subscription)

        Checkout.preload(applicationContext)
        AdminConfigRepository.fetchIfStale()

        val schoolId = intent.getStringExtra("schoolId")
            ?: SessionManager.getSchoolId(this)

        school = ConfigManager.getSchool(this, schoolId)
            ?: run { navigateHome(); return }

        paymentClient = PaymentApiClient(resolvePaymentBaseUrl())

        applySchoolBranding()
        buildPlanCards()
    }

    private fun resolvePaymentBaseUrl(): String {
        val configured = BuildConfig.PAYMENT_BASE_URL.trim()
        if (configured.isNotBlank()) return configured
        return AdminConfigRepository.config.serverUrl.ifBlank { "http://108.181.187.227:8003" }
    }

    private fun applySchoolBranding() {
        val branding = school.branding
        runCatching {
            val primary = Color.parseColor(branding.primaryColor)
            findViewById<LinearLayout>(R.id.subscriptionHeader)
                .setBackgroundColor(primary)
        }
        val studentName = SessionManager.getStudentName(this)
        val studentId = SessionManager.getStudentId(this)
        findViewById<TextView>(R.id.schoolNameHeader).text = school.name
        findViewById<TextView>(R.id.studentInfoHeader).text =
            "$studentName  •  ID: $studentId"
        findViewById<TextView>(R.id.schoolLogoText).text = branding.logoEmoji
    }

    private fun buildPlanCards() {
        val container = findViewById<LinearLayout>(R.id.plansContainer)
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)

        // Identify recommended plan from config (BASIC by default)
        val recommendedPlanId = ConfigManager.getAppConfig(this)
            .let { "BASIC" }  // Could be read from config in future

        school.plans.forEach { plan ->
            val card = inflater.inflate(R.layout.item_plan_card, container, false)
            bindPlanCard(card, plan, plan.id == recommendedPlanId)
            container.addView(card)
        }
    }

    private fun bindPlanCard(view: View, plan: SchoolPlan, isRecommended: Boolean) {
        val branding = school.branding
        val primaryColor = runCatching { Color.parseColor(branding.primaryColor) }
            .getOrDefault(Color.parseColor("#1565C0"))
        val accentColor = runCatching { Color.parseColor(branding.accentColor) }
            .getOrDefault(Color.parseColor("#FF8F00"))

        view.findViewById<TextView>(R.id.planName).apply {
            text = plan.name
            setTextColor(primaryColor)
        }
        view.findViewById<TextView>(R.id.planDuration).text = plan.duration
        view.findViewById<TextView>(R.id.planPrice).apply {
            text = plan.displayPrice
            setTextColor(if (plan.isFree) Color.parseColor("#2E7D32") else primaryColor)
        }

        // Features as bullet list
        view.findViewById<TextView>(R.id.planFeatures).text =
            plan.features.joinToString("\n") { "✓  $it" }

        // Badge
        val badgeView = view.findViewById<TextView>(R.id.planBadge)
        val badgeText = when {
            plan.badge.isNotBlank() -> plan.badge
            isRecommended && plan.badge.isBlank() -> "Recommended"
            else -> ""
        }
        if (badgeText.isNotBlank()) {
            badgeView.text = badgeText
            badgeView.visibility = View.VISIBLE
            badgeView.backgroundTintList = ColorStateList.valueOf(accentColor)
        } else {
            badgeView.visibility = View.GONE
        }

        // CTA button
        val btn = view.findViewById<MaterialButton>(R.id.selectPlanButton)
        btn.text = if (plan.isFree) "Start Free Trial" else "Subscribe - ${plan.displayPrice}"
        btn.backgroundTintList = ColorStateList.valueOf(primaryColor)
        btn.setOnClickListener { startPlanSelection(plan, btn) }

        // Highlight recommended card with border tint
        if (isRecommended) {
            // Slightly elevate the recommended card — done via cardElevation if accessible
            // For now just color the button accent
            btn.backgroundTintList = ColorStateList.valueOf(accentColor)
        }
    }

    private fun startPlanSelection(plan: SchoolPlan, ctaButton: MaterialButton) {
        if (isPaymentInFlight) {
            Toast.makeText(this, "Payment is already in progress", Toast.LENGTH_SHORT).show()
            return
        }

        if (plan.isFree) {
            activatePlanAndContinue(plan)
            return
        }

        val userId = SessionManager.getFirestoreUserId(this)
        val schoolId = SessionManager.getSchoolId(this)
        if (userId.isBlank() || userId == "guest_user" || schoolId.isBlank()) {
            Toast.makeText(this, "Please login again to continue", Toast.LENGTH_LONG).show()
            return
        }

        selectedPaidPlan = plan
        selectedPaidButton = ctaButton
        setPaymentLoading(true, "Creating secure payment order...")

        lifecycleScope.launch(Dispatchers.IO) {
            val createOrderResult = paymentClient.createOrder(
                CreateOrderRequest(
                    userId = userId,
                    schoolId = schoolId,
                    planId = plan.id,
                    amountInr = plan.priceINR,
                    currency = "INR"
                )
            )

            withContext(Dispatchers.Main) {
                createOrderResult
                    .onSuccess { order -> launchRazorpayCheckout(plan, order) }
                    .onFailure { err ->
                        setPaymentLoading(false)
                        showToast("Unable to start payment: ${err.message}")
                    }
            }
        }
    }

    private fun launchRazorpayCheckout(
        plan: SchoolPlan,
        order: com.example.aiguru.payments.CreateOrderResponse
    ) {
        val keyId = order.keyId.ifBlank { BuildConfig.RAZORPAY_KEY_ID }
        if (keyId.isBlank()) {
            setPaymentLoading(false)
            showToast("Missing Razorpay key id. Add RAZORPAY_KEY_ID or return key_id from create-order API.")
            return
        }

        val options = JSONObject().apply {
            put("name", order.checkoutName.ifBlank { school.name })
            put("description", order.checkoutDescription.ifBlank { "${plan.name} plan" })
            put("order_id", order.orderId)
            put("currency", order.currency.ifBlank { "INR" })
            put("amount", order.amountPaise)

            put("retry", JSONObject().apply {
                put("enabled", true)
                put("max_count", 2)
            })

            put("theme", JSONObject().apply {
                put("color", school.branding.primaryColor)
            })

            put("prefill", JSONObject().apply {
                if (order.prefillName.isNotBlank()) put("name", order.prefillName)
                if (order.prefillEmail.isNotBlank()) put("email", order.prefillEmail)
                if (order.prefillContact.isNotBlank()) put("contact", order.prefillContact)
            })

            put("notes", JSONObject().apply {
                put("school_id", SessionManager.getSchoolId(this@SubscriptionActivity))
                put("user_id", SessionManager.getFirestoreUserId(this@SubscriptionActivity))
                put("plan_id", plan.id)
            })
        }

        runCatching {
            val checkout = Checkout()
            checkout.setKeyID(keyId)
            checkout.open(this, options)
        }.onFailure {
            setPaymentLoading(false)
            showToast("Checkout failed to open: ${it.message}")
        }
    }

    override fun onPaymentSuccess(razorpayPaymentId: String?, paymentData: PaymentData?) {
        val plan = selectedPaidPlan
        if (plan == null) {
            setPaymentLoading(false)
            showToast("Payment completed but plan context missing. Please contact support.")
            return
        }

        val paymentId = razorpayPaymentId.orEmpty()
        val orderId = paymentData?.orderId.orEmpty()
        val signature = paymentData?.signature.orEmpty()

        if (paymentId.isBlank() || orderId.isBlank() || signature.isBlank()) {
            setPaymentLoading(false)
            showToast("Payment signature missing. Plan not activated.")
            return
        }

        setPaymentLoading(true, "Verifying payment securely...")

        val userId = SessionManager.getFirestoreUserId(this)
        val schoolId = SessionManager.getSchoolId(this)
        lifecycleScope.launch(Dispatchers.IO) {
            val verifyResult = paymentClient.verifyPayment(
                VerifyPaymentRequest(
                    userId = userId,
                    schoolId = schoolId,
                    planId = plan.id,
                    razorpayPaymentId = paymentId,
                    razorpayOrderId = orderId,
                    razorpaySignature = signature
                )
            )

            withContext(Dispatchers.Main) {
                verifyResult
                    .onSuccess { response ->
                        if (response.verified) {
                            activatePlanAndContinue(plan)
                        } else {
                            setPaymentLoading(false)
                            showToast(response.message.ifBlank { "Payment verification failed. Please contact support." })
                        }
                    }
                    .onFailure { err ->
                        setPaymentLoading(false)
                        showToast("Verification failed: ${err.message}")
                    }
            }
        }
    }

    override fun onPaymentError(code: Int, response: String?, paymentData: PaymentData?) {
        Log.w(TAG, "Payment failed code=$code response=$response paymentData=$paymentData")
        setPaymentLoading(false)
        showToast("Payment cancelled/failed. You were not charged for this plan selection.")
    }

    private fun activatePlanAndContinue(plan: SchoolPlan) {
        SessionManager.savePlan(this, plan.id, plan.name)

        val userId = SessionManager.getFirestoreUserId(this)
        FirestoreManager.updateUserPlan(
            userId = userId,
            planId = plan.id,
            planName = plan.name,
            onSuccess = {
                setPaymentLoading(false)
                navigateHome()
            },
            onFailure = {
                // Keep UX smooth if Firestore write is delayed; local plan is already updated.
                setPaymentLoading(false)
                navigateHome()
            }
        )
    }

    private fun setPaymentLoading(loading: Boolean, statusMessage: String = "") {
        isPaymentInFlight = loading
        selectedPaidButton?.isEnabled = !loading
        if (loading) {
            selectedPaidButton?.text = "Processing..."
            if (statusMessage.isNotBlank()) showToast(statusMessage)
        } else {
            selectedPaidPlan?.let { plan ->
                selectedPaidButton?.text = "Subscribe - ${plan.displayPrice}"
            }
            selectedPaidPlan = null
            selectedPaidButton = null
        }
    }

    private fun showToast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun navigateHome() {
        startActivity(Intent(this, HomeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }
}
