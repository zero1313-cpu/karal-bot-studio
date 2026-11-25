package com.karal.studio.discord

import com.karal.studio.core.json.JsonValue
import com.karal.studio.core.json.longOr
import com.karal.studio.core.json.obj
import com.karal.studio.core.json.string
import com.karal.studio.core.json.stringOr

object Intents {
    const val GUILDS = 1L shl 0
    const val GUILD_MEMBERS = 1L shl 1
    const val GUILD_BANS = 1L shl 2
    const val GUILD_EMOJIS = 1L shl 3
    const val GUILD_INTEGRATIONS = 1L shl 4
    const val GUILD_WEBHOOKS = 1L shl 5
    const val GUILD_INVITES = 1L shl 6
    const val GUILD_VOICE_STATES = 1L shl 7
    const val GUILD_PRESENCES = 1L shl 8
    const val GUILD_MESSAGES = 1L shl 9
    const val GUILD_MESSAGE_REACTIONS = 1L shl 10
    const val GUILD_MESSAGE_TYPING = 1L shl 11
    const val DIRECT_MESSAGES = 1L shl 12
    const val DIRECT_MESSAGE_REACTIONS = 1L shl 13
    const val DIRECT_MESSAGE_TYPING = 1L shl 14
    const val MESSAGE_CONTENT = 1L shl 15
    const val GUILD_SCHEDULED_EVENTS = 1L shl 16

    const val DEFAULT = GUILDS or GUILD_MESSAGES or GUILD_MESSAGE_REACTIONS or DIRECT_MESSAGES
    const val PRIVILEGED = GUILD_MEMBERS or GUILD_PRESENCES or MESSAGE_CONTENT

    fun describe(intents: Long): List<String> = buildList {
        if (intents and MESSAGE_CONTENT != 0L) add("MESSAGE CONTENT (privileged - enable in Developer Portal)")
        if (intents and GUILD_MEMBERS != 0L) add("SERVER MEMBERS (privileged - enable in Developer Portal)")
        if (intents and GUILD_PRESENCES != 0L) add("PRESENCE (privileged - enable in Developer Portal)")
        if (intents and GUILD_MESSAGES != 0L) add("Guild messages")
        if (intents and GUILD_MESSAGE_REACTIONS != 0L) add("Reaction events")
        if (intents and DIRECT_MESSAGES != 0L) add("Direct messages")
    }
}

data class GatewayEvent(val type: String, val data: JsonValue, val sequence: Int)

/** Parses the small subset of Gateway payloads this client uses. */
object GatewayPayload {
    fun op(payload: JsonValue): Int? = payload.obj()?.get("op")?.longOr(-1)?.toInt()
    fun data(payload: JsonValue): JsonValue? = payload.obj()?.get("d")
    fun eventName(payload: JsonValue): String? = payload.obj()?.get("t")?.string()
    fun sequence(payload: JsonValue): Int = payload.obj()?.get("s")?.longOr(0)?.toInt() ?: 0
    fun heartbeatInterval(payload: JsonValue): Long = data(payload)?.obj()?.get("heartbeat_interval")?.longOr(45_000L) ?: 45_000L
    fun sessionId(payload: JsonValue): String? = data(payload)?.obj()?.get("session_id")?.string()
    fun resumeGatewayUrl(payload: JsonValue): String? = data(payload)?.obj()?.get("resume_gateway_url")?.string()
}
