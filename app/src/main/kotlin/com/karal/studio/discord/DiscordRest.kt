package com.karal.studio.discord

import com.karal.studio.core.engine.RateLimiter
import com.karal.studio.core.json.Json
import com.karal.studio.core.json.JsonValue
import com.karal.studio.core.json.array
import com.karal.studio.core.json.obj
import com.karal.studio.core.json.string
import com.karal.studio.core.json.stringOr
import com.karal.studio.core.model.CommandDefinition
import com.karal.studio.core.security.SecretPolicy
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Discord REST v10 adapter. All calls are blocking and must be made from an IO thread. */
class DiscordRest(private val tokenProvider: () -> String?) {
    private val limiter = RateLimiter()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun bucket(path: String): String = path.replace(Regex("/\\d+/"), "/#/")

    fun request(
        method: String,
        path: String,
        bodyJson: String? = null,
        base: String = "https://discord.com/api/v10",
        retryOnRateLimit: Boolean = true
    ): Result<String> = runCatching {
        val token = tokenProvider() ?: throw IllegalStateException("No bot token stored for this session.")
        val key = bucket(path)
        if (!limiter.tryAcquire(key, 5, 1_000L)) throw IllegalStateException("Local REST rate limit reached for $key")
        val body = bodyJson?.toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(base + path)
            .method(method, body)
            .header("Authorization", "Bot $token")
            .header("User-Agent", "KARAL-BOT-STUDIO/1.0 (Android)")
            .header("Content-Type", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 429 && retryOnRateLimit) {
                val retryAfter = runCatching {
                    Json.parse(response.body?.string().orEmpty()).obj()?.get("retry_after")?.string().orEmpty()
                }.getOrDefault("").toDoubleOrNull() ?: 1.0
                Thread.sleep((retryAfter.coerceIn(0.1, 5.0) * 1000).toLong())
                return request(method, path, bodyJson, base, retryOnRateLimit = false)
            }
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("Discord API ${response.code}: " + SecretPolicy.redact(text.take(300)))
            }
            text
        }
    }

    fun gatewayUrl(): Result<String> = request("GET", "/gateway/bot").mapCatching { body ->
        Json.parse(body).obj()?.get("url")?.string() ?: throw IllegalStateException("Gateway URL missing")
    }

    fun sendMessage(channelId: String, content: String): Result<String> =
        request("POST", "/channels/$channelId/messages", Json.stringify(Json.obj("content" to Json.str(content.take(2000)))))

    fun sendEmbed(channelId: String, title: String, description: String, componentsJson: String? = null): Result<String> {
        val embed = Json.obj("title" to Json.str(title.take(256)), "description" to Json.str(description.take(4096)), "color" to Json.num(0x7C5CFF))
        val payload = if (componentsJson != null) {
            Json.obj("embeds" to Json.arr(listOf(embed)), "components" to Json.parse(componentsJson))
        } else Json.obj("embeds" to Json.arr(listOf(embed)))
        return request("POST", "/channels/$channelId/messages", Json.stringify(payload))
    }

    fun editMessage(channelId: String, messageId: String, content: String): Result<String> =
        request("PATCH", "/channels/$channelId/messages/$messageId", Json.stringify(Json.obj("content" to Json.str(content.take(2000)))))

    fun deleteMessage(channelId: String, messageId: String): Result<String> =
        request("DELETE", "/channels/$channelId/messages/$messageId")

    fun messages(channelId: String, limit: Int): Result<JsonValue> =
        request("GET", "/channels/$channelId/messages?limit=${limit.coerceIn(1, 100)}").map { Json.parse(it) }

    fun bulkDelete(channelId: String, ids: List<String>): Result<String> =
        request("POST", "/channels/$channelId/messages/bulk-delete",
            Json.stringify(Json.obj("messages" to Json.strings(ids))))

    fun interactionResponse(interactionId: String, token: String, type: Int, data: JsonValue?): Result<String> {
        val payload = Json.obj("type" to Json.num(type), "data" to (data ?: JsonValue.Null))
        return request("POST", "/interactions/$interactionId/$token/callback", Json.stringify(payload))
    }

    fun followup(applicationId: String, token: String, content: String): Result<String> =
        request("POST", "/webhooks/$applicationId/$token", Json.stringify(Json.obj("content" to Json.str(content.take(2000)))))

    fun editInteractionResponse(applicationId: String, token: String, content: String): Result<String> =
        request(
            "PATCH", "/webhooks/$applicationId/$token/messages/@original",
            Json.stringify(Json.obj("content" to Json.str(content.take(2000))))
        )

    fun deleteInteractionResponse(applicationId: String, token: String): Result<String> =
        request("DELETE", "/webhooks/$applicationId/$token/messages/@original")

    fun registerCommands(applicationId: String, commands: List<CommandDefinition>, guildId: String? = null): Result<Int> {
        val payloads = commands.filter { it.trigger.type.name == "SLASH_COMMAND" }.map { command ->
            Json.obj(
                "name" to Json.str(command.name),
                "description" to Json.str(command.description.ifBlank { "KARAL command" }.take(100)),
                "dm_permission" to Json.bool(command.permissions.dmAllowed)
            )
        }
        val path = if (guildId.isNullOrBlank()) "/applications/$applicationId/commands" else
            "/applications/$applicationId/guilds/$guildId/commands"
        return request("PUT", path, Json.stringify(Json.arr(payloads))).map { Json.parse(it).array().orEmpty().size }
    }

    fun banMember(guildId: String, userId: String, reason: String): Result<String> =
        request("PUT", "/guilds/$guildId/bans/$userId?reason=${java.net.URLEncoder.encode(reason.take(100), "UTF-8")}")

    fun kickMember(guildId: String, userId: String, reason: String): Result<String> =
        request("DELETE", "/guilds/$guildId/members/$userId?reason=${java.net.URLEncoder.encode(reason.take(100), "UTF-8")}")

    fun timeoutMember(guildId: String, userId: String, seconds: Long, reason: String): Result<String> {
        val formatter = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        val communicationDisabledUntil = if (seconds <= 0) JsonValue.Null else
            Json.str(formatter.format(java.util.Date(System.currentTimeMillis() + seconds * 1000L)))
        return request("PATCH", "/guilds/$guildId/members/$userId", Json.stringify(
            Json.obj("communication_disabled_until" to communicationDisabledUntil, "reason" to Json.str(reason.take(100)))
        ))
    }

    fun addRole(guildId: String, userId: String, roleId: String): Result<String> =
        request("PUT", "/guilds/$guildId/members/$userId/roles/$roleId")

    fun removeRole(guildId: String, userId: String, roleId: String): Result<String> =
        request("DELETE", "/guilds/$guildId/members/$userId/roles/$roleId")

    fun member(guildId: String, userId: String): Result<JsonValue> = request("GET", "/guilds/$guildId/members/$userId").map { Json.parse(it) }
    fun guild(guildId: String): Result<JsonValue> = request("GET", "/guilds/$guildId?with_counts=true").map { Json.parse(it) }

    /** Number of servers visible to the bot, up to Discord's 200-guild page limit. */
    fun guildCount(): Result<Int> = request("GET", "/users/@me/guilds?limit=200").map { body ->
        Json.parse(body).array().orEmpty().size
    }
    fun channels(guildId: String): Result<JsonValue> = request("GET", "/guilds/$guildId/channels").map { Json.parse(it) }
    fun roles(guildId: String): Result<JsonValue> = request("GET", "/guilds/$guildId/roles").map { Json.parse(it) }

    fun modifyChannel(channelId: String, body: JsonValue): Result<String> =
        request("PATCH", "/channels/$channelId", Json.stringify(body))

    fun createChannel(guildId: String, name: String): Result<JsonValue> =
        request("POST", "/guilds/$guildId/channels", Json.stringify(Json.obj("name" to Json.str(name), "type" to Json.num(0)))).map { Json.parse(it) }

    fun deleteChannel(channelId: String): Result<String> = request("DELETE", "/channels/$channelId")

    fun createDm(recipientId: String): Result<String> = request(
        "POST", "/users/@me/channels",
        Json.stringify(Json.obj("recipient_id" to Json.str(recipientId)))
    ).map { body -> Json.parse(body).obj()?.get("id")?.stringOr("") ?: "" }

    fun createThread(channelId: String, name: String): Result<JsonValue> =
        request("POST", "/channels/$channelId/threads", Json.stringify(Json.obj("name" to Json.str(name), "type" to Json.num(11)))).map { Json.parse(it) }

    /** Plain HTTPS call for command-defined webhooks. No bot token is ever attached. */
    fun httpExternal(url: String, method: String, bodyJson: String?): Result<String> = runCatching {
        val body = bodyJson?.toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(url)
            .method(method.uppercase(), body)
            .header("User-Agent", "KARAL-BOT-STUDIO/1.0 (Android)")
            .header("Accept", "application/json, text/plain, */*")
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty().take(100_000)
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}: " + SecretPolicy.redact(text.take(300)))
            }
            text
        }
    }

    fun httpGet(url: String): Result<String> {
        if (!url.startsWith("https://")) return Result.failure(IllegalArgumentException("Only HTTPS URLs are allowed."))
        val host = runCatching { java.net.URI(url).host }.getOrNull().orEmpty()
        val allowed = setOf("api.mymemory.translated.net", "discord.com", "discordapp.com")
        if (host !in allowed) return Result.failure(IllegalArgumentException("Host '$host' is not on the HTTP allowlist."))
        val req = Request.Builder().url(url).header("User-Agent", "KARAL-BOT-STUDIO/1.0").build()
        return runCatching {
            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
                response.body?.string().orEmpty()
            }
        }
    }

    fun string(data: JsonValue?, key: String): String = data?.obj()?.get(key)?.stringOr("") ?: ""
}
