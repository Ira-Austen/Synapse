package com.synapse.agent.parser

data class SocketEndpoint(
    val protocol: String, // tcp, tcp6, udp, udp6
    val remoteAddress: String,
    val remotePort: Int,
    val stateHex: String,
    val uid: Int,
    val inode: Long
)

data class ProcessSocketMatch(
    val pid: Int,
    val processName: String,
    val activeSockets: List<SocketEndpoint>
)

/**
 * Correlates `/proc/<pid>/fd` (`socket:[inode]`) with `/proc/net/tcp`, `/proc/net/tcp6`
 * (including IPv4-mapped IPv6 `::ffff:a.b.c.d` with `st == 01` ESTABLISHED), and
 * `/proc/net/udp(6)` (for QUIC / UDP push channels).
 */
object ProcNetParser {

    private val SOCKET_INODE_REGEX = Regex("""socket:\[(\d+)]""")

    /**
     * Parses `ls -l /proc/<pid>/fd` or `readlink` output into a set of socket inodes.
     */
    fun extractSocketInodes(fdListing: String): Set<Long> {
        val out = HashSet<Long>()
        for (m in SOCKET_INODE_REGEX.findAll(fdListing)) {
            m.groupValues[1].toLongOrNull()?.let { out.add(it) }
        }
        return out
    }

    /**
     * Parses `/proc/net/tcp`, `/proc/net/tcp6`, `/proc/net/udp`, `/proc/net/udp6`.
     * For TCP/TCP6, retains ESTABLISHED (`st == "01"`).
     * For UDP/UDP6, retains connected sockets (non-zero remote port, e.g., QUIC push).
     */
    fun parseProcNetTable(content: String, protocol: String): Map<Long, SocketEndpoint> {
        val isTcp = protocol.startsWith("tcp", ignoreCase = true)
        val isV6 = protocol.endsWith("6")
        val result = LinkedHashMap<Long, SocketEndpoint>()

        for (rawLine in content.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("sl")) continue
            val parts = line.split(Regex("\\s+"))
            if (parts.size < 10) continue

            val rem = parts[2]
            val stateHex = parts[3].uppercase()
            val uid = parts[7].toIntOrNull() ?: -1
            val inode = parts[9].toLongOrNull() ?: continue
            if (inode <= 0L) continue

            val colon = rem.indexOf(':')
            if (colon <= 0) continue
            val addrHex = rem.substring(0, colon)
            val port = rem.substring(colon + 1).toIntOrNull(16) ?: 0

            if (isTcp) {
                if (stateHex != "01") continue // ESTABLISHED only
            } else {
                if (port == 0) continue // Ignore unbound listening/wildcard UDP sockets
            }

            val remoteIp = if (isV6) decodeIpv6(addrHex) else decodeIpv4(addrHex)
            if (remoteIp == "127.0.0.1" || remoteIp == "::1" || remoteIp == "0.0.0.0") continue

            result[inode] = SocketEndpoint(
                protocol = protocol.lowercase(),
                remoteAddress = remoteIp,
                remotePort = port,
                stateHex = stateHex,
                uid = uid,
                inode = inode
            )
        }
        return result
    }

    fun matchProcessSockets(
        pid: Int,
        processName: String,
        inodes: Set<Long>,
        socketTable: Map<Long, SocketEndpoint>
    ): ProcessSocketMatch {
        val matched = inodes.mapNotNull { socketTable[it] }
        return ProcessSocketMatch(pid = pid, processName = processName, activeSockets = matched)
    }

    internal fun decodeIpv4(hex: String): String {
        if (hex.length != 8) return hex
        val b0 = hex.substring(6, 8).toIntOrNull(16) ?: return hex
        val b1 = hex.substring(4, 6).toIntOrNull(16) ?: return hex
        val b2 = hex.substring(2, 4).toIntOrNull(16) ?: return hex
        val b3 = hex.substring(0, 2).toIntOrNull(16) ?: return hex
        return "$b0.$b1.$b2.$b3"
    }

    internal fun decodeIpv6(hex: String): String {
        if (hex.length != 32) return hex
        // Detect IPv4-mapped IPv6: 00000000 00000000 FFFF0000 <ipv4-le>
        val upper = hex.uppercase()
        if (upper.startsWith("0000000000000000FFFF0000")) {
            return decodeIpv4(upper.substring(24, 32))
        }
        if (upper == "00000000000000000000000001000000") return "::1"
        // Compact representation of 4 little-endian 32-bit words
        val groups = ArrayList<String>(8)
        for (word in 0 until 4) {
            val w = upper.substring(word * 8, word * 8 + 8)
            val hi = w.substring(6, 8) + w.substring(4, 6)
            val lo = w.substring(2, 4) + w.substring(0, 2)
            groups.add(hi.trimStart('0').ifEmpty { "0" }.lowercase())
            groups.add(lo.trimStart('0').ifEmpty { "0" }.lowercase())
        }
        return groups.joinToString(":")
    }
}
