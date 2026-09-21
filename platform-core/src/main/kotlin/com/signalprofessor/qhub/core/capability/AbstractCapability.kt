package com.signalprofessor.qhub.core.capability

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

abstract class AbstractCapability(
    final override val id: CapabilityId,
) : Capability {
    private val operationMutex = Mutex()
    private val mutableState = MutableStateFlow(CapabilityState.Uninitialized)
    private val mutableHealth = MutableStateFlow(CapabilityHealth(CapabilityState.Uninitialized))

    final override val state: StateFlow<CapabilityState> = mutableState.asStateFlow()
    final override val health: StateFlow<CapabilityHealth> = mutableHealth.asStateFlow()

    protected lateinit var context: CapabilityContext
        private set

    private var config = CapabilityConfig()

    final override suspend fun initialize(context: CapabilityContext) = operationMutex.withLock {
        requireState(CapabilityState.Uninitialized)
        this.context = context
        onInitialize(context)
        transitionTo(CapabilityState.Initialized)
    }

    final override suspend fun configure(config: CapabilityConfig) = operationMutex.withLock {
        require(mutableState.value in setOf(CapabilityState.Initialized, CapabilityState.Configured, CapabilityState.Paused)) {
            "${id.value} cannot be configured from ${mutableState.value}"
        }
        this.config = config
        onConfigure(config)
        transitionTo(CapabilityState.Configured)
    }

    final override suspend fun start() = operationMutex.withLock {
        require(mutableState.value in setOf(CapabilityState.Configured, CapabilityState.Paused, CapabilityState.Stopped)) {
            "${id.value} cannot start from ${mutableState.value}"
        }
        if (!config.enabled) {
            transitionTo(CapabilityState.Stopped, "Disabled by configuration")
            return@withLock
        }
        onStart()
        transitionTo(CapabilityState.Running)
    }

    final override suspend fun pause() = operationMutex.withLock {
        requireState(CapabilityState.Running)
        onPause()
        transitionTo(CapabilityState.Paused)
    }

    final override suspend fun stop() = operationMutex.withLock {
        if (mutableState.value == CapabilityState.Stopped) return@withLock
        require(mutableState.value != CapabilityState.Uninitialized) {
            "${id.value} cannot stop before initialization"
        }
        onStop()
        transitionTo(CapabilityState.Stopped)
    }

    protected fun markDegraded(message: String, diagnostics: Map<String, String> = emptyMap()) {
        transitionTo(CapabilityState.Degraded, message, diagnostics)
    }

    protected fun markError(message: String, diagnostics: Map<String, String> = emptyMap()) {
        transitionTo(CapabilityState.Error, message, diagnostics)
    }

    protected open suspend fun onInitialize(context: CapabilityContext) = Unit
    protected open suspend fun onConfigure(config: CapabilityConfig) = Unit
    protected open suspend fun onStart() = Unit
    protected open suspend fun onPause() = Unit
    protected open suspend fun onStop() = Unit

    private fun requireState(expected: CapabilityState) {
        require(mutableState.value == expected) {
            "${id.value} expected $expected but was ${mutableState.value}"
        }
    }

    private fun transitionTo(
        next: CapabilityState,
        message: String? = null,
        diagnostics: Map<String, String> = emptyMap(),
    ) {
        mutableState.value = next
        mutableHealth.value = CapabilityHealth(next, message, diagnostics)
    }
}
