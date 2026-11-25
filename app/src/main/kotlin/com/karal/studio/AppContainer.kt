package com.karal.studio

import android.content.Context
import com.karal.studio.data.db.KaralDatabase
import com.karal.studio.data.repository.BackupRepository
import com.karal.studio.data.repository.BotRepository
import com.karal.studio.data.repository.CommandRepositoryImpl
import com.karal.studio.data.repository.LogRepository
import com.karal.studio.data.repository.ProjectRepository
import com.karal.studio.data.repository.SettingsRepository
import com.karal.studio.data.repository.TemplateRepository
import com.karal.studio.runtime.AiClient
import com.karal.studio.runtime.BotRuntimeManager
import com.karal.studio.runtime.NetworkMonitor
import com.karal.studio.runtime.RuntimeLogStore
import com.karal.studio.security.SecretManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Manual dependency graph. All repositories share one Room database and one Keystore facade. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val database: KaralDatabase = KaralDatabase.get(appContext)
    val secrets = SecretManager(database.secretDao())

    val projects = ProjectRepository(database)
    val bots = BotRepository(database, secrets)
    val commands = CommandRepositoryImpl(database)
    val settings = SettingsRepository(database.settingsDao())
    val logsRepository = LogRepository(database.logDao())
    val backups = BackupRepository(database, appContext)
    val templates = TemplateRepository(database.templateDao())
    val runtimeLogs = RuntimeLogStore(logsRepository)
    val runtime = BotRuntimeManager(database, bots, commands, projects, runtimeLogs)
    val network = NetworkMonitor(appContext)
    val ai = AiClient(settings, secrets)

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                KaralDatabase.seed(database)
                runtimeLogs.log("INFO", "KARAL Bot Studio ready. Templates and defaults verified.")
            }.onFailure { error ->
                runtimeLogs.log("ERROR", "Database initialisation failed: ${error.message}")
            }
        }
    }
}
