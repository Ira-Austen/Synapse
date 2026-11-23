package com.synapse.kernel.compat

/**
 * OTA fingerprint guard preventing bootloops after ROM updates when OEM private classes/signatures drift.
 */
class OtaSafeguard(
    val currentFingerprint: String
) {
    @Volatile
    var cachedFingerprint: String = ""
        private set

    @Volatile
    var otaChanged: Boolean = false
        private set

    fun evaluate(lastHealthyFingerprint: String): Boolean {
        cachedFingerprint = lastHealthyFingerprint
        otaChanged = lastHealthyFingerprint.isNotBlank() &&
            currentFingerprint.isNotBlank() &&
            lastHealthyFingerprint != currentFingerprint
        return otaChanged
    }

    /**
     * Runs a signature dry-run probe for OEM hooks.
     * - When no OTA change is detected, runs normally.
     * - When OTA fingerprint changed, all required probe checks MUST return true; otherwise returns false to bypass safely.
     */
    fun allowOemHookActivation(dryRunSignatureCheck: () -> Boolean): OemGateResult {
        val signatureOk = runCatching { dryRunSignatureCheck() }.getOrDefault(false)
        return when {
            !signatureOk && otaChanged -> OemGateResult.BYPASSED_ON_OTA
            !signatureOk -> OemGateResult.UNSUPPORTED_ROM
            otaChanged -> OemGateResult.VERIFIED_AFTER_OTA
            else -> OemGateResult.OK
        }
    }

    enum class OemGateResult {
        OK,
        VERIFIED_AFTER_OTA,
        BYPASSED_ON_OTA,
        UNSUPPORTED_ROM
    }
}
