package com.example.data.sync

import android.content.Context
import android.util.Log
import com.example.data.db.AppDatabase
import com.example.data.model.CrashLog
import com.example.utils.TelemetryEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class RegionalBackendSyncEngine private constructor(private val context: Context) {

    private val db = AppDatabase.getDatabase(context)

    // Configured for regional low-bandwidth networks with generous timeouts and retry mechanisms
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private var backendBaseUrl: String = "http://10.0.2.2:5000" // Default local Node.js / Python backend

    fun setBackendUrl(url: String) {
        if (url.isNotBlank()) {
            backendBaseUrl = url.trimEnd('/')
        }
    }

    /**
     * Syncs all pending plain-text crash telemetry logs to the backend Node.js / Python API.
     */
    suspend fun syncCrashLogsToBackend(): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val logs = db.crashLogDao().getAllLogs()
            if (logs.isEmpty()) {
                return@withContext Result.success(0)
            }

            val payloadArray = JSONArray()
            logs.forEach { log ->
                payloadArray.put(JSONObject().apply {
                    put("id", log.id)
                    put("logContent", log.logContent)
                    put("timestamp", log.timestamp)
                })
            }

            val requestBody = JSONObject().apply {
                put("device_type", "ANDROID_MERCHANT")
                put("logs_count", logs.size)
                put("telemetry_logs", payloadArray)
            }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

            val request = Request.Builder()
                .url("$backendBaseUrl/api/telemetry/crash-logs")
                .post(requestBody)
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", "KiranaAccountingApp/1.0 (Low-Bandwidth Regional Sync)")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d("RegionalSyncEngine", "Successfully synced ${logs.size} crash log(s) to backend.")
                    Result.success(logs.size)
                } else {
                    Log.w("RegionalSyncEngine", "Backend returned error code: ${response.code}")
                    Result.failure(IOException("Server error ${response.code}: ${response.message}"))
                }
            }
        } catch (e: Exception) {
            Log.e("RegionalSyncEngine", "Low-bandwidth dropout or network failure during log sync: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Syncs local merchant accounting records (ledgers, vouchers) to Node.js / Python backend API.
     */
    suspend fun syncAccountingDataToBackend(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val dao = db.accountingDao()
            val user = dao.getUserSync()
            val ledgers = dao.getAllLedgersSync()
            val vouchers = dao.getAllVouchersSync()

            val ledgersArray = JSONArray()
            ledgers.forEach { l ->
                ledgersArray.put(JSONObject().apply {
                    put("id", l.id)
                    put("name", l.name)
                    put("category", l.category.name)
                    put("openingBalance", l.openingBalance)
                })
            }

            val vouchersArray = JSONArray()
            vouchers.takeLast(100).forEach { v ->
                vouchersArray.put(JSONObject().apply {
                    put("voucherNumber", v.voucherNo)
                    put("voucherType", v.voucherType.name)
                    put("date", v.date)
                    put("totalAmount", v.totalAmount)
                    put("narration", v.narration)
                })
            }

            val rootJson = JSONObject().apply {
                put("merchantId", user?.id ?: 1)
                put("businessName", user?.businessName ?: "Local Merchant")
                put("gstin", user?.gstin ?: "")
                put("syncTimestamp", System.currentTimeMillis())
                put("ledgers", ledgersArray)
                put("recentVouchers", vouchersArray)
            }

            val requestBody = rootJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

            val request = Request.Builder()
                .url("$backendBaseUrl/api/sync/merchant-ledger")
                .post(requestBody)
                .addHeader("Content-Type", "application/json")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success("Sync successful! ${ledgers.size} ledgers and ${vouchers.size} vouchers synced.")
                } else {
                    Result.failure(IOException("Backend API error: ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Log.e("RegionalSyncEngine", "Sync dropout: ${e.message}")
            Result.failure(e)
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: RegionalBackendSyncEngine? = null

        fun getInstance(context: Context): RegionalBackendSyncEngine {
            return INSTANCE ?: synchronized(this) {
                val instance = RegionalBackendSyncEngine(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }
}
