package com.karal.studio.data.repository

import android.content.Context
import android.net.Uri
import com.karal.studio.core.json.Json
import com.karal.studio.core.json.string
import com.karal.studio.core.model.BotData
import com.karal.studio.core.model.CommandDefinition
import com.karal.studio.core.model.CoreJson
import com.karal.studio.core.model.ProjectBundle
import com.karal.studio.core.model.ProjectData
import com.karal.studio.core.model.Workflow
import com.karal.studio.core.security.SecretPolicy
import com.karal.studio.data.db.BackupDao
import com.karal.studio.data.db.BackupEntity
import com.karal.studio.data.db.BotDao
import com.karal.studio.data.db.BotEntity
import com.karal.studio.data.db.CommandDao
import com.karal.studio.data.db.CommandEntity
import com.karal.studio.data.db.CommandVersionEntity
import com.karal.studio.data.db.KaralDatabase
import com.karal.studio.data.db.LogDao
import com.karal.studio.data.db.ProjectDao
import com.karal.studio.data.db.ProjectEntity
import com.karal.studio.data.db.RuntimeLogEntity
import com.karal.studio.data.db.SettingEntity
import com.karal.studio.data.db.SettingsDao
import com.karal.studio.data.db.TemplateDao
import com.karal.studio.data.db.VariableDao
import com.karal.studio.data.db.VariableEntity
import com.karal.studio.data.db.WorkflowDao
import com.karal.studio.data.db.WorkflowEdgeEntity
import com.karal.studio.data.db.WorkflowEntity
import com.karal.studio.data.db.WorkflowNodeEntity
import com.karal.studio.security.SecretManager
import kotlinx.coroutines.flow.Flow
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private fun newId() = UUID.randomUUID().toString()

class ProjectRepository(private val db: KaralDatabase) {
    private val projects = db.projectDao()
    private val bots = db.botDao()
    private val commands = db.commandDao()
    private val variables = db.variableDao()

    fun observeActive() = projects.observeActive()
    fun observeAll() = projects.observeAll()
    fun observeRecent(limit: Int = 5) = projects.observeRecent(limit)
    fun observeCount(): Flow<Int> = projects.observeCount()
    suspend fun get(id: String) = projects.get(id)

    suspend fun create(
        name: String,
        description: String = "",
        botName: String = "",
        botId: String = "",
        applicationId: String = "",
        prefix: String = "!"
    ): ProjectEntity {
        require(name.isNotBlank()) { "Project name is required." }
        val now = System.currentTimeMillis()
        val project = ProjectEntity(newId(), name.trim(), description.trim(), botName.trim(), botId.trim(), applicationId.trim(), now, now)
        val bot = BotEntity(newId(), project.id, botName.ifBlank { name }, tokenAlias = "bot_token_${newId()}", prefix = prefix)
        projects.upsert(project)
        bots.upsert(bot)
        return project
    }

    suspend fun updateDetails(
        id: String, name: String, description: String, botName: String,
        botId: String, applicationId: String, archived: Boolean
    ) {
        require(name.isNotBlank()) { "Project name is required." }
        projects.updateDetails(id, name.trim(), description.trim(), botName.trim(), botId.trim(), applicationId.trim(), archived)
    }

    suspend fun duplicate(id: String): String? {
        val source = projects.get(id) ?: return null
        val copy = source.copy(id = newId(), name = "${source.name} (copy)", createdAt = System.currentTimeMillis(), modifiedAt = System.currentTimeMillis())
        projects.upsert(copy)
        db.botDao().getAllForProject(id).forEach { bot ->
            bots.upsert(bot.copy(id = newId(), projectId = copy.id, enabled = false, tokenAlias = "bot_token_${newId()}"))
        }
        db.commandDao().getAllForProject(id).forEach { command ->
            val newCommandId = newId()
            commands.upsert(command.copy(id = newCommandId, projectId = copy.id, updatedAt = System.currentTimeMillis()))
            db.workflowDao().getForCommand(command.id)?.let { workflow ->
                db.workflowDao().upsert(
                    workflow.copy(id = newId(), projectId = copy.id, commandId = newCommandId)
                )
            }
        }
        db.variableDao().getAllForProject(id).forEach { variable ->
            variables.upsert(variable.copy(projectId = copy.id))
        }
        return copy.id
    }

    suspend fun setArchived(id: String, archived: Boolean) {
        val project = projects.get(id) ?: return
        projects.upsert(project.copy(archived = archived, modifiedAt = System.currentTimeMillis()))
    }

    suspend fun delete(id: String) {
        projects.delete(id) // foreign keys cascade to bots, commands, triggers and workflows
        db.variableDao().deleteForProject(id)
        db.logDao().deleteForProject(id)
    }

    suspend fun exportBundle(id: String): ProjectBundle? {
        val project = projects.get(id) ?: return null
        val botList = db.botDao().getAllForProject(id).map {
            BotData(it.id, it.projectId, it.name, it.avatarUrl, it.enabled, it.intents, it.prefix, it.autoReconnect)
        }
        val commandList = db.commandDao().getAllForProject(id).mapNotNull { runCatching { CommandRepository.toDefinition(it) }.getOrNull() }
        val vars = db.variableDao().getAllForProject(id).associate { it.key to it.value }
        return ProjectBundle(
            project = ProjectData(
                id = project.id, name = project.name, description = project.description,
                botName = project.botName, botId = project.botId, applicationId = project.applicationId,
                createdAt = project.createdAt, modifiedAt = project.modifiedAt, archived = project.archived,
                runtimeSettings = Json.parseObject(project.runtimeSettingsJson).mapValues { (_, v) -> v.string().orEmpty() },
                databaseConfig = Json.parseObject(project.databaseConfigJson).mapValues { (_, v) -> v.string().orEmpty() }
            ),
            bots = botList,
            commands = commandList,
            variables = vars
        ).sanitized()
    }

    suspend fun importBundle(text: String): Result<String> = runCatching {
        val bundle = ProjectBundle.fromJson(text).sanitized()
        val newProjectId = newId()
        val oldProjectId = bundle.project.id
        projects.upsert(
            ProjectEntity(
                id = newProjectId,
                name = bundle.project.name + " (imported)",
                description = bundle.project.description,
                botName = bundle.project.botName,
                botId = bundle.project.botId,
                applicationId = bundle.project.applicationId,
                createdAt = System.currentTimeMillis(),
                modifiedAt = System.currentTimeMillis(),
                archived = bundle.project.archived
            )
        )
        bundle.bots.forEach { bot ->
            bots.upsert(
                BotEntity(
                    id = newId(), projectId = newProjectId, name = bot.name, avatarUrl = bot.avatarUrl,
                    enabled = false, intents = bot.intents, prefix = bot.prefix, autoReconnect = bot.autoReconnect
                )
            )
        }
        bundle.commands.forEach { command ->
            val id = newId()
            // Imported code is never executable until the user reviews and approves it.
            commands.upsert(CommandRepository.toEntity(command.copy(id = id, projectId = newProjectId, approved = false)))
            if (command.workflow.nodes.isNotEmpty()) {
                db.workflowDao().upsert(
                    WorkflowEntity(newId(), newProjectId, id,
                        Json.stringify(CoreJson.encodeWorkflow(command.workflow)), "[]")
                )
            }
        }
        bundle.variables.forEach { (key, value) ->
            variables.upsert(VariableEntity(newProjectId, key, value))
        }
        newProjectId
    }
}

object CommandRepository {
    fun toEntity(definition: CommandDefinition): CommandEntity = CommandEntity(
        id = definition.id,
        projectId = definition.projectId,
        name = definition.name,
        description = definition.description,
        source = definition.source.name,
        language = definition.language.name,
        script = definition.script,
        triggerJson = Json.stringify(
            Json.obj(
                "type" to Json.str(definition.trigger.type.name),
                "options" to Json.obj(definition.trigger.options.mapValues { Json.str(it.value) })
            )
        ),
        permissionsJson = Json.stringify(
            Json.obj(
                "requiredUserPermissions" to Json.strings(definition.permissions.requiredUserPermissions),
                "allowedRoleIds" to Json.strings(definition.permissions.allowedRoleIds),
                "allowedUserIds" to Json.strings(definition.permissions.allowedUserIds),
                "requireAdministrator" to Json.bool(definition.permissions.requireAdministrator),
                "guildOnly" to Json.bool(definition.permissions.guildOnly),
                "dmAllowed" to Json.bool(definition.permissions.dmAllowed),
                "cooldownMs" to Json.num(definition.permissions.cooldownMs.toDouble())
            )
        ),
        requiredBotPermissionsJson = Json.stringify(Json.strings(definition.requiredBotPermissions)),
        timeoutMs = definition.timeoutMs,
        enabled = definition.enabled,
        version = definition.version,
        metadataJson = Json.stringify(Json.obj(definition.metadata.mapValues { Json.str(it.value) })),
        updatedAt = definition.updatedAt,
        approved = definition.approved
    )

    fun toDefinition(entity: CommandEntity): CommandDefinition = CoreJson.decodeCommand(
        Json.parse(
            Json.stringify(
                Json.obj(
                    "id" to Json.str(entity.id),
                    "projectId" to Json.str(entity.projectId),
                    "name" to Json.str(entity.name),
                    "description" to Json.str(entity.description),
                    "source" to Json.str(entity.source),
                    "language" to Json.str(entity.language),
                    "script" to Json.str(entity.script),
                    "trigger" to Json.parse(entity.triggerJson),
                    "permissions" to Json.parse(entity.permissionsJson),
                    "requiredBotPermissions" to Json.parse(entity.requiredBotPermissionsJson),
                    "timeoutMs" to Json.num(entity.timeoutMs.toDouble()),
                    "enabled" to Json.bool(entity.enabled),
                    "version" to Json.num(entity.version.toDouble()),
                    "metadata" to Json.parse(entity.metadataJson),
                    "updatedAt" to Json.num(entity.updatedAt.toDouble()),
                    "approved" to Json.bool(entity.approved)
                )
            )
        )
    )
}

class CommandRepositoryImpl(private val db: KaralDatabase) {
    private val dao = db.commandDao()
    private val workflowDao = db.workflowDao()

    fun observe(projectId: String) = dao.observeByProject(projectId)
    fun observeAll() = dao.observeAll()
    suspend fun get(id: String) = dao.get(id)

    suspend fun save(definition: CommandDefinition, createVersion: Boolean = true): CommandEntity {
        val entity = CommandRepository.toEntity(definition.copy(updatedAt = System.currentTimeMillis()))
        val previous = dao.get(definition.id)
        dao.upsert(entity)
        if (createVersion && previous != null) {
            dao.insertVersion(
                CommandVersionEntity(
                    id = newId(), commandId = entity.id, projectId = entity.projectId,
                    version = previous.version, definitionJson = Json.stringify(Json.obj("script" to Json.str(previous.script)))
                )
            )
        }
        if (definition.workflow.nodes.isNotEmpty()) {
            val workflowId = workflowDao.getForCommand(definition.id)?.id ?: newId()
            val workflow = WorkflowEntity(
                workflowId, definition.projectId, definition.id,
                Json.stringify(CoreJson.encodeWorkflow(definition.workflow)),
                Json.stringify(Json.obj())
            )
            workflowDao.upsert(workflow)
            workflowDao.clearNodes(workflowId)
            workflowDao.clearEdges(workflowId)
            workflowDao.upsertNodes(definition.workflow.nodes.map {
                WorkflowNodeEntity(it.id, workflowId, it.type, it.group.name,
                    Json.stringify(Json.obj(it.params.mapValues { p -> Json.str(p.value) })), it.x, it.y, it.label)
            })
            workflowDao.upsertEdges(definition.workflow.edges.map {
                WorkflowEdgeEntity(it.id, workflowId, it.from, it.to, it.fromPort, it.toPort)
            })
        }
        return entity
    }

    suspend fun delete(id: String) = dao.delete(id)

    suspend fun workflow(commandId: String): Workflow {
        val entity = workflowDao.getForCommand(commandId) ?: return Workflow()
        return CoreJson.decodeWorkflow(Json.parse(entity.nodesJson).let { nodes ->
            Json.obj("nodes" to nodes, "edges" to Json.parse(entity.edgesJson))
        })
    }
}

class BotRepository(private val db: KaralDatabase, private val secrets: SecretManager) {
    private val dao = db.botDao()
    fun observeAll() = dao.observeAll()
    fun observeByProject(projectId: String) = dao.observeByProject(projectId)
    suspend fun get(id: String) = dao.get(id)

    suspend fun create(projectId: String, name: String, prefix: String = "!", intents: Long = 513L): BotEntity {
        val bot = BotEntity(newId(), projectId, name, tokenAlias = "bot_token_${newId()}", prefix = prefix, intents = intents)
        dao.upsert(bot)
        return bot
    }

    private suspend fun ensureTokenAlias(bot: BotEntity): BotEntity {
        if (bot.tokenAlias.isNotBlank()) return bot
        val fixed = bot.copy(tokenAlias = "bot_token_${newId()}")
        dao.upsert(fixed)
        return fixed
    }

    suspend fun saveToken(botId: String, token: String) {
        val raw = token.trim()
        require(raw.isNotBlank()) { "Bot token is required." }
        require(SecretPolicy.isPlausibleDiscordBotToken(raw)) { "That does not look like a valid Discord bot token." }
        val bot = dao.get(botId) ?: throw IllegalStateException("Bot configuration not found.")
        val withAlias = ensureTokenAlias(bot)
        secrets.store(withAlias.tokenAlias, raw, "DISCORD_BOT_TOKEN")
    }

    suspend fun token(botId: String): String? {
        val bot = dao.get(botId) ?: return null
        if (bot.tokenAlias.isBlank()) return null
        return secrets.read(bot.tokenAlias)
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = dao.setEnabled(id, enabled)
    suspend fun delete(id: String) {
        val bot = dao.get(id)
        dao.delete(id)
        if (bot?.tokenAlias?.isNotBlank() == true) secrets.delete(bot.tokenAlias)
    }
}

class SettingsRepository(private val dao: SettingsDao) {
    fun observeAll() = dao.observeAll()
    suspend fun get(key: String, default: String): String = dao.get(key) ?: default
    suspend fun put(key: String, value: String) = dao.put(SettingEntity(key, value))
}

class LogRepository(private val dao: LogDao) {
    fun observeRecent(limit: Int = 200) = dao.observeRecent(limit)
    fun observeForBot(botId: String, limit: Int = 200) = dao.observeForBot(botId, limit)

    suspend fun log(level: String, message: String, botId: String = "", projectId: String = "", sessionId: String = "") {
        dao.insert(RuntimeLogEntity(level = level, message = SecretPolicy.redact(message), botId = botId, projectId = projectId, sessionId = sessionId))
    }

    suspend fun clear() = dao.clear()
    suspend fun prune(days: Int) = dao.deleteOlderThan(System.currentTimeMillis() - days * 86_400_000L)
}

class BackupRepository(private val db: KaralDatabase, private val context: Context) {
    private val backups: BackupDao = db.backupDao()
    fun observeAll() = backups.observeAll()
    fun observeForProject(projectId: String) = backups.observeForProject(projectId)

    suspend fun create(projectId: String): BackupEntity? {
        val bundle = ProjectRepository(db).exportBundle(projectId) ?: return null
        val text = bundle.toJson()
        val bytes = text.toByteArray(Charsets.UTF_8)
        val dir = java.io.File(context.filesDir, "backups").apply { mkdirs() }
        val file = java.io.File(dir, "$projectId-${System.currentTimeMillis()}.json")
        file.writeBytes(bytes)
        val checksum = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val entity = BackupEntity(
            id = newId(), projectId = projectId, path = file.absolutePath,
            createdAt = System.currentTimeMillis(), sizeBytes = bytes.size.toLong(),
            encrypted = false, checksum = checksum, validated = true
        )
        backups.upsert(entity)
        return entity
    }

    suspend fun restore(backupId: String): Result<String> {
        val backup = backups.get(backupId)
            ?: return Result.failure(IllegalArgumentException("Backup not found."))
        val file = java.io.File(backup.path)
        if (!file.exists()) return Result.failure(IllegalArgumentException("Backup file is missing."))
        val bytes = file.readBytes()
        val checksum = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (checksum != backup.checksum) return Result.failure(IllegalStateException("Backup checksum mismatch."))
        return ProjectRepository(db).importBundle(String(bytes, Charsets.UTF_8))
    }

    suspend fun exportZip(uri: Uri, projectId: String): Result<Unit> = runCatching {
        val bundle = ProjectRepository(db).exportBundle(projectId)
            ?: throw IllegalArgumentException("Project not found.")
        context.contentResolver.openOutputStream(uri)?.use { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write("""{"format":"karal-bot-studio","version":1}""".toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("project.json"))
                zip.write(bundle.toJson().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        } ?: throw IllegalStateException("Cannot open export destination.")
    }

    suspend fun exportJson(uri: Uri, projectId: String): Result<Unit> = runCatching {
        val bundle = ProjectRepository(db).exportBundle(projectId)
            ?: throw IllegalArgumentException("Project not found.")
        context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(bundle.toJson().toByteArray(Charsets.UTF_8))
        } ?: throw IllegalStateException("Cannot open export destination.")
    }

    suspend fun importJson(uri: Uri): Result<String> = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("Cannot read import file.")
        val text = if (bytes.size >= 2 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) {
            java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
                var entry = zip.nextEntry
                var projectJson: String? = null
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.endsWith("project.json")) {
                        projectJson = zip.readBytes().toString(Charsets.UTF_8)
                        break
                    }
                    entry = zip.nextEntry
                }
                projectJson ?: throw IllegalStateException("project.json not found in ZIP archive.")
            }
        } else {
            bytes.toString(Charsets.UTF_8)
        }
        ProjectRepository(db).importBundle(text).getOrThrow()
    }

    suspend fun delete(backupId: String): Result<Unit> = runCatching {
        val backup = backups.get(backupId) ?: throw IllegalArgumentException("Backup not found.")
        runCatching { java.io.File(backup.path).delete() }
        backups.delete(backupId)
    }

    suspend fun verify(backupId: String): Result<Boolean> = runCatching {
        val backup = backups.get(backupId) ?: throw IllegalArgumentException("Backup not found.")
        val file = java.io.File(backup.path)
        if (!file.exists()) throw IllegalStateException("Backup file is missing.")
        val bytes = file.readBytes()
        val checksum = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val ok = checksum == backup.checksum
        backups.upsert(backup.copy(validated = ok))
        ok
    }
}

class TemplateRepository(private val dao: TemplateDao) {
    fun observeAll() = dao.observeAll()
}
