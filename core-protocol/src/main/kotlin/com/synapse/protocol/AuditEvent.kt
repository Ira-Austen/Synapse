package com.synapse.protocol

enum class AuditEventType {
    KERNEL_BOOT,
    POLICY_APPLIED,
    ADJ_CLAMPED,
    PROC_STATE_PROTECTED,
    FREEZE_BLOCKED,
    HANS_BYPASSED,
    DOZE_BPF_BYPASS,
    RESURRECT_SCHEDULED,
    RESURRECT_OK,
    RESURRECT_SKIPPED_STOPPED,
    RESURRECT_SKIPPED_ALIVE,
    RESURRECT_FAILED,
    BREAKER_OPEN,
    BREAKER_RESET,
    STEALTH_FILTERED,
    OTA_BYPASSED,
    HOOK_ERROR;

    companion object {
        fun fromString(raw: String?): AuditEventType =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: HOOK_ERROR
    }
}

data class AuditEvent(
    val seq: Long,
    val timestampMs: Long,
    val type: AuditEventType,
    val pkg: String = "",
    val processName: String = "",
    val userId: Int = 0,
    val detail: String = ""
)
