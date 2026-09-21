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

    fun replay(consumer: (EventEnvelope) -> Unit) {
        while (true) consumer(next() ?: return)
    }
}
