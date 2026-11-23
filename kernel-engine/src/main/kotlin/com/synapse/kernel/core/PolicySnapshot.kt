package com.synapse.kernel.core

import com.synapse.protocol.GlobalSettings
import com.synapse.protocol.KernelPolicyBundle
import com.synapse.protocol.ProcessRule

/**
 * Lock-free immutable snapshot swapped atomically via `@Volatile`.
 * All hot-path lookups inside `system_server` use zero-allocation O(1) fast-path checks
 * so 99.5%+ of unmatched system calls allocate ZERO objects on the heap.
 */
class PolicySnapshot private constructor(
    val bundle: KernelPolicyBundle,
    val masterEnabled: Boolean,
    val stealthEnabled: Boolean,
    val oemHansBypassEnabled: Boolean,
    val memoryDozeBypassEnabled: Boolean,
    val hasActiveProcessRules: Boolean,
    val hasOemHansRules: Boolean,
    val hasMemoryDozeRules: Boolean,
    val activeRuleCount: Int,
    private val managedProcessNames: Set<String>,
    private val managedPkgNames: Set<String>,
    private val byProcessKey: Map<String, ProcessRule>,
    private val byPkgKey: Map<String, List<ProcessRule>>,
    private val aospFreezerExemptPkgKeys: Set<String>,
    private val aospFreezerExemptPkgNames: Set<String>,
    private val hansExemptPkgKeys: Set<String>,
    private val hansExemptPkgNames: Set<String>,
    private val dozeBypassPkgKeys: Set<String>,
    private val dozeBypassPkgNames: Set<String>,
    private val cachedDozeBypassPairs: List<Pair<Int, String>>,
    val stealthAllowlistPkgs: Set<String>
) {
    val globalSettings: GlobalSettings get() = bundle.globalSettings

    fun matchProcess(userId: Int, processName: String): ProcessRule? {
        if (!masterEnabled || !hasActiveProcessRules) return null
        // Zero-allocation fast-path reject for all non-managed processes (~99.5% of calls)
        if (!managedProcessNames.contains(processName)) return null
        return byProcessKey[ProcessRule.processKeyOf(userId, processName)]
    }

    fun matchPackage(userId: Int, pkg: String): List<ProcessRule> {
        if (!masterEnabled || !hasActiveProcessRules) return emptyList()
        if (!managedPkgNames.contains(pkg)) return emptyList()
        return byPkgKey[ProcessRule.keyOf(userId, pkg)] ?: emptyList()
    }

    fun activeRulesForUser(userId: Int): List<ProcessRule> {
        if (!masterEnabled || !hasActiveProcessRules) return emptyList()
        return byProcessKey.values.filter { it.userId == userId }
    }

    fun isAospFreezerExemptPackage(userId: Int, pkg: String): Boolean {
        if (!masterEnabled || !hasActiveProcessRules) return false
        if (!aospFreezerExemptPkgNames.contains(pkg)) return false
        return aospFreezerExemptPkgKeys.contains(ProcessRule.keyOf(userId, pkg))
    }

    fun isHansExempt(userId: Int, pkg: String): Boolean {
        if (!masterEnabled || !oemHansBypassEnabled || !hasOemHansRules) return false
        if (!hansExemptPkgNames.contains(pkg)) return false
        return hansExemptPkgKeys.contains(ProcessRule.keyOf(userId, pkg))
    }

    fun isHansExemptAnyUser(pkg: String): Boolean {
        if (!masterEnabled || !oemHansBypassEnabled || !hasOemHansRules) return false
        return hansExemptPkgNames.contains(pkg)
    }

    fun isMemoryDozeBypass(userId: Int, pkg: String): Boolean {
        if (!masterEnabled || !memoryDozeBypassEnabled || !hasMemoryDozeRules) return false
        if (!dozeBypassPkgNames.contains(pkg)) return false
        return dozeBypassPkgKeys.contains(ProcessRule.keyOf(userId, pkg))
    }

    fun isMemoryDozeBypassAnyUser(pkg: String): Boolean {
        if (!masterEnabled || !memoryDozeBypassEnabled || !hasMemoryDozeRules) return false
        return dozeBypassPkgNames.contains(pkg)
    }

    fun allMemoryDozeBypassPairs(): List<Pair<Int, String>> {
        if (!masterEnabled || !memoryDozeBypassEnabled || !hasMemoryDozeRules) return emptyList()
        return cachedDozeBypassPairs
    }

    companion object {
        val EMPTY: PolicySnapshot = from(KernelPolicyBundle())

        fun from(bundle: KernelPolicyBundle): PolicySnapshot {
            val gs = bundle.globalSettings
            val activeRules = bundle.processRules.values.filter { it.enabled }
            val procNames = HashSet<String>(activeRules.size * 2)
            val pkgNames = HashSet<String>(activeRules.size * 2)
            val byProc = HashMap<String, ProcessRule>(activeRules.size * 2)
            val byPkg = HashMap<String, MutableList<ProcessRule>>(activeRules.size * 2)
            val aospFreezerKeys = HashSet<String>()
            val aospFreezerPkgs = HashSet<String>()
            val hansKeys = HashSet<String>()
            val hansPkgs = HashSet<String>()
            val dozeKeys = HashSet<String>()
            val dozePkgs = HashSet<String>()
            val dozePairs = ArrayList<Pair<Int, String>>()

            for (r in activeRules) {
                procNames.add(r.targetProcess)
                pkgNames.add(r.pkg)
                byProc[r.processKey] = r
                byPkg.getOrPut(r.ruleKey) { ArrayList(2) }.add(r)
                if (r.exemptAospFreezer) {
                    aospFreezerKeys.add(r.ruleKey)
                    aospFreezerPkgs.add(r.pkg)
                }
                if (r.exemptOemFreezer) {
                    hansKeys.add(r.ruleKey)
                    hansPkgs.add(r.pkg)
                }
                if (r.memoryDozeBypass) {
                    if (dozeKeys.add(r.ruleKey)) {
                        dozePairs.add(r.userId to r.pkg)
                    }
                    dozePkgs.add(r.pkg)
                }
            }

            return PolicySnapshot(
                bundle = bundle,
                masterEnabled = gs.masterEnabled,
                stealthEnabled = gs.stealthEnabled,
                oemHansBypassEnabled = gs.oemHansBypassEnabled,
                memoryDozeBypassEnabled = gs.memoryDozeBypassEnabled,
                hasActiveProcessRules = activeRules.isNotEmpty(),
                hasOemHansRules = hansKeys.isNotEmpty(),
                hasMemoryDozeRules = dozeKeys.isNotEmpty(),
                activeRuleCount = activeRules.size,
                managedProcessNames = procNames,
                managedPkgNames = pkgNames,
                byProcessKey = byProc,
                byPkgKey = byPkg,
                aospFreezerExemptPkgKeys = aospFreezerKeys,
                aospFreezerExemptPkgNames = aospFreezerPkgs,
                hansExemptPkgKeys = hansKeys,
                hansExemptPkgNames = hansPkgs,
                dozeBypassPkgKeys = dozeKeys,
                dozeBypassPkgNames = dozePkgs,
                cachedDozeBypassPairs = dozePairs,
                stealthAllowlistPkgs = HashSet(bundle.stealthAllowlistPkgs)
            )
        }
    }
}
