package com.synapse.agent.parser

data class ExitReasonEntry(
    val pid: Int,
    val processName: String,
    val reason: String,
    val description: String,
    val isMemoryLimiterKill: Boolean
)

data class JobPendingStat(
    val jobId: Int,
    val reasons: String
)

data class HansEventSummary(
    val freezeCount: Int,
    val socketDestroyCount: Int,
    val sampleLines: List<String>
)

/**
 * Parsers for Android 15–17 telemetry (`dumpsys activity exit-info`, `am memory-limiter status`,
 * `dumpsys jobscheduler`) and ColorOS `OplusHansManager` logs.
 */
object Android17TelemetryParser {

    private val EXIT_INFO_LINE = Regex(
        """pid=(\d+)\s+process=([^\s]+).*?reason=([^\s]+).*?(?:description=(.*))?$"""
    )

    /**
     * Parses `dumpsys activity exit-info <pkg>`.
     * On Android 17, Memory Limiter terminations report `REASON_OTHER` with description containing
     * `"MemoryLimiter:AnonSwap"`.
     */
    fun parseExitInfo(raw: String, maxEntries: Int = 10): List<ExitReasonEntry> {
        val out = ArrayList<ExitReasonEntry>()
        for (line in raw.lineSequence()) {
            val trimmed = line.trim()
            val m = EXIT_INFO_LINE.find(trimmed) ?: continue
            val pid = m.groupValues[1].toIntOrNull() ?: -1
            val proc = m.groupValues[2]
            val reason = m.groupValues[3]
            val desc = m.groupValues.getOrElse(4) { "" }.trim()
            val isMemLimiter = desc.contains("MemoryLimiter", ignoreCase = true) ||
                desc.contains("AnonSwap", ignoreCase = true)
            out.add(
                ExitReasonEntry(
                    pid = pid,
                    processName = proc,
                    reason = reason,
                    description = desc,
                    isMemoryLimiterKill = isMemLimiter
                )
            )
            if (out.size >= maxEntries) break
        }
        return out
    }

    /**
     * Parses `dumpsys jobscheduler <pkg>` to extract pending job constraint reasons
     * (cross-package equivalent of Android 17 `JobDebugInfo.getPendingJobReasonStats`).
     */
    fun parsePendingJobReasons(raw: String, maxJobs: Int = 8): List<JobPendingStat> {
        val out = ArrayList<JobPendingStat>()
        var currentJobId = -1
        val jobHeader = Regex("""JOB #[a-zA-Z0-9]+/(-?\d+)""")
        for (line in raw.lineSequence()) {
            val trimmed = line.trim()
            jobHeader.find(trimmed)?.let {
                currentJobId = it.groupValues[1].toIntOrNull() ?: -1
            }
            if (currentJobId != -1 && (
                    trimmed.contains("Unsatisfied constraints", ignoreCase = true) ||
                        trimmed.contains("Pending reason", ignoreCase = true) ||
                        trimmed.contains("PENDING_JOB_REASON", ignoreCase = true)
                    )
            ) {
                out.add(JobPendingStat(currentJobId, trimmed))
                if (out.size >= maxJobs) break
            }
        }
        return out
    }

    /**
     * Summarizes ColorOS `OplusHansManager` freeze & `DestroyAllSocket` activity for a target package.
     */
    fun parseColorOsHansLogs(raw: String, pkg: String): HansEventSummary {
        var freezeCount = 0
        var socketCount = 0
        val samples = ArrayList<String>(5)
        for (line in raw.lineSequence()) {
            if (!line.contains(pkg)) continue
            val lower = line.lowercase()
            if (lower.contains("freeze") || lower.contains("m to f")) freezeCount++
            if (lower.contains("destroyallsocket") || lower.contains("socket")) socketCount++
            if (samples.size < 5) samples.add(line.trim().take(200))
        }
        return HansEventSummary(freezeCount, socketCount, samples)
    }

    /**
     * Builds an adaptive cgroup v2 probe shell snippet supporting both:
     * - Android 17 / ColorOS: `/sys/fs/cgroup/apps/uid_<uid>/pid_<pid>`
     * - Android 11–16 AOSP: `/sys/fs/cgroup/uid_<uid>/pid_<pid>`
     */
    fun adaptiveCgroupProbeCommand(uid: Int, pid: Int): String =
        "for d in /sys/fs/cgroup/apps/uid_${uid}/pid_${pid} /sys/fs/cgroup/uid_${uid}/pid_${pid} /sys/fs/cgroup/apps/uid_${uid}; do " +
            "if [ -d \"\$d\" ]; then " +
            "echo \"CGROUP_DIR=\$d\"; " +
            "[ -f \"\$d/cgroup.freeze\" ] && echo \"FREEZE=\$(cat \"\$d/cgroup.freeze\")\"; " +
            "[ -f \"\$d/memory.current\" ] && echo \"MEM_CUR=\$(cat \"\$d/memory.current\")\"; " +
            "[ -f \"\$d/memory.high\" ] && echo \"MEM_HIGH=\$(cat \"\$d/memory.high\")\"; " +
            "[ -f \"\$d/memory.events\" ] && echo \"MEM_EVENTS=\$(tr '\\n' ' ' < \"\$d/memory.events\")\"; " +
            "break; fi; done"
}
