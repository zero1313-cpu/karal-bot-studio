package com.karal.studio.runtime

import com.karal.studio.core.engine.DiscordAdapter
import com.karal.studio.core.model.ActionType
import com.karal.studio.core.model.ExecutionContextData
import com.karal.studio.core.security.SecretPolicy
import com.quickjs.JSArray
import com.quickjs.JSContext
import com.quickjs.JSObject
import com.quickjs.JavaCallback
import com.quickjs.JavaVoidCallback
import com.quickjs.QuickJS
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * QuickJS sandbox. User scripts receive only the documented KARAL SDK object: no filesystem,
 * process, reflection, network client or bot token is exposed.
 *
 * The exported SDK is synchronous, so `async`/`await` keywords are removed before evaluation.
 * This is a normalisation step, not a general JavaScript transpiler.
 *
 * Limit of this design: an in-process QuickJS runtime is not an OS-enforced sandbox. We bound
 * script size, execution time and the SDK surface, but a native parser bug could still escape.
 * Production operators should use a separate process or stronger isolation where feasible.
 */
class ScriptRuntime(
    private val adapter: DiscordAdapter,
    private val contextData: ExecutionContextData
) {
    fun execute(script: String, timeoutMs: Long = 5_000L): Result<String> = runCatching {
        val normalized = normalize(script)
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "karal-quickjs").apply { isDaemon = true } }
        val runtime = QuickJS.createRuntimeWithEventQueue()
        var future: Future<*>? = null
        try {
            future = executor.submit {
                val ctx = runtime.createContext()
                try {
                    installSdk(ctx)
                    ctx.executeVoidScript(normalized, "command.js")
                    ctx.executeVoidScript("if (typeof __karalCallback === 'function') __karalCallback(__karalCtx);", "invoke.js")
                } finally {
                    ctx.close()
                }
            }
            future.get(timeoutMs.coerceIn(100L, 120_000L), TimeUnit.MILLISECONDS)
            "ok"
        } catch (e: TimeoutException) {
            runtime.close()
            throw IllegalStateException("Script execution timed out after ${timeoutMs}ms.")
        } finally {
            future?.cancel(true)
            executor.shutdownNow()
            runCatching { runtime.close() }
        }
    }.onFailure { error ->
        adapter.log("ERROR", SecretPolicy.redact(error.message ?: "Script execution failed"))
    }

    private fun installSdk(ctx: JSContext) {
        var registeredName = ""
        ctx.registerJavaMethod(JavaCallback { _, args ->
            val config = args.getObject(0)
            registeredName = config?.getString("name").orEmpty()
            val callback = args.getObject(1)
            if (callback != null) ctx.set("__karalCallback", callback)
            null
        }, "command")

        val sdk = JSObject(ctx)
        sdk.set("args", JSArray(ctx).apply { contextData.args.forEach { push(it) } })
        sdk.set("user", JSObject(ctx)
            .set("id", contextData.userId)
            .set("name", contextData.userId))
        sdk.set("guild", JSObject(ctx)
            .set("id", contextData.guildId)
            .set("name", contextData.guildId))
        sdk.set("channel", JSObject(ctx)
            .set("id", contextData.channelId))
        sdk.set("permissions", JSObject(ctx).apply {
            contextData.userPermissions.forEach { set(it, true) }
            set("ADMINISTRATOR", contextData.isAdministrator)
        })
        sdk.set("trigger", JSObject(ctx).set("type", contextData.commandId))

        sdk.registerJavaMethod(JavaVoidCallback { _, args ->
            adapter.reply(args.getString(0).orEmpty(), args.getBoolean(1)).onFailure { report(it) }
        }, "reply")
        sdk.registerJavaMethod(JavaVoidCallback { _, args ->
            adapter.sendMessage(args.getString(0).orEmpty(), args.getString(1).orEmpty()).onFailure { report(it) }
        }, "send")
        sdk.registerJavaMethod(JavaVoidCallback { _, args ->
            adapter.editMessage(contextData.channelId, args.getString(0).orEmpty(), args.getString(1).orEmpty()).onFailure { report(it) }
        }, "edit")
        sdk.registerJavaMethod(JavaVoidCallback { _, args ->
            adapter.deleteMessage(contextData.channelId, args.getString(0).orEmpty()).onFailure { report(it) }
        }, "delete")
        sdk.registerJavaMethod(JavaVoidCallback { _, args ->
            val title = args.getString(0).orEmpty()
            val description = args.getString(1).orEmpty()
            val color = runCatching { args.getDouble(2).toLong() }.getOrNull()
            adapter.sendEmbed(contextData.channelId, title, description).onFailure { report(it) }
            if (color != null) adapter.log("DEBUG", "embed color $color")
        }, "embed")
        sdk.registerJavaMethod(JavaVoidCallback { _, args ->
            val buttons = args.getObject(0)
            val count = buttons?.getString("__count")?.toIntOrNull() ?: 0
            adapter.log("DEBUG", "Prepared $count button(s)")
        }, "buttons")
        sdk.registerJavaMethod(JavaVoidCallback { _, _ -> adapter.log("DEBUG", "Select menu prepared") }, "selectMenu")
        sdk.registerJavaMethod(JavaVoidCallback { _, _ -> adapter.log("DEBUG", "Modal prepared") }, "modal")
        sdk.registerJavaMethod(JavaVoidCallback { _, args ->
            adapter.log(args.getString(0) ?: "INFO", args.getString(1).orEmpty())
        }, "log")

        val variables = JSObject(ctx)
        variables.registerJavaMethod(JavaCallback { _, args ->
            contextData.variables[args.getString(0)].orEmpty()
        }, "get")
        variables.registerJavaMethod(JavaCallback { _, args ->
            val key = args.getString(0).orEmpty()
            val value = args.getString(1).orEmpty()
            if (value.length > 8_192 || SecretPolicy.containsSecret(value)) {
                throw IllegalArgumentException("Invalid variable value")
            }
            adapter.setVariable(key, value).getOrThrow()
            value
        }, "set")
        sdk.set("variables", variables)

        val roles = JSObject(ctx)
        roles.registerJavaMethod(JavaCallback { _, args ->
            adapter.moderate(ActionType.ADD_ROLE, mapOf("memberId" to args.getString(0).orEmpty(), "roleId" to args.getString(1).orEmpty()), contextData)
            true
        }, "add")
        roles.registerJavaMethod(JavaCallback { _, args ->
            adapter.moderate(ActionType.REMOVE_ROLE, mapOf("memberId" to args.getString(0).orEmpty(), "roleId" to args.getString(1).orEmpty()), contextData)
            true
        }, "remove")
        roles.registerJavaMethod(JavaCallback { _, _ -> JSArray(ctx).push(contextData.memberRoles.firstOrNull().orEmpty()) }, "list")
        sdk.set("roles", roles)

        val member = JSObject(ctx)
        member.registerJavaMethod(JavaCallback { _, args ->
            adapter.moderate(ActionType.BAN_MEMBER, mapOf("memberId" to args.getString(0).orEmpty(), "reason" to args.getString(1).orEmpty()), contextData); true
        }, "ban")
        member.registerJavaMethod(JavaCallback { _, args ->
            adapter.moderate(ActionType.KICK_MEMBER, mapOf("memberId" to args.getString(0).orEmpty(), "reason" to args.getString(1).orEmpty()), contextData); true
        }, "kick")
        member.registerJavaMethod(JavaCallback { _, args ->
            adapter.moderate(ActionType.TIMEOUT_MEMBER, mapOf("memberId" to args.getString(0).orEmpty(), "durationSeconds" to args.getDouble(1).toLong().toString()), contextData); true
        }, "timeout")
        member.registerJavaMethod(JavaCallback { _, args ->
            JSObject(ctx).set("id", args.getString(0).orEmpty()).set("createdAt", "unknown")
        }, "get")
        sdk.set("member", member)

        val http = JSObject(ctx)
        http.registerJavaMethod(JavaCallback { _, args ->
            val url = args.getString(0).orEmpty()
            val body = args.getString(2).orEmpty()
            adapter.httpRequest(url, args.getString(1) ?: "GET", body).getOrThrow()
        }, "request")
        http.registerJavaMethod(JavaCallback { _, args ->
            adapter.httpRequest(args.getString(0).orEmpty(), "GET", "").getOrThrow()
        }, "get")
        sdk.set("http", http)

        val delete = JSObject(ctx)
        delete.registerJavaMethod(JavaVoidCallback { _, _ ->
            adapter.deleteMessage(contextData.channelId, "").onFailure { report(it) }
        }, "message")
        delete.registerJavaMethod(JavaVoidCallback { _, args ->
            val count = args.getDouble(0).toInt().coerceIn(1, 100)
            adapter.moderate(ActionType.DELETE_MESSAGE, mapOf("messageId" to "bulk:$count"), contextData).onFailure { report(it) }
        }, "bulk")
        sdk.set("delete", delete)

        val ticket = JSObject(ctx)
        ticket.registerJavaMethod(JavaCallback { _, args ->
            val name = args.getString(1).orEmpty()
            adapter.moderate(ActionType.CREATE_TICKET, mapOf("channelName" to name, "userId" to args.getString(0).orEmpty()), contextData)
            JSObject(ctx).set("id", "ticket")
        }, "create")
        ticket.registerJavaMethod(JavaVoidCallback { _, _ -> adapter.log("INFO", "Ticket claimed") }, "claim")
        ticket.registerJavaMethod(JavaVoidCallback { _, _ -> adapter.log("INFO", "Ticket closed") }, "close")
        ticket.registerJavaMethod(JavaCallback { _, _ -> "" }, "transcript")
        sdk.set("ticket", ticket)

        val commands = JSObject(ctx)
        commands.registerJavaMethod(JavaCallback { _, _ -> JSArray(ctx) }, "list")
        sdk.set("commands", commands)

        val channel = sdk.getObject("channel")
        channel.registerJavaMethod(JavaVoidCallback { _, _ -> adapter.log("INFO", "Channel locked") }, "lock")
        channel.registerJavaMethod(JavaVoidCallback { _, _ -> adapter.log("INFO", "Channel unlocked") }, "unlock")
        channel.registerJavaMethod(JavaVoidCallback { _, _ -> adapter.log("INFO", "Slowmode updated") }, "slowmode")

        val guild = sdk.getObject("guild")
        guild.registerJavaMethod(JavaCallback { _, _ -> 0 }, "memberCount")
        guild.registerJavaMethod(JavaCallback { _, _ -> "" }, "iconUrl")
        guild.registerJavaMethod(JavaCallback { _, _ -> "" }, "bannerUrl")
        guild.registerJavaMethod(JavaCallback { _, _ -> JSArray(ctx) }, "channels")
        guild.registerJavaMethod(JavaCallback { _, _ -> JSArray(ctx) }, "roles")

        sdk.registerJavaMethod(JavaCallback { _, _ -> 0L }, "latency")
        sdk.registerJavaMethod(JavaCallback { _, args ->
            adapter.log("INFO", "Scheduled task: " + args.getString(1).orEmpty())
            true
        }, "schedule")

        val console = JSObject(ctx)
        console.registerJavaMethod(JavaVoidCallback { _, args -> adapter.log("INFO", args.getString(0).orEmpty()) }, "log")
        console.registerJavaMethod(JavaVoidCallback { _, args -> adapter.log("ERROR", args.getString(0).orEmpty()) }, "error")
        ctx.set("console", console)
        ctx.set("__karalCtx", sdk)
    }

    private fun report(error: Throwable) {
        adapter.log("ERROR", SecretPolicy.redact(error.message ?: "SDK call failed"))
    }

    /** Strip async/await outside string literals; the KARAL SDK itself is synchronous. */
    internal fun normalize(script: String): String {
        val chars = script.toCharArray()
        var quote: Char? = null
        var escaped = false
        var lineComment = false
        var blockComment = false
        var i = 0
        while (i < chars.size) {
            val c = chars[i]
            when {
                lineComment -> if (c == '\n') lineComment = false
                blockComment -> if (c == '*' && i + 1 < chars.size && chars[i + 1] == '/') { blockComment = false; i++ }
                quote != null -> {
                    when {
                        escaped -> escaped = false
                        c == '\\' -> escaped = true
                        c == quote -> quote = null
                    }
                }
                c == '/' && i + 1 < chars.size && chars[i + 1] == '/' -> lineComment = true
                c == '/' && i + 1 < chars.size && chars[i + 1] == '*' -> blockComment = true
                c == '"' || c == '\'' || c == '`' -> quote = c
                c == 'a' && script.startsWith("async ", i) && script.substring(i + 6).firstOrNull() != null -> {
                    // Only strip when followed by a function arrow (the SDK callback shape).
                    val tail = script.substring(i + 6, minOf(i + 40, script.length))
                    if (tail.trimStart().startsWith("(")) { repeat(6) { chars[i + it] = ' ' }; i += 5 }
                }
                c == 'a' && script.startsWith("await ", i) -> { repeat(6) { chars[i + it] = ' ' }; i += 5 }
            }
            i++
        }
        return String(chars)
    }
}
