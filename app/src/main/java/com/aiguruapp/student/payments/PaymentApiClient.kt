package com.aiguruapp.student.payments

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class CreateOrderRequest(
    val userId: String,
    val schoolId: String,
    val planId: String,
    val planName: String,     // e.g. "Basic", "Premium"
    val amountInr: Int,
    val currency: String = "INR",
    // User identity — used for Razorpay prefill and Firestore audit
    val customerName: String = "",
    val customerEmail: String = "",
    val customerPhone: String = ""
)

data class CreateOrderResponse(
    val orderId: String,
    val amountPaise: Int,      // in paise (server returns amount * 100)
    val currency: String,
    val keyId: String,
    val checkoutName: String,
    val checkoutDescription: String,
    val prefillName: String,
    val prefillEmail: String,
    val prefillContact: String
)

data class VerifyPaymentRequest(
    val userId: String,
    val schoolId: String,
    val planId: String,
    val razorpayPaymentId: String,
    val razorpayOrderId: String,
    val razorpaySignature: String,
    /** Calendar days the plan stays active after purchase. 0 = no expiry (free plan). */
    val validityDays: Int = 0
)

data class VerifyPaymentResponse(
    val verified: Boolean,
    val message: String
)

class PaymentApiClient(
    private val baseUrl: String,
    private val authToken: String? = null
) {

    companion object {
        private const val TAG = "PaymentApiClient"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    fun createOrder(request: CreateOrderRequest): Result<CreateOrderResponse> {
        return runCatching {
            val body = JSONObject().apply {
                put("user_id", request.userId)
                put("school_id", request.schoolId)
                put("plan_id", request.planId)
                put("plan_name", request.planName)
                put("amountInr", request.amountInr)
                put("currency", request.currency)
                if (request.customerName.isNotBlank())  put("customer_name",  request.customerName)
                if (request.customerEmail.isNotBlank()) put("customer_email", request.customerEmail)
                if (request.customerPhone.isNotBlank()) put("customer_phone", request.customerPhone)
            }

            val endpoint = baseUrl.trimEnd('/') + "/payments/razorpay/create-order"
            val httpRequest = Request.Builder()
                .url(endpoint)
                .header("Content-Type", "application/json")
                .apply {
                    if (!authToken.isNullOrBlank()) header("Authorization", "Bearer $authToken")
                }
                .post(body.toString().toRequestBody(JSON))
                .build()

            http.newCall(httpRequest).execute().use { res ->
                val payload = res.body?.string().orEmpty()
                if (!res.isSuccessful) {
                    throw IllegalStateException("create-order failed (${res.code}): $payload")
                }

                val root = JSONObject(payload)
                CreateOrderResponse(
                    orderId = root.optString("order_id", root.optString("orderId")),
                    amountPaise = root.optInt("amount"),
                    currency = root.optString("currency", "INR"),
                    keyId = root.optString("key_id", root.optString("keyId", "")),
                    checkoutName = root.optString("checkout_name", "AI Guru"),
                    checkoutDescription = root.optString("checkout_description", "Plan upgrade"),
                    prefillName = root.optString("prefill_name", ""),
                    prefillEmail = root.optString("prefill_email", ""),
                    prefillContact = root.optString("prefill_contact", "")
                ).also {
                    if (it.orderId.isBlank()) throw IllegalStateException("create-order response missing order_id")
                    if (it.amountPaise <= 0) throw IllegalStateException("create-order response has invalid amount")
                }
            }
        }.onFailure {
            Log.e(TAG, "createOrder failed: ${it.message}", it)
        }
    }

    fun verifyPayment(request: VerifyPaymentRequest): Result<VerifyPaymentResponse> {
        return runCatching {
            val body = JSONObject().apply {
                put("user_id", request.userId)
                put("school_id", request.schoolId)
                put("plan_id", request.planId)
                put("razorpay_payment_id", request.razorpayPaymentId)
                put("razorpay_order_id", request.razorpayOrderId)
                put("razorpay_signature", request.razorpaySignature)
                put("validity_days", request.validityDays)
            }

            val endpoint = baseUrl.trimEnd('/') + "/payments/razorpay/verify"
            val httpRequest = Request.Builder()
                .url(endpoint)
                .header("Content-Type", "application/json")
                .apply {
                    if (!authToken.isNullOrBlank()) header("Authorization", "Bearer $authToken")
                }
                .post(body.toString().toRequestBody(JSON))
                .build()

            http.newCall(httpRequest).execute().use { res ->
                val payload = res.body?.string().orEmpty()
                if (!res.isSuccessful) {
                    throw IllegalStateException("verify failed (${res.code}): $payload")
                }
                val root = JSONObject(payload)
                VerifyPaymentResponse(
                    verified = root.optBoolean("verified", false),
                    message = root.optString("message", "")
                )
            }
        }.onFailure {
            Log.e(TAG, "verifyPayment failed: ${it.message}", it)
        }
    }
}
