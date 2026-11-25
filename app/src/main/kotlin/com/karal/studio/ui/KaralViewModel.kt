package com.karal.studio.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.karal.studio.KaralApplication
import com.karal.studio.core.compiler.CommandCompiler
import com.karal.studio.core.json.Json
import com.karal.studio.core.json.obj
import com.karal.studio.core.json.stringOr
import com.karal.studio.core.model.ActionType
import com.karal.studio.core.model.CommandDefinition
import com.karal.studio.core.model.CommandSource
import com.karal.studio.core.model.NodeGroup
import com.karal.studio.core.model.ScriptLanguage
import com.karal.studio.core.model.TriggerSpec
import com.karal.studio.core.model.TriggerType
import com.karal.studio.core.model.Workflow
import com.karal.studio.core.model.WorkflowEdge
import com.karal.studio.core.model.WorkflowNode
import com.karal.studio.core.security.SecretPolicy
import com.karal.studio.core.templates.TemplateCategory
import com.karal.studio.data.repository.SettingsKeys
import com.karal.studio.runtime.BotHostingService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

class KaralViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as KaralApplication
    private val c = app.container

    val projects = c.projects.observeAll()
    val activeProjects = c.projects.observeActive()
    val recentProjects = c.projects.observeRecent(5)
    val projectCount = c.projects.observeCount()
    val bots = c.bots.observeAll()
    val commands = c.commands.observeAll()
    val templates = c.templates.observeAll()
    val logs = c.logsRepository.observeRecent(200)
    val liveLogs = c.runtimeLogs.events
    val runtime = c.runtime.snapshots
    val backups = c.backups.observeAll()
    val settings = c.settings.observeAll()
    val networkOnline = c.network.online

    fun observeProjectCommands(projectId: String) = c.commands.observe(projectId)
    fun observeProjectBots(projectId: String) = c.bots.observeByProject(projectId)
    fun observeProjectBackups(projectId: String) = c.backups.observeForProject(projectId)
    fun observeBotLogs(botId: String) = c.logsRepository.observeForBot(botId, 200)

    suspend fun setting(key: String, default: String) = c.settings.get(key, default)
    suspend fun command(id: String) = c.commands.get(id)
    suspend fun project(id: String) = c.projects.get(id)
    suspend fun bot(id: String) = c.bots.get(id)
    suspend fun templateDefinitions(): List<Pair<String, String>> = c.templates.observeAll().first().map { it.key to it.definitionJson }

    fun createProject(
        name: String, description: String, botName: String, botId: String,
        applicationId: String, prefix: String, onResult: (Result<String>) -> Unit
    ) = viewModelScope.launch {
        onResult(runCatching {
            c.projects.create(name, description, botName, botId, applicationId, prefix).id
        })
    }

    fun updateProject(
        id: String, name: String, description: String, botName: String, botId: String,
        applicationId: String, archived: Boolean, onResult: (Result<Unit>) -> Unit
    ) = viewModelScope.launch {
        onResult(runCatching {
            c.projects.updateDetails(id, name, description, botName, botId, applicationId, archived)
        })
    }

    fun duplicateProject(id: String, onResult: (Result<String>) -> Unit) = viewModelScope.launch {
        onResult(runCatching { c.projects.duplicate(id) ?: throw IllegalStateException("Project not found.") })
    }

    fun deleteProject(id: String) = viewModelScope.launch {
        c.runtime.runningBotIds().filter { botId -> c.bots.get(botId)?.projectId == id }.forEach(c.runtime::stop)
        c.projects.delete(id)
    }

    fun setArchived(id: String, archived: Boolean) = viewModelScope.launch { c.projects.setArchived(id, archived) }

    fun createBot(projectId: String, name: String, prefix: String, onResult: (Result<String>) -> Unit) =
        viewModelScope.launch {
            onResult(runCatching { c.bots.create(projectId, name, prefix).id })
        }

    fun saveToken(botId: String, token: String, onResult: (Result<Unit>) -> Unit) = viewModelScope.launch {
        onResult(runCatching {
            require(SecretPolicy.isPlausibleDiscordBotToken(token)) { "That does not look like a valid Discord bot token." }
            c.bots.saveToken(botId, token)
            c.runtimeLogs.log("INFO", "Bot token stored in Android Keystore.", botId)
        })
    }

    fun hasToken(botId: String, onResult: (Boolean) -> Unit) = viewModelScope.launch {
        onResult(runCatching { c.bots.token(botId).isNullOrBlank().not() }.getOrDefault(false))
    }

    fun setBotEnabled(botId: String, enabled: Boolean) = viewModelScope.launch {
        c.bots.setEnabled(botId, enabled)
        if (!enabled) c.runtime.stop(botId)
    }

    fun deleteBot(botId: String, onResult: (Result<Unit>) -> Unit) = viewModelScope.launch {
        onResult(runCatching {
            c.runtime.stop(botId)
            c.bots.delete(botId)
        })
    }

    fun createCommand(projectId: String, name: String, source: CommandSource, onResult: (Result<String>) -> Unit) =
        viewModelScope.launch {
            onResult(runCatching {
                val id = UUID.randomUUID().toString()
                val command = CommandDefinition(
                    id = id,
                    projectId = projectId,
                    name = name.trim().lowercase().replace(Regex("[^a-z0-9_-]"), "-"),
                    description = "A KARAL command",
                    source = source,
                    language = ScriptLanguage.JAVASCRIPT,
                    script = """
                        command({ name: "${name.trim().lowercase()}", description: "A KARAL command" }, (ctx) => {
                          ctx.reply("Hello from KARAL!");
                        });
                    """.trimIndent(),
                    trigger = TriggerSpec(TriggerType.SLASH_COMMAND, mapOf("commandName" to name.trim().lowercase())),
                    workflow = Workflow(),
                    approved = source != CommandSource.AI
                )
                c.commands.save(command, createVersion = false)
                id
            })
        }

    fun saveCommand(command: CommandDefinition, onResult: (Result<Unit>) -> Unit) = viewModelScope.launch {
        onResult(runCatching { c.commands.save(command); Unit })
    }

    fun approveCommand(id: String, onResult: (Result<Unit>) -> Unit) = viewModelScope.launch {
        onResult(runCatching {
            val entity = c.commands.get(id) ?: throw IllegalStateException("Command not found.")
            val definition = com.karal.studio.data.repository.CommandRepository.toDefinition(entity)
            c.commands.save(definition.copy(approved = true))
            Unit
        })
    }

    fun validateCommand(command: CommandDefinition): Result<String> = runCatching {
        val plan = CommandCompiler.compile(command)
        "Valid: ${plan.steps.size} step(s)${if (plan.compiledScript != null) " + script" else ""}."
    }

    fun deleteCommand(id: String) = viewModelScope.launch { c.commands.delete(id) }

    fun applyTemplate(key: String, projectId: String, onResult: (Result<String>) -> Unit) = viewModelScope.launch {
        onResult(runCatching {
            val entity = c.templates.observeAll().first().first { it.key == key }
            val parsed = Json.parse(entity.definitionJson)
            val definition = com.karal.studio.core.model.CoreJson.decodeCommand(parsed, projectId).copy(
                id = UUID.randomUUID().toString(),
                projectId = projectId,
                source = CommandSource.TEMPLATE,
                approved = true,
                updatedAt = System.currentTimeMillis()
            )
            c.commands.save(definition, createVersion = false)
            definition.id
        })
    }

    fun generateWithAi(prompt: String, projectId: String, onResult: (Result<CommandDefinition>) -> Unit) =
        viewModelScope.launch { onResult(c.ai.generateCommand(prompt, projectId)) }

    fun startBot(botId: String) {
        BotHostingService.start(getApplication(), botId)
        viewModelScope.launch { c.bots.setEnabled(botId, true) }
    }

    fun stopBot(botId: String) {
        BotHostingService.stop(getApplication(), botId)
        viewModelScope.launch { c.bots.setEnabled(botId, false) }
    }

    fun startAll() = BotHostingService.restore(getApplication())
    fun stopAll() = BotHostingService.stopAll(getApplication())

    fun exportProject(uri: Uri, projectId: String, onResult: (Result<Unit>) -> Unit) = viewModelScope.launch {
        onResult(c.backups.exportZip(uri, projectId))
    }

    fun exportProjectJson(uri: Uri, projectId: String, onResult: (Result<Unit>) -> Unit) = viewModelScope.launch {
        onResult(c.backups.exportJson(uri, projectId))
    }

    fun importProject(uri: Uri, onResult: (Result<String>) -> Unit) = viewModelScope.launch {
        onResult(c.backups.importJson(uri))
    }

    fun createBackup(projectId: String, onResult: (Result<Unit>) -> Unit) = viewModelScope.launch {
        onResult(runCatching {
            c.backups.create(projectId) ?: throw IllegalStateException("Project not found.")
            Unit
        })
    }

    fun restoreBackup(backupId: String, onResult: (Result<String>) -> Unit) = viewModelScope.launch {
        onResult(c.backups.restore(backupId))
    }

    fun deleteBackup(backupId: String, onResult: (Result<Unit>) -> Unit) = viewModelScope.launch {
        onResult(c.backups.delete(backupId))
    }

    fun verifyBackup(backupId: String, onResult: (Result<Boolean>) -> Unit) = viewModelScope.launch {
        onResult(c.backups.verify(backupId))
    }

    fun clearLogs() = viewModelScope.launch {
        c.logsRepository.clear()
        c.runtimeLogs.clear()
    }

    fun putSetting(key: String, value: String) = viewModelScope.launch {
        c.settings.put(key, value)
        if (key == SettingsKeys.BACKUP_INTERVAL_HOURS) {
            app.scheduleBackups(value.toLongOrNull() ?: 24L)
        }
    }

    fun setAiKey(key: String, onResult: (Result<Unit>) -> Unit) = viewModelScope.launch {
        onResult(runCatching {
            if (key.isBlank()) return@runCatching
            c.secrets.store(SettingsKeys.AI_KEY_SECRET_ALIAS, key.trim(), "AI_PROVIDER_KEY")
        })
    }

    fun clearAiKey(onResult: (Result<Unit>) -> Unit) = viewModelScope.launch {
        onResult(runCatching { c.secrets.delete(SettingsKeys.AI_KEY_SECRET_ALIAS) })
    }

    fun hasAiKey(onResult: (Boolean) -> Unit) = viewModelScope.launch {
        onResult(runCatching { c.secrets.read(SettingsKeys.AI_KEY_SECRET_ALIAS).isNullOrBlank().not() }.getOrDefault(false))
    }

    fun diagnostics(onResult: (String) -> Unit) = viewModelScope.launch {
        val text = buildString {
            appendLine(c.runtime.diagnostics())
            appendLine("Projects: ${c.projects.observeCount().first()}")
            appendLine("Commands: ${c.commands.observeAll().first().size}")
            appendLine("Templates: ${c.templates.observeAll().first().size}")
            appendLine("Backups: ${c.backups.observeAll().first().size}")
            appendLine("Network online: ${c.network.isCurrentlyOnline()}")
            appendLine("AI configured: ${c.ai.isConfigured()}")
        }
        onResult(text)
    }

    fun templatesByCategory(): Flow<List<com.karal.studio.data.db.TemplateEntity>> = templates

    fun categories(): List<TemplateCategory> = TemplateCategory.entries.toList()

    fun newVisualCommand(projectId: String, name: String, onResult: (Result<String>) -> Unit) =
        viewModelScope.launch {
            onResult(runCatching {
                val triggerId = UUID.randomUUID().toString()
                val actionId = UUID.randomUUID().toString()
                val command = CommandDefinition(
                    id = UUID.randomUUID().toString(),
                    projectId = projectId,
                    name = name.trim().lowercase().replace(Regex("[^a-z0-9_-]"), "-"),
                    source = CommandSource.VISUAL,
                    language = ScriptLanguage.VISUAL,
                    script = "",
                    trigger = TriggerSpec(TriggerType.SLASH_COMMAND, mapOf("commandName" to name.trim().lowercase())),
                    workflow = Workflow(
                        nodes = listOf(
                            WorkflowNode(triggerId, "SLASH_COMMAND", NodeGroup.TRIGGER, mapOf("commandName" to name.trim().lowercase()), 0f, 0f, "Slash Command"),
                            WorkflowNode(actionId, "REPLY", NodeGroup.ACTION, mapOf("message" to "Hello from KARAL!"), 260f, 0f, "Reply")
                        ),
                        edges = listOf(WorkflowEdge(UUID.randomUUID().toString(), triggerId, actionId))
                    )
                )
                c.commands.save(command, createVersion = false)
                command.id
            })
        }

    fun nodeCatalog() = com.karal.studio.core.model.NodeCatalog.all

    fun actionTypes() = ActionType.entries.toList()

    fun triggerTypes() = TriggerType.entries.toList()

    fun jsonOptions(value: String): Map<String, String> = Json.parseObject(value)
        .mapValues { (_, v) -> v.stringOr(Json.stringify(v)) }
}
