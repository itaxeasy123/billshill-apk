package com.example.data.api

import com.example.data.api.model.TelemetrySyncRequest
import com.example.data.api.model.TelemetrySyncResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

/**
 * Retrofit service interface for syncing local Room database entities and crash logs
 * to a remote REST API microservice endpoint.
 */
interface TelemetryApiService {

    /**
     * Sends sanitized plain text application crash logs and database telemetry streams
     * to the remote microservices endpoint.
     *
     * Structured route path: /api/v1/telemetry/crash-log
     */
    @POST("api/v1/telemetry/crash-log")
    suspend fun syncCrashLogs(
        @Body request: TelemetrySyncRequest,
        @Header("X-API-KEY") apiKey: String? = null
    ): Response<TelemetrySyncResponse>
}
