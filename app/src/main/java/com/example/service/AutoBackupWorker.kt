package com.example.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.data.db.AppDatabase
import com.example.data.preference.UserSettingsDataStore
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
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
            val db = AppDatabase.getDatabase(context)
            val dao = db.accountingDao()

            val user = dao.getUserSync()
            val vouchers = dao.getAllVouchersSync()
            val ledgers = dao.getAllLedgersSync()

            val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

            val backupMap = mapOf(
                "timestamp" to System.currentTimeMillis(),
                "businessName" to (user?.businessName ?: "My Business"),
                "voucherCount" to vouchers.size,
                "ledgerCount" to ledgers.size,
                "vouchers" to vouchers,
                "ledgers" to ledgers
            )

            val jsonAdapter = moshi.adapter(Map::class.java)
            val jsonString = jsonAdapter.toJson(backupMap)

            val backupDir = File(context.filesDir, "backups").apply { if (!exists()) mkdirs() }
            val timeStampStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ENGLISH).format(Date())
            val backupFile = File(backupDir, "AutoBackup_$timeStampStr.json")
            val latestBackupFile = File(backupDir, "auto_backup_latest.json")

            backupFile.writeText(jsonString)
            latestBackupFile.writeText(jsonString)

            val displayTimeStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ENGLISH).format(Date())
            UserSettingsDataStore(context).updateLastBackupTime(displayTimeStr)

            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure()
        }
    }

    companion object {
        const val WORK_NAME = "AutoDatabaseBackupWork"

        fun schedulePeriodicBackup(context: Context, enabled: Boolean) {
            try {
                val workManager = WorkManager.getInstance(context)
                if (enabled) {
                    val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(24, TimeUnit.HOURS)
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
