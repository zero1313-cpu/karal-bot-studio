package com.karal.studio

import com.karal.studio.core.compiler.CommandCompiler
import com.karal.studio.core.compiler.CompileException
import com.karal.studio.core.model.CoreJson
import com.karal.studio.core.security.SecretPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM unit tests that run without an Android SDK. */
class SecurityUnitTests {
    private val realisticToken = "MT${"A".repeat(24)}.${"B".repeat(6)}.${"C".repeat(30)}"

    @Test
    fun redactsDiscordToken() {
        assertTrue(SecretPolicy.containsSecret(realisticToken))
        assertFalse(SecretPolicy.redact("token=$realisticToken").contains(realisticToken))
    }

    @Test
    fun removesSecretKeysFromMaps() {
        val sanitized = SecretPolicy.sanitizeMap(mapOf("token" to realisticToken, "name" to "ping"))
        assertFalse(sanitized.containsKey("token"))
        assertTrue(sanitized["name"] == "ping")
    }

    @Test
    fun unapprovedCommandsCannotCompile() {
        val command = CoreJson.decodeCommand(
            com.karal.studio.core.json.Json.obj(
                "id" to com.karal.studio.core.json.Json.str("c1"),
                "name" to com.karal.studio.core.json.Json.str("ping"),
                "script" to com.karal.studio.core.json.Json.str("command({}, () => {});"),
                "approved" to com.karal.studio.core.json.Json.bool(false)
            )
        )
        val error = runCatching { CommandCompiler.compile(command) }.exceptionOrNull()
        assertTrue(error is CompileException && error.issues.any { it.code == "UNAPPROVED_CODE" })
    }

    @Test
    fun acceptsPlausibleDiscordTokens() {
        assertTrue(SecretPolicy.isPlausibleDiscordBotToken(realisticToken))
        val secondToken = "OD${"8".repeat(24)}.${"G".repeat(6)}.${"a".repeat(30)}"
        assertTrue(SecretPolicy.isPlausibleDiscordBotToken(secondToken))
    }

    @Test
    fun rejectsMalformedDiscordTokens() {
        assertFalse(SecretPolicy.isPlausibleDiscordBotToken(""))
        assertFalse(SecretPolicy.isPlausibleDiscordBotToken("not-a-token"))
        assertFalse(SecretPolicy.isPlausibleDiscordBotToken("abc.def"))
        assertFalse(SecretPolicy.isPlausibleDiscordBotToken("abc def ghi"))
    }
}
