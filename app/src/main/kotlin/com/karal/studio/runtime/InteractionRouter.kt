package com.karal.studio.runtime

import com.karal.studio.core.engine.CommandExecutor
import com.karal.studio.core.engine.DiscordAdapter
import com.karal.studio.core.engine.RateLimiter
import com.karal.studio.core.engine.RuntimeMetrics
import com.karal.studio.core.engine.VariableStore
import com.karal.studio.core.json.JsonValue
import com.karal.studio.core.json.array
import com.karal.studio.core.json.number
import com.karal.studio.core.json.obj
import com.karal.studio.core.json.stringList
import com.karal.studio.core.json.stringOr
import com.karal.studio.core.model.CommandDefinition
import com.karal.studio.core.model.ExecutionContextData
import com.karal.studio.core.model.ExecutionPlan
import com.karal.studio.core.model.TriggerType
import com.karal.studio.discord.GatewayEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ExecutableCommand(val definition: CommandDefinition, val plan: ExecutionPlan)

/**
 * Converts Gateway events into shared [ExecutionContextData] values and dispatches them through
 * the same execution plan used by the visual, script and AI editors.
 */
class InteractionRouter(
    private val commands: () -> List<ExecutableCommand>,
    private val variables: VariableStore,
    private val metrics: RuntimeMetrics,
    private val limiter: RateLimiter,
    private val logger: RuntimeLogStore,
    private val adapterFactory: (InteractionTarget) -> DiscordAdapter
) {
    suspend fun handle(event: GatewayEvent) = withContext(Dispatchers.IO) {
        runCatching {
            when (event.type) {
                "MESSAGE_CREATE" -> handleMessage(event.data)
                "INTERACTION_CREATE" -> handleInteraction(event.data)
                "MESSAGE_REACTION_ADD" -> handleReaction(event.data)
                "GUILD_MEMBER_ADD" -> handleLifecycle(event.data, TriggerType.MEMBER_JOIN)
                "GUILD_MEMBER_REMOVE" -> handleLifecycle(event.data, TriggerType.MEMBER_LEAVE)
                "VOICE_STATE_UPDATE" -> handleVoice(event.data)
                "CHANNEL_CREATE" -> handleChannel(event.data)
                "GUILD_CREATE" -> logger.log(
                    "INFO",
                    "Joined server ${event.data.obj()?.get("name")?.stringOr("unknown")}"
                )
                else -> Unit
            }
        }.onFailure { error ->
            logger.log("ERROR", "Event ${event.type} failed: ${error.message ?: "unknown error"}")
        }
    }

    private fun handleMessage(data: JsonValue) {
        val fields = data.obj() ?: return
        val content = fields["content"]?.stringOr("").orEmpty()
        if (content.startsWith("Bot ")) return // Ignore echoes of our own replies.
        val channelId = fields["channel_id"]?.stringOr("").orEmpty()
        val guildId = fields["guild_id"]?.stringOr("").orEmpty()
        val (userId, roles, userPerms, isAdmin) = identity(fields["author"]?.obj(), fields["member"]?.obj())

        val prefixCommand = commands().firstOrNull { entry ->
            entry.definition.trigger.type == TriggerType.PREFIX_COMMAND && run {
                val prefix = entry.definition.trigger.options["prefix"]?.ifBlank { "!" } ?: "!"
                val name = entry.definition.trigger.options["commandName"]
                    ?.ifBlank { entry.definition.name } ?: entry.definition.name
                content.trimStart().startsWith(prefix + name, ignoreCase = true)
            }
        }
        val entry = prefixCommand ?: commands().firstOrNull {
            it.definition.trigger.type == TriggerType.MESSAGE_CREATE
        } ?: return

        val prefix = entry.definition.trigger.options["prefix"]?.ifBlank { "!" } ?: "!"
        val name = entry.definition.trigger.options["commandName"]
            ?.ifBlank { entry.definition.name } ?: entry.definition.name
        val args = if (prefixCommand != null) {
            content.trimStart().removePrefix(prefix + name).trim()
        } else content
        val adapter = adapterFactory(InteractionTarget(channelId = channelId, guildId = guildId, userId = userId))
        execute(
            entry,
            ExecutionContextData(
                commandId = entry.plan.commandId,
                userId = userId,
                guildId = guildId,
                channelId = channelId,
                userPermissions = userPerms,
                botPermissions = setOf("VIEW_CHANNEL", "SEND_MESSAGES"),
                memberRoles = roles,
                isAdministrator = isAdmin,
                args = args.split(Regex("\\s+")).filter { it.isNotBlank() }
            ),
            adapter
        )
    }

    private fun handleInteraction(data: JsonValue) {
        val fields = data.obj() ?: return
        val interactionId = fields["id"]?.stringOr("").orEmpty()
        val token = fields["token"]?.stringOr("").orEmpty()
        val type = fields["type"]?.number()?.toInt()
            ?: fields["type"]?.stringOr("0")?.toIntOrNull() ?: 0
        val commandData = fields["data"]?.obj()
        val name = commandData?.get("name")?.stringOr("").orEmpty()
        val channelId = fields["channel_id"]?.stringOr("").orEmpty()
        val guildId = fields["guild_id"]?.stringOr("").orEmpty()
        val member = fields["member"]?.obj()
        val user = (member?.get("user")?.obj() ?: fields["user"]?.obj())
        val (userId, roles, userPerms, isAdmin) = identity(user, member)

        val triggerType = when (type) {
            2 -> TriggerType.SLASH_COMMAND
            3 -> TriggerType.BUTTON_CLICK
            5 -> TriggerType.SELECT_MENU
            4 -> TriggerType.MODAL_SUBMIT
            else -> return
        }
        val entry = commands().firstOrNull { candidate ->
            candidate.definition.trigger.type == triggerType &&
                (name.isBlank() || candidate.definition.name.equals(name, ignoreCase = true) ||
                    candidate.definition.trigger.options["commandName"]?.equals(name, ignoreCase = true) == true)
        } ?: return
        val options = commandData?.get("options")?.array().orEmpty().mapNotNull { option ->
            option.obj()?.get("value")?.let { value ->
                value.stringOr(value.number()?.toString().orEmpty())
            }
        }
        val customId = commandData?.get("custom_id")?.stringOr("").orEmpty()
        val adapter = adapterFactory(
            InteractionTarget(
                interactionId = interactionId,
                interactionToken = token,
                applicationId = fields["application_id"]?.stringOr("").orEmpty(),
                channelId = channelId,
                guildId = guildId,
                userId = userId
            )
        )
        execute(
            entry,
            ExecutionContextData(
                commandId = entry.plan.commandId,
                userId = userId,
                guildId = guildId,
                channelId = channelId,
                userPermissions = userPerms,
                botPermissions = setOf("VIEW_CHANNEL", "SEND_MESSAGES"),
                memberRoles = roles,
                isAdministrator = isAdmin,
                args = buildList {
                    if (options.isNotEmpty()) addAll(options)
                    if (customId.isNotBlank()) add(customId)
                    commandData?.get("values")?.stringList()?.let { addAll(it) }
                }
            ),
            adapter
        )
    }

    private fun handleReaction(data: JsonValue) {
        val fields = data.obj() ?: return
        val channelId = fields["channel_id"]?.stringOr("").orEmpty()
        val guildId = fields["guild_id"]?.stringOr("").orEmpty()
        val userId = fields["user_id"]?.stringOr("").orEmpty()
        val emoji = fields["emoji"]?.obj()?.get("name")?.stringOr("").orEmpty()
        val entry = commands().firstOrNull { it.definition.trigger.type == TriggerType.REACTION_ADD } ?: return
        val adapter = adapterFactory(InteractionTarget(channelId = channelId, guildId = guildId, userId = userId))
        execute(
            entry,
            ExecutionContextData(
                commandId = entry.plan.commandId,
                userId = userId,
                guildId = guildId,
                channelId = channelId,
                args = listOf(emoji, fields["message_id"]?.stringOr("").orEmpty())
            ),
            adapter
        )
    }

    private fun handleLifecycle(data: JsonValue, trigger: TriggerType) {
        val fields = data.obj() ?: return
        val user = fields["user"]?.obj()
        val userId = user?.get("id")?.stringOr("").orEmpty()
        val guildId = fields["guild_id"]?.stringOr("").orEmpty()
        val entry = commands().firstOrNull { it.definition.trigger.type == trigger } ?: return
        val adapter = adapterFactory(InteractionTarget(guildId = guildId, userId = userId))
        execute(entry, ExecutionContextData(entry.plan.commandId, userId, guildId, args = listOf(userId)), adapter)
    }

    private fun handleVoice(data: JsonValue) {
        val fields = data.obj() ?: return
        val guildId = fields["guild_id"]?.stringOr("").orEmpty()
        val userId = fields["user_id"]?.stringOr("").orEmpty()
        val channelId = fields["channel_id"]?.stringOr("").orEmpty()
        val entry = commands().firstOrNull { it.definition.trigger.type == TriggerType.VOICE_STATE_UPDATE } ?: return
        val adapter = adapterFactory(InteractionTarget(channelId = channelId, guildId = guildId, userId = userId))
        execute(
            entry,
            ExecutionContextData(entry.plan.commandId, userId, guildId, channelId, args = listOf(channelId)),
            adapter
        )
    }

    private fun handleChannel(data: JsonValue) {
        val fields = data.obj() ?: return
        val guildId = fields["guild_id"]?.stringOr("").orEmpty()
        val channelId = fields["id"]?.stringOr("").orEmpty()
        val entry = commands().firstOrNull { it.definition.trigger.type == TriggerType.CHANNEL_CREATE } ?: return
        val adapter = adapterFactory(InteractionTarget(channelId = channelId, guildId = guildId))
        execute(
            entry,
            ExecutionContextData(entry.plan.commandId, guildId = guildId, channelId = channelId, args = listOf(channelId)),
            adapter
        )
    }

    private fun execute(entry: ExecutableCommand, context: ExecutionContextData, adapter: DiscordAdapter) {
        if (!entry.definition.isExecutable) {
            logger.log("WARN", "Command ${entry.definition.name} is disabled or unapproved and was skipped.")
            return
        }
        if (entry.plan.compiledScript != null) {
            logger.log("DEBUG", "Acknowledging script command ${entry.definition.name}")
            adapter.defer()
            if (adapter is DiscordAdapterImpl) {
                adapter.configureSafeScriptRunner { script ->
                    ScriptRuntime(adapter, context).execute(script, entry.plan.timeoutMs)
                }
            }
            val result = ScriptRuntime(adapter, context).execute(entry.plan.compiledScript!!, entry.plan.timeoutMs)
            metrics.record(entry.plan.commandId, result.isSuccess, 0L)
            logger.log(
                if (result.isSuccess) "INFO" else "ERROR",
                "${entry.definition.name}: ${result.getOrElse { result.exceptionOrNull()?.message.orEmpty() }}"
            )
        } else {
            val result = CommandExecutor(adapter, variables, metrics, limiter)
                .execute(entry.plan, context)
            logger.log(
                if (result.success) "INFO" else "WARN",
                "${entry.definition.name}: ${result.message.ifBlank { result.error }} (${result.stepsExecuted} steps, ${result.durationMs}ms)"
            )
        }
    }

    private fun identity(
        user: Map<String, JsonValue>?,
        member: Map<String, JsonValue>?
    ): Quadruple<String, Set<String>, Set<String>, Boolean> {
        val userId = user?.get("id")?.stringOr("").orEmpty()
        val roles = member?.get("roles")?.stringList().orEmpty().toSet()
        val rawPermissions = (member?.get("permissions") ?: user?.get("permissions")).stringOr("0")
        val bits = rawPermissions.toLongOrNull() ?: 0L
        val permissions = permissionNames(bits)
        return Quadruple(userId, roles, permissions, bits and (1L shl 3) != 0L)
    }

    private fun permissionNames(bits: Long): Set<String> = buildSet {
        if (bits and (1L shl 3) != 0L) add("ADMINISTRATOR")
        if (bits and (1L shl 2) != 0L) add("BAN_MEMBERS")
        if (bits and (1L shl 1) != 0L) add("KICK_MEMBERS")
        if (bits and (1L shl 4) != 0L) add("MANAGE_CHANNELS")
        if (bits and (1L shl 5) != 0L) add("MANAGE_GUILD")
        if (bits and (1L shl 10) != 0L) add("VIEW_CHANNEL")
        if (bits and (1L shl 11) != 0L) add("SEND_MESSAGES")
        if (bits and (1L shl 13) != 0L) add("MANAGE_MESSAGES")
        if (bits and (1L shl 20) != 0L) add("CONNECT")
        if (bits and (1L shl 21) != 0L) add("SPEAK")
        if (bits and (1L shl 28) != 0L) add("MANAGE_ROLES")
    }

    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}
