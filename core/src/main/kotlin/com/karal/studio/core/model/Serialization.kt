package com.karal.studio.core.model

import com.karal.studio.core.json.Json
import com.karal.studio.core.json.JsonValue
import com.karal.studio.core.json.array
import com.karal.studio.core.json.boolOr
import com.karal.studio.core.json.intOr
import com.karal.studio.core.json.longOr
import com.karal.studio.core.json.number
import com.karal.studio.core.json.obj
import com.karal.studio.core.json.string
import com.karal.studio.core.json.stringList
import com.karal.studio.core.json.stringOr
import com.karal.studio.core.security.SecretPolicy

private inline fun <reified T : Enum<T>> enumOf(name: String?, default: T): T =
    name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default

private fun stringMap(value: JsonValue?): Map<String, String> =
    value?.obj().orEmpty().mapValues { (_, v) -> (v as? JsonValue.Str)?.value ?: "" }

private fun strings(value: Map<String, String>): JsonValue =
    JsonValue.Obj(value.mapValues { JsonValue.Str(it.value) })

private fun stringListValue(items: List<String>): JsonValue = Json.strings(items)

object CoreJson {
    const val BUNDLE_FORMAT = "karal-bot-studio"
    const val BUNDLE_VERSION = 1

    fun encodeWorkflow(workflow: Workflow): JsonValue = Json.obj(
        "nodes" to Json.arr(workflow.nodes.map { node ->
            Json.obj(
                "id" to Json.str(node.id),
                "type" to Json.str(node.type),
                "group" to Json.str(node.group.name),
                "params" to strings(node.params),
                "x" to Json.num(node.x.toDouble()),
                "y" to Json.num(node.y.toDouble()),
                "label" to Json.str(node.label)
            )
        }),
        "edges" to Json.arr(workflow.edges.map { edge ->
            Json.obj(
                "id" to Json.str(edge.id),
                "from" to Json.str(edge.from),
                "to" to Json.str(edge.to),
                "fromPort" to Json.str(edge.fromPort),
                "toPort" to Json.str(edge.toPort)
            )
        })
    )

    fun decodeWorkflow(value: JsonValue?): Workflow {
        val obj = value?.obj() ?: return Workflow()
        val nodes = (obj["nodes"]?.array().orEmpty()).mapNotNull { item ->
            val n = item.obj() ?: return@mapNotNull null
            val id = n["id"]?.stringOr("") ?: return@mapNotNull null
            val type = n["type"]?.stringOr("") ?: return@mapNotNull null
            WorkflowNode(
                id = id,
                type = type,
                group = enumOf(n["group"]?.string(), NodeGroup.ACTION),
                params = stringMap(n["params"]),
                x = (n["x"]?.number() ?: 0.0).toFloat(),
                y = (n["y"]?.number() ?: 0.0).toFloat(),
                label = n["label"]?.stringOr("") ?: ""
            )
        }
        val edges = (obj["edges"]?.array().orEmpty()).mapNotNull { item ->
            val e = item.obj() ?: return@mapNotNull null
            val id = e["id"]?.stringOr("") ?: return@mapNotNull null
            WorkflowEdge(
                id = id,
                from = e["from"]?.stringOr("") ?: return@mapNotNull null,
                to = e["to"]?.stringOr("") ?: return@mapNotNull null,
                fromPort = e["fromPort"]?.stringOr("out") ?: "out",
                toPort = e["toPort"]?.stringOr("in") ?: "in"
            )
        }
        return Workflow(nodes, edges)
    }

    fun encodeCommand(command: CommandDefinition): JsonValue = Json.obj(
        "id" to Json.str(command.id),
        "projectId" to Json.str(command.projectId),
        "name" to Json.str(command.name),
        "description" to Json.str(command.description),
        "source" to Json.str(command.source.name),
        "language" to Json.str(command.language.name),
        "script" to Json.str(command.script),
        "trigger" to Json.obj(
            "type" to Json.str(command.trigger.type.name),
            "options" to strings(command.trigger.options)
        ),
        "workflow" to encodeWorkflow(command.workflow),
        "permissions" to Json.obj(
            "requiredUserPermissions" to stringListValue(command.permissions.requiredUserPermissions),
            "allowedRoleIds" to stringListValue(command.permissions.allowedRoleIds),
            "allowedUserIds" to stringListValue(command.permissions.allowedUserIds),
            "requireAdministrator" to Json.bool(command.permissions.requireAdministrator),
            "guildOnly" to Json.bool(command.permissions.guildOnly),
            "dmAllowed" to Json.bool(command.permissions.dmAllowed),
            "cooldownMs" to Json.num(command.permissions.cooldownMs)
        ),
        "requiredBotPermissions" to stringListValue(command.requiredBotPermissions),
        "timeoutMs" to Json.num(command.timeoutMs),
        "enabled" to Json.bool(command.enabled),
        "version" to Json.num(command.version),
        "metadata" to strings(command.metadata),
        "updatedAt" to Json.num(command.updatedAt),
        "approved" to Json.bool(command.approved)
    )

    fun decodeCommand(value: JsonValue, projectIdFallback: String = ""): CommandDefinition {
        val o = value.obj() ?: throw IllegalArgumentException("Command must be a JSON object")
        val triggerObj = o["trigger"]?.obj()
        val permissionObj = o["permissions"]?.obj()
        val trigger = TriggerSpec(
            type = enumOf(triggerObj?.get("type")?.string(), TriggerType.SLASH_COMMAND),
            options = stringMap(triggerObj?.get("options"))
        )
        val permissions = CommandPermissions(
            requiredUserPermissions = permissionObj?.get("requiredUserPermissions").stringList(),
            allowedRoleIds = permissionObj?.get("allowedRoleIds").stringList(),
            allowedUserIds = permissionObj?.get("allowedUserIds").stringList(),
            requireAdministrator = permissionObj?.get("requireAdministrator").boolOr(false),
            guildOnly = permissionObj?.get("guildOnly").boolOr(false),
            dmAllowed = permissionObj?.get("dmAllowed").boolOr(true),
            cooldownMs = permissionObj?.get("cooldownMs").longOr(0L)
        )
        return CommandDefinition(
            id = o["id"]?.stringOr("") ?: "",
            projectId = o["projectId"]?.stringOr(projectIdFallback) ?: projectIdFallback,
            name = o["name"]?.stringOr("") ?: "",
            description = o["description"]?.stringOr("") ?: "",
            source = enumOf(o["source"]?.string(), CommandSource.SCRIPT),
            language = enumOf(o["language"]?.string(), ScriptLanguage.JAVASCRIPT),
            script = o["script"]?.stringOr("") ?: "",
            trigger = trigger,
            workflow = decodeWorkflow(o["workflow"]),
            permissions = permissions,
            requiredBotPermissions = o["requiredBotPermissions"].stringList(),
            timeoutMs = o["timeoutMs"].longOr(5_000L).coerceIn(100L, 120_000L),
            enabled = o["enabled"].boolOr(true),
            version = o["version"].intOr(1),
            metadata = stringMap(o["metadata"]),
            updatedAt = o["updatedAt"].longOr(System.currentTimeMillis()),
            approved = o["approved"].boolOr(false)
        )
    }

    fun encodeBundle(bundle: ProjectBundle): JsonValue = Json.obj(
        "format" to Json.str(bundle.format),
        "formatVersion" to Json.num(bundle.formatVersion),
        "exportedAt" to Json.num(bundle.exportedAt),
        "project" to Json.obj(
            "id" to Json.str(bundle.project.id),
            "name" to Json.str(bundle.project.name),
            "description" to Json.str(bundle.project.description),
            "botName" to Json.str(bundle.project.botName),
            "botId" to Json.str(bundle.project.botId),
            "applicationId" to Json.str(bundle.project.applicationId),
            "createdAt" to Json.num(bundle.project.createdAt),
            "modifiedAt" to Json.num(bundle.project.modifiedAt),
            "archived" to Json.bool(bundle.project.archived),
            "runtimeSettings" to strings(bundle.project.runtimeSettings),
            "databaseConfig" to strings(bundle.project.databaseConfig)
        ),
        "bots" to Json.arr(bundle.bots.map {
            Json.obj(
                "id" to Json.str(it.id),
                "projectId" to Json.str(it.projectId),
                "name" to Json.str(it.name),
                "avatarUrl" to Json.str(it.avatarUrl),
                "enabled" to Json.bool(it.enabled),
                "intents" to Json.num(it.intents),
                "prefix" to Json.str(it.prefix),
                "autoReconnect" to Json.bool(it.autoReconnect)
            )
        }),
        "commands" to Json.arr(bundle.commands.map(::encodeCommand)),
        "variables" to strings(bundle.variables),
        "settings" to strings(bundle.settings),
        "warnings" to Json.strings(bundle.warnings)
    )

    fun decodeBundle(value: JsonValue): ProjectBundle {
        val o = value.obj() ?: throw IllegalArgumentException("Backup root must be a JSON object")
        val format = o["format"]?.stringOr("") ?: ""
        if (format != BUNDLE_FORMAT) throw IllegalArgumentException("Not a KARAL BOT STUDIO backup")
        val version = o["formatVersion"]?.intOr(0) ?: 0
        if (version !in 1..BUNDLE_VERSION) throw IllegalArgumentException("Unsupported backup version: $version")
        val p = o["project"]?.obj() ?: throw IllegalArgumentException("Backup is missing the project record")
        val project = ProjectData(
            id = p["id"]?.stringOr("") ?: "",
            name = p["name"]?.stringOr("") ?: "",
            description = p["description"]?.stringOr("") ?: "",
            botName = p["botName"]?.stringOr("") ?: "",
            botId = p["botId"]?.stringOr("") ?: "",
            applicationId = p["applicationId"]?.stringOr("") ?: "",
            createdAt = p["createdAt"].longOr(System.currentTimeMillis()),
            modifiedAt = p["modifiedAt"].longOr(System.currentTimeMillis()),
            archived = p["archived"].boolOr(false),
            runtimeSettings = stringMap(p["runtimeSettings"]),
            databaseConfig = stringMap(p["databaseConfig"])
        )
        val bots = o["bots"]?.array().orEmpty().mapNotNull { item ->
            val b = item.obj() ?: return@mapNotNull null
            BotData(
                id = b["id"]?.stringOr("") ?: "",
                projectId = b["projectId"]?.stringOr(project.id) ?: project.id,
                name = b["name"]?.stringOr("") ?: "",
                avatarUrl = b["avatarUrl"]?.stringOr("") ?: "",
                enabled = b["enabled"].boolOr(false),
                intents = b["intents"].longOr(0L),
                prefix = b["prefix"]?.stringOr("!") ?: "!",
                autoReconnect = b["autoReconnect"].boolOr(true)
            )
        }
        val commands = o["commands"]?.array().orEmpty().mapNotNull { item ->
            runCatching { decodeCommand(item, project.id) }.getOrNull()
        }
        return ProjectBundle(
            format = format,
            formatVersion = version,
            exportedAt = o["exportedAt"].longOr(System.currentTimeMillis()),
            project = project,
            bots = bots,
            commands = commands,
            variables = stringMap(o["variables"]),
            settings = stringMap(o["settings"]),
            warnings = o["warnings"].stringList()
        )
    }
}

data class ProjectData(
    val id: String,
    val name: String,
    val description: String = "",
    val botName: String = "",
    val botId: String = "",
    val applicationId: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val modifiedAt: Long = System.currentTimeMillis(),
    val archived: Boolean = false,
    val runtimeSettings: Map<String, String> = emptyMap(),
    val databaseConfig: Map<String, String> = emptyMap()
)

data class BotData(
    val id: String,
    val projectId: String,
    val name: String,
    val avatarUrl: String = "",
    val enabled: Boolean = false,
    val intents: Long = 0L,
    val prefix: String = "!",
    val autoReconnect: Boolean = true
)

/**
 * Portable project format. Tokens and API keys live only in the Android Keystore-backed
 * secrets table and are never part of this bundle.
 */
data class ProjectBundle(
    val format: String = CoreJson.BUNDLE_FORMAT,
    val formatVersion: Int = CoreJson.BUNDLE_VERSION,
    val exportedAt: Long = System.currentTimeMillis(),
    val project: ProjectData,
    val bots: List<BotData> = emptyList(),
    val commands: List<CommandDefinition> = emptyList(),
    val variables: Map<String, String> = emptyMap(),
    val settings: Map<String, String> = emptyMap(),
    val warnings: List<String> = emptyList()
) {
    fun toJson(): String = Json.stringify(CoreJson.encodeBundle(this))

    /** Removes any credential-looking value before the bundle is written to disk or shared. */
    fun sanitized(): ProjectBundle {
        val found = mutableListOf<String>()
        fun clean(key: String, value: String): String {
            if (SecretPolicy.isSecretKey(key) || SecretPolicy.containsSecret(value)) {
                found += key
                return SecretPolicy.REDACTED
            }
            return SecretPolicy.redact(value)
        }
        return copy(
            project = project.copy(
                description = clean("description", project.description),
                runtimeSettings = project.runtimeSettings.mapValues { clean(it.key, it.value) },
                databaseConfig = project.databaseConfig.mapValues { clean(it.key, it.value) }
            ),
            bots = bots.map { it.copy(avatarUrl = clean("avatarUrl", it.avatarUrl)) },
            commands = commands.map { command ->
                command.copy(
                    description = clean("description", command.description),
                    script = clean("script", command.script),
                    metadata = command.metadata.mapValues { clean(it.key, it.value) },
                    trigger = command.trigger.copy(options = command.trigger.options.mapValues { clean(it.key, it.value) }),
                    workflow = command.workflow.copy(nodes = command.workflow.nodes.map { node ->
                        node.copy(params = node.params.mapValues { clean(it.key, it.value) })
                    })
                )
            },
            variables = variables.mapValues { clean(it.key, it.value) },
            settings = settings.mapValues { clean(it.key, it.value) },
            warnings = warnings + if (found.isEmpty()) emptyList() else
                listOf("Removed or redacted ${found.size} credential-looking value(s) before export.")
        )
    }

    companion object {
        fun fromJson(text: String): ProjectBundle = CoreJson.decodeBundle(Json.parse(text))
    }
}
