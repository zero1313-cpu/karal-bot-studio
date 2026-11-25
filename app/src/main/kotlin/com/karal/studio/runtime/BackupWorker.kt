package com.karal.studio.runtime

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.karal.studio.KaralApplication
import com.karal.studio.data.repository.SettingsKeys
import kotlinx.coroutines.flow.first

/** Periodic local backup worker. Backups contain sanitized JSON only — never bot tokens. */
class BackupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as KaralApplication
        val settings = app.container.settings
        val enabled = settings.get(SettingsKeys.AUTO_BACKUP, "false").toBoolean()
        if (!enabled) return Result.success()
        val projects = app.container.projects.observeAll().first()
        var failures = 0
        projects.forEach { project ->
            runCatching { app.container.backups.create(project.id) }.onFailure { failures++ }
        }
        val retention = settings.get(SettingsKeys.LOG_RETENTION_DAYS, "30").toIntOrNull() ?: 30
        runCatching { app.container.logsRepository.prune(retention) }
        return if (failures == 0) Result.success() else Result.retry()
    }
}
