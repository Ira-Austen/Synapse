package com.synapse.protocol

/**
 * Shared IPC contract between Manager App and `system_server` Magic Binder bridge.
 */
object IpcProtocol {
    const val PROTOCOL_VERSION = 1

    /**
     * High-entropy safe transaction code range inside Android's IBinder
     * FIRST_CALL_TRANSACTION (0x00000001)..LAST_CALL_TRANSACTION (0x00ffffff),
     * deliberately far from AOSP AMS codes and common OEM (Oplus 10001..20000, MIUI) custom codes.
     */
    const val MIN_SAFE_MAGIC_CODE = 0x00530000
    const val MAX_SAFE_MAGIC_CODE = 0x007EFFFF

    const val DEFAULT_MAGIC_CODE = 0x005A4E50
    const val DEFAULT_HEADER_MAGIC = 0x53594E31 // "SYN1"

    const val PREFS_NAME = "synapse_policy_store"
    const val PREFS_KEY_BUNDLE_JSON = "policy_bundle_json"
    const val PREFS_KEY_FINGERPRINT = "last_healthy_fingerprint"

    const val CMD_GET_HEALTH = 1
    const val CMD_PUSH_POLICY = 2
    const val CMD_DRAIN_AUDIT = 3
    const val CMD_RESET_BREAKER = 4
    const val CMD_DRAIN_NOTIFICATIONS = 5
    const val CMD_QUERY_PROCESSES = 6
    const val CMD_WAKE_PROCESS = 7
    const val CMD_REGISTER_OBSERVER = 8

    // One-way callback transaction & event bitmasks pushed from system_server to active foreground UI
    const val OBSERVER_TX_EVENT = 1
    const val OBSERVER_EVENT_NOTIFICATION = 1 shl 0
    const val OBSERVER_EVENT_PROCESS = 1 shl 1
    const val OBSERVER_EVENT_AUDIT = 1 shl 2

    const val STATUS_OK = 0
    const val STATUS_ERR_INVALID_CMD = -1
    const val STATUS_ERR_INVALID_PAYLOAD = -2
    const val STATUS_ERR_INTERNAL = -3

    fun isSafeMagicCode(code: Int): Boolean =
        code in MIN_SAFE_MAGIC_CODE..MAX_SAFE_MAGIC_CODE
}
