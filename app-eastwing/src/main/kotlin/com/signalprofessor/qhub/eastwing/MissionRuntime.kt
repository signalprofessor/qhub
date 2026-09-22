package com.signalprofessor.qhub.eastwing

import android.content.Context

/** Process-local owner of recording, independent of an Activity instance. */
internal class MissionRuntime private constructor(context: Context) {
    private var listenerOwner: Any? = null
    @Volatile private var stateListener: ((MissionState) -> Unit)? = null
    @Volatile private var liveListener: ((String) -> Unit)? = null
    var lastState: MissionState = MissionState("Ready")
        private set
    var liveStatus: String = "Live OFF"
        private set

    val liveSender = LiveTelemetrySender(::publishLiveStatus)
    val recorder = MissionRecorder(context.applicationContext, ::publishState, liveSender::enqueue)

    fun attach(owner: Any, onState: (MissionState) -> Unit, onLiveStatus: (String) -> Unit) {
        listenerOwner = owner
        stateListener = onState
        liveListener = onLiveStatus
        onState(lastState)
        onLiveStatus(liveStatus)
    }

    fun detach(owner: Any) {
        if (listenerOwner !== owner) return
        listenerOwner = null
        stateListener = null
        liveListener = null
    }

    fun publishState(state: MissionState) {
        lastState = state
        stateListener?.invoke(state)
    }

    private fun publishLiveStatus(status: String) {
        liveStatus = status
        liveListener?.invoke(status)
    }

    companion object {
        @Volatile private var instance: MissionRuntime? = null

        fun get(context: Context): MissionRuntime = instance ?: synchronized(this) {
            instance ?: MissionRuntime(context.applicationContext).also { instance = it }
        }
    }
}
