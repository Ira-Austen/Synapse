package com.synapse.kernel.plugins

import android.content.pm.ApplicationInfo
import android.os.Binder
import com.synapse.kernel.compat.SystemAdapter
import com.synapse.kernel.core.KernelContext
import com.synapse.kernel.core.KernelPlugin
import com.synapse.kernel.core.PolicySnapshot
import com.synapse.kernel.core.SafeHook
import com.synapse.protocol.AuditEventType
import com.synapse.protocol.PluginHealth
import com.synapse.protocol.PluginStatus
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Pure-memory, zero-allocation hot-path Doze + background network allowlist plugin.
 */
class MemoryDozePlugin : KernelPlugin {
    override val name: String = "MemoryDozePlugin"

    private lateinit var ctx: KernelContext
    private var hookCount = 0
    private var status = PluginStatus.DISABLED
    private var statusMessage = "Not initialized"

    @Volatile
    private var npmsInstance: Any? = null

    @Volatile
    private var managedUids: Set<Int> = emptySet()

    @Volatile
    private var managedAppIds: Set<Int> = emptySet()

    private var updatePowerRestrictionsMethod: Method? = null
    private var updateDeviceIdleMethod: Method? = null
    private var npmsUidRulesLockField: Field? = null
    private val auditThrottleByUid = ConcurrentHashMap<Int, Long>(16)

    override fun install(ctx: KernelContext): PluginHealth {
        this.ctx = ctx
        var count = 0

        // 1. DeviceIdleController in-memory whitelist query hooks (zero-allocation O(1) lookup)
        val idleClass = ctx.adapter.deviceIdleControllerClass
        if (idleClass != null) {
            val idleTargets = listOf(
                "isPowerSaveWhitelistAppInternal",
                "isPowerSaveWhitelistExceptIdleAppInternal",
                "isPowerSaveWhitelistApp"
            )
            for (methodName in idleTargets) {
                for (method in ctx.adapter.findAllMethodsByName(idleClass, methodName)) {
                    if (method.returnType != Boolean::class.javaPrimitiveType &&
                        method.returnType != Boolean::class.javaObjectType
                    ) continue
                    SafeHook.hookReplaceable(ctx.bridge, ctx.audit, name, method) { call ->
                        val snap = ctx.currentSnapshot()
                        if (snap.masterEnabled && snap.memoryDozeBypassEnabled && snap.hasMemoryDozeRules) {
                            val pkg = findStringArg(call.args)
                            if (pkg != null && snap.isMemoryDozeBypassAnyUser(pkg)) {
                                return@hookReplaceable true
                            }
                        }
                        call.proceed()
                    }
                    count++
                }
            }
        }

        // 2. NetworkPolicyManagerService (NPMS) + BpfNetMaps coordination hooks
        val npmsClass = ctx.adapter.npmsClass
        if (npmsClass != null) {
            updatePowerRestrictionsMethod =
                ctx.adapter.findMethod(npmsClass, "updateRulesForPowerRestrictionsUL", Int::class.javaPrimitiveType!!)
            updateDeviceIdleMethod =
                ctx.adapter.findMethod(npmsClass, "updateRuleForDeviceIdleUL", Int::class.javaPrimitiveType!!)
            npmsUidRulesLockField = ctx.adapter.findField(npmsClass, "mUidRulesFirstLock")

            val whitelistCheckNames = listOf(
                "isWhitelistedFromPowerSaveUL",
                "isUidWhitelistedFromPowerSaveUL",
                "isAllowlistedFromPowerSaveUL"
            )
            for (methodName in whitelistCheckNames) {
                for (method in ctx.adapter.findAllMethodsByName(npmsClass, methodName)) {
                    if (method.returnType != Boolean::class.javaPrimitiveType &&
                        method.returnType != Boolean::class.javaObjectType
                    ) continue
                    val mName = method.name
                    SafeHook.hookReplaceable(ctx.bridge, ctx.audit, name, method) { call ->
                        captureNpms(call.thisObject)
                        val snap = ctx.currentSnapshot()
                        if (snap.masterEnabled && snap.memoryDozeBypassEnabled && snap.hasMemoryDozeRules) {
                            val uidOrAppId = findIntArg(call.args)
                            if (uidOrAppId >= 10_000 && isUidOrAppIdManaged(uidOrAppId)) {
                                recordDozeAuditThrottled(uidOrAppId, mName)
                                return@hookReplaceable true
                            }
                        }
                        call.proceed()
                    }
                    count++
                }
            }

            // Capture NPMS instance once at systemReady (no hot-path before-hook overhead on every rule update)
            for (method in ctx.adapter.findAllMethodsByName(npmsClass, "systemReady")) {
                SafeHook.hookBefore(ctx.bridge, ctx.audit, name, method) { call ->
                    captureNpms(call.thisObject)
                }
                count++
            }
        }

        refreshManagedUids(ctx.currentSnapshot())

        hookCount = count
        status = if (count > 0) PluginStatus.ACTIVE else PluginStatus.HOOK_FAILED
        statusMessage = if (count > 0) {
            "DeviceIdle + NPMS/BpfNetMaps active ($count hooks)"
        } else {
            "Failed hooking DeviceIdleController / NPMS"
        }
        return health()
    }

    override fun onPolicyUpdated(snapshot: PolicySnapshot) {
        val oldUids = managedUids
        refreshManagedUids(snapshot)
        val changedUids = oldUids + managedUids
        if (changedUids.isNotEmpty()) {
            syncNpmsRulesForUids(changedUids)
        }
    }

    override fun onPackagesChanged() {
        refreshManagedUids(ctx.currentSnapshot())
    }

    override fun health(): PluginHealth = PluginHealth(name, status, hookCount, statusMessage)

    private fun recordDozeAuditThrottled(uidOrAppId: Int, methodName: String) {
        val now = System.currentTimeMillis()
        val prev = auditThrottleByUid[uidOrAppId]
        if (prev != null && (now - prev) < 60_000L) return
        if (auditThrottleByUid.size > 32) {
            auditThrottleByUid.entries.removeIf { (now - it.value) > 60_000L }
        }
        auditThrottleByUid[uidOrAppId] = now
        ctx.audit.record(
            type = AuditEventType.DOZE_BPF_BYPASS,
            detail = "NPMS.$methodName(uid=$uidOrAppId) -> true"
        )
    }

    private fun captureNpms(instance: Any?) {
        if (instance != null && npmsInstance == null) {
            npmsInstance = instance
            if (managedUids.isNotEmpty()) {
                syncNpmsRulesForUids(managedUids)
            }
        }
    }

    private fun isUidOrAppIdManaged(uidOrAppId: Int): Boolean {
        if (managedUids.contains(uidOrAppId)) return true
        val appId = uidOrAppId % SystemAdapter.PER_USER_RANGE
        return managedAppIds.contains(appId)
    }

    private fun findStringArg(args: Array<Any?>): String? {
        for (i in args.indices) {
            val a = args[i]
            if (a is String) return a
        }
        return null
    }

    private fun findIntArg(args: Array<Any?>): Int {
        for (i in args.indices) {
            val a = args[i]
            if (a is Int) return a
        }
        return -1
    }

    private fun refreshManagedUids(snapshot: PolicySnapshot) {
        val pairs = snapshot.allMemoryDozeBypassPairs()
        if (pairs.isEmpty()) {
            managedUids = emptySet()
            managedAppIds = emptySet()
            return
        }
        val context = ctx.adapter.getAmsContext() ?: return
        val pm = context.packageManager ?: return
        val uids = HashSet<Int>(pairs.size)
        val appIds = HashSet<Int>(pairs.size)
        val token = Binder.clearCallingIdentity()
        try {
            val getAsUser = runCatching {
                pm.javaClass.getMethod(
                    "getApplicationInfoAsUser",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                )
            }.getOrNull()
            for ((userId, pkg) in pairs) {
                val appInfo = runCatching {
                    getAsUser?.invoke(pm, pkg, 0, userId) as? ApplicationInfo
                }.getOrNull() ?: runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
                val uid = appInfo?.uid ?: continue
                uids.add(uid)
                appIds.add(uid % SystemAdapter.PER_USER_RANGE)
            }
        } finally {
            Binder.restoreCallingIdentity(token)
        }
        managedUids = uids
        managedAppIds = appIds
    }

    private fun syncNpmsRulesForUids(uids: Set<Int>) {
        val npms = npmsInstance ?: return
        val updateMethod = updatePowerRestrictionsMethod ?: updateDeviceIdleMethod ?: return
        val lockObj = runCatching { npmsUidRulesLockField?.get(npms) }.getOrNull()
        val token = Binder.clearCallingIdentity()
        try {
            val runner = {
                for (uid in uids) {
                    runCatching { updateMethod.invoke(npms, uid) }
                }
            }
            if (lockObj != null) {
                synchronized(lockObj) { runner() }
            } else {
                runner()
            }
            ctx.audit.record(
                type = AuditEventType.DOZE_BPF_BYPASS,
                detail = "Synced NPMS/BpfNetMaps for ${uids.size} UIDs"
            )
        } catch (t: Throwable) {
            ctx.bridge.logError(name, "Failed syncing NPMS rules", t)
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }
}
