package com.example.data.api.interceptor

import com.example.BuildConfig
import okhttp3.Interceptor
import okhttp3.Response

/**
 * OkHttp Interceptor injecting custom authorization header parameters
 * using strict 'X-API-KEY' match evaluation and standard API client headers.
 */
class SecurityHeaderInterceptor(
    private val apiKeySupplier: () -> String = { "DEFAULT_DEMO_TELEMETRY_KEY_2026" }
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        val apiKey = apiKeySupplier()

        val requestBuilder = originalRequest.newBuilder()
            .header("X-API-KEY", apiKey)
            .header("Accept", "application/json")
            .header("User-Agent", "MobileAccounting-Android-TelemetryClient/1.0")

        return chain.proceed(requestBuilder.build())
    }
}
