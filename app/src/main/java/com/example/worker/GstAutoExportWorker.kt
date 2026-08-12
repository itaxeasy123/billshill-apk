package com.example.worker

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.data.gst.GstAutomationEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Android WorkManager background worker that executes the automated billing cycle end
 * GST data mapping, tax splitting, GSTR-1 & GSTR-3B aggregation, and file export routine.
 */
class GstAutoExportWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d(TAG, "GstAutoExportWorker triggered. Executing background GST export routine...")

        val result = GstAutomationEngine.executeAutomatedGstExport(applicationContext)

        if (result.isSuccess) {
            val file = result.getOrNull()
            Log.d(TAG, "GstAutoExportWorker completed successfully. Saved to: ${file?.absolutePath}")
            Result.success(
                workDataOf(
                    "export_file_path" to (file?.absolutePath ?: ""),
                    "status" to "SUCCESS"
                )
            )
        } else {
            val errorMsg = result.exceptionOrNull()?.message ?: "Unknown worker failure"
            Log.e(TAG, "GstAutoExportWorker failed: $errorMsg")
            Result.failure(
                workDataOf(
                    "error_message" to errorMsg,
                    "status" to "FAILED"
                )
            )
        }
    }

    companion object {
        private const val TAG = "GstAutoExportWorker"
        const val WORK_NAME_PERIODIC = "GstAutoExportPeriodicWork"
        const val WORK_NAME_ONE_TIME = "GstAutoExportImmediateWork"

        /**
         * Schedules a recurring periodic background job at billing cycle intervals (e.g. daily/monthly).
         */
        fun schedulePeriodicExport(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()

            val periodicRequest = PeriodicWorkRequestBuilder<GstAutoExportWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME_PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                periodicRequest
            )
            Log.d(TAG, "Scheduled periodic GstAutoExportWorker (24 hours).")
        }

        /**
         * Triggers an immediate one-time background worker execution.
         */
        fun triggerImmediateExport(context: Context) {
            val immediateRequest = OneTimeWorkRequestBuilder<GstAutoExportWorker>()
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_ONE_TIME,
                ExistingWorkPolicy.REPLACE,
                immediateRequest
            )
            Log.d(TAG, "Triggered immediate GstAutoExportWorker.")
        }
    }
}
