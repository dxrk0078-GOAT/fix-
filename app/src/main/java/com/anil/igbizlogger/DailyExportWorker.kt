package com.anil.igbizlogger

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.Constraints
import java.util.concurrent.TimeUnit

class DailyExportWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val passphrase = SecurePrefs.getPassphrase(applicationContext) ?: return Result.success()
            val exported = EncryptedExporter.exportToday(applicationContext, passphrase) ?: return Result.success()
            AppLog.log(applicationContext, "Export created: ${exported.name}")

            if (SecurePrefs.getDriveAccount(applicationContext) != null) {
                val ok = DriveUploader.uploadTodayExport(applicationContext, exported)
                AppLog.log(applicationContext, if (ok) "Uploaded to Drive" else "Drive upload skipped/failed")
            }
            Result.success()
        } catch (e: Exception) {
            // A failed export/upload must never crash the app process — just log and retry next cycle.
            AppLog.log(applicationContext, "Export worker error: ${e.message}")
            Result.failure()
        }
    }

    companion object {
        private const val WORK_NAME = "daily_igbiz_export"

        /** Reads the configured schedule and (re)installs the periodic job to match. */
        fun schedule(context: Context) {
            val mode = SecurePrefs.getExportMode(context)
            val hours = when (mode) {
                "PERIODIC_1" -> 1L
                "PERIODIC_6" -> 6L
                "PERIODIC_24" -> 24L
                else -> null // ON_OPEN or MANUAL -> no periodic job at all
            }
            val wm = WorkManager.getInstance(context)
            if (hours == null) {
                wm.cancelUniqueWork(WORK_NAME)
                return
            }
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            // WorkManager enforces a 15-minute floor on periodic intervals; our smallest
            // option (1 hour) is comfortably above that, so no clamping needed.
            val request = PeriodicWorkRequestBuilder<DailyExportWorker>(hours, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
