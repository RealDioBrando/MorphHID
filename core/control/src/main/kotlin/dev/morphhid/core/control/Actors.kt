package dev.morphhid.core.control

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** Who is performing an operation: a human via the UI, or an agent adapter. */
sealed interface Actor {
    val id: String

    data class Human(override val id: String = "human-ui") : Actor

    data class Agent(
        override val id: String,
        /** Adapter kind: "mcp", "aidl", "appfunctions", "intent". */
        val transport: String,
    ) : Actor
}

sealed interface OpResult {
    data object Ok : OpResult
    data class Denied(val reason: String) : OpResult
    data class Error(val message: String) : OpResult

    companion object {
        fun okUnless(denied: OpResult?): OpResult = denied ?: Ok
    }
}

data class AuditEvent(
    val timestampMs: Long,
    val actorId: String,
    val action: String,
    val target: String? = null,
    val result: String,
    val detail: String? = null,
)

interface AuditLog {
    fun record(event: AuditEvent)
    fun recent(limit: Int = 50): List<AuditEvent>
    val events: SharedFlow<AuditEvent>
}

/** In-memory ring-buffer audit log; the app layer adds persistence. */
class InMemoryAuditLog(
    private val capacity: Int = 500,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : AuditLog {
    private val buffer = ArrayDeque<AuditEvent>(capacity)
    private val _events = MutableSharedFlow<AuditEvent>(extraBufferCapacity = 128)

    override val events: SharedFlow<AuditEvent> = _events

    override fun record(event: AuditEvent) {
        synchronized(buffer) {
            if (buffer.size >= capacity) buffer.removeFirst()
            buffer.addLast(event)
        }
        _events.tryEmit(event)
    }

    override fun recent(limit: Int): List<AuditEvent> =
        synchronized(buffer) { buffer.toList().takeLast(limit) }
}