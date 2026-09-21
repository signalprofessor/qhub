package com.signalprofessor.qhub.eastwing

import android.os.SystemClock
import com.signalprofessor.qhub.core.event.EventTimestamp
import com.signalprofessor.qhub.core.event.QhubClock

/** Android clock adapter used by live capabilities. */
object AndroidQhubClock : QhubClock {
    override fun now(): EventTimestamp = EventTimestamp(
        monotonicNanos = SystemClock.elapsedRealtimeNanos(),
        utcEpochMillis = System.currentTimeMillis(),
    )
}
