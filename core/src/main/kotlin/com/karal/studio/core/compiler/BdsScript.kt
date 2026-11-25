package com.karal.studio.core.compiler

import com.karal.studio.core.model.ActionType
import com.karal.studio.core.model.ConditionType
import com.karal.studio.core.model.ExecutionStep
import com.karal.studio.core.model.ValidationIssue

/**
 * BDScript-like scripting language implemented in pure Kotlin (no Node.js required).
 *
 * Example:
 * ```
 * # /warn command
 * require permission MODERATE_MEMBERS
 * if permission MANAGE_MESSAGES
 *   reply "Warned {user.name}: {arg1}"
 *   setvar lastWarning "{arg1}"
 * else
 *   reply "Moderator permission required."
 * endif
 * ```
 */
object BdsScriptCompiler {
    fun compile(script: String): List<ExecutionStep> = parse(script).steps

    fun parse(script: String): BdsProgram {
        val lines = tokenize(script)
        val parser = LineParser(lines)
        val steps = parser.parseBlock(setOf("endif", "else"))
        if (!parser.atEnd()) {
            throw CompileException(
                listOf(
                    ValidationIssue(
                        ValidationIssue.Severity.ERROR, "UNEXPECTED_TOKEN",
                        "Unexpected '${parser.peek().firstOrNull()}' outside an if block.", ""
                    )
                )
            )
        }
        val required = lines.filter { it.firstOrNull() == "require" && it.getOrNull(1) == "permission" }
            .map { it.getOrNull(2).orEmpty() }.filter { it.isNotBlank() }
        return BdsProgram(steps, required)
    }

    private fun tokenize(script: String): List<List<String>> = script.lines().mapNotNull { raw ->
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) return@mapNotNull null
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaped = false
        for (c in line) {
            when {
                escaped -> { current.append(c); escaped = false }
                c == '\\' && quote != null -> escaped = true
                quote != null -> if (c == quote) { tokens += current.toString(); current.clear(); quote = null }
                else current.append(c)
                c == '"' || c == '\'' -> {
                    if (current.isNotEmpty()) { tokens += current.toString(); current.clear() }
                    quote = c
                }
                c.isWhitespace() -> {
                    if (current.isNotEmpty()) { tokens += current.toString(); current.clear() }
                }
                else -> current.append(c)
            }
        }
        if (current.isNotEmpty()) tokens += current.toString()
        tokens.takeIf { it.isNotEmpty() }
    }

    private class LineParser(private val lines: List<List<String>>) {
        private var index = 0

        fun atEnd(): Boolean = index >= lines.size
        fun peek(): List<String> = if (atEnd()) emptyList() else lines[index]

        fun parseBlock(stops: Set<String>): List<ExecutionStep> {
            val result = mutableListOf<ExecutionStep>()
            while (!atEnd() && peek()[0] !in stops) {
                parseStatement()?.let { result += it }
            }
            return result
        }

        private fun fail(message: String): Nothing = throw CompileException(
            listOf(ValidationIssue(ValidationIssue.Severity.ERROR, "BDSCRIPT_ERROR", message, ""))
        )

        private fun parseStatement(): ExecutionStep? {
            val tokens = peek()
            index++
            val keyword = tokens[0].lowercase()
            fun arg(i: Int) = tokens.getOrNull(i) ?: fail("'$keyword' is missing argument ${i - 1}.")
            fun action(type: ActionType, params: Map<String, String>) =
                ExecutionStep.Action(type, params, "bdscript:${index - 1}")

            return when (keyword) {
                "command", "require", "name", "description" -> {
                    // Metadata statements are consumed by parse()/the project editor, not executed.
                    null
                }
                "send", "say" -> action(ActionType.SEND_MESSAGE, mapOf("message" to arg(1)))
                "reply" -> action(ActionType.REPLY, mapOf("message" to arg(1)))
                "dm" -> action(ActionType.SEND_DM, mapOf("message" to arg(1)))
                "embed" -> action(
                    ActionType.SEND_EMBED,
                    mapOf("title" to arg(1), "description" to tokens.getOrNull(2).orEmpty())
                )
                "edit" -> action(ActionType.EDIT_MESSAGE, mapOf("messageId" to arg(1), "message" to arg(2)))
                "delete" -> action(ActionType.DELETE_MESSAGE, mapOf("messageId" to arg(1)))
                "ban" -> action(ActionType.BAN_MEMBER, mapOf("memberId" to arg(1), "reason" to tokens.getOrNull(2).orEmpty()))
                "kick" -> action(ActionType.KICK_MEMBER, mapOf("memberId" to arg(1), "reason" to tokens.getOrNull(2).orEmpty()))
                "timeout" -> action(
                    ActionType.TIMEOUT_MEMBER,
                    mapOf("memberId" to arg(1), "durationSeconds" to arg(2))
                )
                "addrole" -> action(ActionType.ADD_ROLE, mapOf("memberId" to arg(1), "roleId" to arg(2)))
                "removerole" -> action(ActionType.REMOVE_ROLE, mapOf("memberId" to arg(1), "roleId" to arg(2)))
                "setvar" -> action(ActionType.SET_VARIABLE, mapOf("variable" to arg(1), "value" to arg(2)))
                "getvar" -> action(ActionType.GET_VARIABLE, mapOf("variable" to arg(1)))
                "http" -> action(
                    ActionType.HTTP_REQUEST,
                    mapOf("method" to arg(1), "url" to arg(2), "body" to tokens.getOrNull(3).orEmpty())
                )
                "log" -> action(ActionType.LOG_EVENT, mapOf("message" to arg(1)))
                "delay" -> {
                    val ms = arg(1).toLongOrNull() ?: fail("delay expects milliseconds.")
                    ExecutionStep.Delay(ms.coerceIn(0L, 600_000L), "bdscript:${index - 1}")
                }
                "stop" -> ExecutionStep.Stop("bdscript:${index - 1}")
                "if" -> {
                    val (condition, params) = parseCondition(tokens.drop(1))
                    val thenSteps = parseBlock(setOf("else", "endif"))
                    val elseSteps = if (!atEnd() && peek()[0] == "else") {
                        index++
                        parseBlock(setOf("endif"))
                    } else emptyList()
                    if (atEnd() || peek()[0] != "endif") fail("Missing 'endif' for 'if' statement.")
                    index++
                    ExecutionStep.Branch(condition, params, thenSteps, elseSteps, "bdscript:${index - 1}")
                }
                else -> fail("Unknown BDScript statement '$keyword'.")
            }
        }

        private fun parseCondition(tokens: List<String>): Pair<ConditionType, Map<String, String>> {
            if (tokens.isEmpty()) fail("if statement needs a condition.")
            return when (tokens[0].lowercase()) {
                "permission" -> ConditionType.USER_HAS_PERMISSION to mapOf("permission" to tokens.getOrNull(1).orEmpty())
                "role" -> ConditionType.USER_HAS_ROLE to mapOf("roleId" to tokens.getOrNull(1).orEmpty())
                "admin" -> ConditionType.IS_ADMINISTRATOR to emptyMap()
                "contains" -> ConditionType.MESSAGE_CONTAINS to mapOf("text" to tokens.getOrNull(1).orEmpty())
                "var" -> ConditionType.VARIABLE_EQUALS to mapOf(
                    "variable" to tokens.getOrNull(1).orEmpty(), "value" to tokens.getOrNull(2).orEmpty()
                )
                "number" -> ConditionType.NUMBER_COMPARISON to mapOf(
                    "variable" to tokens.getOrNull(1).orEmpty(),
                    "operator" to tokens.getOrNull(2).orEmpty(),
                    "value" to tokens.getOrNull(3).orEmpty()
                )
                else -> fail("Unknown condition '${tokens[0]}'.")
            }
        }
    }
}

data class BdsProgram(val steps: List<ExecutionStep>, val requiredBotPermissions: List<String>)
