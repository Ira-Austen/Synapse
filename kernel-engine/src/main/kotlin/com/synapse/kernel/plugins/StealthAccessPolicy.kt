package com.synapse.kernel.plugins

import com.synapse.kernel.compat.SystemAdapter

/**
 * Pure, unit-testable stealth visibility decision logic.
 * Prevents self-inflicted breakage of Root managers, Xposed managers, default Launcher, and Synapse itself.
 */
object StealthAccessPolicy {

    const val FIRST_APPLICATION_UID = 10_000
    const val FIRST_ISOLATED_UID = 99_000
    const val LAST_ISOLATED_UID = 99_999

    fun shouldHideModuleFromCaller(
        stealthEnabled: Boolean,
        callingUid: Int,
        moduleUid: Int,
        exemptUids: Set<Int>
    ): Boolean {
        if (!stealthEnabled) return false
        if (moduleUid <= 0) return false
        val appId = callingUid % SystemAdapter.PER_USER_RANGE
        // 1. Always visible to system / root / shell / core UIDs (< 10000)
        if (appId < FIRST_APPLICATION_UID) return false
        // 2. Always visible to Synapse itself
        if (callingUid == moduleUid) return false
        // 3. Always visible to allowlisted Root/Xposed managers & default launcher
        if (exemptUids.contains(callingUid) || exemptUids.contains(appId)) return false
        return true
    }

    fun filterPackageArray(
        packages: Array<String>?,
        modulePkg: String
    ): Array<String>? {
        if (packages == null || packages.isEmpty()) return packages
        if (!packages.contains(modulePkg)) return packages
        val filtered = packages.filterNot { it == modulePkg }
        return if (filtered.isEmpty()) null else filtered.toTypedArray()
    }

    fun filterNamesForUids(
        uids: IntArray?,
        names: Array<String?>?,
        moduleUid: Int,
        modulePkg: String
    ): Array<String?>? {
        if (uids == null || names == null) return names
        val limit = minOf(uids.size, names.size)
        var modified = false
        for (i in 0 until limit) {
            if (uids[i] == moduleUid || names[i] == modulePkg) {
                names[i] = null
                modified = true
            }
        }
        return if (modified) names else names
    }
}
