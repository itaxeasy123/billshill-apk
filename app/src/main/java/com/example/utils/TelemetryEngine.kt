package com.example.utils

import android.content.Context
import android.os.Build
import com.example.data.db.AppDatabase
import com.example.data.model.CrashLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TelemetryEngine {

    private var lastCrashReportJson: String = ""
    private val queryLatencyLog = mutableListOf<Pair<String, Long>>()
    private val featureEngagementCount = mutableMapOf<String, Int>()

    fun trackFeatureEngagement(featureName: String) {
        val current = featureEngagementCount[featureName] ?: 0
        featureEngagementCount[featureName] = current + 1
    }

    fun recordQueryLatency(queryName: String, durationMs: Long) {
        synchronized(queryLatencyLog) {
            queryLatencyLog.add(queryName to durationMs)
            if (queryLatencyLog.size > 100) {
                queryLatencyLog.removeAt(0)
            }
        }
    }

    /**
     * Parses a raw crash/telemetry JSON payload into a beautifully clean,
     * human-readable plain text string (NOT raw JSON).
     */
    fun parseJsonToHumanReadableText(jsonString: String): String {
        return try {
            val json = JSONObject(jsonString)

            val timestamp = json.optString("timestamp", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
            val deviceName = when {
                json.has("device_name") -> json.optString("device_name")
                json.has("deviceModel") -> "${json.optString("deviceManufacturer", "")} ${json.optString("deviceModel")}".trim()
                json.has("deviceInfo") -> {
                    val info = json.optJSONObject("deviceInfo")
                    "${info?.optString("brand", "")} ${info?.optString("model", "")}".trim()
                }
                else -> "${Build.MANUFACTURER} ${Build.MODEL}"
            }
            val appVersion = json.optString("app_version", json.optString("appVersion", "1.0.0"))
            val exceptionType = json.optString("exception_type", json.optString("exceptionType", json.optString("exceptionClass", "Unknown Exception")))
            val errorMessage = json.optString("error_message", json.optString("errorMessage", json.optString("message", "No error description available")))
            val lineNumber = when {
                json.has("line_number") -> json.optString("line_number")
                json.has("lineNumber") -> json.optString("lineNumber")
                json.has("stackTrace") -> {
                    val stack = json.optString("stackTrace")
                    val lines = stack.split("\n")
                    lines.firstOrNull { it.contains("at ") && !it.contains("android.") && !it.contains("java.") }
                        ?: lines.getOrNull(1) ?: "N/A"
                }
                else -> "N/A"
            }

            buildString {
                appendLine("================ CRASH TELEMETRY REPORT ================")
                appendLine("Time          : $timestamp")
                appendLine("Device        : $deviceName")
                appendLine("App Version   : $appVersion")
                appendLine("Exception Type: $exceptionType")
                appendLine("Error Message : $errorMessage")
                appendLine("Line / Stack  : ${lineNumber.trim()}")
                appendLine("=======================================================")
            }
        } catch (e: Exception) {
            // Fallback plain text if JSON parsing fails
            buildString {
                appendLine("================ CRASH TELEMETRY REPORT ================")
                appendLine("Time          : ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
                appendLine("Device        : ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("Raw Telemetry : $jsonString")
                appendLine("=======================================================")
            }
        }
    }

    /**
     * Converts a raw JSON crash payload into human-readable plain text,
     * inserts it directly into the local Room Database (CrashLog) via TelemetryDao,
     * and triggers automated storage maintenance.
     */
    suspend fun processAndStoreCrashJson(context: Context, jsonString: String): CrashLog {
        val plainTextLog = parseJsonToHumanReadableText(jsonString)
        val crashLog = CrashLog(
            logContent = plainTextLog,
            timestamp = System.currentTimeMillis()
        )

        withContext(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(context)
            db.telemetryDao().insertCrashLog(crashLog)

            // Trigger automated storage maintenance on database write operation
            try {
                TelemetryMaintenanceEngine.runMaintenance(context)
            } catch (e: Exception) {
                // Ignore maintenance errors during crash log write
            }
        }

        return crashLog
    }

    /**
     * Generates a raw JSON crash payload from a throwable, converts it to plain text,
     * and stores it into the local Room DB.
     */
    suspend fun recordThrowable(context: Context, throwable: Throwable, stateSnapshot: String = "Active"): CrashLog {
        val jsonPayload = generateCrashReportJson(throwable, stateSnapshot)
        return processAndStoreCrashJson(context, jsonPayload)
    }

    fun generateCrashReportJson(throwable: Throwable, stateSnapshot: String = "App Active"): String {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val stackTraceString = sw.toString()

        val json = JSONObject().apply {
            put("telemetryType", "MOBILE_CRASH_REPORT")
            put("timestamp", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date()))
            put("device_name", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("deviceModel", Build.MODEL)
            put("deviceManufacturer", Build.MANUFACTURER)
            put("osVersion", "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            put("app_version", "1.0.0")
            put("exception_type", throwable.javaClass.simpleName)
            put("exceptionClass", throwable.javaClass.name)
            put("error_message", throwable.message ?: "Unknown Exception")
            put("errorMessage", throwable.message ?: "Unknown Exception")
            put("stackTrace", stackTraceString)
            put("line_number", throwable.stackTrace.firstOrNull()?.lineNumber?.toString() ?: "N/A")
            put("stateSnapshot", stateSnapshot)
        }

        lastCrashReportJson = json.toString(2)
        return lastCrashReportJson
    }

    fun generateAnalyticsReportJson(
        avgQuerySpeedMs: Long = 12L,
        avgRenderingLatencyMs: Long = 16L
    ): String {
        val engagementArray = JSONArray()
        featureEngagementCount.forEach { (feature, count) ->
            engagementArray.put(JSONObject().apply {
                put("feature", feature)
                put("interactionCount", count)
            })
        }

        val queriesArray = JSONArray()
        synchronized(queryLatencyLog) {
            queryLatencyLog.takeLast(10).forEach { (q, timeMs) ->
                queriesArray.put(JSONObject().apply {
                    put("queryName", q)
                    put("executionTimeMs", timeMs)
                })
            }
        }

        val json = JSONObject().apply {
            put("telemetryType", "USER_ANALYTICS_REPORT")
            put("timestamp", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date()))
            put("appVersion", "1.0.0-PROD")
            put("deviceInfo", JSONObject().apply {
                put("model", Build.MODEL)
                put("brand", Build.BRAND)
                put("sdkVersion", Build.VERSION.SDK_INT)
            })
            put("performanceMetrics", JSONObject().apply {
                put("averageDatabaseQuerySpeedMs", avgQuerySpeedMs)
                put("averageRenderingLatencyMs", avgRenderingLatencyMs)
                put("fpsTarget", 60)
            })
            put("featureEngagement", engagementArray)
            put("recentDatabaseQueries", queriesArray)
        }

        return json.toString(2)
    }

    fun getLatestCrashLogJson(): String {
        return if (lastCrashReportJson.isNotEmpty()) {
            lastCrashReportJson
        } else {
            generateCrashReportJson(
                IllegalStateException("Diagnostic test exception for telemetry boundary check"),
                "Normal Operational State"
            )
        }
    }
}
