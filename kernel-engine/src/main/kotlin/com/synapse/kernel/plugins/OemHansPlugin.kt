package com.synapse.kernel.plugins

import android.content.pm.ResolveInfo
import com.synapse.kernel.compat.OtaSafeguard
import com.synapse.kernel.compat.SystemAdapter
import com.synapse.kernel.core.KernelContext
import com.synapse.kernel.core.KernelPlugin
import com.synapse.kernel.core.SafeHook
import com.synapse.protocol.AuditEventType
import com.synapse.protocol.IpcProtocol
import com.synapse.protocol.PluginHealth
import com.synapse.protocol.PluginStatus
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Non-destructive, zero-allocation hot-path ColorOS Hans / OFreezer & AppStartup exemption plugin
 * spanning ColorOS 12–17 (Android 12–17).
 *
 * Optimizations:
 * - Caches `OplusHansPackage` pkg/uid reflection accessors per Class once (0 reflection scans on hot path).
 * - Throttles duplicate `HANS_BYPASSED` audit events per package (30s) while maintaining 100% real-time exemption.
 * - Never blocks unfreeze operations (`writeDevFile(..., freeze == false)` always proceeds).
 * - Exempts managed packages from ColorOS `ActiveServicesExtImpl` / `OplusAppStartupManager` background
 *   service & broadcast startup blocks so both manual wake and auto-resurrection actually spawn the process.
 */
class OemHansPlugin : KernelPlugin {
    override val name: String = "OemHansPlugin"

    private lateinit var ctx: KernelContext
    private var hookCount = 0
    private var status = PluginStatus.UNSUPPORTED_ROM
    private var statusMessage = "Oplus Hans not detected"

    // Cached reflection accessors per concrete OplusHansPackage class -> zero reflection overhead
    private val pkgAccessorCache = ConcurrentHashMap<Class<*>, Any>(8)
    private val uidAccessorCache = ConcurrentHashMap<Class<*>, Any>(8)
    private val auditThrottleByPkg = ConcurrentHashMap<String, Long>(16)

    override fun install(ctx: KernelContext): PluginHealth {
        this.ctx = ctx

        val importanceClass = ctx.adapter.oplusHansImportanceClass
        val cgroupClass = ctx.adapter.oplusHansCGroupClass
        val managerClass = ctx.adapter.oplusHansManagerClass
        val activeServicesExtClass = ctx.adapter.oplusActiveServicesExtClass
        val startupManagerClass = ctx.adapter.oplusAppStartupManagerClass

        val allowlistMethods = ArrayList<Method>().apply {
            addAll(ctx.adapter.findAllMethodsByName(importanceClass, "isImportantPkg"))
            addAll(ctx.adapter.findAllMethodsByName(importanceClass, "isExemptFreezeImApp"))
            addAll(ctx.adapter.findAllMethodsByName(managerClass, "inCachedFreezeWhiteList"))
            addAll(ctx.adapter.findAllMethodsByName(managerClass, "inCachedFreezeKillWhiteList"))
        }.filter {
            it.returnType == Boolean::class.javaPrimitiveType || it.returnType == Boolean::class.javaObjectType
        }

        val freezeMethods = ArrayList<Method>().apply {
            addAll(ctx.adapter.findAllMethodsByName(cgroupClass, "hansFreezeLocked"))
            addAll(ctx.adapter.findAllMethodsByName(cgroupClass, "writeDevFile"))
            addAll(ctx.adapter.findAllMethodsByName(managerClass, "hansFreezeLocked"))
            addAll(ctx.adapter.findAllMethodsByName(managerClass, "freezeCgroupUid"))
        }

        // ColorOS startup / service-launch interception gates (returns true when BLOCKING start)
        val startupPreventServiceMethods = ArrayList<Method>().apply {
            addAll(ctx.adapter.findAllMethodsByName(activeServicesExtClass, "interceptStartServiceLockedAfterStartMode"))
            addAll(ctx.adapter.findAllMethodsByName(activeServicesExtClass, "interceptBringUpServices"))
            addAll(ctx.adapter.findAllMethodsByName(startupManagerClass, "shouldPreventStartService"))
        }.filter {
            it.returnType == Boolean::class.javaPrimitiveType || it.returnType == Boolean::class.javaObjectType
        }

        val startupValidStartMethods = ctx.adapter.findAllMethodsByName(startupManagerClass, "validStartProcess")
            .filter { m ->
                (m.returnType == Boolean::class.javaPrimitiveType || m.returnType == Boolean::class.javaObjectType) &&
                    m.parameterTypes.size >= 2 &&
                    m.parameterTypes[0] == String::class.java &&
                    m.parameterTypes[1] == Int::class.javaPrimitiveType
            }

        val startupPreventReceiverMethods = ctx.adapter.findAllMethodsByName(startupManagerClass, "shouldPreventSendReceiver")
            .filter {
                it.returnType == Boolean::class.javaPrimitiveType || it.returnType == Boolean::class.javaObjectType
            }

        val startupAllowRestartMethods = ctx.adapter.findAllMethodsByName(startupManagerClass, "isAllowRestartService")
            .filter {
                it.returnType == Boolean::class.javaPrimitiveType || it.returnType == Boolean::class.javaObjectType
            }

        val startupProtectWhitelistMethods = ctx.adapter.findAllMethodsByName(startupManagerClass, "inProtectWhiteList")
            .filter { m ->
                (m.returnType == Boolean::class.javaPrimitiveType || m.returnType == Boolean::class.javaObjectType) &&
                    m.parameterTypes.size == 1 &&
                    m.parameterTypes[0] == String::class.java
            }

        val gate = ctx.otaSafeguard.allowOemHookActivation {
            allowlistMethods.isNotEmpty() ||
                freezeMethods.isNotEmpty() ||
                startupPreventServiceMethods.isNotEmpty() ||
                startupValidStartMethods.isNotEmpty()
        }

        when (gate) {
            OtaSafeguard.OemGateResult.BYPASSED_ON_OTA -> {
                status = PluginStatus.OTA_CHANGED_BYPASSED
                statusMessage = "OTA fingerprint changed & OEM signatures drifted; bypassed safely"
                ctx.audit.record(AuditEventType.OTA_BYPASSED, detail = "$name bypassed safely")
                return health()
            }
            OtaSafeguard.OemGateResult.UNSUPPORTED_ROM -> {
                status = PluginStatus.UNSUPPORTED_ROM
                statusMessage = "ColorOS OplusHans classes not present on this ROM"
                return health()
            }
            OtaSafeguard.OemGateResult.OK,
            OtaSafeguard.OemGateResult.VERIFIED_AFTER_OTA -> Unit
        }

        var count = 0

        for (method in allowlistMethods) {
            val detailTag = "${method.declaringClass.simpleName}.${method.name} -> true"
            SafeHook.hookReplaceable(ctx.bridge, ctx.audit, name, method) { call ->
                val snap = ctx.currentSnapshot()
                if (snap.masterEnabled && snap.oemHansBypassEnabled && snap.hasOemHansRules) {
                    val pkg = extractPackageName(call.args)
                    if (pkg != null && snap.isHansExemptAnyUser(pkg)) {
                        val uid = extractUid(call.args)
                        val userId = if (uid >= 0) uid / SystemAdapter.PER_USER_RANGE else -1
                        val exempt = if (userId >= 0) snap.isHansExempt(userId, pkg) else true
                        if (exempt) {
                            recordHansAuditThrottled(pkg, userId.coerceAtLeast(0), detailTag)
                            return@hookReplaceable true
                        }
                    }
                }
                call.proceed()
            }
            count++
        }

        for (method in freezeMethods) {
            val isWriteDevFile = method.name == "writeDevFile"
            val returnsBool = method.returnType == Boolean::class.javaPrimitiveType
            val detailTag = "${method.declaringClass.simpleName}.${method.name} bypassed"
            SafeHook.hookReplaceable(ctx.bridge, ctx.audit, name, method) { call ->
                val snap = ctx.currentSnapshot()
                if (snap.masterEnabled && snap.oemHansBypassEnabled && snap.hasOemHansRules) {
                    if (isWriteDevFile && !hasTrueBooleanArg(call.args)) {
                        return@hookReplaceable call.proceed()
                    }
                    val pkg = extractPackageName(call.args)
                    if (pkg != null && snap.isHansExemptAnyUser(pkg)) {
                        val uid = extractUid(call.args)
                        val userId = if (uid >= 0) uid / SystemAdapter.PER_USER_RANGE else 0
                        if (snap.isHansExempt(userId, pkg) || snap.isHansExemptAnyUser(pkg)) {
                            recordHansAuditThrottled(pkg, userId, detailTag)
                            return@hookReplaceable if (returnsBool) false else null
                        }
                    }
                }
                call.proceed()
            }
            count++
        }

        // 3. Exempt managed packages from ColorOS service start interception (`ss_startup`, `sv_guardelf`, etc.)
        for (method in startupPreventServiceMethods) {
            val detailTag = "${method.declaringClass.simpleName}.${method.name} -> false"
            SafeHook.hookReplaceable(ctx.bridge, ctx.audit, name, method) { call ->
                val snap = ctx.currentSnapshot()
                if (snap.masterEnabled && snap.oemHansBypassEnabled && snap.hasOemHansRules) {
                    val sr = findServiceRecordArg(call.args)
                    if (sr != null) {
                        val pkg = ctx.adapter.getServiceRecordPackageName(sr)
                        val userId = ctx.adapter.getServiceRecordUserId(sr)
                        if (pkg != null && snap.isHansExempt(userId, pkg)) {
                            recordHansAuditThrottled(pkg, userId, detailTag)
                            return@hookReplaceable false
                        }
                    }
                }
                call.proceed()
            }
            count++
        }

        // 4. Exempt managed packages from `OplusAppStartupManager.validStartProcess(pkg, userId, type)` (true = prevent)
        for (method in startupValidStartMethods) {
            val detailTag = "${method.declaringClass.simpleName}.${method.name} -> false"
            SafeHook.hookReplaceable(ctx.bridge, ctx.audit, name, method) { call ->
                val snap = ctx.currentSnapshot()
                if (snap.masterEnabled && snap.oemHansBypassEnabled && snap.hasOemHansRules) {
                    val pkg = call.args.getOrNull(0) as? String
                    val userId = (call.args.getOrNull(1) as? Int) ?: 0
                    if (pkg != null && snap.isHansExempt(userId, pkg)) {
                        recordHansAuditThrottled(pkg, userId, detailTag)
                        return@hookReplaceable false
                    }
                }
                call.proceed()
            }
            count++
        }

        // 5. Exempt managed packages from ColorOS broadcast startup interception
        for (method in startupPreventReceiverMethods) {
            val detailTag = "${method.declaringClass.simpleName}.${method.name} -> false"
            SafeHook.hookReplaceable(ctx.bridge, ctx.audit, name, method) { call ->
                val snap = ctx.currentSnapshot()
                if (snap.masterEnabled && snap.oemHansBypassEnabled && snap.hasOemHansRules) {
                    val target = extractReceiverTarget(call.args)
                    if (target != null && snap.isHansExempt(target.second, target.first)) {
                        recordHansAuditThrottled(target.first, target.second, detailTag)
                        return@hookReplaceable false
                    }
                }
                call.proceed()
            }
            count++
        }

        // 6. Allow service restarts & startup protection whitelist for managed packages
        for (method in startupAllowRestartMethods) {
            val detailTag = "${method.declaringClass.simpleName}.${method.name} -> true"
            SafeHook.hookReplaceable(ctx.bridge, ctx.audit, name, method) { call ->
                val snap = ctx.currentSnapshot()
                if (snap.masterEnabled && snap.oemHansBypassEnabled && snap.hasOemHansRules) {
                    val pr = findProcessRecordArg(call.args)
                    if (pr != null) {
                        val pkg = ctx.adapter.getProcessPackageName(pr)
                        val userId = ctx.adapter.getProcessUserId(pr)
                        if (pkg != null && snap.isHansExempt(userId, pkg)) {
                            recordHansAuditThrottled(pkg, userId, detailTag)
                            return@hookReplaceable true
                        }
                    }
                }
                call.proceed()
            }
            count++
        }

        for (method in startupProtectWhitelistMethods) {
            val detailTag = "${method.declaringClass.simpleName}.${method.name} -> true"
            SafeHook.hookReplaceable(ctx.bridge, ctx.audit, name, method) { call ->
                val snap = ctx.currentSnapshot()
                if (snap.masterEnabled && snap.oemHansBypassEnabled && snap.hasOemHansRules) {
                    val pkg = call.args.getOrNull(0) as? String
                    if (pkg != null && snap.isHansExemptAnyUser(pkg)) {
                        recordHansAuditThrottled(pkg, 0, detailTag)
                        return@hookReplaceable true
                    }
                }
                call.proceed()
            }
            count++
        }

        hookCount = count
        status = if (count > 0) PluginStatus.ACTIVE else PluginStatus.UNSUPPORTED_ROM
        statusMessage = if (gate == OtaSafeguard.OemGateResult.VERIFIED_AFTER_OTA) {
            "ColorOS Hans & Startup verified after OTA ($count hooks)"
        } else {
            "ColorOS Hans & Startup active ($count hooks)"
        }
        return health()
    }

    private fun findServiceRecordArg(args: Array<Any?>): Any? {
        val srClass = ctx.adapter.serviceRecordClass ?: return null
        for (i in args.indices) {
            val arg = args[i]
            if (arg != null && srClass.isInstance(arg)) return arg
        }
        return null
    }

    private fun findProcessRecordArg(args: Array<Any?>): Any? {
        val prClass = ctx.adapter.processRecordClass ?: return null
        for (i in args.indices) {
            val arg = args[i]
            if (arg != null && prClass.isInstance(arg)) return arg
        }
        return null
    }

    private fun extractReceiverTarget(args: Array<Any?>): Pair<String, Int>? {
        val bfClass = ctx.adapter.broadcastFilterClass
        for (i in args.indices) {
            val arg = args[i] ?: continue
            if (arg is ResolveInfo) {
                val ai = arg.activityInfo
                val pkg = ai?.packageName
                if (!pkg.isNullOrBlank()) {
                    val uid = ai.applicationInfo?.uid ?: 0
                    val userId = if (uid >= 0) uid / SystemAdapter.PER_USER_RANGE else 0
                    return pkg to userId
                }
            }
            if (bfClass != null && bfClass.isInstance(arg)) {
                val pkg = ctx.adapter.getBroadcastFilterPackageName(arg)
                if (!pkg.isNullOrBlank()) {
                    val userId = ctx.adapter.getBroadcastFilterUserId(arg)
                    return pkg to userId
                }
            }
        }
        return null
    }

    override fun health(): PluginHealth = PluginHealth(name, status, hookCount, statusMessage)

    private fun recordHansAuditThrottled(pkg: String, userId: Int, detail: String) {
        val now = System.currentTimeMillis()
        val prev = auditThrottleByPkg[pkg]
        if (prev != null && (now - prev) < 30_000L) return
        if (auditThrottleByPkg.size > 32) {
            auditThrottleByPkg.entries.removeIf { (now - it.value) > 30_000L }
        }
        auditThrottleByPkg[pkg] = now
        ctx.audit.record(
            type = AuditEventType.HANS_BYPASSED,
            pkg = pkg,
            userId = userId,
            detail = detail
        )
        ctx.observerHub.notifyEvent(IpcProtocol.OBSERVER_EVENT_AUDIT)
    }

    private fun hasTrueBooleanArg(args: Array<Any?>): Boolean {
        for (i in args.indices) {
            if (args[i] == true) return true
        }
        return false
    }

    private fun extractPackageName(args: Array<Any?>): String? {
        for (i in args.indices) {
            val arg = args[i] ?: continue
            if (arg is String) {
                if (arg.indexOf('.') > 0 && arg.indexOf(' ') < 0) return arg
                continue
            }
            if (arg is Number || arg is Boolean) continue
            val acc = resolvePkgAccessor(arg.javaClass)
            val v = when (acc) {
                is Field -> runCatching { acc.get(arg) as? String }.getOrNull()
                is Method -> runCatching { acc.invoke(arg) as? String }.getOrNull()
                else -> null
            }
            if (!v.isNullOrBlank()) return v
        }
        return null
    }

    private fun extractUid(args: Array<Any?>): Int {
        for (i in args.indices) {
            val arg = args[i] ?: continue
            if (arg is Int) {
                if (arg >= 10_000) return arg
                continue
            }
            if (arg is String || arg is Boolean || arg is Number) continue
            val acc = resolveUidAccessor(arg.javaClass)
            val v = when (acc) {
                is Field -> runCatching { acc.getInt(arg) }.getOrNull()
                is Method -> runCatching { acc.invoke(arg) as? Int }.getOrNull()
                else -> null
            }
            if (v != null && v >= 10_000) return v
        }
        return -1
    }

    private fun resolvePkgAccessor(cls: Class<*>): Any {
        pkgAccessorCache[cls]?.let { return it }
        val resolved: Any = ctx.adapter.findField(cls, "mPkgName")
            ?: ctx.adapter.findField(cls, "mPackageName")
            ?: ctx.adapter.findField(cls, "pkgName")
            ?: ctx.adapter.findMethod(cls, "getPkgName")
            ?: NO_ACCESSOR
        if (pkgAccessorCache.size < 32) {
            pkgAccessorCache[cls] = resolved
        }
        return resolved
    }

    private fun resolveUidAccessor(cls: Class<*>): Any {
        uidAccessorCache[cls]?.let { return it }
        val resolved: Any = ctx.adapter.findField(cls, "mUid")
            ?: ctx.adapter.findField(cls, "uid")
            ?: ctx.adapter.findMethod(cls, "getUid")
            ?: NO_ACCESSOR
        if (uidAccessorCache.size < 32) {
            uidAccessorCache[cls] = resolved
        }
        return resolved
    }

    companion object {
        private val NO_ACCESSOR = Any()
    }
}
