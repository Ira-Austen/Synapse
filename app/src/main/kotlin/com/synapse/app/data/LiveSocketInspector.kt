package com.synapse.app.data

import com.synapse.agent.parser.ProcNetParser
import com.synapse.agent.parser.SocketEndpoint
import com.synapse.agent.shell.PersistentRootShell
import com.synapse.agent.shell.RootCommandExecutor

data class LiveProcessSocketProbe(
    val pid: Int,
    val processName: String,
    val tcpSockets: List<SocketEndpoint>,
    val udpSockets: List<SocketEndpoint>
) {
    val tcpCount: Int get() = tcpSockets.size
    val udpCount: Int get() = udpSockets.size
    val hasActiveLongLink: Boolean get() = tcpCount > 0 || udpCount > 0
    val matchedPushPorts: List<Int>
        get() = (tcpSockets + udpSockets)
            .map { it.remotePort }
            .filter { it in PushSdkClassifier.WELL_KNOWN_PUSH_PORTS }
            .distinct()
            .sorted()
    val samplePortsText: String
        get() {
            val ports = (tcpSockets + udpSockets)
                .map { it.remotePort }
                .distinct()
                .sortedByDescending { it in PushSdkClassifier.WELL_KNOWN_PUSH_PORTS }
                .take(3)
            return ports.joinToString(", ")
        }
}

data class PackageSocketProbeResult(
    val pkg: String,
    val rootAvailable: Boolean,
    val probesByProcess: Map<String, LiveProcessSocketProbe>
) {
    val anyProcessRunning: Boolean get() = probesByProcess.isNotEmpty()
    val anyActiveLongLink: Boolean get() = probesByProcess.values.any { it.hasActiveLongLink }
}

/**
 * Fast (<120ms) on-demand live TCP/UDP long-connection detector for a single selected package.
 * Correlates `/proc/<pid>/fd` socket inodes against `/proc/net/tcp(6)` & `/proc/net/udp(6)`
 * using [ProcNetParser] to prove which exact process currently holds an active push channel.
 */
object LiveSocketInspector {

    suspend fun probePackageSockets(pkg: String): PackageSocketProbeResult {
        if (pkg.isBlank()) {
            return PackageSocketProbeResult(pkg, rootAvailable = false, probesByProcess = emptyMap())
        }
        val shell = PersistentRootShell()
        return try {
            probePackageSocketsWithExecutor(pkg, shell)
        } finally {
            shell.close()
        }
    }

    internal suspend fun probePackageSocketsWithExecutor(
        pkg: String,
        shell: RootCommandExecutor
    ): PackageSocketProbeResult {
        if (pkg.isBlank()) {
            return PackageSocketProbeResult(pkg, rootAvailable = false, probesByProcess = emptyMap())
        }
        val escaped = pkg.replace(".", "\\.")
        return try {
            // Single fast shell script without `exit` (which would terminate the persistent `su`
            // pipeline before PersistentRootShell prints its EOF marker) and ending with `:` so
            // optional missing /proc/net/* files never flip the exit code into a false NO_ROOT.
            val cmd = """
                if [ "${'$'}(id -u 2>/dev/null)" != "0" ]; then
                    echo "===NO_ROOT==="
                else
                    echo "===ROOT_OK==="
                    PIDS=${'$'}(ps -A -o PID,NAME 2>/dev/null | grep -E "[[:space:]]${escaped}(:|[[:space:]]|${'$'})")
                    if [ -z "${'$'}PIDS" ]; then
                        echo "===NO_RUNNING_PIDS==="
                    else
                        echo "===PIDS==="
                        echo "${'$'}PIDS"
                        echo "${'$'}PIDS" | while read pid name; do
                            case "${'$'}pid" in ''|*[!0-9]*) continue ;; esac
                            echo "===FD:${'$'}pid:${'$'}name==="
                            ls -l /proc/${'$'}pid/fd 2>/dev/null | grep "socket:\["
                        done
                        echo "===TCP==="
                        cat /proc/net/tcp 2>/dev/null
                        echo "===TCP6==="
                        cat /proc/net/tcp6 2>/dev/null
                        echo "===UDP==="
                        cat /proc/net/udp 2>/dev/null
                        echo "===UDP6==="
                        cat /proc/net/udp6 2>/dev/null
                    fi
                fi
                :
            """.trimIndent()

            val res = shell.exec(cmd, timeoutMs = 5_000L)
            if (res.timedOut || !res.isSuccess || res.stdout.contains("===NO_ROOT===") ||
                (!res.stdout.contains("===ROOT_OK===") &&
                    !res.stdout.contains("===PIDS===") &&
                    !res.stdout.contains("===NO_RUNNING_PIDS==="))
            ) {
                return PackageSocketProbeResult(pkg, rootAvailable = false, probesByProcess = emptyMap())
            }
            parseProbeOutput(pkg, res.stdout)
        } catch (_: Throwable) {
            PackageSocketProbeResult(pkg, rootAvailable = false, probesByProcess = emptyMap())
        }
    }

    internal fun parseProbeOutput(pkg: String, stdout: String): PackageSocketProbeResult {
        if (stdout.contains("===NO_RUNNING_PIDS===")) {
            return PackageSocketProbeResult(pkg, rootAvailable = true, probesByProcess = emptyMap())
        }

        val pidToProc = LinkedHashMap<Int, String>()
        val pidToFdBuf = LinkedHashMap<Int, StringBuilder>()
        val tcpBuf = StringBuilder()
        val tcp6Buf = StringBuilder()
        val udpBuf = StringBuilder()
        val udp6Buf = StringBuilder()

        var section = ""
        var currentFdPid = -1

        for (rawLine in stdout.lineSequence()) {
            val line = rawLine.trim()
            when {
                line == "===PIDS===" -> { section = "PIDS"; continue }
                line == "===TCP===" -> { section = "TCP"; continue }
                line == "===TCP6===" -> { section = "TCP6"; continue }
                line == "===UDP===" -> { section = "UDP"; continue }
                line == "===UDP6===" -> { section = "UDP6"; continue }
                line.startsWith("===FD:") && line.endsWith("===") -> {
                    section = "FD"
                    val inner = line.removePrefix("===FD:").removeSuffix("===")
                    val sep = inner.indexOf(':')
                    if (sep > 0) {
                        currentFdPid = inner.substring(0, sep).toIntOrNull() ?: -1
                        val proc = inner.substring(sep + 1).trim()
                        if (currentFdPid > 0 && proc.isNotBlank()) {
                            pidToProc[currentFdPid] = proc
                            pidToFdBuf.getOrPut(currentFdPid) { StringBuilder() }
                        }
                    }
                    continue
                }
            }

            when (section) {
                "PIDS" -> {
                    val parts = line.split(Regex("\\s+"), limit = 2)
                    if (parts.size == 2) {
                        val pid = parts[0].toIntOrNull()
                        val proc = parts[1].trim()
                        if (pid != null && (proc == pkg || proc.startsWith("$pkg:"))) {
                            pidToProc[pid] = proc
                        }
                    }
                }
                "FD" -> {
                    if (currentFdPid > 0) {
                        pidToFdBuf.getOrPut(currentFdPid) { StringBuilder() }.append(line).append('\n')
                    }
                }
                "TCP" -> tcpBuf.append(line).append('\n')
                "TCP6" -> tcp6Buf.append(line).append('\n')
                "UDP" -> udpBuf.append(line).append('\n')
                "UDP6" -> udp6Buf.append(line).append('\n')
            }
        }

        val tcpTable = LinkedHashMap<Long, SocketEndpoint>().apply {
            putAll(ProcNetParser.parseProcNetTable(tcpBuf.toString(), "tcp"))
            putAll(ProcNetParser.parseProcNetTable(tcp6Buf.toString(), "tcp6"))
        }
        val udpTable = LinkedHashMap<Long, SocketEndpoint>().apply {
            putAll(ProcNetParser.parseProcNetTable(udpBuf.toString(), "udp"))
            putAll(ProcNetParser.parseProcNetTable(udp6Buf.toString(), "udp6"))
        }

        val probesByProc = LinkedHashMap<String, LiveProcessSocketProbe>()
        for ((pid, procName) in pidToProc) {
            val fdText = pidToFdBuf[pid]?.toString().orEmpty()
            val inodes = ProcNetParser.extractSocketInodes(fdText)
            val tcpMatches = inodes.mapNotNull { tcpTable[it] }
            val udpMatches = inodes.mapNotNull { udpTable[it] }

            val existing = probesByProc[procName]
            if (existing == null || (tcpMatches.size + udpMatches.size) > (existing.tcpCount + existing.udpCount)) {
                probesByProc[procName] = LiveProcessSocketProbe(
                    pid = pid,
                    processName = procName,
                    tcpSockets = tcpMatches,
                    udpSockets = udpMatches
                )
            }
        }

        return PackageSocketProbeResult(
            pkg = pkg,
            rootAvailable = true,
            probesByProcess = probesByProc
        )
    }
}
