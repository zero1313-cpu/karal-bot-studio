package com.karal.studio.runtime

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
/** WorkManager entry point: restores auto-start bots after boot without doing network work here. */
class BootRestoreWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = runCatching {
        BotHostingService.restore(applicationContext)
        Result.success()
    }.getOrDefault(Result.retry())
}
