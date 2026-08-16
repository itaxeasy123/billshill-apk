package com.example.data.auth

import com.example.BuildConfig
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

/** Outcome of an OTP step, with a message fit to show the user. */
sealed interface OtpResult {
    data object Sent : OtpResult
    /** The code checked out. Nothing else is taken from the response. */
    data object Verified : OtpResult
    data class Failed(val message: String) : OtpResult
}

/**
 * Talks to the APK backend's OTP endpoints.
 *
 * Failures are returned, never swallowed. The screen this replaces accepted any input and
 * reported success, so a user could believe they were authenticated when nothing had
 * happened at all.
 */
object OtpAuthClient {

    /**
     * From `APK_API_BASE_URL` in the gitignored `.env`, injected at build time.
     *
     * Was a hardcoded constant, so pointing the app at a deployed backend — or at a LAN
     * IP for a physical device — meant editing Kotlin and rebuilding from source.
     *
     * The default is `http://10.0.2.2:54110/`: `10.0.2.2` is the host machine as seen
     * from the Android emulator, because `localhost` inside the emulator is the emulator
     * itself. If a request here fails with "could not reach the server", the usual cause
     * is that the backend is not running — start it with
     * `cd itaxeasy-apk-backend && ./dev_start.sh`.
     */
    private val BASE_URL: String = BuildConfig.APK_API_BASE_URL

    private val api: AuthApi by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
            else HttpLoggingInterceptor.Level.NONE
        }
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .build()

        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(
                MoshiConverterFactory.create(
                    Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
                )
            )
            .build()
            .create(AuthApi::class.java)
    }

    /** Normalises to E.164. The backend matches on the last 10 digits either way. */
    fun normalisePhone(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        return when {
            digits.length == 10 -> "+91$digits"
            digits.length > 10 -> "+${digits.takeLast(12)}"
            else -> raw.trim()
        }
    }

    suspend fun sendOtp(phone: String): OtpResult = runCatching {
        val response = api.sendOtp(OtpSendRequest(normalisePhone(phone)))
        if (response.isSuccessful) OtpResult.Sent
        else OtpResult.Failed(errorMessage(response.errorBody()?.string(), "Could not send the OTP."))
    }.getOrElse { OtpResult.Failed(networkMessage(it)) }

    suspend fun resendOtp(phone: String): OtpResult = runCatching {
        val response = api.resendOtp(OtpSendRequest(normalisePhone(phone)))
        if (response.isSuccessful) OtpResult.Sent
        else OtpResult.Failed(errorMessage(response.errorBody()?.string(), "Could not resend the OTP."))
    }.getOrElse { OtpResult.Failed(networkMessage(it)) }

    suspend fun verifyOtp(phone: String, otp: String): OtpResult = runCatching {
        val normalised = normalisePhone(phone)
        val response = api.verifyOtp(
            OtpVerifyRequest(
                phone = normalised,
                otp = otp.trim(),
                // The other product's user table needs a name to create a row. The user is
                // not asked for one here — they enter their real details once, in their
                // own business profile — so the phone itself is sent. Nothing that comes
                // back is read.
                fullName = normalised,
                deviceInfo = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
            )
        )
        if (response.isSuccessful) OtpResult.Verified
        else OtpResult.Failed(errorMessage(response.errorBody()?.string(), "Could not verify the OTP."))
    }.getOrElse { OtpResult.Failed(networkMessage(it)) }

    /** FastAPI reports problems as `{"detail": "..."}`. */
    private fun errorMessage(body: String?, fallback: String): String {
        if (body.isNullOrBlank()) return fallback
        return runCatching {
            when (val detail = JSONObject(body).get("detail")) {
                is String -> detail
                else -> detail.toString()
            }
        }.getOrDefault(fallback)
    }

    private fun networkMessage(t: Throwable): String = when (t) {
        is java.net.SocketTimeoutException -> "The server did not respond. Check your connection and try again."
        is java.net.ConnectException, is java.net.UnknownHostException ->
            "Could not reach the server. Check your connection and try again."
        else -> t.message ?: "Something went wrong sending the OTP."
    }
}
