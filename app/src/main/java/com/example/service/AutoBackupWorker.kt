package com.example.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.data.preference.UserSettingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class AutoBackupWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val context = applicationContext

            // Writes the same full-fidelity format the manual backup and the restore
            // path use. Previously this serialised a Moshi map of vouchers+ledgers only,
            // which nothing in the app could read back -- the one genuinely working
            // backup in the codebase produced files that could not restore the books.
            val file = BookBackupStore.writeBackup(context)

            val displayTimeStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ENGLISH).format(Date())
            UserSettingsDataStore(context).updateLastBackupTime(displayTimeStr)

            android.util.Log.i(TAG, "Auto-backup written: ${file.name} (${file.length()} bytes)")
            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "AutoBackupWorker"
        const val WORK_NAME = "AutoDatabaseBackupWork"

        /**
         * Maps the Settings frequency chip to a real interval. The chips persisted a
         * value that never reached this function -- picking Weekly or Monthly changed
         * the stored preference and nothing else, because the interval was hardcoded
         * to 24 hours.
         */
        private fun intervalHoursFor(frequency: String): Long = when (frequency.uppercase()) {
            "WEEKLY" -> 7 * 24L
            "MONTHLY" -> 30 * 24L
            else -> 24L   // DAILY
        }

        fun schedulePeriodicBackup(context: Context, enabled: Boolean, frequency: String = "DAILY") {
            try {
                val workManager = WorkManager.getInstance(context)
                if (enabled) {
                    val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(
                        intervalHoursFor(frequency), TimeUnit.HOURS
                    )
                        .setConstraints(
                            Constraints.Builder()
                                .setRequiresStorageNotLow(true)
                                .build()
                        )
                        .build()
                    workManager.enqueueUniquePeriodicWork(
                        WORK_NAME,
                        ExistingPeriodicWorkPolicy.UPDATE,
                        request
                    )
                } else {
                    workManager.cancelUniqueWork(WORK_NAME)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
