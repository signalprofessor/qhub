package com.signalprofessor.qhub.log

import com.signalprofessor.qhub.core.event.EventEnvelope

class EventReplay(
    private val events: List<EventEnvelope>,
) {
    private var index = 0

    val size: Int get() = events.size
    val position: Int get() = index

    fun reset() {
        index = 0
    }

    fun next(): EventEnvelope? = events.getOrNull(index)?.also { index += 1 }

    /** Delay until the next recorded event, based on monotonic sensor timing. */
    fun delayUntilNextMillis(): Long {
        val previous = events.getOrNull(index - 1) ?: return 0
        val upcoming = events.getOrNull(index) ?: return 0
        return ((upcoming.timestamp.monotonicNanos - previous.timestamp.monotonicNanos)
            .coerceAtLeast(0) / 1_000_000)
    }

    fun replay(consumer: (EventEnvelope) -> Unit) {
        while (true) consumer(next() ?: return)
    }
}
