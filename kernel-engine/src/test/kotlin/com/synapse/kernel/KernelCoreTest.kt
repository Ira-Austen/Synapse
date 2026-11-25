package com.synapse.kernel

import com.synapse.kernel.compat.OtaSafeguard
import com.synapse.kernel.core.AuditRingBuffer
import com.synapse.kernel.core.NotificationRingBuffer
import com.synapse.kernel.core.PolicySnapshot
import com.synapse.kernel.core.ResurrectionBreaker
import com.synapse.kernel.plugins.StealthAccessPolicy
import com.synapse.protocol.AuditEventType
import com.synapse.protocol.KernelPolicyBundle
import com.synapse.protocol.ProcessRule
import com.synapse.protocol.PushMode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KernelCoreTest {

    @Test
    fun policySnapshotIndexesMultiUserAndFlags() {
        val r0 = ProcessRule(
            pkg = "com.chat.app",
            userId = 0,
            pushMode = PushMode.ISOLATED_PUSH,
            targetProcess = "com.chat.app:push",
            exemptOemFreezer = true,
            memoryDozeBypass = true
        )
        val r999 = ProcessRule(
            pkg = "com.chat.app",
            userId = 999,
            pushMode = PushMode.ISOLATED_PUSH,
            targetProcess = "com.chat.app:push",
            exemptOemFreezer = false,
            memoryDozeBypass = false
        )
        val disabled = ProcessRule(
            pkg = "com.off.app",
            enabled = false,
            targetProcess = "com.off.app:push"
        )
        val snap = PolicySnapshot.from(
            KernelPolicyBundle(
                processRules = mapOf(
                    r0.ruleKey to r0,
                    r999.ruleKey to r999,
                    disabled.ruleKey to disabled
                )
            )
        )

        assertEquals(2, snap.activeRuleCount)
        assertNotNull(snap.matchProcess(0, "com.chat.app:push"))
        assertNotNull(snap.matchProcess(999, "com.chat.app:push"))
        assertNull(snap.matchProcess(0, "com.chat.app")) // main process adj untouched
        assertNull(snap.matchProcess(0, "com.off.app:push"))
        assertTrue(snap.isAospFreezerExemptPackage(0, "com.chat.app"))
        assertEquals(1, snap.activeRulesForUser(0).size)
        assertTrue(snap.isHansExempt(0, "com.chat.app"))
        assertTrue(snap.isHansExemptAnyUser("com.chat.app"))
        assertFalse(snap.isHansExempt(999, "com.chat.app"))
        // Process-level Hans cached-freeze exemption: ONLY targetProcess (`:push`) is exempt, NOT main or bloat subprocesses
        assertTrue(snap.isHansExemptProcess(0, "com.chat.app:push"))
        assertTrue(snap.isHansExemptProcessAnyUser("com.chat.app:push"))
        assertFalse(snap.isHansExemptProcess(0, "com.chat.app"))
        assertFalse(snap.isHansExemptProcess(0, "com.chat.app:tools"))
        assertFalse(snap.isHansExemptProcess(999, "com.chat.app:push"))
        assertTrue(snap.isMemoryDozeBypass(0, "com.chat.app"))
        assertTrue(snap.isMemoryDozeBypassAnyUser("com.chat.app"))
        assertFalse(snap.isMemoryDozeBypass(999, "com.chat.app"))
        assertFalse(snap.isMemoryDozeBypassAnyUser("com.unrelated.app"))
    }

    @Test
    fun auditRingBufferBoundedOverwriteAndDrain() {
        val ring = AuditRingBuffer(capacity = 8)
        for (i in 1..12) {
            ring.record(AuditEventType.ADJ_CLAMPED, pkg = "pkg.$i", detail = "ev$i", timestampMs = i.toLong())
        }
        assertEquals(8, ring.totalRecorded())
        val drained = ring.drain()
        assertEquals(8, drained.size)
        assertEquals(5L, drained.first().seq)
        assertEquals(12L, drained.last().seq)

        // Subsequent drain without new events returns empty
        assertTrue(ring.drain().isEmpty())

        ring.record(AuditEventType.RESURRECT_OK, pkg = "pkg.13")
        val next = ring.drain()
        assertEquals(1, next.size)
        assertEquals(13L, next.single().seq)
    }

    @Test
    fun resurrectionBreakerOpensAfterThresholdAndRecovers() {
        val breaker = ResurrectionBreaker()
        val key = "0:com.chat.app:push"
        assertEquals(ResurrectionBreaker.Decision.ALLOW, breaker.tryAcquire(key, 1_000L, 30_000L, 3, 300_000L))
        assertEquals(ResurrectionBreaker.Decision.ALLOW, breaker.tryAcquire(key, 5_000L, 30_000L, 3, 300_000L))
        assertEquals(ResurrectionBreaker.Decision.ALLOW, breaker.tryAcquire(key, 10_000L, 30_000L, 3, 300_000L))
        assertEquals(ResurrectionBreaker.Decision.OPENED_NOW, breaker.tryAcquire(key, 12_000L, 30_000L, 3, 300_000L))
        assertEquals(ResurrectionBreaker.Decision.COOLDOWN, breaker.tryAcquire(key, 20_000L, 30_000L, 3, 300_000L))
        assertTrue(breaker.isOpen(key, 20_000L))

        // After cooldown elapses -> allowed again
        assertEquals(ResurrectionBreaker.Decision.ALLOW, breaker.tryAcquire(key, 320_000L, 30_000L, 3, 300_000L))
        assertFalse(breaker.isOpen(key, 320_000L))
    }

    @Test
    fun otaSafeguardBypassesOnDriftedOemSignatures() {
        val guard = OtaSafeguard("OnePlus/A17_NEW")
        assertTrue(guard.evaluate("OnePlus/A16_OLD"))

        assertEquals(
            OtaSafeguard.OemGateResult.BYPASSED_ON_OTA,
            guard.allowOemHookActivation { false }
        )
        assertEquals(
            OtaSafeguard.OemGateResult.VERIFIED_AFTER_OTA,
            guard.allowOemHookActivation { true }
        )
    }

    @Test
    fun stealthAccessPolicyProtectsManagersAndHidesFromThirdParty() {
        val moduleUid = 10250
        val ksuUid = 10180
        val launcherUid = 10110
        val bankUid = 10300
        val exempt = setOf(ksuUid, launcherUid)

        // System / shell / self / root manager / launcher -> visible
        assertFalse(StealthAccessPolicy.shouldHideModuleFromCaller(true, 1000, moduleUid, exempt))
        assertFalse(StealthAccessPolicy.shouldHideModuleFromCaller(true, 2000, moduleUid, exempt))
        assertFalse(StealthAccessPolicy.shouldHideModuleFromCaller(true, moduleUid, moduleUid, exempt))
        assertFalse(StealthAccessPolicy.shouldHideModuleFromCaller(true, ksuUid, moduleUid, exempt))
        assertFalse(StealthAccessPolicy.shouldHideModuleFromCaller(true, launcherUid, moduleUid, exempt))

        // Third-party bank app -> hidden
        assertTrue(StealthAccessPolicy.shouldHideModuleFromCaller(true, bankUid, moduleUid, exempt))

        // Batch UID sweep scrubbing
        val uids = intArrayOf(10100, moduleUid, 10300)
        val names = arrayOf<String?>("com.a", "com.local.utility.1234abcd", "com.bank")
        val scrubbed = StealthAccessPolicy.filterNamesForUids(uids, names, moduleUid, "com.local.utility.1234abcd")
        assertArrayEquals(arrayOf<String?>("com.a", null, "com.bank"), scrubbed)
    }

    @Test
    fun notificationRingBufferBoundedDrainWorks() {
        val ring = NotificationRingBuffer(capacity = 4)
        for (i in 1..6) {
            ring.record(
                pkg = "com.coolapk.market",
                userId = 0,
                channelId = "5",
                title = "Title $i",
                text = "Body $i",
                timestampMs = i * 1000L
            )
        }
        assertEquals(4, ring.totalRecorded())
        val drained = ring.drain()
        assertEquals(4, drained.size)
        assertEquals("Title 3", drained.first().title)
        assertEquals("Title 6", drained.last().title)
        assertTrue(ring.drain().isEmpty())
    }
}
