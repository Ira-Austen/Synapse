package com.synapse.protocol

enum class PluginStatus {
    ACTIVE,
    DISABLED,
    DRY_RUN_BYPASSED,
    OTA_CHANGED_BYPASSED,
    UNSUPPORTED_ROM,
    HOOK_FAILED;

    companion object {
        fun fromString(raw: String?): PluginStatus =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: HOOK_FAILED
    }
}

data class PluginHealth(
    val name: String,
    val status: PluginStatus,
    val hookCount: Int = 0,
    val message: String = ""
)

data class KernelHealthReport(
    val kernelVersion: String,
    val apiLevel: Int,
    val frameworkName: String,
    val frameworkApiVersion: Int,
    val oomAdjusterImpl: String,
    val currentFingerprint: String,
    val cachedFingerprint: String,
    val otaChanged: Boolean,
    val policyRevision: Long,
    val activeRuleCount: Int,
    val auditCount: Int,
    val uptimeMs: Long,
    val plugins: List<PluginHealth>
)
