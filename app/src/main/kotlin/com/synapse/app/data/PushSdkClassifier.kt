package com.synapse.app.data

import com.synapse.protocol.WakeType

enum class DeviceBrand(val displayName: String) {
    XIAOMI("小米 / 红米"),
    HUAWEI("华为"),
    HONOR("荣耀"),
    OPPO_ONEPLUS("OPPO / 一加 / 真我"),
    VIVO("vivo / iQOO"),
    MEIZU("魅族"),
    SAMSUNG("三星"),
    GOOGLE_AOSP("原生 / 类原生"),
    OTHER("其他机型");

    companion object {
        fun detect(manufacturer: String?, brand: String?): DeviceBrand {
            val combined = "${manufacturer.orEmpty()} ${brand.orEmpty()}".lowercase()
            return when {
                combined.contains("xiaomi") || combined.contains("redmi") || combined.contains("poco") -> XIAOMI
                combined.contains("huawei") -> HUAWEI
                combined.contains("honor") -> HONOR
                combined.contains("oppo") || combined.contains("oneplus") || combined.contains("realme") || combined.contains("oplus") -> OPPO_ONEPLUS
                combined.contains("vivo") || combined.contains("iqoo") -> VIVO
                combined.contains("meizu") -> MEIZU
                combined.contains("samsung") -> SAMSUNG
                combined.contains("google") || combined.contains("aosp") || combined.contains("android") || combined.contains("netease") || combined.contains("mumu") -> GOOGLE_AOSP
                else -> OTHER
            }
        }
    }
}

data class ProcessClassification(
    val hintTag: String,
    val baseScore: Int,
    val isNoiseProcess: Boolean = false,
    val isDormantOem: Boolean = false
)

data class ComponentClassification(
    val tag: String,
    val score: Int,
    val isDormantOem: Boolean = false
)

/**
 * Comprehensive, brand-aware Push SDK & background process classifier covering major Chinese
 * and international push channels, IM long-link engines, and noise process suppression.
 */
object PushSdkClassifier {

    val WELL_KNOWN_PUSH_PORTS: Set<Int> = setOf(
        1883, 1884, 8883,       // MQTT / 腾讯 TPNS
        5222, 5223, 5224, 5228, 5229, 5230, // XMPP / Google FCM / 极光 / IM
        3000, 7000, 7001, 19000 // 极光 / 个推 / 阿里 ACCS
    )

    private val NOISE_PROCESS_TOKENS = listOf(
        "uploadlog", "crash", "bugly", "sandbox", "webview", "download",
        "game", "miniapp", "miniprogram", "widget", "tinker", "patch",
        "dexopt", "leakcanary", "fileprovider", "player", "media", "video",
        "camera", "ocr", "scan", "pay", "wallet", "splash", "adservice",
        "browser", "plugin", "hotfix", "installer", "backup", "feedback"
    )

    private val NOISE_COMPONENT_TOKENS = listOf(
        "jobservice", "workmanager", "androidx.", "crash", "bugly", "analytics",
        "billing", "download", "webview", "uploadlog", "fileprovider", "tinker",
        "leakcanary", "adservice", "gameaidl", "h5game"
    )

    fun isNoiseProcessName(procName: String, isMain: Boolean): Boolean {
        if (isMain) return false
        val suffix = procName.substringAfter(':', procName).lowercase()
        return NOISE_PROCESS_TOKENS.any { suffix.contains(it) }
    }

    fun classifyProcess(
        procName: String,
        isMain: Boolean,
        componentNames: List<String>,
        deviceBrand: DeviceBrand
    ): ProcessClassification {
        if (isMain) {
            return ProcessClassification(
                hintTag = "应用主进程（单进程应用适用）",
                baseScore = 30
            )
        }

        val suffix = procName.substringAfter(':', procName).lowercase()
        if (isNoiseProcessName(procName, isMain = false)) {
            return ProcessClassification(
                hintTag = "辅助 / 日志进程（不建议作为推送保活）",
                baseScore = -120,
                isNoiseProcess = true
            )
        }

        val allLower = (suffix + " " + componentNames.joinToString(" ") { it.lowercase() })

        // 1. Cross-platform self-hosted TCP push SDKs (active on ANY device brand!)
        when {
            suffix.contains("xg_vip") || allLower.contains("xgvippushservice") || allLower.contains("tpush") || allLower.contains("tpns") ->
                return ProcessClassification("腾讯 TPNS / 信鸽长连接推送", 130)

            suffix.contains("pushcore") || allLower.contains("cn.jpush") || allLower.contains("jpush") || allLower.contains("jiguang") ->
                return ProcessClassification("极光推送 (JPush) 长连接", 125)

            allLower.contains("igexin") || allLower.contains("getui") || suffix == "pushservice" ->
                return ProcessClassification("个推 (GeTui) 长连接推送", 122)

            allLower.contains("taobao.accs") || allLower.contains("umeng.message") || allLower.contains("aliyun.ams.push") || suffix == "channel" ->
                return ProcessClassification("阿里 ACCS / 友盟推送通道", 120)

            suffix.contains("bdservice") || allLower.contains("baidu.android.pushservice") ->
                return ProcessClassification("百度云推送通道", 115)

            suffix == "msf" || allLower.contains("tencent.mars") || allLower.contains("timpush") || allLower.contains("tencent.imsdk") ->
                return ProcessClassification("腾讯 IM / Mars 长连接通道", 125)

            allLower.contains("bytedance.push") || allLower.contains("ss.android.message") || allLower.contains("frontier") ->
                return ProcessClassification("字节跳动长连接推送通道", 120)

            allLower.contains("netease.nim") || allLower.contains("nimservice") ->
                return ProcessClassification("网易云信 IM 长连接", 118)

            allLower.contains("io.rong.push") || allLower.contains("rongpush") ->
                return ProcessClassification("融云 IM 推送通道", 118)

            allLower.contains("hyphenate") || allLower.contains("easemob") ->
                return ProcessClassification("环信 IM 推送通道", 118)

            allLower.contains("firebase.messaging") || allLower.contains("fcm") || allLower.contains("c2dm") ->
                return ProcessClassification("Google FCM 推送通道", 105)
        }

        // 2. OEM-only push SDKs — strongly brand-aware!
        detectOemChannel(allLower)?.let { (oemBrand, oemLabel) ->
            return if (oemBrand == deviceBrand) {
                ProcessClassification(
                    hintTag = "$oemLabel（匹配当前机型）",
                    baseScore = 110,
                    isDormantOem = false
                )
            } else {
                ProcessClassification(
                    hintTag = "$oemLabel（非对应机型，通常不生效）",
                    baseScore = -60,
                    isDormantOem = true
                )
            }
        }

        // 3. Generic independent push / long-link subprocess naming
        return when {
            suffix.contains("push") || suffix.contains("notify") || suffix.contains("msg") ||
                suffix.contains("ims") || suffix.contains("mqtt") || suffix.contains("comet") ||
                suffix.contains("longlink") || suffix.contains("socket") ->
                ProcessClassification("独立消息推送子进程", 100)

            suffix.contains("remote") || suffix.contains("daemon") || suffix.contains("service") ||
                suffix.contains("core") || suffix.contains("ipc") || suffix.contains("guard") ->
                ProcessClassification("后台常驻子进程", 72)

            else ->
                ProcessClassification("辅助子进程", 20)
        }
    }

    fun scoreComponent(
        procName: String,
        className: String,
        type: WakeType,
        isSubprocess: Boolean,
        deviceBrand: DeviceBrand
    ): ComponentClassification {
        val lower = className.lowercase()
        val procLower = procName.lowercase()

        if (NOISE_COMPONENT_TOKENS.any { lower.contains(it) }) {
            return ComponentClassification(
                tag = if (type == WakeType.SERVICE) "普通辅助服务" else "普通广播",
                score = -35
            )
        }

        // Check OEM-only component first
        detectOemChannel(lower)?.let { (oemBrand, oemLabel) ->
            val matches = oemBrand == deviceBrand
            return ComponentClassification(
                tag = if (matches) "$oemLabel 服务" else "$oemLabel（非当前机型）",
                score = if (matches) 95 else -50,
                isDormantOem = !matches
            )
        }

        var score = if (isSubprocess) 25 else 5
        var tag = if (type == WakeType.SERVICE) "后台服务" else "唤醒广播"

        when {
            lower.contains("xgvippushservice") -> { score += 110; tag = "腾讯 TPNS 核心服务" }
            lower.contains("tpush") || lower.contains("tpns") -> { score += 80; tag = "腾讯推送服务" }
            lower.contains("cn.jpush.android.service.pushservice") || lower.contains("jcommonservice") -> {
                score += 105; tag = "极光核心推送服务"
            }
            lower.contains("jpush") || lower.contains("jiguang") -> { score += 85; tag = "极光推送服务" }
            lower.contains("igexin.sdk.pushservice") || lower.contains("gtpush") -> {
                score += 100; tag = "个推核心推送服务"
            }
            lower.contains("getui") || lower.contains("igexin") -> { score += 80; tag = "个推推送服务" }
            lower.contains("taobao.accs") || lower.contains("channelservice") || lower.contains("umeng.message") -> {
                score += 95; tag = "阿里/友盟通道服务"
            }
            lower.contains("baidu.android.pushservice") -> { score += 90; tag = "百度推送服务" }
            lower.contains("firebasemessaging") -> { score += 85; tag = "FCM 消息服务" }
            lower.contains("rongpush") || lower.contains("nimservice") || lower.contains("emchat") -> {
                score += 90; tag = "IM 长连接服务"
            }
            lower.contains("pushservice") || lower.contains("push") -> { score += 70; tag = "推送服务" }
            lower.contains("mqtt") || lower.contains("socket") || lower.contains("channel") || lower.contains("ims") || lower.contains("longlink") -> {
                score += 65; tag = "长连接服务"
            }
            lower.contains("daemon") || lower.contains("keepalive") || lower.contains("guard") -> {
                score += 55; tag = "守护服务"
            }
            procLower.contains("push") && type == WakeType.SERVICE -> {
                score += 45; tag = "推送进程服务"
            }
        }

        if (type == WakeType.BROADCAST) {
            score -= 6 // Prefer explicit Service over Broadcast when equal
        }

        return ComponentClassification(tag = tag, score = score)
    }

    private fun detectOemChannel(lower: String): Pair<DeviceBrand, String>? = when {
        lower.contains("xiaomi.push") || lower.contains("mipush") || lower.contains("xmpushservice") ->
            DeviceBrand.XIAOMI to "小米推送通道"
        lower.contains("huawei.hms") || lower.contains("huawei.push") || lower.contains("hmsmsgservice") || lower.contains("agconnect") ->
            DeviceBrand.HUAWEI to "华为 HMS 推送"
        lower.contains("hihonor.push") || lower.contains("honormsgservice") ->
            DeviceBrand.HONOR to "荣耀推送通道"
        lower.contains("heytap.msp.push") || lower.contains("coloros.mcssdk") || lower.contains("oplus.push") ->
            DeviceBrand.OPPO_ONEPLUS to "OPPO / 一加推送"
        lower.contains("vivo.push") ->
            DeviceBrand.VIVO to "vivo 推送通道"
        lower.contains("meizu.cloud.push") || lower.contains("flyme.push") ->
            DeviceBrand.MEIZU to "魅族推送通道"
        else -> null
    }
}
