package com.example.utils

import android.content.Context
import android.util.Log
import com.example.data.db.AppDatabase
import com.example.data.model.MonthlyArchive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

object TelemetryMaintenanceEngine {

    private const val TAG = "TelemetryMaintenance"
    private const val DEFAULT_RETENTION_DAYS = 30

    /**
     * Executes the background maintenance task for storage optimization:
     * 1. Identifies logs exceeding the specified retention window (default 30 days).
     * 2. Groups logs by 'yyyy-MM' string pattern key.
     * 3. String-concatenates text payloads and commits them safely into the MonthlyArchive table.
     * 4. Subsequently deletes archived raw logs from the crash_logs table.
     *
     * @param context Application context
     * @param retentionDays Lifecycle window in days (default 30)
     * @return Result containing total logs archived and deleted
     */
    suspend fun runMaintenance(
        context: Context,
        retentionDays: Int = DEFAULT_RETENTION_DAYS
    ): Result<MaintenanceResult> = withContext(Dispatchers.IO) {
        try {
            val db = AppDatabase.getDatabase(context)
            val telemetryDao = db.telemetryDao()

            val retentionMs = retentionDays.toLong() * 24 * 60 * 60 * 1000L
            val cutoffTimestamp = System.currentTimeMillis() - retentionMs

            // 1. Query individual logs exceeding the 30-day lifecycle window
            val oldLogs = telemetryDao.getLogsOlderThan(cutoffTimestamp)
            if (oldLogs.isEmpty()) {
                Log.d(TAG, "No crash logs exceeding $retentionDays days found. Database is optimal.")
                return@withContext Result.success(MaintenanceResult(archivedCount = 0, deletedCount = 0))
            }

            Log.d(TAG, "Found ${oldLogs.size} logs older than $retentionDays days. Initiating archival...")

            // 2. Group logs by 'yyyy-MM' string index pattern
            val dateFormat = SimpleDateFormat("yyyy-MM", Locale.US)
            val groupedByMonth = oldLogs.groupBy { log ->
                dateFormat.format(Date(log.timestamp))
            }

            var archivesUpdated = 0

            // 3. String-concatenate text payloads and commit to MonthlyArchive table
            groupedByMonth.forEach { (monthYearKey, logsInMonth) ->
                val existingArchive = telemetryDao.getMonthlyArchive(monthYearKey)

                val concatenatedPayload = buildString {
                    if (existingArchive != null && existingArchive.archivedText.isNotBlank()) {
                        append(existingArchive.archivedText)
                        append("\n\n")
                    }
                    append("=== ARCHIVED BULK LOGS FOR MONTH: $monthYearKey (Count: ${logsInMonth.size}) ===\n")
                    logsInMonth.forEachIndexed { index, crashLog ->
                        append("[Log #${crashLog.id} | Timestamp: ${crashLog.timestamp}]\n")
                        append(crashLog.logContent.trim())
                        if (index < logsInMonth.size - 1) {
                            append("\n--- LOG SEPARATOR ---\n")
                        }
                    }
                }

                val monthlyArchive = MonthlyArchive(
                    monthYearKey = monthYearKey,
                    archivedText = concatenatedPayload,
                    archivedAt = System.currentTimeMillis()
                )

                telemetryDao.insertOrUpdateMonthlyArchive(monthlyArchive)
                archivesUpdated++
            }

            // 4. Subsequently execute clear operation on the raw transaction table
            val deletedCount = telemetryDao.deleteLogsOlderThan(cutoffTimestamp)

            Log.d(
                TAG,
                "Maintenance complete: $deletedCount raw logs purged across $archivesUpdated monthly archive bucket(s)."
            )

            Result.success(
                MaintenanceResult(
                    archivedCount = oldLogs.size,
                    deletedCount = deletedCount,
                    monthBucketsProcessed = archivesUpdated
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Telemetry maintenance error: ${e.message}", e)
            Result.failure(e)
        }
    }

    data class MaintenanceResult(
        val archivedCount: Int,
        val deletedCount: Int,
        val monthBucketsProcessed: Int = 0
    )
}
