package com.karal.studio.core.ai

import com.karal.studio.core.json.Json
import com.karal.studio.core.json.JsonValue
import com.karal.studio.core.json.array
import com.karal.studio.core.json.obj
import com.karal.studio.core.json.string
import com.karal.studio.core.json.stringList
import com.karal.studio.core.json.stringOr
import com.karal.studio.core.model.CommandDefinition
import com.karal.studio.core.model.CommandSource
import com.karal.studio.core.model.ScriptLanguage
import com.karal.studio.core.model.TriggerSpec
import com.karal.studio.core.security.SecretPolicy

enum class AiProvider(val label: String, val defaultEndpoint: String) {
    GEMINI("Google Gemini", "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent"),
    GROQ("Groq", "https://api.groq.com/openai/v1/chat/completions"),
    OPENAI_COMPATIBLE("OpenAI-compatible", "https://api.openai.com/v1/chat/completions")
}

data class AiProviderConfig(
    val provider: AiProvider = AiProvider.GEMINI,
    val endpoint: String = AiProvider.GEMINI.defaultEndpoint,
    val model: String = "gemini-2.0-flash",
    /** Name of the encrypted secret in the Keystore-backed table, never the key itself. */
    val keySecretName: String = "ai_key_gemini",
    val enabled: Boolean = false
)

object AiPromptBuilder {
    fun build(instruction: String, projectName: String, existingNames: List<String>): String = """
You are KARAL BOT STUDIO's command designer. Produce a Discord bot command design from the user request.
Return ONLY one JSON object with exactly these keys:
{
  "name": "lowercase command name (a-z0-9_- max 32)",
  "description": "max 100 characters",
  "triggerType": "SLASH_COMMAND or PREFIX_COMMAND",
  "script": "JavaScript using the KARAL SDK: command({name, description}, async (ctx) => { ... })",
  "permissions": ["DISCORD_PERMISSION_NAMES_REQUIRED_FROM_USERS"],
  "requiredBotPermissions": ["DISCORD_PERMISSION_NAMES_REQUIRED_FROM_BOT"],
  "errorHandling": "short description of error handling in the script",
  "testingInstructions": "short numbered testing steps",
  "notes": "any caveats"
}
Rules:
- No bot tokens, API keys, filesystem, process, shell or eval APIs.
- Never assume privileged intents are enabled; note when they are required.
- Do not invent SDK methods outside: reply, send, edit, delete, embed, buttons, selectMenu, modal,
  user, member, guild, channel, permissions, roles.add, roles.remove, variables.get, variables.set.
- The user must review and approve generated code before it can run.
Project: $projectName
Existing command names: ${existingNames.joinToString(", ").ifBlank { "none" }}
User request: $instruction
""".trimIndent()
}

object AiRequests {
    fun geminiBody(prompt: String, model: String): String = Json.stringify(
        Json.obj(
            "systemInstruction" to Json.obj(
                "parts" to Json.arr(listOf(Json.obj("text" to Json.str(prompt))))
            ),
            "contents" to Json.arr(
                listOf(
                    Json.obj(
                        "role" to Json.str("user"),
                        "parts" to Json.arr(listOf(Json.obj("text" to Json.str("Generate the command design JSON now."))))
                    )
                )
            ),
            "generationConfig" to Json.obj(
                "temperature" to Json.num(0.2),
                "responseMimeType" to Json.str("application/json"),
                "maxOutputTokens" to Json.num(4096)
            )
        )
    )

    fun chatBody(prompt: String, model: String): String = Json.stringify(
        Json.obj(
            "model" to Json.str(model),
            "messages" to Json.arr(
                listOf(
                    Json.obj("role" to Json.str("system"), "content" to Json.str(prompt)),
                    Json.obj("role" to Json.str("user"), "content" to Json.str("Generate the command design JSON now."))
                )
            ),
            "temperature" to Json.num(0.2),
            "max_tokens" to Json.num(4096),
            "response_format" to Json.obj("type" to Json.str("json_object"))
        )
    )

    fun responseText(provider: AiProvider, body: String): String {
        val root = Json.parse(body).obj() ?: throw IllegalArgumentException("AI response is not a JSON object")
        return when (provider) {
            AiProvider.GEMINI -> root["candidates"]?.array().orEmpty()
                .firstNotNullOfOrNull { candidate ->
                    candidate.obj()?.get("content")?.obj()?.get("parts")?.array()
                        ?.firstNotNullOfOrNull { it.obj()?.get("text")?.string() }
                } ?: throw IllegalArgumentException("Gemini response contained no text")
            AiProvider.GROQ, AiProvider.OPENAI_COMPATIBLE -> root["choices"]?.array().orEmpty()
                .firstNotNullOfOrNull { choice ->
                    choice.obj()?.get("message")?.obj()?.get("content")?.string()
                } ?: throw IllegalArgumentException("AI response contained no message content")
        }
    }
}

data class AiDraft(
    val name: String,
    val description: String,
    val triggerType: String,
    val script: String,
    val permissions: List<String>,
    val requiredBotPermissions: List<String>,
    val errorHandling: String,
    val testingInstructions: String,
    val notes: String,
    val rawResponse: String
) {
    fun toCommand(projectId: String): CommandDefinition = CommandDefinition(
        id = "cmd_${System.currentTimeMillis()}_${name}",
        projectId = projectId,
        name = name,
        description = description,
        source = CommandSource.AI,
        language = ScriptLanguage.JAVASCRIPT,
        script = script,
        trigger = TriggerSpec(type = runCatching {
            com.karal.studio.core.model.TriggerType.valueOf(triggerType.ifBlank { "SLASH_COMMAND" })
        }.getOrDefault(com.karal.studio.core.model.TriggerType.SLASH_COMMAND)),
        permissions = com.karal.studio.core.model.CommandPermissions(
            requiredUserPermissions = permissions,
            guildOnly = true
        ),
        requiredBotPermissions = requiredBotPermissions,
        metadata = mapOf(
            "ai_error_handling" to errorHandling,
            "ai_testing" to testingInstructions,
            "ai_notes" to notes
        ),
        approved = false
    )
}

object AiResponseParser {
    /** Extracts a JSON object even if the model wrapped it in Markdown fences or prose. */
    fun extractJson(text: String): String {
        val start = text.indexOf('{')
        if (start < 0) throw IllegalArgumentException("AI response did not contain a JSON object")
        var depth = 0
        var quote = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            when {
                escaped -> escaped = false
                quote && c == '\\' -> escaped = true
                quote && c == '"' -> quote = false
                !quote && c == '"' -> quote = true
                !quote && c == '{' -> depth++
                !quote && c == '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        throw IllegalArgumentException("AI response contained an unterminated JSON object")
    }

    fun parse(text: String): AiDraft {
        val json = Json.parseObject(extractJson(SecretPolicy.redact(text)))
        val name = json["name"]?.stringOr("").orEmpty().trim().lowercase()
            .replace(Regex("[^a-z0-9_-]"), "-").take(32).trim('-')
        if (name.isBlank()) throw IllegalArgumentException("AI response did not include a command name")
        return AiDraft(
            name = name,
            description = json["description"]?.stringOr("").orEmpty().take(100),
            triggerType = json["triggerType"]?.stringOr("SLASH_COMMAND").orEmpty(),
            script = json["script"]?.stringOr("").orEmpty(),
            permissions = json["permissions"].stringList().map { it.uppercase() },
            requiredBotPermissions = json["requiredBotPermissions"].stringList().map { it.uppercase() },
            errorHandling = json["errorHandling"]?.stringOr("").orEmpty(),
            testingInstructions = json["testingInstructions"]?.stringOr("").orEmpty(),
            notes = json["notes"]?.stringOr("").orEmpty(),
            rawResponse = SecretPolicy.redact(text)
        )
    }
}
