package com.example.data.api.interceptor

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * OkHttp Interceptor that handles retry logic with exponential backoff
 * to recover from unstable network conditions when syncing telemetry data.
 */
class RetryInterceptor(
    private val maxRetries: Int = 3,
    private val initialBackoffMs: Long = 1000L
) : Interceptor {

    private companion object {
        private const val TAG = "RetryInterceptor"
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var response: Response? = null
        var exception: Exception? = null
        var tryCount = 0

        while (tryCount <= maxRetries) {
            try {
                if (tryCount > 0) {
                    val backoffDelay = initialBackoffMs * (1 shl (tryCount - 1))
                    Log.w(TAG, "Network retry attempt #$tryCount for ${request.url} after ${backoffDelay}ms delay...")
                    Thread.sleep(backoffDelay)
                }

                response?.close()
                response = chain.proceed(request)

                // If successful (2xx) or client error (4xx except 429), don't retry
                if (response.isSuccessful || (response.code in 400..499 && response.code != 429)) {
                    return response
                }

                Log.w(TAG, "Server returned HTTP status ${response.code} on attempt #${tryCount + 1}")
            } catch (e: IOException) {
                exception = e
                Log.w(TAG, "Network failure on attempt #${tryCount + 1}: ${e.message}")
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Retry interrupted", e)
            }

            tryCount++
        }

        response?.let { return it }
        throw exception ?: IOException("Failed after $maxRetries retries due to unstable network conditions")
    }
}
