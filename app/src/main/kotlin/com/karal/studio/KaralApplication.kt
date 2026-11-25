package com.karal.studio

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.karal.studio.runtime.BackupWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class KaralApplication : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        scheduleBackups(24)
    }

    fun scheduleBackups(intervalHours: Long) {
        val hours = intervalHours.coerceIn(1L, 168L)
        val request = PeriodicWorkRequestBuilder<BackupWorker>(hours, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        runCatching {
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "karal_auto_backup", ExistingPeriodicWorkPolicy.UPDATE, request
            )
        }
    }

    fun onTerminated() {
        container.runtime.stopAll()
    }
}

/** Launches work on the IO dispatcher from the application scope. */
fun CoroutineScope.launchIO(block: suspend () -> Unit) = launch(Dispatchers.IO) { block() }
