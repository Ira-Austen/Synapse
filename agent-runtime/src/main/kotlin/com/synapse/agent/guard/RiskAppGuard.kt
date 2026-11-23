package com.synapse.agent.guard

enum class RiskLevel {
    LOW,
    ELEVATED,
    HIGH_RISK
}

data class RiskAssessment(
    val pkg: String,
    val level: RiskLevel,
    val allowActiveProbe: Boolean,
    val reason: String
)

/**
 * Safety guardrail protecting banking, payment, government, and strong anti-cheat games
 * from invasive cold-start / kill-9 active probing (`probe_wake_component`).
 */
object RiskAppGuard {

    private val HIGH_RISK_EXACT = setOf(
        "com.eg.android.AlipayGphone",
        "com.unionpay",
        "com.icbc",
        "com.chinamworld.main",
        "com.chinamworld.bocmbci",
        "com.android.bankabc",
        "cmb.pb",
        "com.bankcomm.Bankcomm",
        "com.pingan.paces.ccms",
        "com.tencent.tmgp.sgame",
        "com.tencent.tmgp.pubgmhd",
        "com.miHoYo.Yuanshen",
        "com.miHoYo.hkrpg",
        "com.kurogame.mingchao",
        "com.google.android.apps.walletnfcrel",
        "com.paypal.android.p2pmobile"
    )

    private val HIGH_RISK_KEYWORDS = listOf(
        "bank", "alipay", "unionpay", "wallet", "pay.", ".pay", "icbc", "cmb.",
        "ebank", "mbank", "ccb", "bocmbci", "abchina", "psbc", "citic",
        "securities", "broker", "crypto", "binance", "okx", "coinbase",
        "gov.cn", "tax", "police", "anticheat", "tmgp", "mihoyo", "hoyoverse"
    )

    private val HIGH_RISK_MANIFEST_SIGNALS = listOf(
        "com.tencent.msdk",
        "com.tencent.tp",
        "com.netease.htprotect",
        "com.bangcle",
        "com.ijiami",
        "com.secneo"
    )

    fun assess(pkg: String, manifestHints: Collection<String> = emptyList()): RiskAssessment {
        val lower = pkg.lowercase()
        if (HIGH_RISK_EXACT.any { it.equals(pkg, ignoreCase = true) }) {
            return RiskAssessment(
                pkg = pkg,
                level = RiskLevel.HIGH_RISK,
                allowActiveProbe = false,
                reason = "Matched protected finance/payment/anti-cheat package list; active probe blocked"
            )
        }
        val matchedKw = HIGH_RISK_KEYWORDS.firstOrNull { lower.contains(it) }
        if (matchedKw != null) {
            return RiskAssessment(
                pkg = pkg,
                level = RiskLevel.HIGH_RISK,
                allowActiveProbe = false,
                reason = "Package name matches high-risk keyword '$matchedKw'; active probe blocked"
            )
        }
        val matchedSignal = manifestHints.firstOrNull { hint ->
            val h = hint.lowercase()
            HIGH_RISK_MANIFEST_SIGNALS.any { sig -> h.contains(sig) }
        }
        if (matchedSignal != null) {
            return RiskAssessment(
                pkg = pkg,
                level = RiskLevel.HIGH_RISK,
                allowActiveProbe = false,
                reason = "Manifest contains anti-tamper/hardening signature '$matchedSignal'; active probe blocked"
            )
        }
        return RiskAssessment(
            pkg = pkg,
            level = RiskLevel.LOW,
            allowActiveProbe = true,
            reason = "Standard application; sandboxed active probing permitted"
        )
    }
}
