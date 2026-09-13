package com.anil.igbizlogger

import android.content.Context
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.File
import java.util.concurrent.TimeUnit

class DeleteLocalFileWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {
    override fun doWork(): Result {
        val path = inputData.getString(KEY_PATH) ?: return Result.success()
        File(path).delete()
        AppLog.log(applicationContext, "Auto-deleted local export after configured delay")
        return Result.success()
    }

    companion object {
        private const val KEY_PATH = "path"
        fun scheduleDelete(context: Context, file: File, delayHours: Int) {
            val request = OneTimeWorkRequestBuilder<DeleteLocalFileWorker>()
                .setInitialDelay(delayHours.toLong(), TimeUnit.HOURS)
                .setInputData(Data.Builder().putString(KEY_PATH, file.absolutePath).build())
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
