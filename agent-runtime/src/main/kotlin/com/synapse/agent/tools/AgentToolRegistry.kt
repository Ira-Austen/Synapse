package com.synapse.agent.tools

import com.synapse.agent.guard.RiskAppGuard
import com.synapse.agent.parser.Android17TelemetryParser
import com.synapse.agent.parser.BinaryAxmlParser
import com.synapse.agent.parser.ProcNetParser
import com.synapse.agent.shell.RootCommandExecutor
import com.synapse.protocol.AuditEvent
import com.synapse.protocol.MiniJson
import com.synapse.protocol.ProcessRule
import com.synapse.protocol.PushMode
import com.synapse.protocol.WakeType
import com.synapse.protocol.bool
import com.synapse.protocol.int
import com.synapse.protocol.str
import com.synapse.protocol.strOrNull
import kotlinx.coroutines.delay

data class ToolDefinition(
    val name: String,
    val description: String,
    val parametersSchema: Map<String, Any?>
)

data class ToolResult(
    val toolName: String,
    val success: Boolean,
    val jsonOutput: String
)

/**
 * Callback gateway allowing `agent-runtime` tools to talk to the app's `KernelIpcClient` & `RemotePreferences`.
 */
interface KernelBridgeGateway {
    suspend fun queryKernelAudit(pkg: String): List<AuditEvent>
    suspend fun commitRule(rule: ProcessRule): Result<String>
    fun resolveApkBasePath(pkg: String): String?
    fun getApiLevel(): Int
}

/**
 * Registry of all 6 parameterized diagnostic & verification tools exposed to the ReAct Agent.
 */
class AgentToolRegistry(
    private val shell: RootCommandExecutor,
    private val gateway: KernelBridgeGateway
) {

    fun definitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "inspect_manifest",
            description = "Directly reads binary AndroidManifest.xml from base.apk via ZipFile and groups Services/Receivers by processName.",
            parametersSchema = objSchema(
                required = listOf("pkg"),
                props = linkedMapOf(
                    "pkg" to prop("string", "Target package name"),
                    "userId" to prop("integer", "Android user ID (default 0)")
                )
            )
        ),
        ToolDefinition(
            name = "inspect_live_state",
            description = "Inspects live processes, RSS, oom_score_adj, adaptive cgroup v2 freeze/memory state, active TCP/TCP6/UDP6 sockets, Android 17 MemoryLimiter & JobScheduler pending stats, and ColorOS Hans logs.",
            parametersSchema = objSchema(
                required = listOf("pkg"),
                props = linkedMapOf(
                    "pkg" to prop("string", "Target package name"),
                    "userId" to prop("integer", "Android user ID (default 0)")
                )
            )
        ),
        ToolDefinition(
            name = "probe_wake_component",
            description = "Sandboxed trial probe: checks foreground safety & RiskAppGuard, kills ONLY target subprocess, grants 10s temp whitelist, invokes am start-service/broadcast, and inspects survival, main-process impact, popups, and logcat.",
            parametersSchema = objSchema(
                required = listOf("pkg", "targetProcess", "wakeComponent", "wakeType"),
                props = linkedMapOf(
                    "pkg" to prop("string", "Target package name"),
                    "userId" to prop("integer", "Android user ID (default 0)"),
                    "targetProcess" to prop("string", "Subprocess name to probe"),
                    "wakeComponent" to prop("string", "Component name (pkg/.ClassName)"),
                    "wakeType" to prop("string", "SERVICE or BROADCAST"),
                    "wakeAction" to prop("string", "Optional intent action")
                )
            )
        ),
        ToolDefinition(
            name = "query_kernel_audit",
            description = "Fetches recent micro-kernel AuditRingBuffer events for a package via AMS Magic Binder.",
            parametersSchema = objSchema(
                required = listOf("pkg"),
                props = linkedMapOf("pkg" to prop("string", "Target package name"))
            )
        ),
        ToolDefinition(
            name = "read_recent_logs",
            description = "Reads recent logcat lines and Android ApplicationExitInfo (`dumpsys activity exit-info`, identifying LMKD vs Android 17 MemoryLimiter:AnonSwap).",
            parametersSchema = objSchema(
                required = listOf("pkg"),
                props = linkedMapOf(
                    "pkg" to prop("string", "Target package name"),
                    "lines" to prop("integer", "Max logcat lines (default 120)")
                )
            )
        ),
        ToolDefinition(
            name = "commit_and_verify_rule",
            description = "Commits a verified ProcessRule to RemotePreferences + Magic Binder (zero OS disk mutation). Optionally runs E2E kill-9 test to verify system_server auto-resurrection.",
            parametersSchema = objSchema(
                required = listOf("pkg", "targetProcess", "pushMode", "wakeType"),
                props = linkedMapOf(
                    "pkg" to prop("string", "Target package name"),
                    "userId" to prop("integer", "Android user ID (default 0)"),
                    "appName" to prop("string", "Human-readable app name"),
                    "targetProcess" to prop("string", "Target process name"),
                    "pushMode" to prop("string", "ISOLATED_PUSH, SINGLE_PROCESS, or KEEP_ALIVE_ONLY"),
                    "wakeComponent" to prop("string", "Optional wake component"),
                    "wakeType" to prop("string", "SERVICE, BROADCAST, or KEEP_ALIVE_ONLY"),
                    "wakeAction" to prop("string", "Optional intent action"),
                    "keepAliveAdj" to prop("integer", "Safe adj 100..800 (default 200)"),
                    "memoryDozeBypass" to prop("boolean", "Pure-memory Doze + NPMS/eBPF bypass (default true)"),
                    "exemptOemFreezer" to prop("boolean", "Exempt ColorOS Hans/OEM freezer (default true)"),
                    "aiSummary" to prop("string", "Concise summary of diagnosis"),
                    "runE2eTest" to prop("boolean", "If true, kills targetProcess and verifies kernel resurrection after 3s")
                )
            )
        )
    )

    suspend fun invoke(toolName: String, args: Map<String, Any?>): ToolResult {
        return try {
            when (toolName) {
                "inspect_manifest" -> inspectManifest(args)
                "inspect_live_state" -> inspectLiveState(args)
                "probe_wake_component" -> probeWakeComponent(args)
                "query_kernel_audit" -> queryKernelAudit(args)
                "read_recent_logs" -> readRecentLogs(args)
                "commit_and_verify_rule" -> commitAndVerifyRule(args)
                else -> ToolResult(toolName, false, MiniJson.encode(mapOf("error" to "Unknown tool: $toolName")))
            }
        } catch (t: Throwable) {
            ToolResult(
                toolName,
                false,
                MiniJson.encode(mapOf("error" to "${t.javaClass.simpleName}: ${t.message}"))
            )
        }
    }

    private suspend fun inspectManifest(args: Map<String, Any?>): ToolResult {
        val pkg = sanitizePkg(args.str("pkg"))
        val path = gateway.resolveApkBasePath(pkg)
            ?: shell.exec("pm path $pkg | head -n 1 | cut -d: -f2").stdout.trim()
        if (path.isBlank()) {
            return ToolResult("inspect_manifest", false, MiniJson.encode(mapOf("error" to "Could not resolve APK path for $pkg")))
        }
        val parsed = BinaryAxmlParser.parseApk(path)
        val risk = RiskAppGuard.assess(pkg, parsed.allComponents.map { it.className })
        val out = linkedMapOf<String, Any?>(
            "packageName" to parsed.packageName,
            "defaultProcess" to parsed.defaultProcess,
            "riskLevel" to risk.level.name,
            "allowActiveProbe" to risk.allowActiveProbe,
            "riskReason" to risk.reason,
            "processes" to parsed.componentsByProcess.mapValues { (_, list) ->
                list.map { c ->
                    linkedMapOf<String, Any?>(
                        "type" to c.type,
                        "className" to c.className,
                        "exported" to c.exported,
                        "enabled" to c.enabled,
                        "actions" to c.actions
                    )
                }
            }
        )
        return ToolResult("inspect_manifest", true, MiniJson.encode(out))
    }

    private suspend fun inspectLiveState(args: Map<String, Any?>): ToolResult {
        val pkg = sanitizePkg(args.str("pkg"))
        val psOut = shell.exec("ps -eo pid,uid,rss,args | grep '$pkg' | grep -v grep").stdout
        val processes = ArrayList<Map<String, Any?>>()

        val tcp = ProcNetParser.parseProcNetTable(shell.exec("cat /proc/net/tcp 2>/dev/null").stdout, "tcp")
        val tcp6 = ProcNetParser.parseProcNetTable(shell.exec("cat /proc/net/tcp6 2>/dev/null").stdout, "tcp6")
        val udp = ProcNetParser.parseProcNetTable(shell.exec("cat /proc/net/udp 2>/dev/null").stdout, "udp")
        val udp6 = ProcNetParser.parseProcNetTable(shell.exec("cat /proc/net/udp6 2>/dev/null").stdout, "udp6")
        val allSockets = LinkedHashMap<Long, com.synapse.agent.parser.SocketEndpoint>().apply {
            putAll(tcp); putAll(tcp6); putAll(udp); putAll(udp6)
        }

        for (line in psOut.lineSequence()) {
            val parts = line.trim().split(Regex("\\s+"), limit = 4)
            if (parts.size < 4) continue
            val pid = parts[0].toIntOrNull() ?: continue
            val uid = parts[1].toIntOrNull() ?: -1
            val rssKb = parts[2].toLongOrNull() ?: 0L
            val procName = parts[3].trim()

            val adj = shell.exec("cat /proc/$pid/oom_score_adj 2>/dev/null").stdout.trim().toIntOrNull()
            val cgroupInfo = shell.exec(Android17TelemetryParser.adaptiveCgroupProbeCommand(uid, pid)).stdout
            val fdListing = shell.exec("ls -l /proc/$pid/fd 2>/dev/null").stdout
            val inodes = ProcNetParser.extractSocketInodes(fdListing)
            val sockMatch = ProcNetParser.matchProcessSockets(pid, procName, inodes, allSockets)

            processes.add(
                linkedMapOf(
                    "pid" to pid,
                    "uid" to uid,
                    "rssKb" to rssKb,
                    "processName" to procName,
                    "oomScoreAdj" to adj,
                    "cgroup" to cgroupInfo,
                    "activeSockets" to sockMatch.activeSockets.map {
                        "${it.protocol}://${it.remoteAddress}:${it.remotePort}"
                    }
                )
            )
        }

        val apiLevel = gateway.getApiLevel()
        val memLimiterStatus = if (apiLevel >= 37) {
            shell.exec("am memory-limiter status 2>/dev/null").stdout.take(1500)
        } else {
            "N/A (API $apiLevel < 37)"
        }
        val jobStats = Android17TelemetryParser.parsePendingJobReasons(
            shell.exec("dumpsys jobscheduler '$pkg' 2>/dev/null").stdout
        )
        val hansSummary = Android17TelemetryParser.parseColorOsHansLogs(
            shell.exec("logcat -d -s OplusHansManager -t 150 2>/dev/null").stdout,
            pkg
        )

        val out = linkedMapOf<String, Any?>(
            "pkg" to pkg,
            "apiLevel" to apiLevel,
            "processes" to processes,
            "memoryLimiterStatus" to memLimiterStatus,
            "pendingJobs" to jobStats.map { mapOf("jobId" to it.jobId, "reasons" to it.reasons) },
            "colorOsHans" to mapOf(
                "freezeCount" to hansSummary.freezeCount,
                "socketDestroyCount" to hansSummary.socketDestroyCount,
                "samples" to hansSummary.sampleLines
            )
        )
        return ToolResult("inspect_live_state", true, MiniJson.encode(out))
    }

    private suspend fun probeWakeComponent(args: Map<String, Any?>): ToolResult {
        val pkg = sanitizePkg(args.str("pkg"))
        val userId = args.int("userId", 0).coerceAtLeast(0)
        val targetProcess = sanitizeToken(args.str("targetProcess"))
        val wakeComponent = sanitizeComponent(args.str("wakeComponent"))
        val wakeType = WakeType.fromString(args.str("wakeType"))
        val wakeAction = args.strOrNull("wakeAction")?.let(::sanitizeToken)

        // 1. RiskAppGuard check
        val risk = RiskAppGuard.assess(pkg)
        if (!risk.allowActiveProbe) {
            return ToolResult(
                "probe_wake_component",
                false,
                MiniJson.encode(mapOf("blockedByRiskGuard" to true, "reason" to risk.reason))
            )
        }

        // 2. Foreground avoidance check
        val topResumed = shell.exec("dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity' | head -n 3").stdout
        if (topResumed.contains("$pkg/")) {
            return ToolResult(
                "probe_wake_component",
                false,
                MiniJson.encode(mapOf("skipped" to true, "reason" to "Target app is currently in foreground"))
            )
        }

        // 3. Baseline processes & kill ONLY target subprocess
        val beforePs = shell.exec("ps -eo pid,rss,args | grep '$pkg' | grep -v grep").stdout
        val mainAliveBefore = beforePs.lines().any { it.trim().endsWith(" $pkg") }
        val targetPids = beforePs.lines().mapNotNull { line ->
            val p = line.trim().split(Regex("\\s+"), limit = 3)
            if (p.size == 3 && p[2].trim() == targetProcess) p[0].toIntOrNull() else null
        }
        for (pid in targetPids) {
            shell.exec("kill -9 $pid")
        }
        delay(500L)

        // 4. Temporary Doze allowlist + wake dispatch
        shell.exec("cmd deviceidle tempwhitelist -d 10000 $pkg 2>/dev/null")
        val actionFlag = if (!wakeAction.isNullOrBlank()) "-a '$wakeAction'" else ""
        val wakeCmd = when (wakeType) {
            WakeType.SERVICE -> "am start-service --user $userId $actionFlag -n '$wakeComponent'"
            WakeType.BROADCAST -> "am broadcast --user $userId $actionFlag -n '$wakeComponent'"
            WakeType.KEEP_ALIVE_ONLY -> return ToolResult(
                "probe_wake_component",
                false,
                MiniJson.encode(mapOf("error" to "wakeType KEEP_ALIVE_ONLY cannot be actively probed"))
            )
        }
        val wakeRes = shell.exec(wakeCmd)
        delay(2_000L)

        // 5. Post-probe inspection
        val afterPs = shell.exec("ps -eo pid,rss,args | grep '$pkg' | grep -v grep").stdout
        val targetAlive = afterPs.lines().any { it.trim().endsWith(" $targetProcess") }
        val mainAliveAfter = afterPs.lines().any { it.trim().endsWith(" $pkg") }
        val topAfter = shell.exec("dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity' | head -n 3").stdout
        val popupDetected = topAfter.contains("$pkg/")
        val logSnippet = shell.exec("logcat -d -v epoch -t 300 | grep -i '$pkg' | tail -n 35").stdout

        val out = linkedMapOf<String, Any?>(
            "wakeCommandExitCode" to wakeRes.exitCode,
            "wakeCommandOutput" to wakeRes.stdout.take(500),
            "targetSubprocessAlive" to targetAlive,
            "mainProcessPulledUpByAccident" to (!mainAliveBefore && mainAliveAfter),
            "popupActivityDetected" to popupDetected,
            "afterProcesses" to afterPs,
            "recentLogcat" to logSnippet
        )
        return ToolResult("probe_wake_component", true, MiniJson.encode(out))
    }

    private suspend fun queryKernelAudit(args: Map<String, Any?>): ToolResult {
        val pkg = sanitizePkg(args.str("pkg"))
        val events = gateway.queryKernelAudit(pkg)
        val out = events.map { e ->
            linkedMapOf<String, Any?>(
                "seq" to e.seq,
                "ts" to e.timestampMs,
                "type" to e.type.name,
                "processName" to e.processName,
                "userId" to e.userId,
                "detail" to e.detail
            )
        }
        return ToolResult("query_kernel_audit", true, MiniJson.encode(mapOf("events" to out)))
    }

    private suspend fun readRecentLogs(args: Map<String, Any?>): ToolResult {
        val pkg = sanitizePkg(args.str("pkg"))
        val lines = args.int("lines", 120).coerceIn(20, 400)
        val logcat = shell.exec("logcat -d -t $lines | grep -i '$pkg' | tail -n 60").stdout
        val exitRaw = shell.exec("dumpsys activity exit-info '$pkg' 2>/dev/null").stdout
        val exitEntries = Android17TelemetryParser.parseExitInfo(exitRaw)
        val out = linkedMapOf<String, Any?>(
            "pkg" to pkg,
            "exitInfo" to exitEntries.map { e ->
                linkedMapOf<String, Any?>(
                    "pid" to e.pid,
                    "processName" to e.processName,
                    "reason" to e.reason,
                    "description" to e.description,
                    "isAndroid17MemoryLimiterKill" to e.isMemoryLimiterKill
                )
            },
            "logcat" to logcat
        )
        return ToolResult("read_recent_logs", true, MiniJson.encode(out))
    }

    private suspend fun commitAndVerifyRule(args: Map<String, Any?>): ToolResult {
        val pkg = sanitizePkg(args.str("pkg"))
        val userId = args.int("userId", 0).coerceAtLeast(0)
        val targetProcess = sanitizeToken(args.str("targetProcess", pkg))
        val pushMode = PushMode.fromString(args.str("pushMode"))
        val wakeType = WakeType.fromString(args.str("wakeType"))
        val rule = ProcessRule(
            pkg = pkg,
            userId = userId,
            appName = args.str("appName", pkg),
            enabled = true,
            pushMode = pushMode,
            targetProcess = targetProcess,
            wakeComponent = args.strOrNull("wakeComponent"),
            wakeType = wakeType,
            wakeAction = args.strOrNull("wakeAction"),
            keepAliveAdj = args.int("keepAliveAdj", 200).coerceIn(ProcessRule.MIN_SAFE_ADJ, ProcessRule.MAX_SAFE_ADJ),
            protectProcState = args.bool("protectProcState", true),
            exemptAospFreezer = args.bool("exemptAospFreezer", true),
            exemptOemFreezer = args.bool("exemptOemFreezer", true),
            memoryDozeBypass = args.bool("memoryDozeBypass", true),
            allowResurrect = wakeType != WakeType.KEEP_ALIVE_ONLY,
            verifiedByAgent = true,
            aiSummary = args.str("aiSummary", "")
        )

        val commitRes = gateway.commitRule(rule)
        if (commitRes.isFailure) {
            return ToolResult(
                "commit_and_verify_rule",
                false,
                MiniJson.encode(mapOf("committed" to false, "error" to commitRes.exceptionOrNull()?.message))
            )
        }

        val runE2e = args.bool("runE2eTest", false)
        var e2eResurrected: Boolean? = null
        if (runE2e && rule.allowResurrect && RiskAppGuard.assess(pkg).allowActiveProbe) {
            val ps = shell.exec("ps -eo pid,args | grep '$targetProcess' | grep -v grep").stdout
            val pids = ps.lines().mapNotNull { it.trim().split(Regex("\\s+"), limit = 2).firstOrNull()?.toIntOrNull() }
            for (pid in pids) {
                shell.exec("kill -9 $pid")
            }
            delay(3_000L)
            val after = shell.exec("ps -eo pid,args | grep '$targetProcess' | grep -v grep").stdout
            e2eResurrected = after.lines().any { it.trim().endsWith(" $targetProcess") }
        }

        return ToolResult(
            "commit_and_verify_rule",
            true,
            MiniJson.encode(
                linkedMapOf<String, Any?>(
                    "committed" to true,
                    "kernelReply" to commitRes.getOrNull(),
                    "e2eResurrectionVerified" to e2eResurrected
                )
            )
        )
    }

    private fun sanitizePkg(raw: String): String {
        val clean = raw.trim()
        require(PKG_REGEX.matches(clean)) { "Invalid package name: $raw" }
        return clean
    }

    private fun sanitizeToken(raw: String): String {
        val clean = raw.trim()
        require(TOKEN_REGEX.matches(clean)) { "Unsafe shell token: $raw" }
        return clean
    }

    private fun sanitizeComponent(raw: String): String {
        val clean = raw.trim()
        require(COMPONENT_REGEX.matches(clean)) { "Invalid component name: $raw" }
        return clean
    }

    private fun objSchema(required: List<String>, props: Map<String, Any?>): Map<String, Any?> = linkedMapOf(
        "type" to "object",
        "properties" to props,
        "required" to required
    )

    private fun prop(type: String, description: String): Map<String, Any?> = linkedMapOf(
        "type" to type,
        "description" to description
    )

    companion object {
        private val PKG_REGEX = Regex("""^[a-zA-Z0-9._-]+$""")
        private val TOKEN_REGEX = Regex("""^[a-zA-Z0-9._:/-]+$""")
        private val COMPONENT_REGEX = Regex("""^[a-zA-Z0-9._/$]+$""")
    }
}
