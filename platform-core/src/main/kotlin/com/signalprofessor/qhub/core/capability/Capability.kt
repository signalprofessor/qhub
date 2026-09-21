package com.signalprofessor.qhub.core.capability

import com.signalprofessor.qhub.core.event.EventFactory
import com.signalprofessor.qhub.core.event.EventSink
import kotlinx.coroutines.flow.StateFlow

@JvmInline
value class CapabilityId(val value: String) {
    init {
        require(value.isNotBlank()) { "CapabilityId must not be blank" }
    }
}

enum class CapabilityState {
    Uninitialized,
    Initialized,
    Configured,
    Running,
    Paused,
    Degraded,
    Stopped,
    Error,
}

data class CapabilityHealth(
    val state: CapabilityState,
    val message: String? = null,
    val diagnostics: Map<String, String> = emptyMap(),
)

data class CapabilityConfig(
    val enabled: Boolean = true,
    val parameters: Map<String, String> = emptyMap(),
)

data class CapabilityContext(
    val events: EventSink,
    val eventFactory: EventFactory,
)

interface Capability {
    val id: CapabilityId
    val state: StateFlow<CapabilityState>
    val health: StateFlow<CapabilityHealth>

    suspend fun initialize(context: CapabilityContext)
    suspend fun configure(config: CapabilityConfig)
    suspend fun start()
    suspend fun pause()
    suspend fun stop()
}
