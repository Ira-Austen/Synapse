package com.synapse.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyCodecTest {

    @Test
    fun roundTripKernelPolicyBundle() {
        val rule = ProcessRule(
            pkg = "com.example.chat",
            userId = 999,
            appName = "Chat \"Clone\"\nLine2",
            enabled = true,
            pushMode = PushMode.ISOLATED_PUSH,
            targetProcess = "com.example.chat:push",
            wakeComponent = "com.example.chat/.push.PushService",
            wakeType = WakeType.SERVICE,
            wakeAction = "com.example.chat.ACTION_KEEP_ALIVE",
            keepAliveAdj = 200,
            protectProcState = true,
            exemptAospFreezer = true,
            exemptOemFreezer = true,
            memoryDozeBypass = true,
            allowResurrect = true,
            verifiedByAgent = true,
            aiSummary = "Verified isolated :push with active TCP socket"
        )
        val bundle = KernelPolicyBundle(
            version = 3,
            revision = 42L,
            romFingerprint = "OnePlus/PJZ110/OP5CF9L1:17/AQ3A.260101.001/U.1234:user/release-keys",
            globalSettings = GlobalSettings(
                masterEnabled = true,
                stealthEnabled = true,
                oemHansBypassEnabled = true,
                memoryDozeBypassEnabled = true,
                resurrectDelayMs = 1500L,
                breakerWindowMs = 30_000L,
                breakerThreshold = 3,
                breakerCooldownMs = 300_000L
            ),
            stealthAllowlistPkgs = linkedSetOf("me.weishu.kernelsu", "org.lsposed.manager"),
            processRules = mapOf(rule.ruleKey to rule),
            componentBlockRules = mapOf("com.ad.pkg" to listOf("com.ad.pkg/.AdService")),
            intentFirewallRules = listOf(IntentRule("r1", "com.a", "com.b", "android.intent.action.VIEW", true)),
            alarmThrottleRules = mapOf("com.noisy" to AlarmRule("com.noisy", 600_000L, 60_000L, true))
        )

        val encoded = PolicyCodec.encodeBundle(bundle)
        val decoded = PolicyCodec.decodeBundle(encoded)

        assertEquals(bundle, decoded)
        assertEquals("999:com.example.chat", decoded.processRules.keys.single())
    }

    @Test
    fun roundTripHealthAndAuditEvents() {
        val health = KernelHealthReport(
            kernelVersion = "3.1.0",
            apiLevel = 37,
            frameworkName = "Vector",
            frameworkApiVersion = 101,
            oomAdjusterImpl = "OomAdjusterModernImpl",
            currentFingerprint = "fp-new",
            cachedFingerprint = "fp-old",
            otaChanged = true,
            policyRevision = 7L,
            activeRuleCount = 2,
            auditCount = 12,
            uptimeMs = 99_000L,
            plugins = listOf(
                PluginHealth("ProcessGuardPlugin", PluginStatus.ACTIVE, 4, "OK"),
                PluginHealth("OemHansPlugin", PluginStatus.OTA_CHANGED_BYPASSED, 0, "Fingerprint changed")
            )
        )
        assertEquals(health, PolicyCodec.decodeHealth(PolicyCodec.encodeHealth(health)))

        val events = listOf(
            AuditEvent(1L, 1700000001L, AuditEventType.ADJ_CLAMPED, "com.a", "com.a:push", 0, "900->200"),
            AuditEvent(2L, 1700000002L, AuditEventType.DOZE_BPF_BYPASS, "com.a", "", 0, "uid=10234")
        )
        assertEquals(events, PolicyCodec.decodeAuditEvents(PolicyCodec.encodeAuditEvents(events)))

        val notifs = listOf(
            NotificationHistoryRecord(1L, 1700000010L, "com.coolapk.market", 0, "5", "落灬雪 私信了你", "测试消息", "", false)
        )
        assertEquals(notifs, PolicyCodec.decodeNotifications(PolicyCodec.encodeNotifications(notifs)))

        val procs = listOf(
            LiveProcessSnapshot(
                pkg = "com.coolapk.market",
                userId = 0,
                processName = "com.coolapk.market:xg_vip_service",
                pid = 3338,
                uid = 10056,
                curAdj = 200,
                curProcState = 10,
                frozen = false,
                rssKb = 96_256L
            )
        )
        assertEquals(procs, PolicyCodec.decodeLiveProcesses(PolicyCodec.encodeLiveProcesses(procs)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeAdjIsRejected() {
        ProcessRule(pkg = "com.example.bad", keepAliveAdj = -900)
    }

    @Test
    fun safeMagicCodeRangeValidation() {
        assertTrue(IpcProtocol.isSafeMagicCode(IpcProtocol.DEFAULT_MAGIC_CODE))
        assertTrue(IpcProtocol.isSafeMagicCode(0x00530000))
        assertTrue(IpcProtocol.isSafeMagicCode(0x007EFFFF))
        assertFalse(IpcProtocol.isSafeMagicCode(10001)) // Oplus custom range
        assertFalse(IpcProtocol.isSafeMagicCode(1))     // AOSP AMS range
        assertFalse(IpcProtocol.isSafeMagicCode(0x01000000))
    }
}
