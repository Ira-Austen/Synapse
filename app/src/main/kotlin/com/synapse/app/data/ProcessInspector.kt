package com.synapse.app.data

import com.synapse.agent.shell.PersistentRootShell
import com.synapse.protocol.ProcessRule
import com.synapse.protocol.WakeType

data class LiveProcessInfo(
    val pid: Int,
    val uid: Int,
    val processName: String,
    val shortName: String,
    val rssKb: Long,
    val oomScoreAdj: Int?,
    val isFrozen: Boolean,
    val isProtectedTarget: Boolean,
    val isMainProcess: Boolean
) {
    val rssMb: Double get() = rssKb / 1024.0

    val priorityLabel: String
        get() = when {
            oomScoreAdj == null -> "运行中"
            oomScoreAdj <= 0 -> "前台活跃"
            oomScoreAdj <= 250 -> "重点保活"
            oomScoreAdj <= 500 -> "普通后台"
            else -> "后台缓存"
        }
}

data class ManagedAppProcessGroup(
    val rule: ProcessRule,
    val appLabel: String,
    val processes: List<LiveProcessInfo>
) {
    val aliveCount: Int get() = processes.size
    val totalRssMb: Double get() = processes.sumOf { it.rssMb }
    val targetAlive: Boolean get() = processes.any { it.isProtectedTarget }
}

data class ProcessMonitorSnapshot(
    val groups: List<ManagedAppProcessGroup>,
    val rootAvailable: Boolean,
    val errorMessage: String? = null,
    val updatedAtMs: Long = System.currentTimeMillis()
) {
    val totalAliveProcesses: Int get() = groups.sumOf { it.aliveCount }
    val runningAppsCount: Int get() = groups.count { it.aliveCount > 0 }
    val totalRssMb: Double get() = groups.sumOf { it.totalRssMb }
}

/**
 * On-demand live process monitor for managed apps.
 * Opens a short-lived root shell only when the user refreshes, reads process state, and closes immediately.
 */
object ProcessInspector {

    suspend fun inspectManagedApps(
        rules: List<ProcessRule>,
        labelResolver: (String) -> String
    ): ProcessMonitorSnapshot {
        if (rules.isEmpty()) {
            return ProcessMonitorSnapshot(groups = emptyList(), rootAvailable = true)
        }
        val shell = PersistentRootShell()
        return try {
            inspectManagedAppsWithShell(shell, rules, labelResolver)
        } finally {
            shell.close()
        }
    }

    /**
     * Reuses a caller-managed foreground root shell to avoid repeated `su` forks during
     * active-screen live monitoring. Caller MUST close [shell] on tab exit / ON_PAUSE.
     */
    suspend fun inspectManagedAppsWithShell(
        shell: PersistentRootShell,
        rules: List<ProcessRule>,
        labelResolver: (String) -> String
    ): ProcessMonitorSnapshot {
        if (rules.isEmpty()) {
            return ProcessMonitorSnapshot(groups = emptyList(), rootAvailable = true)
        }

        return try {
            val pkgs = rules.map { it.pkg }.distinct()
            val pkgFilter = pkgs.joinToString("|") { escapeRegex(it) }
            // Single fast shell invocation without `exit` (never kill the persistent `su` pipeline)
            val cmd = """
                if [ "${'$'}(id -u 2>/dev/null)" != "0" ]; then
                    echo "__NO_ROOT__"
                else
                    echo "__ROOT_OK__"
                    ps -A -o PID,UID,RSS,NAME 2>/dev/null | grep -E "($pkgFilter)" | while read pid uid rss name; do
                        case "${'$'}pid" in ''|*[!0-9]*) continue ;; esac
                        adj=${'$'}(cat /proc/${'$'}pid/oom_score_adj 2>/dev/null || echo NA)
                        wchan=${'$'}(cat /proc/${'$'}pid/wchan 2>/dev/null || echo "")
                        frozen=0
                        case "${'$'}wchan" in *refrigerator*|*freezer*) frozen=1 ;; esac
                        cg=${'$'}(grep -m1 '^0::' /proc/${'$'}pid/cgroup 2>/dev/null | cut -d: -f3)
                        if [ -n "${'$'}cg" ] && [ -f "/sys/fs/cgroup${'$'}cg/cgroup.freeze" ]; then
                            fval=${'$'}(cat "/sys/fs/cgroup${'$'}cg/cgroup.freeze" 2>/dev/null || echo 0)
                            if [ "${'$'}fval" = "1" ]; then frozen=1; fi
                        fi
                        echo "${'$'}pid|${'$'}uid|${'$'}rss|${'$'}adj|${'$'}frozen|${'$'}name"
                    done
                fi
                :
            """.trimIndent()

            val res = shell.exec(cmd, timeoutMs = 6_000L)
            if (res.timedOut) {
                return ProcessMonitorSnapshot(
                    groups = rules.map { ManagedAppProcessGroup(it, labelResolver(it.pkg), emptyList()) },
                    rootAvailable = false,
                    errorMessage = "读取进程超时，请稍后下拉重试"
                )
            }
            if (!res.isSuccess || res.stdout.contains("__NO_ROOT__") || !res.stdout.contains("__ROOT_OK__")) {
                return ProcessMonitorSnapshot(
                    groups = rules.map { ManagedAppProcessGroup(it, labelResolver(it.pkg), emptyList()) },
                    rootAvailable = false,
                    errorMessage = "未授予 Root 权限，暂无法通过命令行读取进程（重启激活新版微内核后可免 Root 直读，或在 KernelSU / Magisk 中授权）"
                )
            }

            val parsed = parseProcessLines(res.stdout)
            val groups = rules.map { rule ->
                val matching = parsed
                    .filter { proc ->
                        val userId = proc.uid / 100_000
                        val pkgMatch = proc.processName == rule.pkg || proc.processName.startsWith("${rule.pkg}:")
                        pkgMatch && userId == rule.userId
                    }
                    .map { proc ->
                        val isTarget = proc.processName == rule.targetProcess
                        val isMain = proc.processName == rule.pkg
                        val shortName = when {
                            isMain -> "主进程"
                            proc.processName.contains(':') -> proc.processName.substringAfter(':')
                            else -> proc.processName
                        }
                        proc.copy(
                            shortName = shortName,
                            isProtectedTarget = isTarget,
                            isMainProcess = isMain
                        )
                    }
                    .sortedWith(
                        compareByDescending<LiveProcessInfo> { it.isProtectedTarget }
                            .thenByDescending { it.isMainProcess }
                            .thenBy { it.processName }
                    )

                ManagedAppProcessGroup(
                    rule = rule,
                    appLabel = labelResolver(rule.pkg),
                    processes = matching
                )
            }

            ProcessMonitorSnapshot(
                groups = groups,
                rootAvailable = true
            )
        } catch (t: Throwable) {
            ProcessMonitorSnapshot(
                groups = rules.map { ManagedAppProcessGroup(it, labelResolver(it.pkg), emptyList()) },
                rootAvailable = false,
                errorMessage = "需要授予 Root 权限才能查看其他应用的实时后台进程（不影响微内核保活本身）"
            )
        }
    }

    /**
     * One-tap wake for a managed rule's push service/broadcast via Root shell.
     */
    suspend fun wakeRuleNow(rule: ProcessRule): Result<String> {
        val comp = rule.wakeComponent?.takeIf { it.isNotBlank() }
            ?: return Result.failure(IllegalStateException("该应用未配置唤醒组件，请先手动打开一次应用"))
        val shell = PersistentRootShell()
        return try {
            if (!shell.verifyRootAccess()) {
                return Result.failure(IllegalStateException("未授予 Root 权限，请在 KernelSU / Magisk 中授权（重启激活新版微内核后可免 Root 唤醒）"))
            }
            // Pure in-memory 10s temp allowlist so Android 12-17 permits background startService cleanly
            val allowPrefix = "cmd deviceidle tempwhitelist -u ${rule.userId} -d 10000 '${rule.pkg}' >/dev/null 2>&1; "
            val cmd = when (rule.wakeType) {
                WakeType.SERVICE -> {
                    val actPart = rule.wakeAction?.takeIf { it.isNotBlank() }?.let { " -a '$it'" }.orEmpty()
                    "${allowPrefix}am startservice --user ${rule.userId} -n '$comp'$actPart"
                }
                WakeType.BROADCAST -> {
                    val actPart = rule.wakeAction?.takeIf { it.isNotBlank() }?.let { " -a '$it'" }.orEmpty()
                    "${allowPrefix}am broadcast --user ${rule.userId} -n '$comp'$actPart"
                }
                WakeType.KEEP_ALIVE_ONLY -> {
                    "monkey -p '${rule.pkg}' -c android.intent.category.LAUNCHER 1"
                }
            }
            val res = shell.exec(cmd, timeoutMs = 5_000L)
            if (res.exitCode == 0 && !res.stdout.contains("Error:", ignoreCase = true)) {
                Result.success("已尝试唤醒 ${rule.targetProcess}")
            } else {
                Result.failure(IllegalStateException(res.stdout.ifBlank { "命令返回码 ${res.exitCode}" }.take(120)))
            }
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            shell.close()
        }
    }

    private fun parseProcessLines(stdout: String): List<LiveProcessInfo> {
        val list = ArrayList<LiveProcessInfo>()
        for (line in stdout.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val parts = trimmed.split('|')
            if (parts.size < 6) continue
            val pid = parts[0].toIntOrNull() ?: continue
            val uid = parts[1].toIntOrNull() ?: continue
            val rss = parts[2].toLongOrNull() ?: 0L
            val adj = parts[3].toIntOrNull()
            val frozen = parts[4] == "1"
            val name = parts.subList(5, parts.size).joinToString("|").trim()
            if (name.isEmpty()) continue
            list.add(
                LiveProcessInfo(
                    pid = pid,
                    uid = uid,
                    processName = name,
                    shortName = name,
                    rssKb = rss,
                    oomScoreAdj = adj,
                    isFrozen = frozen,
                    isProtectedTarget = false,
                    isMainProcess = false
                )
            )
        }
        return list
    }

    private fun escapeRegex(raw: String): String = raw.replace(".", "\\.")
}
