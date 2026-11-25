package com.karal.studio.runtime

import com.karal.studio.core.engine.DiscordAdapter
import com.karal.studio.core.engine.VariableStore
import com.karal.studio.core.json.Json
import com.karal.studio.core.json.string
import com.karal.studio.core.json.stringList
import com.karal.studio.core.json.stringOr
import com.karal.studio.core.model.ActionType
import com.karal.studio.core.model.ExecutionContextData
import com.karal.studio.core.security.SecretPolicy
import com.karal.studio.discord.DiscordRest
import java.net.URL

/** Immutable per-interaction routing information supplied by the gateway event parser. */
data class InteractionTarget(
    val interactionId: String? = null,
    val interactionToken: String? = null,
    val applicationId: String = "",
    val channelId: String = "",
    val guildId: String = "",
    val userId: String = "",
    val deferred: Boolean = false
)

/**
 * The only Discord-facing implementation of [DiscordAdapter]. Scripts and visual plans share it,
 * and it holds no token: the token lives in [DiscordRest]'s Keystore-backed provider.
 */
class DiscordAdapterImpl(
    private val rest: DiscordRest,
    private val target: InteractionTarget,
    private val variables: VariableStore,
    private val logger: RuntimeLogStore,
    safeScriptRunner: (String) -> Result<String> = { Result.success("") }
) : DiscordAdapter {
    var safeScriptRunner: (String) -> Result<String> = safeScriptRunner
        private set

    fun configureSafeScriptRunner(runner: (String) -> Result<String>) {
        safeScriptRunner = runner
    }

    private var deferred = target.deferred

    override fun defer(): Result<Unit> {
        val id = target.interactionId
        val token = target.interactionToken
        if (id.isNullOrBlank() || token.isNullOrBlank() || deferred) return Result.success(Unit)
        return rest.interactionResponse(id, token, 5, Json.obj()).map { deferred = true; Unit }
            .onFailure { report("defer", it) }
    }

    override fun reply(message: String, ephemeral: Boolean): Result<Unit> {
        val id = target.interactionId
        val token = target.interactionToken
        if (id.isNullOrBlank() || token.isNullOrBlank()) {
            return if (target.channelId.isBlank()) {
                logger.log("WARN", "Reply requested without a channel or interaction target.")
                Result.failure(IllegalStateException("No reply target available."))
            } else rest.sendMessage(target.channelId, message).map { Unit }
        }
        if (ephemeral && deferred) {
            logger.log("WARN", "Ephemeral reply requested after deferred response; sent publicly instead.")
        }
        val result = if (deferred) {
            rest.editInteractionResponse(target.applicationId, token, message).map { Unit }
        } else {
            rest.interactionResponse(
                id, token, 4,
                Json.obj(
                    "content" to Json.str(SecretPolicy.redact(message).take(2000)),
                    "flags" to Json.num(if (ephemeral) 64 else 0)
                )
            ).map { Unit }
        }
        return result.onFailure { report("reply", it) }
    }

    override fun sendMessage(channelId: String, message: String): Result<Unit> {
        val channel = channelId.ifBlank { target.channelId }
        if (channel.isBlank()) return Result.failure(IllegalArgumentException("Channel id is missing."))
        return rest.sendMessage(channel, message).map { Unit }.onFailure { report("sendMessage", it) }
    }

    override fun sendEmbed(channelId: String, title: String, description: String): Result<Unit> {
        val channel = channelId.ifBlank { target.channelId }
        if (channel.isBlank()) return Result.failure(IllegalArgumentException("Channel id is missing."))
        return rest.sendEmbed(channel, title, description).map { Unit }.onFailure { report("sendEmbed", it) }
    }

    override fun editMessage(channelId: String, messageId: String, message: String): Result<Unit> {
        val channel = channelId.ifBlank { target.channelId }
        if (channel.isBlank() || messageId.isBlank()) {
            return Result.failure(IllegalArgumentException("Channel and message ids are required."))
        }
        return rest.editMessage(channel, messageId, message).map { Unit }.onFailure { report("editMessage", it) }
    }

    override fun deleteMessage(channelId: String, messageId: String): Result<Unit> {
        val channel = channelId.ifBlank { target.channelId }
        if (channel.isBlank() || messageId.isBlank()) {
            return Result.failure(IllegalArgumentException("Channel and message ids are required."))
        }
        return rest.deleteMessage(channel, messageId).map { Unit }.onFailure { report("deleteMessage", it) }
    }

    override fun moderate(
        action: ActionType,
        params: Map<String, String>,
        context: ExecutionContextData
    ): Result<Unit> {
        val member = params["memberId"].orEmpty().ifBlank { params["userId"].orEmpty() }
            .ifBlank { context.mentionedUserId.ifBlank { context.userId } }
        val reason = params["reason"].orEmpty().take(512)
        val channel = params["channelId"].orEmpty().ifBlank { context.channelId }
        return runCatching {
            when (action) {
                ActionType.BAN_MEMBER -> rest.banMember(context.guildId, member, reason).getOrThrow()
                ActionType.KICK_MEMBER -> rest.kickMember(context.guildId, member, reason).getOrThrow()
                ActionType.TIMEOUT_MEMBER -> {
                    val seconds = params["durationSeconds"]?.toLongOrNull()?.coerceIn(1L, 2_419_200L)
                        ?: throw IllegalArgumentException("Timeout duration must be 1-2419200 seconds.")
                    rest.timeoutMember(context.guildId, member, seconds, reason).getOrThrow()
                }
                ActionType.ADD_ROLE -> rest.addRole(context.guildId, member, params["roleId"].orEmpty()).getOrThrow()
                ActionType.REMOVE_ROLE -> rest.removeRole(context.guildId, member, params["roleId"].orEmpty()).getOrThrow()
                ActionType.CREATE_CHANNEL -> rest.createChannel(
                    context.guildId, params["channelName"].orEmpty().ifBlank { "karal-channel" }
                ).getOrThrow()
                ActionType.DELETE_CHANNEL -> {
                    val id = params["channelId"].orEmpty().ifBlank { channel }
                    require(id.isNotBlank()) { "Channel id is required to delete a channel." }
                    rest.deleteChannel(id).getOrThrow()
                }
                ActionType.CREATE_THREAD -> rest.createThread(
                    channel, params["threadName"].orEmpty().ifBlank { "karal-thread" }
                ).getOrThrow()
                ActionType.CREATE_TICKET -> {
                    val name = params["channelName"].orEmpty().ifBlank { "ticket-${member.take(4)}" }
                    rest.createChannel(context.guildId, name).getOrThrow()
                }
                ActionType.SEND_DM -> {
                    val dm = rest.createDm(member).getOrThrow()
                    rest.sendMessage(dm, params["message"].orEmpty()).getOrThrow()
                }
                ActionType.DELETE_MESSAGE -> {
                    val messageId = params["messageId"].orEmpty()
                    if (messageId.startsWith("bulk:")) {
                        val count = messageId.removePrefix("bulk:").toIntOrNull()?.coerceIn(1, 100) ?: 1
                        val ids = rest.messages(channel, count.coerceAtMost(100)).getOrThrow()
                            .stringList().take(count)
                        if (ids.isEmpty()) "" else rest.bulkDelete(channel, ids).getOrThrow()
                    } else {
                        rest.deleteMessage(channel, messageId).getOrThrow()
                    }
                }
                ActionType.HTTP_REQUEST -> httpRequest(
                    params["url"].orEmpty(), params["method"] ?: "GET", params["body"].orEmpty()
                ).getOrThrow()
                ActionType.SET_VARIABLE -> {
                    val name = params["variable"].orEmpty()
                    val value = params["value"].orEmpty()
                    require(name.isNotBlank() && value.length <= 8_192 && !SecretPolicy.containsSecret(value)) {
                        "Invalid variable name or value."
                    }
                    variables.set(name, value)
                    ""
                }
                ActionType.GET_VARIABLE -> {
                    logger.log("INFO", variables.get(params["variable"].orEmpty()).orEmpty())
                    ""
                }
                ActionType.EXECUTE_SAFE_SCRIPT -> safeScriptRunner(params["script"].orEmpty()).getOrThrow()
                else -> throw IllegalArgumentException("Action ${action.name} is not supported by this adapter.")
            }
            Unit
        }.onFailure { report(action.name, it) }
    }

    override fun httpRequest(url: String, method: String, body: String): Result<String> = runCatching {
        require(url.startsWith("https://", ignoreCase = true)) { "Only HTTPS URLs are allowed." }
        val host = runCatching { URL(url).host.lowercase() }.getOrNull()
            ?: throw IllegalArgumentException("Invalid URL.")
        require(host != "localhost" && !host.endsWith(".local") && !host.endsWith(".internal")) {
            "Local network addresses are blocked."
        }
        require(!isPrivateAddress(host)) { "Private network addresses are blocked." }
        rest.httpExternal(url, method.uppercase(), body.ifBlank { null }).getOrThrow()
    }.onFailure { report("httpRequest", it) }

    override fun setVariable(name: String, value: String): Result<Unit> = runCatching {
        variables.set(name, value)
    }.onFailure { report("setVariable", it) }

    override fun log(level: String, message: String) {
        logger.log(level, message)
    }

    private fun report(operation: String, error: Throwable) {
        logger.log("ERROR", "$operation failed: ${error.message ?: "unknown error"}")
    }

    /** Rejects common private/link-local/loopback IPv4 and IPv6 literals. */
    private fun isPrivateAddress(host: String): Boolean {
        val ipv4 = host.split('.').mapNotNull { it.toIntOrNull() }
        if (ipv4.size == 4) {
            val a = ipv4[0]
            val b = ipv4[1]
            return a == 10 || a == 127 || a == 0 || (a == 169 && b == 254) ||
                (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 100 && b in 64..127)
        }
        return host == "::1" || host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe80")
    }
}
