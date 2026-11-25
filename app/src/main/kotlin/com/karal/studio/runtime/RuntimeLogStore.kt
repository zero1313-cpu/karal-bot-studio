package com.karal.studio.runtime

import com.karal.studio.core.security.SecretPolicy
import com.karal.studio.data.repository.LogRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class RuntimeEvent(
    val level: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
    val botId: String = "",
    val projectId: String = ""
)

/**
 * Live console backing both the in-memory screen and the persistent Room log table.
 * Every message is redacted before it reaches either sink.
 */
class RuntimeLogStore(private val repository: LogRepository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _events = MutableStateFlow<List<RuntimeEvent>>(emptyList())
    val events: StateFlow<List<RuntimeEvent>> = _events.asStateFlow()

    fun log(
        level: String,
        message: String,
        botId: String = "",
        projectId: String = "",
        sessionId: String = ""
    ) {
        val safe = SecretPolicy.redact(message)
        val event = RuntimeEvent(level, safe, botId = botId, projectId = projectId)
        _events.value = (_events.value + event).takeLast(300)
        scope.launch {
            runCatching { repository.log(level, safe, botId, projectId, sessionId) }
        }
    }

    fun clear() {
        _events.value = emptyList()
        scope.launch { runCatching { repository.clear() } }
    }
}
