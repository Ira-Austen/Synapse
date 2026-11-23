package com.synapse.kernel.plugins

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import com.synapse.kernel.compat.SystemAdapter
import com.synapse.kernel.core.KernelContext
import com.synapse.kernel.core.KernelPlugin
import com.synapse.kernel.core.PolicySnapshot
import com.synapse.kernel.core.SafeHook
import com.synapse.protocol.AuditEventType
import com.synapse.protocol.PluginHealth
import com.synapse.protocol.PluginStatus
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ultra-low-overhead system-level self-stealth plugin across Android 11–17:
 * - Zero reflection array allocation on hot paths (`getPackageName()` cached per concrete Class).
 * - Event-driven UID cache refresh (policy updates + package broadcasts, zero 30s polling).
 * - Shares MicroKernel's single background worker thread (0 extra threads).
 */
class StealthHidePlugin : KernelPlugin {
    override val name: String = "StealthHidePlugin"

    private lateinit var ctx: KernelContext
    private var hookCount = 0
    private var status = PluginStatus.DISABLED
    private var statusMessage = "Not initialized"

    @Volatile
    private var cachedModuleUid: Int = -1

    @Volatile
    private var exemptUids: Set<Int> = emptySet()

    @Volatile
    private var lastCacheRefreshMs: Long = 0L

    private val refreshRunning = AtomicBoolean(false)

    private val inHookRefresh = object : ThreadLocal<Boolean>() {
        override fun initialValue(): Boolean = false
    }

    // Per-class cached `getPackageName()` Method (or NO_METHOD sentinel) -> zero reflection scans on hot path
    private val pkgNameMethodCache = ConcurrentHashMap<Class<*>, Method>(16)
    private val auditThrottleByUid = ConcurrentHashMap<Int, Long>(32)

    override fun install(ctx: KernelContext): PluginHealth {
        this.ctx = ctx
        var count = 0

        val useComputerEngine = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ctx.adapter.computerEngineClass != null
        val queryHostClass = if (useComputerEngine) {
            ctx.adapter.computerEngineClass
        } else {
            ctx.adapter.pmsClass
        }
        val filterHostClass = if (useComputerEngine) {
            ctx.adapter.computerEngineClass
        } else {
            ctx.adapter.appsFilterBaseClass ?: ctx.adapter.pmsClass
        }

        // 1. shouldFilterApplication (blocks getInstalledPackages, getPackageInfo, queryIntent*, QUERY_ALL_PACKAGES)
        if (filterHostClass != null) {
            for (method in ctx.adapter.findAllMethodsByName(filterHostClass, "shouldFilterApplication")) {
                if (method.returnType != Boolean::class.javaPrimitiveType) continue
                SafeHook.hookReplaceable(ctx.bridge, ctx.audit, name, method) { call ->
                    val snap = ctx.currentSnapshot()
                    if (!snap.masterEnabled || !snap.stealthEnabled) {
                        return@hookReplaceable call.proceed()
                    }
                    // Fast-path: reject system/shell/allowlisted callers BEFORE inspecting target args
                    val callingUid = extractCallingUid(call.args)
                    if (shouldHide(callingUid) && referencesModulePackage(call.args)) {
                        recordStealthAuditThrottled(callingUid)
                        return@hookReplaceable true
                    }
                    call.proceed()
                }
                count++
            }
        }

        // 2. UID enumeration blockers: getPackagesForUid, getNameForUid, getNamesForUids, getPackageUid
        if (queryHostClass != null) {
            for (method in ctx.adapter.findAllMethodsByName(queryHostClass, "getPackagesForUid")) {
                SafeHook.hookReplaceable(ctx.bridge, ctx.audit, name, method) { call ->
                    val result = call.proceed()
                    val callingUid = Binder.getCallingUid()
                    if (shouldHide(callingUid) && result is Array<*>) {
                        @Suppress("UNCHECKED_CAST")
                        return@hookReplaceable StealthAccessPolicy.filterPackageArray(
                            result as Array<String>,
                            ctx.bridge.modulePackageName
                        )
                    }
                    result
                }
                count++
            }

            for (method in ctx.adapter.findAllMethodsByName(queryHostClass, "getNameForUid")) {
                SafeHook.hookReplaceable(ctx.bridge, ctx.audit, name, method) { call ->
                    val result = call.proceed()
                    val callingUid = Binder.getCallingUid()
                    if (shouldHide(callingUid) && result is String && result == ctx.bridge.modulePackageName) {
                        return@hookReplaceable null
                    }
                    result
                }
                count++
            }

            for (method in ctx.adapter.findAllMethodsByName(queryHostClass, "getNamesForUids")) {
                SafeHook.hookReplaceable(ctx.bridge, ctx.audit, name, method) { call ->
                    val result = call.proceed()
                    val callingUid = Binder.getCallingUid()
                    if (shouldHide(callingUid) && result is Array<*>) {
                        val uids = findIntArrayArg(call.args)
                        @Suppress("UNCHECKED_CAST")
                        return@hookReplaceable StealthAccessPolicy.filterNamesForUids(
                            uids = uids,
                            names = result as Array<String?>,
                            moduleUid = cachedModuleUid,
                            modulePkg = ctx.bridge.modulePackageName
                        )
                    }
                    result
                }
                count++
            }

            for (method in ctx.adapter.findAllMethodsByName(queryHostClass, "getPackageUid")) {
                SafeHook.hookReplaceable(ctx.bridge, ctx.audit, name, method) { call ->
                    val targetPkg = findStringArg(call.args)
                    if (targetPkg == ctx.bridge.modulePackageName && shouldHide(Binder.getCallingUid())) {
                        return@hookReplaceable -1
                    }
                    call.proceed()
                }
                count++
            }
        }

        hookCount = count
        status = if (count > 0) PluginStatus.ACTIVE else PluginStatus.HOOK_FAILED
        statusMessage = if (count > 0) {
            "Active on ${if (useComputerEngine) "ComputerEngine" else "AppsFilter/PMS"} ($count hooks)"
        } else {
            "No PMS/ComputerEngine hooks resolved"
        }
        return health()
    }

    override fun onPolicyUpdated(snapshot: PolicySnapshot) {
        scheduleAsyncCacheRefresh(force = true)
    }

    override fun onPackagesChanged() {
        scheduleAsyncCacheRefresh(force = true)
    }

    private fun recordStealthAuditThrottled(callingUid: Int) {
        val now = System.currentTimeMillis()
        val prev = auditThrottleByUid[callingUid]
        if (prev != null && (now - prev) < 60_000L) return
        if (auditThrottleByUid.size > 64) {
            auditThrottleByUid.entries.removeIf { (now - it.value) > 60_000L }
        }
        auditThrottleByUid[callingUid] = now
        ctx.audit.record(
            type = AuditEventType.STEALTH_FILTERED,
            detail = "shouldFilterApplication callerUid=$callingUid"
        )
    }

    override fun health(): PluginHealth = PluginHealth(name, status, hookCount, statusMessage)

    private fun shouldHide(callingUid: Int): Boolean {
        if (inHookRefresh.get() == true) return false
        val appId = callingUid % SystemAdapter.PER_USER_RANGE
        if (appId < StealthAccessPolicy.FIRST_APPLICATION_UID) return false

        val snap = ctx.currentSnapshot()
        if (!snap.masterEnabled || !snap.stealthEnabled) return false

        // Event-driven cache refresh: only lazily bootstrap when UID is unknown (or 10min fallback)
        if (cachedModuleUid <= 0) {
            scheduleAsyncCacheRefresh(force = false)
        }
        return StealthAccessPolicy.shouldHideModuleFromCaller(
            stealthEnabled = true,
            callingUid = callingUid,
            moduleUid = cachedModuleUid,
            exemptUids = exemptUids
        )
    }

    private fun scheduleAsyncCacheRefresh(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && cachedModuleUid > 0 && (now - lastCacheRefreshMs) < 600_000L) return
        if (!force && (now - lastCacheRefreshMs) < 5_000L) return
        if (!refreshRunning.compareAndSet(false, true)) return

        lastCacheRefreshMs = now
        ctx.workerHandler.post {
            inHookRefresh.set(true)
            try {
                refreshUidCacheBlocking()
            } finally {
                inHookRefresh.set(false)
                refreshRunning.set(false)
            }
        }
    }

    private fun refreshUidCacheBlocking() {
        val context = ctx.adapter.getAmsContext() ?: return
        val pm = context.packageManager ?: return

        val token = Binder.clearCallingIdentity()
        try {
            val modulePkg = ctx.bridge.modulePackageName
            if (modulePkg.isNotBlank()) {
                runCatching { pm.getApplicationInfo(modulePkg, 0).uid }
                    .onSuccess { cachedModuleUid = it }
            }

            val allowSet = HashSet<Int>()
            for (pkg in ctx.currentSnapshot().stealthAllowlistPkgs) {
                runCatching { pm.getApplicationInfo(pkg, 0).uid }.onSuccess { uid ->
                    allowSet.add(uid)
                    allowSet.add(uid % SystemAdapter.PER_USER_RANGE)
                }
            }
            runCatching {
                val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                val homePkg = pm.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
                    ?.activityInfo?.packageName
                if (!homePkg.isNullOrBlank()) {
                    val uid = pm.getApplicationInfo(homePkg, 0).uid
                    allowSet.add(uid)
                    allowSet.add(uid % SystemAdapter.PER_USER_RANGE)
                }
            }
            exemptUids = allowSet
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    private fun extractCallingUid(args: Array<Any?>): Int {
        for (i in args.indices) {
            val arg = args[i]
            if (arg is Int && arg >= 0) return arg
        }
        return Binder.getCallingUid()
    }

    private fun findStringArg(args: Array<Any?>): String? {
        for (i in args.indices) {
            val arg = args[i]
            if (arg is String) return arg
        }
        return null
    }

    private fun findIntArrayArg(args: Array<Any?>): IntArray? {
        for (i in args.indices) {
            val arg = args[i]
            if (arg is IntArray) return arg
        }
        return null
    }

    private fun referencesModulePackage(args: Array<Any?>): Boolean {
        val modulePkg = ctx.bridge.modulePackageName
        if (modulePkg.isBlank()) return false
        for (i in args.indices) {
            val arg = args[i] ?: continue
            if (arg is String) {
                if (arg == modulePkg) return true
                continue
            }
            // Skip primitive wrappers quickly without touching reflection cache
            if (arg is Number || arg is Boolean) continue
            val m = resolveGetPackageNameMethod(arg.javaClass)
            if (m !== NO_METHOD) {
                val pkgName = runCatching { m.invoke(arg) as? String }.getOrNull()
                if (pkgName == modulePkg) return true
            }
        }
        return false
    }

    private fun resolveGetPackageNameMethod(clazz: Class<*>): Method {
        pkgNameMethodCache[clazz]?.let { return it }
        val resolved = runCatching { clazz.getMethod("getPackageName") }.getOrNull() ?: NO_METHOD
        if (pkgNameMethodCache.size < 64) {
            pkgNameMethodCache[clazz] = resolved
        }
        return resolved
    }

    companion object {
        // Sentinel method indicating a class has no zero-arg getPackageName()
        private val NO_METHOD: Method = Any::class.java.getMethod("toString")
    }
}
