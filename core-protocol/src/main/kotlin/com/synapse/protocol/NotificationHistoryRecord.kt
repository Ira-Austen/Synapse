package com.synapse.protocol

/**
 * Immutable notification push record captured passively inside `system_server`
 * without requiring `NotificationListenerService` or mutating `Settings.Secure`.
 */
data class NotificationHistoryRecord(
    val seq: Long,
    val timestampMs: Long,
    val pkg: String,
    val userId: Int = 0,
    val channelId: String = "",
    val title: String = "",
    val text: String = "",
    val subText: String = "",
    val isOngoing: Boolean = false
)
