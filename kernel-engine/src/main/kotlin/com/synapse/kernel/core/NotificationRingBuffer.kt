package com.synapse.kernel.core

import com.synapse.protocol.NotificationHistoryRecord
import java.util.concurrent.atomic.AtomicLong

/**
 * Fixed-capacity bounded ring buffer (128 slots, zero dynamic reallocation) storing
 * passively observed push notifications inside `system_server`.
 */
class NotificationRingBuffer(val capacity: Int = DEFAULT_CAPACITY) {

    init {
        require(capacity > 0 && (capacity and (capacity - 1)) == 0) {
            "capacity must be a positive power of two"
        }
    }

    private val mask = capacity - 1
    private val slots = arrayOfNulls<NotificationHistoryRecord>(capacity)
    private val seqGen = AtomicLong(0L)
    private val drainCursor = AtomicLong(0L)

    fun record(
        pkg: String,
        userId: Int,
        channelId: String,
        title: String,
        text: String,
        subText: String = "",
        isOngoing: Boolean = false,
        timestampMs: Long = System.currentTimeMillis()
    ): NotificationHistoryRecord {
        val seq = seqGen.incrementAndGet()
        val rec = NotificationHistoryRecord(
            seq = seq,
            timestampMs = timestampMs,
            pkg = pkg,
            userId = userId,
            channelId = channelId.take(64),
            title = title.take(120),
            text = text.take(280),
            subText = subText.take(120),
            isOngoing = isOngoing
        )
        slots[(seq - 1L).toInt() and mask] = rec
        return rec
    }

    fun drain(pkgFilter: String? = null, limit: Int = capacity): List<NotificationHistoryRecord> {
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

        val out = ArrayList<NotificationHistoryRecord>(minOf(limit, (latest - startSeq + 1L).toInt()))
        var s = startSeq
        while (s <= latest && out.size < limit) {
            val item = slots[(s - 1L).toInt() and mask]
            if (item != null && item.seq == s) {
                if (pkgFilter.isNullOrBlank() || item.pkg == pkgFilter) {
                    out.add(item)
                }
            }
            s++
        }
        return out
    }

    fun totalRecorded(): Int = minOf(seqGen.get(), capacity.toLong()).toInt()

    companion object {
        const val DEFAULT_CAPACITY = 128
    }
}
