package com.karal.studio.core.security

/**
 * Defence-in-depth helpers. The Android layer encrypts credentials with the Keystore; these
 * pure-Kotlin rules make sure credentials never reach logs, exports or AI prompts by accident.
 * This is not a substitute for OS-level isolation.
 */
object SecretPolicy {
    private val discordToken = Regex("""MT[A-Za-z0-9_-]{23,28}\.[A-Za-z0-9_-]{6,7}\.[A-Za-z0-9_-]{27,}""")
    private val googleKey = Regex("""AIza[0-9A-Za-z_-]{30,}""")
    private val openAiKey = Regex("""sk-[A-Za-z0-9_-]{20,}""")
    private val groqKey = Regex("""gsk_[A-Za-z0-9]{20,}""")
    private val webhook = Regex("""https://(?:canary\.|ptb\.)?discord(?:app)?\.com/api/webhooks/\d+/[A-Za-z0-9_-]+""")
    private val patterns = listOf(discordToken, googleKey, openAiKey, groqKey, webhook)

    private val secretKeyNames = setOf(
        "token", "bottoken", "apitoken", "apikey", "secret", "clientsecret",
        "password", "passphrase", "authorization", "webhook", "webhookurl", "privatekey"
    )

    const val REDACTED = "[REDACTED]"

    fun containsSecret(text: String): Boolean = patterns.any { it.containsMatchIn(text) }

    fun redact(text: String): String {
        var result = text
        patterns.forEach { result = it.replace(result, REDACTED) }
        return result
    }

    fun isSecretKey(key: String): Boolean {
        val normalized = key.lowercase().filter { it.isLetterOrDigit() }
        return secretKeyNames.any { normalized.contains(it) }
    }

    /** Returns the map with secret-looking keys and values removed. */
    fun sanitizeMap(values: Map<String, String>): Map<String, String> = buildMap {
        values.forEach { (key, value) ->
            if (!isSecretKey(key)) put(key, redact(value))
        }
    }

    /** Counts how many secret-looking values were removed or redacted; used in export warnings. */
    fun countSecrets(values: Map<String, String>): Int = values.count { (key, value) ->
        isSecretKey(key) || containsSecret(value)
    }
}
