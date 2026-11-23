package com.synapse.kernel.core

import java.util.concurrent.ConcurrentHashMap

/**
 * Sliding-window circuit breaker per (userId, processName) to prevent resurrection storms.
 * Default: > 3 attempts within 30s opens breaker for 5 minutes.
 */
class ResurrectionBreaker {

    private class WindowState {
        val timestamps = ArrayDeque<Long>(8)
        var openUntilMs: Long = 0L
    }

    private val states = ConcurrentHashMap<String, WindowState>()

    enum class Decision {
        ALLOW,
        OPENED_NOW,
        COOLDOWN
    }

    fun tryAcquire(
        processKey: String,
        nowMs: Long,
        windowMs: Long,
        threshold: Int,
        cooldownMs: Long
    ): Decision {
        val state = states.computeIfAbsent(processKey) { WindowState() }
        synchronized(state) {
            if (nowMs < state.openUntilMs) {
                return Decision.COOLDOWN
            }
            val cutoff = nowMs - windowMs
            while (state.timestamps.isNotEmpty() && state.timestamps.first() < cutoff) {
                state.timestamps.removeFirst()
            }
            if (state.timestamps.size >= threshold) {
                state.openUntilMs = nowMs + cooldownMs
                state.timestamps.clear()
                return Decision.OPENED_NOW
            }
            state.timestamps.addLast(nowMs)
            return Decision.ALLOW
        }
    }

    fun reset(processKey: String? = null) {
        if (processKey.isNullOrBlank()) {
            states.clear()
        } else {
            states.remove(processKey)
        }
    }

    fun isOpen(processKey: String, nowMs: Long): Boolean {
        val state = states[processKey] ?: return false
        synchronized(state) {
            return nowMs < state.openUntilMs
        }
    }
}
