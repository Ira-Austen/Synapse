package com.synapse.protocol

enum class PushMode {
    ISOLATED_PUSH,
    SINGLE_PROCESS,
    KEEP_ALIVE_ONLY;

    companion object {
        fun fromString(raw: String?): PushMode =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: KEEP_ALIVE_ONLY
    }
}

enum class WakeType {
    SERVICE,
    BROADCAST,
    KEEP_ALIVE_ONLY;

    companion object {
        fun fromString(raw: String?): WakeType =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: KEEP_ALIVE_ONLY
    }
}

data class GlobalSettings(
    val masterEnabled: Boolean = true,
    val stealthEnabled: Boolean = true,
    val oemHansBypassEnabled: Boolean = true,
    val memoryDozeBypassEnabled: Boolean = true,
    val resurrectDelayMs: Long = 1500L,
    val breakerWindowMs: Long = 30_000L,
    val breakerThreshold: Int = 3,
    val breakerCooldownMs: Long = 300_000L
)

data class ProcessRule(
    val pkg: String,
    val userId: Int = 0,
    val appName: String = pkg,
    val enabled: Boolean = true,
    val pushMode: PushMode = PushMode.KEEP_ALIVE_ONLY,
    val targetProcess: String = pkg,
    val wakeComponent: String? = null,
    val wakeType: WakeType = WakeType.KEEP_ALIVE_ONLY,
    val wakeAction: String? = null,
    val keepAliveAdj: Int = 200,
    val protectProcState: Boolean = true,
    val exemptAospFreezer: Boolean = true,
    val exemptOemFreezer: Boolean = true,
    val memoryDozeBypass: Boolean = true,
    val allowResurrect: Boolean = true,
    val verifiedByAgent: Boolean = false,
    val aiSummary: String = ""
) {
    init {
        require(pkg.isNotBlank()) { "pkg must not be blank" }
        require(targetProcess.isNotBlank()) { "targetProcess must not be blank" }
        require(keepAliveAdj in MIN_SAFE_ADJ..MAX_SAFE_ADJ) {
            "keepAliveAdj must be in $MIN_SAFE_ADJ..$MAX_SAFE_ADJ (negative system adj is forbidden)"
        }
        require(userId >= 0) { "userId must be >= 0" }
    }

    val ruleKey: String get() = keyOf(userId, pkg)
    val processKey: String get() = processKeyOf(userId, targetProcess)

    companion object {
        const val MIN_SAFE_ADJ = 100
        const val MAX_SAFE_ADJ = 800

        fun keyOf(userId: Int, pkg: String): String = "$userId:$pkg"
        fun processKeyOf(userId: Int, processName: String): String = "$userId:$processName"
    }
}

/** Reserved slot for future Intent Firewall plugin. */
data class IntentRule(
    val id: String,
    val callerPkg: String,
    val targetPkg: String,
    val action: String? = null,
    val block: Boolean = true
)

/** Reserved slot for future Alarm Throttle plugin. */
data class AlarmRule(
    val pkg: String,
    val minIntervalMs: Long = 300_000L,
    val alignWindowMs: Long = 60_000L,
    val enabled: Boolean = true
)

data class KernelPolicyBundle(
    val version: Int = CURRENT_SCHEMA_VERSION,
    val revision: Long = 1L,
    val romFingerprint: String = "",
    val globalSettings: GlobalSettings = GlobalSettings(),
    val stealthAllowlistPkgs: Set<String> = DEFAULT_STEALTH_ALLOWLIST,
    val processRules: Map<String, ProcessRule> = emptyMap(),
    val componentBlockRules: Map<String, List<String>> = emptyMap(),
    val intentFirewallRules: List<IntentRule> = emptyList(),
    val alarmThrottleRules: Map<String, AlarmRule> = emptyMap()
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 3

        val DEFAULT_STEALTH_ALLOWLIST: Set<String> = linkedSetOf(
            "me.weishu.kernelsu",
            "me.bmax.apatch",
            "com.topjohnwu.magisk",
            "org.lsposed.manager",
            "io.github.jingmatrix.lsposed"
        )
    }
}
