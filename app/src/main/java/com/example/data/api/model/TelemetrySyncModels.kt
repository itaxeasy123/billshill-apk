package com.example.data.api.model

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class TelemetrySyncRequest(
    val deviceId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val payload: String,
    val logCount: Int
)

@JsonClass(generateAdapter = true)
data class TelemetrySyncResponse(
    val success: Boolean,
    val message: String,
    val processedCount: Int = 0,
    val timestamp: Long = System.currentTimeMillis()
)
