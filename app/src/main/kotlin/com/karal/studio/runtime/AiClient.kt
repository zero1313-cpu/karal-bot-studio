package com.karal.studio.runtime

import com.karal.studio.core.json.Json
import com.karal.studio.core.json.array
import com.karal.studio.core.json.obj
import com.karal.studio.core.json.stringOr
import com.karal.studio.core.model.CommandDefinition
import com.karal.studio.core.model.CoreJson
import com.karal.studio.core.security.SecretPolicy
import com.karal.studio.data.repository.SettingsKeys
import com.karal.studio.data.repository.SettingsRepository
import com.karal.studio.security.SecretManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Optional OpenAI-compatible AI builder. The endpoint and key are user supplied; the prompt is
 * redacted before it leaves the device. Generated commands are always returned unapproved.
 */
class AiClient(
    private val settings: SettingsRepository,
    private val secrets: SecretManager
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun isConfigured(): Boolean = endpoint().isNotBlank() && !apiKey().isNullOrBlank()

    suspend fun generateCommand(prompt: String, projectId: String): Result<CommandDefinition> = runCatching {
        val endpoint = endpoint()
        require(endpoint.startsWith("https://")) { "AI endpoint must use HTTPS." }
        val key = apiKey() ?: throw IllegalStateException("Add an AI provider key in Settings first.")
        val model = settings.get(SettingsKeys.AI_MODEL, "gpt-4o-mini")
        val system = """
            You output only JSON for a KARAL Bot Studio CommandDefinition. Fields: id, projectId,
            name, description, source ("AI"), language ("JAVASCRIPT"), script, trigger
            {type:"SLASH_COMMAND"|"PREFIX_COMMAND"|"MESSAGE_CREATE",options:{}}, permissions,
            enabled, approved. The script may only use the documented KARAL SDK: ctx.reply,
            ctx.send, ctx.embed, ctx.args, ctx.user, ctx.channel, console.log. Never emit tokens,
            credentials, filesystem, process, import, require, fetch, eval or network calls to
            private addresses. Do not set approved to true. Return a single JSON object only.
        """.trimIndent()
        val body = Json.stringify(
            Json.obj(
                "model" to Json.str(model),
                "messages" to Json.arr(
                    listOf(
                        Json.obj("role" to Json.str("system"), "content" to Json.str(system)),
                        Json.obj("role" to Json.str("user"), "content" to Json.str(SecretPolicy.redact(prompt).take(8_000)))
                    )
                ),
                "temperature" to Json.num(0.2),
                "max_tokens" to Json.num(2_000)
            )
        )
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val response = client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("AI API ${response.code}: " + SecretPolicy.redact(text.take(300)))
            }
            text
        }
        val content = Json.parse(response).obj()?.get("choices")?.array().orEmpty()
            .firstOrNull()?.obj()?.get("message")?.obj()?.get("content")?.stringOr("")
            ?: throw IllegalStateException("AI response did not contain a message.")
        val cleaned = content.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        val generated = CoreJson.decodeCommand(Json.parse(cleaned), projectId)
        generated.copy(
            id = generated.id.ifBlank { java.util.UUID.randomUUID().toString() },
            projectId = projectId,
            source = com.karal.studio.core.model.CommandSource.AI,
            approved = false,
            enabled = generated.enabled
        )
    }

    private suspend fun endpoint(): String =
        settings.get(SettingsKeys.AI_ENDPOINT, "https://api.openai.com/v1/chat/completions").trim()

    private suspend fun apiKey(): String? = secrets.read(SettingsKeys.AI_KEY_SECRET_ALIAS)
}
