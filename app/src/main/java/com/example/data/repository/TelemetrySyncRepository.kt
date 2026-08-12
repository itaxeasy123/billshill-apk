package com.example.data.repository

import android.content.Context
import android.os.Build
import android.util.Log
import com.example.data.api.ApiClient
import com.example.data.api.TelemetryApiService
import com.example.data.api.model.TelemetrySyncRequest
import com.example.data.api.model.TelemetrySyncResponse
import com.example.data.db.AppDatabase
import com.example.utils.TelemetrySanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repository responsible for syncing local Room database crash logs and telemetry entities
 * to the remote REST API endpoint (/api/v1/telemetry/crash-log).
 */
class TelemetrySyncRepository(
    private val context: Context,
    private val apiService: TelemetryApiService = ApiClient.createTelemetryService()
) {

    private companion object {
        private const val TAG = "TelemetrySyncRepo"
        private const val MAX_PAYLOAD_BYTES = 10240 // 10 KB strict threshold limit
    }

    /**
     * Reads local Room database crash logs, sanitizes content using regex, enforces 10KB threshold,
     * and uploads to the remote REST API microservice with automatic retries on unstable network conditions.
     */
    suspend fun syncLocalCrashLogsToRemote(): Result<TelemetrySyncResponse> = withContext(Dispatchers.IO) {
        try {
            val db = AppDatabase.getDatabase(context)
            val telemetryDao = db.telemetryDao()

            val crashLogs = telemetryDao.getAllCrashLogs()
            if (crashLogs.isEmpty()) {
                Log.d(TAG, "No crash logs in local Room database to sync.")
                return@withContext Result.success(
                    TelemetrySyncResponse(
                        success = true,
                        message = "No local logs pending sync.",
                        processedCount = 0
                    )
                )
            }

            // 1. Build plain text application data stream from local Room entities
            val rawStreamBuilder = StringBuilder()
            rawStreamBuilder.append("=== TELEMETRY DATA STREAM (DEVICE: ${Build.MODEL}) ===\n")
            crashLogs.forEachIndexed { index, log ->
                rawStreamBuilder.append("LOG_ID:${log.id}|TIMESTAMP:${log.timestamp}\n")
                rawStreamBuilder.append("CONTENT: ${log.logContent.trim()}\n")
                if (index < crashLogs.size - 1) rawStreamBuilder.append("---\n")
            }

            // 2. Perform string data sanitization sequences using regex models (strip tags/script injection tokens)
            val sanitizedPayload = TelemetrySanitizer.sanitizePayload(rawStreamBuilder.toString())

            // 3. Enforce strict 10 KB threshold restriction preventing payload injection overflow
            val cappedPayload = TelemetrySanitizer.enforceMaxPayloadSize(sanitizedPayload, MAX_PAYLOAD_BYTES)

            val deviceId = "${Build.MANUFACTURER}_${Build.MODEL}_${Build.VERSION.SDK_INT}"
            val requestBody = TelemetrySyncRequest(
                deviceId = deviceId,
                timestamp = System.currentTimeMillis(),
                payload = cappedPayload,
                logCount = crashLogs.size
            )

            Log.d(TAG, "Initiating REST API sync to /api/v1/telemetry/crash-log (${cappedPayload.toByteArray().size} bytes)...")

            // 4. Send via Retrofit with RetryInterceptor + X-API-KEY header
            val response = apiService.syncCrashLogs(requestBody)

            if (response.isSuccessful && response.body() != null) {
                val responseBody = response.body()!!
                Log.d(TAG, "Sync successful: ${responseBody.message}")
                Result.success(responseBody)
            } else {
                val errorMsg = "HTTP ${response.code()}: ${response.errorBody()?.string() ?: response.message()}"
                Log.e(TAG, "Sync failed: $errorMsg")
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during telemetry sync: ${e.message}", e)
            Result.failure(e)
        }
    }
}
