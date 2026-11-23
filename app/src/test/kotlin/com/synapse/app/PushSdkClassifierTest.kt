package com.synapse.app

import com.synapse.app.data.DeviceBrand
import com.synapse.app.data.LiveSocketInspector
import com.synapse.app.data.PushSdkClassifier
import com.synapse.protocol.WakeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PushSdkClassifierTest {

    @Test
    fun brandDetectionIdentifiesMajorOems() {
        assertEquals(DeviceBrand.XIAOMI, DeviceBrand.detect("Xiaomi", "Redmi"))
        assertEquals(DeviceBrand.OPPO_ONEPLUS, DeviceBrand.detect("OnePlus", "OnePlus"))
        assertEquals(DeviceBrand.OPPO_ONEPLUS, DeviceBrand.detect("OPPO", "realme"))
        assertEquals(DeviceBrand.HUAWEI, DeviceBrand.detect("HUAWEI", "HUAWEI"))
        assertEquals(DeviceBrand.HONOR, DeviceBrand.detect("HONOR", "HONOR"))
        assertEquals(DeviceBrand.VIVO, DeviceBrand.detect("vivo", "iQOO"))
        assertEquals(DeviceBrand.GOOGLE_AOSP, DeviceBrand.detect("Netease", "MuMu"))
    }

    @Test
    fun noiseProcessLikeUploadLogIsRejectedEvenWithPushReceiver() {
        val cls = PushSdkClassifier.classifyProcess(
            procName = "com.coolapk.market:uploadLogProcess",
            isMain = false,
            componentNames = listOf("com.xiaomi.push.service.XMPushService"),
            deviceBrand = DeviceBrand.GOOGLE_AOSP
        )
        assertTrue(cls.isNoiseProcess)
        assertTrue(cls.baseScore < 0)
    }

    @Test
    fun oemPushIsDemotedOnNonMatchingDeviceAndPromotedOnMatchingDevice() {
        val onAosp = PushSdkClassifier.classifyProcess(
            procName = "com.demo.app:xm_push",
            isMain = false,
            componentNames = listOf("com.xiaomi.push.service.XMPushService"),
            deviceBrand = DeviceBrand.GOOGLE_AOSP
        )
        assertTrue(onAosp.isDormantOem)
        assertTrue(onAosp.baseScore < 0)

        val onXiaomi = PushSdkClassifier.classifyProcess(
            procName = "com.demo.app:xm_push",
            isMain = false,
            componentNames = listOf("com.xiaomi.push.service.XMPushService"),
            deviceBrand = DeviceBrand.XIAOMI
        )
        assertFalse(onXiaomi.isDormantOem)
        assertTrue(onXiaomi.baseScore >= 100)
    }

    @Test
    fun crossPlatformPushSdksAreRankedAboveMainProcess() {
        val tpns = PushSdkClassifier.classifyProcess(
            procName = "com.coolapk.market:xg_vip_service",
            isMain = false,
            componentNames = listOf("com.tencent.android.tpush.service.XGVipPushService"),
            deviceBrand = DeviceBrand.OPPO_ONEPLUS
        )
        val jpush = PushSdkClassifier.classifyProcess(
            procName = "com.qq.reader:pushcore",
            isMain = false,
            componentNames = listOf("cn.jpush.android.service.PushService"),
            deviceBrand = DeviceBrand.OPPO_ONEPLUS
        )
        val accs = PushSdkClassifier.classifyProcess(
            procName = "com.demo.shop:channel",
            isMain = false,
            componentNames = listOf("com.taobao.accs.ChannelService"),
            deviceBrand = DeviceBrand.OPPO_ONEPLUS
        )
        val main = PushSdkClassifier.classifyProcess(
            procName = "com.coolapk.market",
            isMain = true,
            componentNames = listOf("com.tencent.android.tpush.service.XGVipPushService"),
            deviceBrand = DeviceBrand.OPPO_ONEPLUS
        )
        assertTrue(tpns.baseScore > main.baseScore)
        assertTrue(jpush.baseScore > main.baseScore)
        assertTrue(accs.baseScore > main.baseScore)

        val svcScore = PushSdkClassifier.scoreComponent(
            procName = "com.coolapk.market:xg_vip_service",
            className = "com.tencent.android.tpush.service.XGVipPushService",
            type = WakeType.SERVICE,
            isSubprocess = true,
            deviceBrand = DeviceBrand.OPPO_ONEPLUS
        )
        assertTrue(svcScore.score >= 120)
    }

    @Test
    fun liveSocketProbeCorrelatesEstablishedPushPortToExactSubprocess() {
        val sample = """
            ===PIDS===
            3338 com.coolapk.market:xg_vip_service
            3377 com.coolapk.market
            ===FD:3338:com.coolapk.market:xg_vip_service===
            lrwx------ 1 u0_a56 u0_a56 64 2026-11-23 19:38 55 -> socket:[90011]
            ===FD:3377:com.coolapk.market===
            lrwx------ 1 u0_a56 u0_a56 64 2026-11-23 19:38 42 -> socket:[90022]
            ===TCP===
              sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
               0: 0100007F:9A20 017CF178:075C 01 00000000:00000000 00:00000000 00000000 10056        0 90011 1 0000000000000000
            ===TCP6===
            ===UDP===
            ===UDP6===
        """.trimIndent()
        // 0x075C == 1884 (TPNS MQTT port)
        val result = LiveSocketInspector.parseProbeOutput("com.coolapk.market", sample)
        assertTrue(result.rootAvailable)
        assertTrue(result.anyProcessRunning)
        assertTrue(result.anyActiveLongLink)

        val pushProbe = result.probesByProcess["com.coolapk.market:xg_vip_service"]!!
        assertEquals(1, pushProbe.tcpCount)
        assertEquals(listOf(1884), pushProbe.matchedPushPorts)

        val mainProbe = result.probesByProcess["com.coolapk.market"]!!
        assertEquals(0, mainProbe.tcpCount)
    }

    @Test
    fun friendlyTimeTransitionsAccuratelyAcrossMinuteAndDayBoundaries() {
        val now = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.NOVEMBER, 23, 23, 10, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis

        assertEquals("刚刚", com.synapse.app.ui.formatFriendlyTime(now - 5_000L, now))
        assertEquals("30 秒前", com.synapse.app.ui.formatFriendlyTime(now - 30_000L, now))
        assertEquals("1 分钟前", com.synapse.app.ui.formatFriendlyTime(now - 65_000L, now))
        assertEquals("15 分钟前", com.synapse.app.ui.formatFriendlyTime(now - 15 * 60_000L, now))
        assertTrue(com.synapse.app.ui.formatFriendlyTime(now - 2 * 3_600_000L, now).startsWith("今天 "))
        assertTrue(com.synapse.app.ui.formatFriendlyTime(now - 25 * 3_600_000L, now).startsWith("昨天 "))
    }
}
