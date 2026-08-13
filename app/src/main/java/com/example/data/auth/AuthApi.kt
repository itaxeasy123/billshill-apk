package com.example.data.auth

import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * OTP send and verify, borrowed from the iTaxEasy APK backend.
 *
 * That backend belongs to a DIFFERENT product. Bill-Shill uses it purely as an SMS
 * verification service — it asks MSG91 to send the code and confirms the code is right —
 * and takes nothing else from it. The user's profile, business details and books are
 * created and kept in Bill-Shill's own local database.
 *
 * So the response is read for one bit of information: did the code check out. The user
 * record, access token and refresh token it also returns are ignored. Consuming them
 * would tie this app's identity to another product's user table, which is a version of
 * the bug C13 fixed — logging in overwriting a carefully entered business profile.
 *
 * Mounted at `/api/auth` in that backend's `main.py`.
 */
interface AuthApi {

    @POST("api/auth/otp/send")
    suspend fun sendOtp(@Body body: OtpSendRequest): Response<OtpSendResponse>

    @POST("api/auth/otp/resend")
    suspend fun resendOtp(@Body body: OtpSendRequest): Response<OtpSendResponse>

    @POST("api/auth/otp/verify")
    suspend fun verifyOtp(@Body body: OtpVerifyRequest): Response<OtpVerifyResponse>
}

@JsonClass(generateAdapter = true)
data class OtpSendRequest(val phone: String)

@JsonClass(generateAdapter = true)
data class OtpSendResponse(val success: Boolean = false, val message: String? = null)

/**
 * [fullName] exists only to satisfy the other product's user table, which requires a name
 * to create a row. Bill-Shill does not ask the user for one here — they enter their real
 * details once, in their own business profile — so the phone number is sent instead. It
 * is a true value in a required field of a foreign system, not an invented identity.
 */
@JsonClass(generateAdapter = true)
data class OtpVerifyRequest(
    val phone: String,
    val otp: String,
    val fullName: String? = null,
    val deviceInfo: String? = null
)

/**
 * Deliberately empty of user data.
 *
 * The endpoint returns an access token, a refresh token and a full user record. None are
 * read: a successful HTTP status IS the answer to the only question being asked, and
 * storing a credential this app never calls another endpoint with would be keeping a
 * secret for no purpose.
 */
@JsonClass(generateAdapter = true)
class OtpVerifyResponse
