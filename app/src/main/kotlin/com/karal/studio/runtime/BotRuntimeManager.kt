package com.karal.studio.runtime

import com.karal.studio.core.compiler.CommandCompiler
import com.karal.studio.core.engine.RateLimiter
import com.karal.studio.core.engine.RuntimeMetrics
import com.karal.studio.core.engine.VariableStore
import com.karal.studio.core.json.Json
import com.karal.studio.data.db.KaralDatabase
import com.karal.studio.data.db.RuntimeSessionEntity
import com.karal.studio.data.repository.BotRepository
import com.karal.studio.data.repository.CommandRepository
import com.karal.studio.data.repository.CommandRepositoryImpl
import com.karal.studio.data.repository.ProjectRepository
import com.karal.studio.discord.DiscordGateway
import com.karal.studio.discord.DiscordRest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class RuntimeState { STOPPED, STARTING, CONNECTING, READY, RECONNECTING, ERROR }

data class RuntimeSnapshot(
    val botId: String,
    val projectId: String = "",
    val state: RuntimeState = RuntimeState.STOPPED,
    val startedAt: Long = 0L,
    val lastError: String = "",
    val uptimeMs: Long = 0L,
    val serverCount: Int = 0
)

/**
 * Owns one gateway connection per enabled bot. Each bot has isolated variables, metrics and
 * rate-limit buckets; stopping a bot cancels only its own scope.
 */
class BotRuntimeManager(
    private val db: KaralDatabase,
    private val bots: BotRepository,
    private val commands: CommandRepositoryImpl,
    private val projects: ProjectRepository,
    private val logs: RuntimeLogStore
) {
    private val sessions = ConcurrentHashMap<String, RuntimeSession>()
    private val _snapshots = MutableStateFlow<Map<String, RuntimeSnapshot>>(emptyMap())
    val snapshots: StateFlow<Map<String, RuntimeSnapshot>> = _snapshots.asStateFlow()

    private class RuntimeSession(
        val scope: CoroutineScope,
        var gateway: DiscordGateway? = null,
        var projectId: String = "",
        var sessionId: String = "",
        var startedAt: Long = 0L,
        var state: RuntimeState = RuntimeState.STOPPED,
        var lastError: String = "",
        var serverCount: Int = 0,
        var monitor: Job? = null
    )

    fun snapshot(botId: String): RuntimeSnapshot =
        _snapshots.value[botId] ?: RuntimeSnapshot(botId)

    fun isRunning(botId: String): Boolean =
        snapshot(botId).state in setOf(RuntimeState.STARTING, RuntimeState.CONNECTING, RuntimeState.READY, RuntimeState.RECONNECTING)

    fun runningBotIds(): Set<String> = _snapshots.value.filter { isRunning(it.key) }.keys

    fun start(botId: String) {
        val existing = sessions[botId]
        if (existing != null) {
            if (existing.state in setOf(RuntimeState.ERROR, RuntimeState.STOPPED)) {
                sessions.remove(botId)
                existing.gateway?.disconnect()
                existing.monitor?.cancel()
                existing.scope.cancel()
            } else {
                return
            }
        }
        val session = RuntimeSession(
            CoroutineScope(SupervisorJob() + Dispatchers.IO),
            sessionId = UUID.randomUUID().toString(),
            startedAt = System.currentTimeMillis()
        )
        sessions[botId] = session
        publish(botId, session)
        session.scope.launch {
            runCatching { initialize(botId, session) }.onFailure { error ->
                logs.log("ERROR", "Failed to start bot: ${error.message ?: "unknown error"}", botId)
                session.gateway?.disconnect()
                session.monitor?.cancel()
                update(botId, session, RuntimeState.ERROR, error.message.orEmpty())
                sessions.remove(botId)
                delay(500L)
                session.scope.cancel()
            }
        }
    }

    private suspend fun initialize(botId: String, session: RuntimeSession) {
        val bot = bots.get(botId)
            ?: throw IllegalStateException("Bot configuration not found.")
        session.projectId = bot.projectId
        publish(botId, session)
        val project = projects.get(bot.projectId)
        val token = bots.token(botId)
            ?: throw IllegalStateException("No stored bot token. Add the token in Project settings first.")
        update(botId, session, RuntimeState.STARTING)

        val rest = DiscordRest { token }
        val variables = VariableStore("project:${bot.projectId}:bot:$botId")
        val metrics = RuntimeMetrics()
        val limiter = RateLimiter()
        val executable = commands.observe(bot.projectId).first()
            .filter { it.enabled && it.approved }
            .mapNotNull { entity ->
                runCatching {
                    val definition = CommandRepository.toDefinition(entity)
                    ExecutableCommand(definition, CommandCompiler.compile(definition))
                }.onFailure { error ->
                    logs.log("WARN", "Skipped command ${entity.name}: ${error.message}", botId, bot.projectId, session.sessionId)
                }.getOrNull()
            }

        val applicationId = project?.applicationId.orEmpty()
        if (applicationId.isNotBlank()) {
            val slash = executable.filter { it.definition.trigger.type.name == "SLASH_COMMAND" }
                .map { it.definition }
            rest.registerCommands(applicationId, slash).onFailure { error ->
                logs.log("WARN", "Slash command registration failed: ${error.message}", botId, bot.projectId, session.sessionId)
            }.onSuccess { count ->
                logs.log("INFO", "Registered $count slash command(s).", botId, bot.projectId, session.sessionId)
            }
        } else {
            logs.log("WARN", "Application ID is empty; slash commands were not registered.", botId, bot.projectId, session.sessionId)
        }

        val router = InteractionRouter(
            commands = { executable },
            variables = variables,
            metrics = metrics,
            limiter = limiter,
            logger = logs,
            adapterFactory = { target ->
                DiscordAdapterImpl(rest, target, variables, logs)
            }
        )
        val gateway = DiscordGateway(
            token = token,
            intents = bot.intents,
            rest = rest,
            onEvent = { event ->
                session.scope.launch { router.handle(event) }
            },
            onState = { rawState ->
                val mapped = mapState(rawState)
                val error = if (mapped == RuntimeState.ERROR) rawState.removePrefix("ERROR: ") else ""
                update(botId, session, mapped, error)
                logs.log(
                    if (mapped == RuntimeState.ERROR) "ERROR" else "INFO",
                    "Gateway $rawState",
                    botId, bot.projectId, session.sessionId
                )
            }
        )
        session.gateway = gateway
        gateway.connect()
        persist(botId, bot.projectId, session)
        session.monitor = session.scope.launch {
            while (isActive) {
                delay(30_000L)
                rest.guildCount().onSuccess { count ->
                    session.serverCount = count
                    publish(botId, session)
                }
                persist(botId, bot.projectId, session)
            }
        }
    }

    fun stop(botId: String) {
        val session = sessions.remove(botId) ?: return
        session.gateway?.disconnect()
        session.monitor?.cancel()
        update(botId, session, RuntimeState.STOPPED)
        val projectId = _snapshots.value[botId]?.projectId.orEmpty()
        session.scope.launch { persist(botId, projectId, session, stopped = true) }
        session.scope.launch {
            delay(1_000L)
            session.scope.cancel()
        }
    }

    fun stopAll() = runningBotIds().forEach { stop(it) }

    suspend fun restoreAutoStart() {
        val enabled = bots.observeAll().first().filter { it.enabled }
        enabled.forEach { start(it.id) }
        if (enabled.isEmpty()) {
            logs.log("INFO", "No bots are enabled for automatic startup.")
        }
    }

    private fun mapState(raw: String): RuntimeState = when {
        raw == "READY" || raw.startsWith("READY") -> RuntimeState.READY
        raw == "CONNECTED" || raw == "IDENTIFYING" || raw == "RESUMING" -> RuntimeState.CONNECTING
        raw.startsWith("RECONNECTING") -> RuntimeState.RECONNECTING
        raw.startsWith("ERROR") -> RuntimeState.ERROR
        raw == "STOPPED" -> RuntimeState.STOPPED
        else -> RuntimeState.CONNECTING
    }

    private fun update(
        botId: String,
        session: RuntimeSession,
        state: RuntimeState,
        error: String = ""
    ) {
        session.state = state
        session.lastError = error
        publish(botId, session)
        session.scope.launch {
            runCatching {
                val projectId = bots.get(botId)?.projectId.orEmpty()
                persist(botId, projectId, session, stopped = state == RuntimeState.STOPPED)
            }
        }
    }

    private fun publish(botId: String, session: RuntimeSession) {
        val previous = _snapshots.value[botId]
        _snapshots.value = _snapshots.value + (botId to RuntimeSnapshot(
            botId = botId,
            projectId = previous?.projectId?.ifBlank { session.projectId } ?: session.projectId,
            state = session.state,
            startedAt = session.startedAt,
            lastError = session.lastError,
            uptimeMs = if (session.startedAt == 0L) 0L else System.currentTimeMillis() - session.startedAt,
            serverCount = session.serverCount
        ))
    }

    private suspend fun persist(
        botId: String,
        projectId: String,
        session: RuntimeSession,
        stopped: Boolean = false
    ) {
        runCatching {
            db.sessionDao().upsert(
                RuntimeSessionEntity(
                    id = session.sessionId,
                    botId = botId,
                    projectId = projectId,
                    state = session.state.name,
                    startedAt = session.startedAt,
                    stoppedAt = if (stopped) System.currentTimeMillis() else null,
                    lastError = session.lastError,
                    uptimeMs = if (session.startedAt == 0L) 0L else System.currentTimeMillis() - session.startedAt,
                    serverCount = session.serverCount
                )
            )
        }
    }

    /** Called by the service after a bot is resolved so snapshots can carry the project id. */
    fun bindProject(botId: String, projectId: String) {
        val session = sessions[botId] ?: return
        session.projectId = projectId
        publish(botId, session)
    }

    fun diagnostics(): String = buildString {
        appendLine("Active bot sessions: ${sessions.size}")
        _snapshots.value.values.forEach { snapshot ->
            appendLine("- ${snapshot.botId}: ${snapshot.state.name}, uptime ${snapshot.uptimeMs / 1000}s, servers ${snapshot.serverCount}")
        }
        appendLine("Core JSON round-trip: " + runCatching {
            Json.stringify(Json.parse(Json.stringify(Json.obj("ok" to Json.bool(true)))))
        }.getOrDefault("FAILED"))
    }
}
