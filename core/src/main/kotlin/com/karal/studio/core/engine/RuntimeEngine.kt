package com.karal.studio.core.engine

import com.karal.studio.core.model.ActionType
import com.karal.studio.core.model.ExecutionContextData
import com.karal.studio.core.model.ExecutionPlan
import com.karal.studio.core.model.ExecutionStep
import com.karal.studio.core.security.SecretPolicy
import com.karal.studio.core.validation.PermissionEngine
import java.util.concurrent.ConcurrentHashMap

/** Token-bucket limiter plus per-command cooldown tracking. */
class RateLimiter {
    private val buckets = ConcurrentHashMap<String, Bucket>()
    private val lastUse = ConcurrentHashMap<String, Long>()

    private class Bucket(var tokens: Double, var timestamp: Long)

    @Synchronized
    fun tryAcquire(key: String, maxRequests: Int, windowMs: Long): Boolean {
        val now = System.currentTimeMillis()
        val bucket = buckets.getOrPut(key) { Bucket(maxRequests.toDouble(), now) }
        val elapsed = (now - bucket.timestamp).coerceAtLeast(0)
        bucket.tokens = (bucket.tokens + elapsed.toDouble() * maxRequests / windowMs)
            .coerceAtMost(maxRequests.toDouble())
        bucket.timestamp = now
        if (bucket.tokens < 1.0) return false
        bucket.tokens -= 1.0
        return true
    }

    @Synchronized
    fun cooldownRemaining(key: String, cooldownMs: Long): Long {
        if (cooldownMs <= 0) return 0L
        val now = System.currentTimeMillis()
        val previous = lastUse[key] ?: return 0L
        val remaining = cooldownMs - (now - previous)
        return if (remaining > 0) remaining else 0L
    }

    @Synchronized
    fun markUsed(key: String) { lastUse[key] = System.currentTimeMillis() }

    @Synchronized
    fun clear() { buckets.clear(); lastUse.clear() }
}

/** Hard bounds for every execution, independent of the editor that produced the plan. */
class ExecutionBudget(private val timeoutMs: Long, private val maxSteps: Int = CommandExecutor.MAX_STEPS) {
    private val started = System.currentTimeMillis()
    var stepsUsed: Int = 0
        private set

    fun consumeStep() {
        stepsUsed++
        if (stepsUsed > maxSteps) throw ExecutionLimitException("Step limit of $maxSteps exceeded.")
        if (System.currentTimeMillis() - started > timeoutMs) {
            throw ExecutionLimitException("Execution timeout of ${timeoutMs}ms exceeded.")
        }
    }

    fun remainingMs(): Long = (timeoutMs - (System.currentTimeMillis() - started)).coerceAtLeast(0)
}

class ExecutionLimitException(message: String) : Exception(message)

/** Project-isolated, size-bounded variable storage. Never use this for credentials. */
class VariableStore(private val namespace: String = "default") {
    private val values = ConcurrentHashMap<String, String>()

    private fun key(name: String) = "$namespace:$name"

    fun get(name: String): String? = values[key(name)]
    fun set(name: String, value: String) {
        require(name.length in 1..128) { "Variable name must be 1-128 characters." }
        require(value.length <= 8_192) { "Variable value must be at most 8192 characters." }
        require(!SecretPolicy.containsSecret(value)) { "Credential-looking values cannot be stored as variables. Use the secure store." }
        values[key(name)] = value
    }

    fun remove(name: String) { values.remove(key(name)) }
    fun snapshot(): Map<String, String> = values.filterKeys { it.startsWith("$namespace:") }
        .mapKeys { it.key.removePrefix("$namespace:") }

    fun clear() { values.keys.filter { it.startsWith("$namespace:") }.forEach { values.remove(it) } }
}

data class RuntimeStats(
    var executions: Long = 0,
    var successes: Long = 0,
    var failures: Long = 0,
    var totalDurationMs: Long = 0
) {
    val averageDurationMs: Long get() = if (executions == 0L) 0L else totalDurationMs / executions
}

class RuntimeMetrics {
    private val stats = ConcurrentHashMap<String, RuntimeStats>()

    fun record(commandId: String, success: Boolean, durationMs: Long) {
        val entry = stats.getOrPut(commandId) { RuntimeStats() }
        synchronized(entry) {
            entry.executions++
            if (success) entry.successes++ else entry.failures++
            entry.totalDurationMs += durationMs
        }
    }

    fun snapshot(): Map<String, RuntimeStats> = stats.mapValues { (_, v) -> synchronized(v) { v.copy() } }
}

/**
 * The only bridge between the shared engine and Discord. The Android app implements this with
 * verified REST calls; scripts never receive the token or raw HTTP client.
 */
interface DiscordAdapter {
    fun reply(message: String, ephemeral: Boolean): Result<Unit>
    fun sendMessage(channelId: String, message: String): Result<Unit>
    fun sendEmbed(channelId: String, title: String, description: String): Result<Unit>
    fun editMessage(channelId: String, messageId: String, message: String): Result<Unit>
    fun deleteMessage(channelId: String, messageId: String): Result<Unit>
    fun moderate(action: ActionType, params: Map<String, String>, context: ExecutionContextData): Result<Unit>
    fun httpRequest(url: String, method: String, body: String): Result<String>
    /** In-memory, project-scoped variable storage. Override to persist variables per project. */
    fun setVariable(name: String, value: String): Result<Unit> = Result.success(Unit)
    /** Acknowledges an interaction within Discord's three-second window when needed. */
    fun defer(): Result<Unit> = Result.success(Unit)
    fun log(level: String, message: String)
}

data class ExecutionResult(
    val success: Boolean,
    val message: String = "",
    val error: String = "",
    val stepsExecuted: Int = 0,
    val durationMs: Long = 0
)

/** Shared interpreter used by visual, script and template commands without a script runtime. */
class CommandExecutor(
    private val adapter: DiscordAdapter,
    private val variables: VariableStore,
    private val metrics: RuntimeMetrics,
    private val limiter: RateLimiter
) {
    companion object { const val MAX_STEPS = 500 }

    fun execute(
        plan: ExecutionPlan,
        context: ExecutionContextData,
        rateLimit: Int = 5,
        rateWindowMs: Long = 1_000L
    ): ExecutionResult {
        val started = System.currentTimeMillis()
        fun finish(success: Boolean, message: String = "", error: String = "", steps: Int = 0) = ExecutionResult(
            success, message, error, steps, System.currentTimeMillis() - started
        ).also { metrics.record(plan.commandId, success, it.durationMs) }

        val permission = PermissionEngine.evaluate(
            com.karal.studio.core.model.CommandDefinition(
                id = plan.commandId,
                projectId = "",
                name = plan.commandId,
                trigger = plan.trigger,
                permissions = plan.permissions,
                timeoutMs = plan.timeoutMs
            ),
            context
        )
        if (!permission.allowed) return finish(false, error = permission.reason)

        val cooldownKey = "${plan.commandId}:${context.userId}"
        val remaining = limiter.cooldownRemaining(cooldownKey, plan.permissions.cooldownMs)
        if (remaining > 0) return finish(false, error = "Please wait ${(remaining + 999) / 1000}s before using this command.")
        if (!limiter.tryAcquire("cmd:${plan.commandId}:${context.userId}", rateLimit, rateWindowMs)) {
            return finish(false, error = "Rate limit exceeded. Try again shortly.")
        }
        limiter.markUsed(cooldownKey)

        val budget = ExecutionBudget(plan.timeoutMs)
        var steps = 0
        try {
            steps = runSteps(plan.steps, context, budget)
            return finish(true, steps = steps)
        } catch (e: ExecutionLimitException) {
            adapter.log("ERROR", SecretPolicy.redact(e.message ?: "Execution limit exceeded"))
            return finish(false, error = e.message ?: "Execution limit exceeded", steps = steps)
        } catch (e: Exception) {
            adapter.log("ERROR", SecretPolicy.redact(e.message ?: "Unknown execution error"))
            return finish(false, error = SecretPolicy.redact(e.message ?: "Unknown execution error"), steps = steps)
        }
    }

    private fun runSteps(
        steps: List<ExecutionStep>,
        context: ExecutionContextData,
        budget: ExecutionBudget
    ): Int {
        var executed = 0
        for (step in steps) {
            budget.consumeStep()
            executed++
            val resolvedContext = context.copy(variables = variables.snapshot() + context.variables)
            when (step) {
                is ExecutionStep.Action -> executeAction(step, resolvedContext)
                is ExecutionStep.Branch -> {
                    val condition = PermissionEngine.evaluateCondition(step.condition.name, step.params, resolvedContext)
                    executed += runSteps(if (condition) step.thenSteps else step.elseSteps, resolvedContext, budget)
                }
                is ExecutionStep.Delay -> {
                    val ms = step.durationMs.coerceIn(0L, 60_000L)
                    if (ms > budget.remainingMs()) throw ExecutionLimitException("Delay exceeds execution budget.")
                    Thread.sleep(ms)
                }
                is ExecutionStep.Repeat -> repeat(step.count.coerceIn(1, 100)) {
                    executed += runSteps(step.steps, resolvedContext, budget)
                }
                is ExecutionStep.Stop -> return executed
            }
        }
        return executed
    }

    private fun executeAction(step: ExecutionStep.Action, context: ExecutionContextData) {
        val p = step.params.mapValues { (_, v) -> resolve(v, context) }
        val result = when (step.action) {
            ActionType.REPLY -> adapter.reply(p["message"].orEmpty(), p["ephemeral"]?.toBoolean() ?: false)
            ActionType.SEND_MESSAGE -> adapter.sendMessage(p["channelId"]?.ifBlank { context.channelId } ?: context.channelId, p["message"].orEmpty())
            ActionType.SEND_EMBED -> adapter.sendEmbed(
                p["channelId"]?.ifBlank { context.channelId } ?: context.channelId,
                p["title"].orEmpty(), p["description"].orEmpty()
            )
            ActionType.EDIT_MESSAGE -> adapter.editMessage(
                p["channelId"]?.ifBlank { context.channelId } ?: context.channelId,
                p["messageId"].orEmpty(), p["message"].orEmpty()
            )
            ActionType.DELETE_MESSAGE -> adapter.deleteMessage(
                p["channelId"]?.ifBlank { context.channelId } ?: context.channelId, p["messageId"].orEmpty()
            )
            ActionType.SET_VARIABLE -> {
                p["variable"]?.let { name -> p["value"]?.let { value -> variables.set(name, value) } }
                Result.success(Unit)
            }
            ActionType.GET_VARIABLE -> {
                p["variable"]?.let { adapter.log("INFO", variables.get(it).orEmpty()) }
                Result.success(Unit)
            }
            ActionType.HTTP_REQUEST -> {
                val url = p["url"].orEmpty()
                if (!url.startsWith("https://")) Result.failure(IllegalArgumentException("Only HTTPS URLs are allowed."))
                else adapter.httpRequest(url, p["method"] ?: "GET", p["body"].orEmpty()).map { Unit }
            }
            ActionType.LOG_EVENT -> { adapter.log("INFO", p["message"].orEmpty()); Result.success(Unit) }
            else -> adapter.moderate(step.action, p, context)
        }
        result.onFailure { adapter.log("ERROR", SecretPolicy.redact(it.message ?: "Action failed")) }
    }

    private fun resolve(template: String, context: ExecutionContextData): String {
        val replacements = mapOf(
            "{user}" to context.userId,
            "{user.name}" to context.userId,
            "{channel}" to context.channelId,
            "{guild}" to context.guildId,
            "{args}" to context.args.joinToString(" ")
        ) + context.args.mapIndexed { i, arg -> "{arg${i + 1}}" to arg } +
            context.variables.map { (k, v) -> "{var:$k}" to v }
        return replacements.entries.fold(SecretPolicy.redact(template)) { acc, (k, v) -> acc.replace(k, v) }
    }
}
