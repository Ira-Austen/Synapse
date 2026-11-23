package com.synapse.kernel.core

import com.synapse.protocol.AuditEvent
import com.synapse.protocol.AuditEventType
import java.util.concurrent.atomic.AtomicLong

/**
 * Fixed-capacity ring buffer (~40KB resident ceiling, zero dynamic reallocation)
 * to keep `system_server` memory footprint strictly bounded under Android 17 PMGD.
 */
class AuditRingBuffer(val capacity: Int = DEFAULT_CAPACITY) {

    init {
        require(capacity > 0 && (capacity and (capacity - 1)) == 0) {
            "capacity must be a positive power of two"
        }
    }

    private val mask = capacity - 1
    private val slots = arrayOfNulls<AuditEvent>(capacity)
    private val seqGen = AtomicLong(0L)
    private val drainCursor = AtomicLong(0L)

    fun record(
        type: AuditEventType,
        pkg: String = "",
        processName: String = "",
        userId: Int = 0,
        detail: String = "",
        timestampMs: Long = System.currentTimeMillis()
    ): AuditEvent {
        val seq = seqGen.incrementAndGet()
        val event = AuditEvent(
            seq = seq,
            timestampMs = timestampMs,
            type = type,
            pkg = pkg,
            processName = processName,
            userId = userId,
            detail = if (detail.length > MAX_DETAIL_LEN) detail.substring(0, MAX_DETAIL_LEN) else detail
        )
        slots[(seq - 1L).toInt() and mask] = event
        return event
    }

    /**
     * Drains all unseen events since the previous drain call (or all currently retained events if overwritten).
     */
    fun drain(pkgFilter: String? = null, limit: Int = capacity): List<AuditEvent> {
        val latest = seqGen.get()
        if (latest == 0L) return emptyList()
        val earliestRetained = maxOf(1L, latest - capacity + 1L)
        val startSeq = if (pkgFilter.isNullOrBlank()) {
            val prev = drainCursor.getAndSet(latest)
            maxOf(earliestRetained, prev + 1L)
        } else {
            earliestRetained
        }
        if (startSeq > latest) return emptyList()

        val out = ArrayList<AuditEvent>(minOf(limit, (latest - startSeq + 1L).toInt()))
        var s = startSeq
        while (s <= latest && out.size < limit) {
            val ev = slots[(s - 1L).toInt() and mask]
            if (ev != null && ev.seq == s) {
                if (pkgFilter.isNullOrBlank() || ev.pkg == pkgFilter) {
                    out.add(ev)
                }
            }
            s++
        }
        return out
    }

    fun snapshotRecent(limit: Int = 64, pkgFilter: String? = null): List<AuditEvent> {
        val latest = seqGen.get()
        if (latest == 0L) return emptyList()
        val earliest = maxOf(1L, latest - capacity + 1L)
        val out = ArrayList<AuditEvent>(minOf(limit, capacity))
        var s = latest
        while (s >= earliest && out.size < limit) {
            val ev = slots[(s - 1L).toInt() and mask]
            if (ev != null && ev.seq == s) {
                if (pkgFilter.isNullOrBlank() || ev.pkg == pkgFilter) {
                    out.add(ev)
                }
            }
            s--
        }
        out.reverse()
        return out
    }

    fun totalRecorded(): Int = minOf(seqGen.get(), capacity.toLong()).toInt()

    companion object {
        const val DEFAULT_CAPACITY = 512
        const val MAX_DETAIL_LEN = 256
    }
}
