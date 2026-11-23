package com.synapse.agent

import com.synapse.agent.guard.RiskAppGuard
import com.synapse.agent.guard.RiskLevel
import com.synapse.agent.ledger.MutationLedger
import com.synapse.agent.ledger.MutationType
import com.synapse.agent.llm.LlmClient
import com.synapse.agent.llm.LlmMessage
import com.synapse.agent.llm.LlmResponse
import com.synapse.agent.llm.LlmToolCall
import com.synapse.agent.orchestrator.AgentStreamEvent
import com.synapse.agent.orchestrator.ReActOrchestrator
import com.synapse.agent.parser.Android17TelemetryParser
import com.synapse.agent.parser.BinaryAxmlParser
import com.synapse.agent.parser.ProcNetParser
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import com.synapse.agent.shell.RootCommandExecutor
import com.synapse.agent.shell.ShellResult
import com.synapse.agent.tools.AgentToolRegistry
import com.synapse.agent.tools.KernelBridgeGateway
import com.synapse.agent.tools.ToolDefinition
import com.synapse.protocol.AuditEvent
import com.synapse.protocol.ProcessRule
import java.io.File
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentRuntimeTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun procNetParserMatchesTcpTcp6MappedAndUdpPushSockets() {
        val tcp6Sample = """
              sl  local_address                         remote_address                        st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
               0: 0000000000000000FFFF00006401A8C0:C123 0000000000000000FFFF000008080808:01BB 01 00000000:00000000 00:00000000 00000000 10240        0 987654 1 0
               1: 0000000000000000FFFF00006401A8C0:C124 0000000000000000FFFF00000100007F:01BB 06 00000000:00000000 00:00000000 00000000 10240        0 111111 1 0
        """.trimIndent()

        val table = ProcNetParser.parseProcNetTable(tcp6Sample, "tcp6")
        assertEquals(1, table.size)
        val ep = table[987654L]!!
        assertEquals("8.8.8.8", ep.remoteAddress)
        assertEquals(443, ep.remotePort)

        val fdListing = """
            lrwx------ 1 u0_a240 u0_a240 64 2026-11-22 23:00 41 -> socket:[987654]
            lrwx------ 1 u0_a240 u0_a240 64 2026-11-22 23:00 42 -> /dev/null
        """.trimIndent()
        val inodes = ProcNetParser.extractSocketInodes(fdListing)
        val match = ProcNetParser.matchProcessSockets(4321, "com.chat.app:push", inodes, table)
        assertEquals(1, match.activeSockets.size)
        assertEquals(443, match.activeSockets.single().remotePort)
    }

    @Test
    fun android17TelemetryParserIdentifiesMemoryLimiterAndHans() {
        val exitRaw = """
            ApplicationExitInfo #0:
              timestamp=2026-11-22 22:00:00.000 pid=8821 process=com.heavy.app reason=REASON_OTHER subreason=UNKNOWN description=MemoryLimiter:AnonSwap limit exceeded
              timestamp=2026-11-22 21:00:00.000 pid=7100 process=com.heavy.app:push reason=REASON_LOW_MEMORY description=lmkd kill
        """.trimIndent()
        val parsed = Android17TelemetryParser.parseExitInfo(exitRaw)
        assertEquals(2, parsed.size)
        assertTrue(parsed[0].isMemoryLimiterKill)
        assertFalse(parsed[1].isMemoryLimiterKill)

        val hansLogs = """
            11-22 22:10:01 I OplusHansManager: freeze uid: 10234 pkg: com.chat.app scene: LcdOn
            11-22 22:10:02 I OplusHansManager: DestroyAllSocket uid: 10234 pkg: com.chat.app
        """.trimIndent()
        val hans = Android17TelemetryParser.parseColorOsHansLogs(hansLogs, "com.chat.app")
        assertEquals(1, hans.freezeCount)
        assertEquals(1, hans.socketDestroyCount)
    }

    @Test
    fun riskAppGuardBlocksBankingAndAntiCheat() {
        val bank = RiskAppGuard.assess("com.icbc")
        assertEquals(RiskLevel.HIGH_RISK, bank.level)
        assertFalse(bank.allowActiveProbe)

        val antiCheat = RiskAppGuard.assess("com.demo.game", listOf("com.netease.htprotect.GuardService"))
        assertEquals(RiskLevel.HIGH_RISK, antiCheat.level)
        assertFalse(antiCheat.allowActiveProbe)

        val normal = RiskAppGuard.assess("com.example.messenger")
        assertEquals(RiskLevel.LOW, normal.level)
        assertTrue(normal.allowActiveProbe)
    }

    @Test
    fun mutationLedgerRecordsAndRollsBackInLifoOrder() = runBlocking {
        val store = LinkedHashMap<String, String>()
        store["autostart:com.chat.app"] = "deny"
        val executed = ArrayList<String>()

        val fakeShell = object : RootCommandExecutor {
            override suspend fun exec(command: String, timeoutMs: Long): ShellResult {
                executed.add(command)
                return when {
                    command.startsWith("GET ") -> ShellResult(0, store[command.removePrefix("GET ")] ?: "")
                    command.startsWith("SET ") -> {
                        val parts = command.removePrefix("SET ").split("=", limit = 2)
                        store[parts[0]] = parts[1]
                        ShellResult(0, "OK")
                    }
                    else -> ShellResult(0, "")
                }
            }

            override fun isAlive(): Boolean = true
            override fun close() = Unit
        }

        val ledgerFile = File(tempFolder.root, "ledger.json")
        val ledger = MutationLedger(ledgerFile)
        val rec = ledger.recordAndApply(
            shell = fakeShell,
            targetPkg = "com.chat.app",
            mutationType = MutationType.OEM_AUTOSTART,
            key = "autostart:com.chat.app",
            queryBaselineCommand = "GET autostart:com.chat.app",
            applyCommand = "SET autostart:com.chat.app=allow",
            appliedValue = "allow",
            rollbackCommandBuilder = { orig -> "SET autostart:com.chat.app=$orig" }
        )
        assertTrue(rec.isSuccess)
        assertEquals("allow", store["autostart:com.chat.app"])
        assertEquals(1, ledger.listAll().size)

        // Reload from disk to verify persistence
        val reloaded = MutationLedger(ledgerFile)
        assertEquals(1, reloaded.listAll().size)

        val report = reloaded.rollbackAllForCleanUninstall(fakeShell)
        assertTrue(report.allClean)
        assertEquals(1, report.succeeded)
        assertEquals("deny", store["autostart:com.chat.app"])
        assertTrue(reloaded.listAll().isEmpty())
    }

    @Test
    fun reactOrchestratorExecutesToolAndCompletes() = runBlocking {
        val fakeShell = object : RootCommandExecutor {
            override suspend fun exec(command: String, timeoutMs: Long): ShellResult = ShellResult(0, "")
            override fun isAlive(): Boolean = true
            override fun close() = Unit
        }
        val committed = ArrayList<ProcessRule>()
        val gateway = object : KernelBridgeGateway {
            override suspend fun queryKernelAudit(pkg: String): List<AuditEvent> = emptyList()
            override suspend fun commitRule(rule: ProcessRule): Result<String> {
                committed.add(rule)
                return Result.success("rev=2;active=1")
            }

            override fun resolveApkBasePath(pkg: String): String? = null
            override fun getApiLevel(): Int = 37
        }
        val registry = AgentToolRegistry(fakeShell, gateway)

        var turn = 0
        val fakeLlm = object : LlmClient {
            override suspend fun chat(messages: List<LlmMessage>, tools: List<ToolDefinition>): LlmResponse {
                turn++
                return if (turn == 1) {
                    LlmResponse(
                        LlmMessage(
                            role = "assistant",
                            content = "提交保守保活规则",
                            toolCalls = listOf(
                                LlmToolCall(
                                    id = "call_1",
                                    name = "commit_and_verify_rule",
                                    argumentsJson = """{"pkg":"com.example.chat","targetProcess":"com.example.chat:push","pushMode":"ISOLATED_PUSH","wakeType":"KEEP_ALIVE_ONLY","aiSummary":"TCP verified"}"""
                                )
                            )
                        )
                    )
                } else {
                    LlmResponse(LlmMessage(role = "assistant", content = "已成功部署并生效。"))
                }
            }
        }

        val orchestrator = ReActOrchestrator(fakeLlm, registry, maxSteps = 4)
        val events = orchestrator.diagnoseAndOptimize("com.example.chat").toList()

        assertEquals(1, committed.size)
        assertEquals("com.example.chat:push", committed.single().targetProcess)
        assertTrue(events.any { it is AgentStreamEvent.Completed && it.stepsUsed == 2 })
    }

    @Test
    fun binaryAxmlParserExtractsProcessGroupedServiceAndAction() {
        val axml = buildMinimalAxml(
            strings = listOf(
                "manifest", "package", "com.demo.im",
                "application", "service", "name", ".push.KeepAliveService",
                "process", ":push", "exported",
                "intent-filter", "action", "com.demo.im.ACTION_WAKE"
            )
        )
        val parsed = BinaryAxmlParser.parseBytes(axml)
        assertEquals("com.demo.im", parsed.packageName)
        val pushComponents = parsed.componentsByProcess["com.demo.im:push"]!!
        assertEquals(1, pushComponents.size)
        val svc = pushComponents.single()
        assertEquals("service", svc.type)
        assertEquals("com.demo.im.push.KeepAliveService", svc.className)
        assertEquals(true, svc.exported)
        assertEquals(listOf("com.demo.im.ACTION_WAKE"), svc.actions)
    }

    private fun buildMinimalAxml(strings: List<String>): ByteArray {
        // 1. UTF-8 string pool chunk
        val strData = ByteArrayOutputStream()
        val offsets = IntArray(strings.size)
        for (i in strings.indices) {
            offsets[i] = strData.size()
            val utf8 = strings[i].toByteArray(Charsets.UTF_8)
            strData.write(strings[i].length)
            strData.write(utf8.size)
            strData.write(utf8)
            strData.write(0)
        }
        while (strData.size() % 4 != 0) strData.write(0)

        val spHeaderSize = 28
        val stringsStart = spHeaderSize + strings.size * 4
        val spSize = stringsStart + strData.size()
        val spBuf = ByteBuffer.allocate(spSize).order(ByteOrder.LITTLE_ENDIAN)
        spBuf.putShort(0x0001).putShort(spHeaderSize.toShort()).putInt(spSize)
        spBuf.putInt(strings.size).putInt(0).putInt(1 shl 8).putInt(stringsStart).putInt(0)
        offsets.forEach { spBuf.putInt(it) }
        spBuf.put(strData.toByteArray())

        fun startElem(nameIdx: Int, attrs: List<Triple<Int, Int, Int>>): ByteArray {
            val size = 36 + attrs.size * 20
            val b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
            b.putShort(0x0102).putShort(16).putInt(size)
            b.putInt(1).putInt(-1) // line, comment
            b.putInt(-1).putInt(nameIdx) // ns, name
            b.putShort(20).putShort(20).putShort(attrs.size.toShort())
            b.putShort(0).putShort(0).putShort(0)
            for ((attrNameIdx, valType, valData) in attrs) {
                b.putInt(-1) // ns
                b.putInt(attrNameIdx)
                b.putInt(if (valType == 0x03) valData else -1)
                b.putShort(8).put(0).put(valType.toByte()).putInt(valData)
            }
            return b.array()
        }

        fun endElem(nameIdx: Int): ByteArray {
            val b = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            b.putShort(0x0103).putShort(16).putInt(24)
            b.putInt(1).putInt(-1).putInt(-1).putInt(nameIdx)
            return b.array()
        }

        val chunks = ByteArrayOutputStream()
        chunks.write(spBuf.array())
        // <manifest package="com.demo.im">
        chunks.write(startElem(0, listOf(Triple(1, 0x03, 2))))
        // <application>
        chunks.write(startElem(3, emptyList()))
        // <service name=".push.KeepAliveService" process=":push" exported="true">
        chunks.write(startElem(4, listOf(Triple(5, 0x03, 6), Triple(7, 0x03, 8), Triple(9, 0x12, 1))))
        // <intent-filter><action name="com.demo.im.ACTION_WAKE"/></intent-filter>
        chunks.write(startElem(10, emptyList()))
        chunks.write(startElem(11, listOf(Triple(5, 0x03, 12))))
        chunks.write(endElem(11))
        chunks.write(endElem(10))
        chunks.write(endElem(4))
        chunks.write(endElem(3))
        chunks.write(endElem(0))

        val body = chunks.toByteArray()
        val total = 8 + body.size
        val root = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        root.putShort(0x0003).putShort(8).putInt(total)
        root.put(body)
        return root.array()
    }
}
