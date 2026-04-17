package com.aiguruapp.student

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
import com.aiguruapp.student.config.AdminConfigRepository
import com.aiguruapp.student.firestore.FirestoreManager
import com.aiguruapp.student.models.FirestorePlan
import com.aiguruapp.student.models.School
import com.aiguruapp.student.models.SchoolPlan
import com.aiguruapp.student.payments.CreateOrderRequest
import com.aiguruapp.student.payments.PaymentApiClient
import com.aiguruapp.student.payments.VerifyPaymentRequest
import com.aiguruapp.student.utils.ConfigManager
import com.aiguruapp.student.utils.SessionManager
import com.google.android.material.button.MaterialButton
import com.razorpay.Checkout
import com.razorpay.PaymentData
import com.razorpay.PaymentResultWithDataListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SubscriptionActivity : BaseActivity(), PaymentResultWithDataListener {

    companion object {
        private const val TAG = "SubscriptionActivity"
    }

    private var school: School? = null
    private lateinit var paymentClient: PaymentApiClient
    private var selectedPaidPlan: SchoolPlan? = null
    private var selectedPaidButton: MaterialButton? = null
    private var isPaymentInFlight = false

    // Firestore-fetched plans & user plan state
    private var firestorePlans: List<FirestorePlan> = emptyList()
    private var userActivePlanId: String = "free"
    private var userPlanExpiryMs: Long = 0L
    /** Calendar days the selected paid plan stays active — sent to server for expiry calculation. */
    private var selectedValidityDays: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_subscription)

        findViewById<android.widget.ImageButton>(R.id.subscriptionBackBtn).setOnClickListener { finish() }

        Checkout.preload(applicationContext)
        AdminConfigRepository.fetchIfStale()

        val schoolId = intent.getStringExtra("schoolId")
            ?: SessionManager.getSchoolId(this)

        school = ConfigManager.getSchool(this, schoolId)
            ?: ConfigManager.getSchools(this).firstOrNull()
        // Continue even if school is null — plans and payment work without local school config

        paymentClient = PaymentApiClient(resolvePaymentBaseUrl())

        applySchoolBranding()
        loadPlansAndRender()
    }

    private fun resolvePaymentBaseUrl(): String = AdminConfigRepository.effectiveServerUrl()

    private fun applySchoolBranding() {
        val branding = school?.branding
        if (branding != null) {
            runCatching {
                val primary = Color.parseColor(branding.primaryColor)
                findViewById<LinearLayout>(R.id.subscriptionHeader)
                    .setBackgroundColor(primary)
            }
            findViewById<TextView>(R.id.schoolLogoText).text = branding.logoEmoji
        }
        val studentName = SessionManager.getStudentName(this)
        val studentId = SessionManager.getStudentId(this)
        val schoolName = school?.name ?: SessionManager.getSchoolName(this)
        findViewById<TextView>(R.id.schoolNameHeader).text = schoolName
        findViewById<TextView>(R.id.studentInfoHeader).text =
            "$studentName  •  ID: $studentId"
    }

    // ── Plan loading ─────────────────────────────────────────────────────

    /**
     * Primary entry point — fetches plans and user data from Firestore in parallel,
     * then renders cards with correct enabled/disabled state.
     * Falls back to local school.plans if Firestore fetch returns nothing.
     */
    private fun loadPlansAndRender() {
        showPlansLoading(true)
        val userId = SessionManager.getFirestoreUserId(this)
        var plansReady = false
        var userReady = false
        var fetchedPlans: List<FirestorePlan> = emptyList()

        fun tryRender() {
            if (!plansReady || !userReady) return
            showPlansLoading(false)
            if (fetchedPlans.isNotEmpty()) {
                firestorePlans = fetchedPlans
                buildPlanCardsFromFirestore()
            } else {
                // Firestore collection empty or unreachable — fall back to local config
                buildPlanCards()
            }
        }

        FirestoreManager.fetchPlans(
            onSuccess = { plans ->
                fetchedPlans = plans
                plansReady = true
                tryRender()
            },
            onFailure = {
                plansReady = true
                tryRender()
            }
        )
        FirestoreManager.getUserMetadata(
            userId = userId,
            onSuccess = { metadata ->
                userActivePlanId = metadata?.planId ?: SessionManager.getPlanId(this)
                userPlanExpiryMs = metadata?.planExpiryDate ?: SessionManager.getPlanExpiryMs(this)
                if (userPlanExpiryMs > 0) SessionManager.savePlanExpiry(this, userPlanExpiryMs)
                userReady = true
                tryRender()
            },
            onFailure = {
                userActivePlanId = SessionManager.getPlanId(this)
                userPlanExpiryMs = SessionManager.getPlanExpiryMs(this)
                userReady = true
                tryRender()
            }
        )
    }

    private fun showPlansLoading(loading: Boolean) {
        // Use the existing plansContainer visibility as loading indicator
        val container = findViewById<LinearLayout>(R.id.plansContainer)
        if (loading) {
            container.removeAllViews()
            val tv = TextView(this).apply {
                text = "Loading plans…"
                textSize = 14f
                setTextColor(Color.parseColor("#666B8A"))
                setPadding(32, 48, 32, 48)
            }
            container.addView(tv)
        }
    }

    // ── Firestore-backed plan cards ─────────────────────────────────

    private fun buildPlanCardsFromFirestore() {
        val container = findViewById<LinearLayout>(R.id.plansContainer)
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)

        // Find the price of the plan the user is currently subscribed to
        val activePlan = firestorePlans.find { it.id == userActivePlanId }
        val activePriceInr = activePlan?.priceInr ?: 0
        val isPlanValid = !activePlan?.isFree.let { it == true } &&
                          userPlanExpiryMs > 0 &&
                          System.currentTimeMillis() < userPlanExpiryMs

        firestorePlans.forEach { plan ->
            val card = inflater.inflate(R.layout.item_plan_card, container, false)
            // Block current-or-lower plans when user has a live paid subscription
            val isBlocked = isPlanValid && !plan.isFree && plan.priceInr <= activePriceInr
            val isCurrentPlan = plan.id == userActivePlanId
            bindFirestorePlanCard(card, plan, isBlocked, isCurrentPlan)
            container.addView(card)
        }
    }

    private fun bindFirestorePlanCard(
        view: View,
        plan: FirestorePlan,
        isBlocked: Boolean,
        isCurrentPlan: Boolean
    ) {
        val accentColor = runCatching { Color.parseColor(plan.accentColor) }
            .getOrDefault(Color.parseColor("#1565C0"))

        view.findViewById<TextView>(R.id.planName).apply {
            text = plan.name
            setTextColor(Color.parseColor("#1A2332"))
        }
        view.findViewById<TextView>(R.id.planDuration).text = plan.duration
        view.findViewById<TextView>(R.id.planPrice).apply {
            text = plan.displayPrice
            setTextColor(if (plan.isFree) Color.parseColor("#10B981") else accentColor)
        }
        view.findViewById<TextView>(R.id.planFeatures).text =
            plan.features.joinToString("\n") { "✓  $it" }

        // Badge
        val badgeView = view.findViewById<TextView>(R.id.planBadge)
        val badgeText = when {
            isCurrentPlan && userPlanExpiryMs > 0 -> "🟢 Active"
            plan.badge.isNotBlank() -> plan.badge
            else -> ""
        }
        if (badgeText.isNotBlank()) {
            badgeView.text = badgeText
            badgeView.visibility = View.VISIBLE
            badgeView.backgroundTintList = ColorStateList.valueOf(
                if (isCurrentPlan) Color.parseColor("#10B981") else accentColor
            )
        } else {
            badgeView.visibility = View.GONE
        }

        // CTA button
        val btn = view.findViewById<MaterialButton>(R.id.selectPlanButton)
        when {
            isBlocked && isCurrentPlan -> {
                val expiryLabel = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
                    .format(Date(userPlanExpiryMs))
                btn.text = "✓ Active — expires $expiryLabel"
                btn.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#10B981"))
                btn.isEnabled = false
                btn.alpha = 0.85f
            }
            isBlocked -> {
                btn.text = "Included in your plan"
                btn.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#9CA3AF"))
                btn.isEnabled = false
                btn.alpha = 0.6f
            }
            plan.isFree -> {
                btn.text = "Start Free →"
                btn.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#374151"))
                btn.isEnabled = true
                btn.alpha = 1f
                btn.setOnClickListener { startFirestorePlanSelection(plan, btn) }
            }
            else -> {
                btn.text = "Subscribe  ${plan.displayPrice} →"
                btn.backgroundTintList = ColorStateList.valueOf(accentColor)
                btn.isEnabled = true
                btn.alpha = 1f
                btn.setOnClickListener { startFirestorePlanSelection(plan, btn) }
            }
        }
    }

    private fun startFirestorePlanSelection(plan: FirestorePlan, ctaButton: MaterialButton) {
        if (isPaymentInFlight) { showToast("Payment is already in progress"); return }
        if (plan.isFree) {
            // Free plan — save locally; server does not need to act
            SessionManager.savePlan(this, plan.id, plan.name)
            SessionManager.savePlanExpiry(this, 0L)
            selectedValidityDays = 0
            navigateHome()
            return
        }
        // Wrap as SchoolPlan for the shared Razorpay checkout flow
        val schoolPlan = SchoolPlan(
            id = plan.id, name = plan.name, badge = plan.badge,
            priceINR = plan.priceInr, duration = plan.duration, features = plan.features
        )
        selectedValidityDays = plan.validityDays
        startPlanSelection(schoolPlan, ctaButton)
    }

    // ── Legacy SchoolPlan card builder (fallback when Firestore is empty) ─────

    private fun buildPlanCards() {
        val container = findViewById<LinearLayout>(R.id.plansContainer)
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)

        // Identify recommended plan from config (BASIC by default)
        val recommendedPlanId = ConfigManager.getAppConfig(this)
            .let { "BASIC" }  // Could be read from config in future

        val schoolPlans = school?.plans ?: emptyList()
        if (schoolPlans.isEmpty()) {
            val tv = TextView(this).apply {
                text = "No plans available right now.\nPlease check back later or contact support."
                textSize = 14f
                setTextColor(Color.parseColor("#666B8A"))
                setPadding(32, 48, 32, 48)
                gravity = android.view.Gravity.CENTER
            }
            container.addView(tv)
            return
        }
        schoolPlans.forEach { plan ->
            val card = inflater.inflate(R.layout.item_plan_card, container, false)
            bindPlanCard(card, plan, plan.id == recommendedPlanId)
            container.addView(card)
        }
    }

    private fun bindPlanCard(view: View, plan: SchoolPlan, isRecommended: Boolean) {
        val branding = school?.branding
        val primaryColor = runCatching { Color.parseColor(branding?.primaryColor ?: "") }
            .getOrDefault(Color.parseColor("#1565C0"))
        val accentColor = runCatching { Color.parseColor(branding?.accentColor ?: "") }
            .getOrDefault(Color.parseColor("#1A1A2E"))

        view.findViewById<TextView>(R.id.planName).apply {
            text = plan.name
            setTextColor(Color.parseColor("#1A2332"))
        }
        view.findViewById<TextView>(R.id.planDuration).text = plan.duration
        view.findViewById<TextView>(R.id.planPrice).apply {
            text = plan.displayPrice
            setTextColor(if (plan.isFree) Color.parseColor("#10B981") else Color.parseColor("#0891B2"))
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
        btn.text = if (plan.isFree) "Start Free →" else "Subscribe  ${plan.displayPrice} →"
        val btnColor = if (isRecommended) Color.parseColor("#0F1724") else Color.parseColor("#374151")
        btn.backgroundTintList = ColorStateList.valueOf(btnColor)
        btn.setOnClickListener { startPlanSelection(plan, btn) }
    }

    private fun startPlanSelection(plan: SchoolPlan, ctaButton: MaterialButton) {
        if (isPaymentInFlight) {
            Toast.makeText(this, "Payment is already in progress", Toast.LENGTH_SHORT).show()
            return
        }

        if (plan.isFree) {
            selectedValidityDays = 0
            activatePlanAndContinue(plan)
            return
        }

        // Default validity for SchoolPlan fallback: 30 days unless overridden by Firestore
        if (selectedValidityDays == 0) selectedValidityDays = 30

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
            val studentName  = SessionManager.getStudentName(this@SubscriptionActivity)
            val firebaseUser = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
            val email        = firebaseUser?.email
                ?: SessionManager.getStudentId(this@SubscriptionActivity).takeIf { it.contains("@") }
                ?: ""
            val phone        = firebaseUser?.phoneNumber ?: ""

            val createOrderResult = paymentClient.createOrder(
                CreateOrderRequest(
                    userId    = userId,
                    schoolId  = schoolId,
                    planId    = plan.id,
                    planName  = plan.name,
                    amountInr = plan.priceINR,
                    currency  = "INR",
                    customerName  = studentName,
                    customerEmail = email,
                    customerPhone = phone
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
        order: com.aiguruapp.student.payments.CreateOrderResponse
    ) {
        val keyId = order.keyId.ifBlank { AdminConfigRepository.razorpayKeyId() }
        if (keyId.isBlank()) {
            setPaymentLoading(false)
            showToast("Razorpay key not loaded yet. Please wait a moment and retry.")
            return
        }

        val options = JSONObject().apply {
            put("name", order.checkoutName.ifBlank { school?.name ?: "AI Guru" })
            put("description", order.checkoutDescription.ifBlank { "${plan.name} plan" })
            put("order_id", order.orderId)
            put("currency", order.currency.ifBlank { "INR" })
            put("amount", order.amountPaise)   // paise — required by Razorpay SDK

            put("retry", JSONObject().apply {
                put("enabled", true)
                put("max_count", 2)
            })

            put("theme", JSONObject().apply {
                put("color", school?.branding?.primaryColor ?: "#1565C0")
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

    // Required for singleTop launchMode: lets Razorpay SDK receive the
    // razorpay:// callback intent that UPI apps fire after payment.
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
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
                    razorpaySignature = signature,
                    validityDays = selectedValidityDays
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
        val now = System.currentTimeMillis()
        val expiryMs = if (selectedValidityDays > 0)
            now + selectedValidityDays.toLong() * 86_400_000L else 0L

        // Save locally so quota checks work offline immediately.
        // The server (FastAPI) has already written the authoritative plan record
        // to users_table/{userId} in Firestore after verifyPayment succeeded.
        // Android does NOT write plan data to Firestore to prevent client-side tampering.
        SessionManager.savePlan(this, plan.id, plan.name)
        SessionManager.savePlanExpiry(this, expiryMs)

        setPaymentLoading(false)
        navigateHome()
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
