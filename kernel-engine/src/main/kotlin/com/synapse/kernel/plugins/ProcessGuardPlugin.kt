package com.synapse.kernel.plugins

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Binder
import android.os.UserHandle
import com.synapse.kernel.compat.SystemAdapter
import com.synapse.kernel.core.KernelContext
import com.synapse.kernel.core.KernelPlugin
import com.synapse.kernel.core.SafeHook
import com.synapse.protocol.AuditEventType
import com.synapse.protocol.IpcProtocol
import com.synapse.protocol.PluginHealth
import com.synapse.protocol.PluginStatus
import com.synapse.protocol.ProcessRule
import com.synapse.protocol.WakeType
import java.util.concurrent.ConcurrentHashMap

/**
 * Core process protection plugin:
 * 1. Pre-commit clamping of `curAdj` and `curProcState` in `OomAdjuster`/`OomAdjusterModernImpl.applyOomAdjLSP`.
 * 2. PID-level AOSP `CachedAppOptimizer` freeze interception solely for `targetProcess`.
 * 3. Dual-hook (`cleanUpApplicationRecordLocked` + `appDiedLocked`) deduplicated off-lock resurrection with
 *    `FLAG_STOPPED` respect, sliding-window breaker, multi-user `UserHandle`, and 10s `DeviceIdleInternal` allowlist.
 */
class ProcessGuardPlugin : KernelPlugin {
    override val name: String = "ProcessGuardPlugin"

    private lateinit var ctx: KernelContext
    private var hookCount = 0
    private var status = PluginStatus.DISABLED
    private var statusMessage = "Not initialized"

    // Deduplicate cleanUpApplicationRecordLocked + appDiedLocked within a short window
    private val recentDeathDedupe = ConcurrentHashMap<String, Long>()
    private val bootWokenUsers = ConcurrentHashMap.newKeySet<Int>()
    private val auditThrottle = ConcurrentHashMap<String, Long>(32)

    private fun shouldRecordAudit(key: String, minIntervalMs: Long = 30_000L): Boolean {
        val now = System.currentTimeMillis()
        val prev = auditThrottle[key]
        if (prev != null && (now - prev) < minIntervalMs) return false
        if (auditThrottle.size > 64) {
            auditThrottle.entries.removeIf { (now - it.value) > minIntervalMs }
        }
        auditThrottle[key] = now
        return true
    }

    override fun install(ctx: KernelContext): PluginHealth {
        this.ctx = ctx
        var count = 0

        // 1. OomAdjuster / OomAdjusterModernImpl.applyOomAdjLSP (hook BEFORE commit!)
        val oomClasses = linkedSetOf<Class<*>>()
        ctx.adapter.oomAdjusterClass?.let { oomClasses.add(it) }
        ctx.adapter.oomAdjusterModernClass?.let { oomClasses.add(it) }

        val hookedApplyMethods = HashSet<String>()
        for (clazz in oomClasses) {
            for (method in ctx.adapter.findAllMethodsByName(clazz, "applyOomAdjLSP")) {
                val sigKey = "${method.declaringClass.name}#${method.parameterTypes.joinToString(",") { it.name }}"
                if (!hookedApplyMethods.add(sigKey)) continue
                SafeHook.hookBefore(ctx.bridge, ctx.audit, name, method) { call ->
                    handleApplyOomAdjBefore(call.args)
                }
                count++
            }
        }

        // 2. CachedAppOptimizer PID-level & sibling anti-binder-kill freeze interception
        val caoClass = ctx.adapter.cachedAppOptimizerClass
        if (caoClass != null) {
            val freezeNames = listOf(
                "freezeAppAsyncLSP",
                "freezeAppAsyncImmediateLSP",
                "freezeAppAsyncInternalLSP",
                "freezeProcess"
            )
            for (methodName in freezeNames) {
                for (method in ctx.adapter.findAllMethodsByName(caoClass, methodName)) {
                    val returnsBoolean = method.returnType == Boolean::class.javaPrimitiveType
                    SafeHook.hookReplaceable(ctx.bridge, ctx.audit, name, method) { call ->
                        if (shouldBlockAospFreeze(call.args)) {
                            return@hookReplaceable if (returnsBoolean) false else null
                        }
                        call.proceed()
                    }
                    count++
                }
            }
        }

        // 3. Death / Recents swipe-away hooks on AMS (cleanUpApplicationRecordLocked + appDiedLocked)
        val amsClass = ctx.adapter.amsClass
        if (amsClass != null) {
            for (methodName in listOf("cleanUpApplicationRecordLocked", "appDiedLocked")) {
                for (method in ctx.adapter.findAllMethodsByName(amsClass, methodName)) {
                    SafeHook.hookAfter(ctx.bridge, ctx.audit, name, method) { call, _ ->
                        ctx.adapter.captureAmsInstance(call.thisObject)
                        handleProcessCleanup(call.args, source = methodName)
                    }
                    count++
                }
            }
        }

        hookCount = count
        status = if (count > 0) PluginStatus.ACTIVE else PluginStatus.HOOK_FAILED
        statusMessage = if (count > 0) {
            "Active on ${ctx.adapter.oomAdjusterImplName} ($count hooks)"
        } else {
            "No AMS/OomAdjuster hooks resolved"
        }
        return health()
    }

    override fun health(): PluginHealth = PluginHealth(name, status, hookCount, statusMessage)

    override fun onUserUnlocked(userId: Int) {
        if (bootWokenUsers.contains(userId)) return
        // Delay 6s after unlock/boot-completed so PackageManager / AMS / network settle, then start
        // any managed push services that are not running (respecting FLAG_STOPPED and breaker).
        ctx.workerHandler.postDelayed({
            val snap = ctx.currentSnapshot()
            if (!snap.masterEnabled || !snap.hasActiveProcessRules) return@postDelayed
            if (bootWokenUsers.contains(userId)) return@postDelayed
            var allHandled = true
            for (rule in snap.activeRulesForUser(userId)) {
                if (rule.allowResurrect &&
                    rule.wakeType != WakeType.KEEP_ALIVE_ONLY &&
                    !rule.wakeComponent.isNullOrBlank()
                ) {
                    if (!executeResurrection(rule)) {
                        allHandled = false
                    }
                }
            }
            if (allHandled) {
                bootWokenUsers.add(userId)
            }
        }, 6_000L)
    }

    private fun handleApplyOomAdjBefore(args: Array<Any?>) {
        val snap = ctx.currentSnapshot()
        if (!snap.masterEnabled || !snap.hasActiveProcessRules) return

        val pr = findProcessRecordArg(args) ?: return
        val procName = ctx.adapter.getProcessName(pr) ?: return
        val userId = ctx.adapter.getProcessUserId(pr)
        val rule = snap.matchProcess(userId, procName) ?: return

        val curAdj = ctx.adapter.getCurAdj(pr)
        if (curAdj != null && curAdj > rule.keepAliveAdj) {
            if (ctx.adapter.setCurAdj(pr, rule.keepAliveAdj)) {
                if (shouldRecordAudit("adj:$userId:$procName")) {
                    ctx.audit.record(
                        type = AuditEventType.ADJ_CLAMPED,
                        pkg = rule.pkg,
                        processName = procName,
                        userId = userId,
                        detail = "$curAdj->${rule.keepAliveAdj}"
                    )
                    ctx.observerHub.notifyEvent(IpcProtocol.OBSERVER_EVENT_PROCESS or IpcProtocol.OBSERVER_EVENT_AUDIT)
                }
            }
        }

        if (rule.protectProcState) {
            val curState = ctx.adapter.getCurProcState(pr)
            if (curState != null && curState > SystemAdapter.PROCESS_STATE_SERVICE) {
                if (ctx.adapter.setCurProcState(pr, SystemAdapter.PROCESS_STATE_SERVICE)) {
                    if (shouldRecordAudit("state:$userId:$procName")) {
                        ctx.audit.record(
                            type = AuditEventType.PROC_STATE_PROTECTED,
                            pkg = rule.pkg,
                            processName = procName,
                            userId = userId,
                            detail = "state $curState->${SystemAdapter.PROCESS_STATE_SERVICE}"
                        )
                        ctx.observerHub.notifyEvent(IpcProtocol.OBSERVER_EVENT_PROCESS or IpcProtocol.OBSERVER_EVENT_AUDIT)
                    }
                }
            }
        }
    }

    private fun shouldBlockAospFreeze(args: Array<Any?>): Boolean {
        val snap = ctx.currentSnapshot()
        if (!snap.masterEnabled || !snap.hasActiveProcessRules) return false

        val pr = findProcessRecordArg(args) ?: return false
        val procName = ctx.adapter.getProcessName(pr) ?: return false
        val userId = ctx.adapter.getProcessUserId(pr)

        // 1. Always exempt the managed target push process itself
        val directRule = snap.matchProcess(userId, procName)
        if (directRule != null && directRule.exemptAospFreezer) {
            if (shouldRecordAudit("frz:$userId:$procName")) {
                ctx.audit.record(
                    type = AuditEventType.FREEZE_BLOCKED,
                    pkg = directRule.pkg,
                    processName = procName,
                    userId = userId,
                    detail = "AOSP CachedAppOptimizer bypassed (target)"
                )
                ctx.observerHub.notifyEvent(IpcProtocol.OBSERVER_EVENT_AUDIT)
            }
            return true
        }

        // 2. Anti-Binder-Freeze-Kill ONLY for the MAIN process (`procName == pkg`) of a managed package:
        //    Bloat subprocesses like `:gameservice`, `:tool`, `:qzone`, `:miniapp`, `:webview`,
        //    `:sandboxed_process` MUST remain frozen by AOSP Freezer to preserve maximum battery life!
        val pkg = ctx.adapter.getProcessPackageName(pr) ?: return false
        if (procName == pkg && snap.isAospFreezerExemptPackage(userId, pkg)) {
            if (shouldRecordAudit("frz:$userId:$procName")) {
                ctx.audit.record(
                    type = AuditEventType.FREEZE_BLOCKED,
                    pkg = pkg,
                    processName = procName,
                    userId = userId,
                    detail = "AOSP CachedAppOptimizer bypassed (main anti-binder-kill)"
                )
                ctx.observerHub.notifyEvent(IpcProtocol.OBSERVER_EVENT_AUDIT)
            }
            return true
        }
        return false
    }

    private fun handleProcessCleanup(args: Array<Any?>, source: String) {
        val snap = ctx.currentSnapshot()
        if (!snap.masterEnabled || !snap.hasActiveProcessRules) return

        val pr = findProcessRecordArg(args) ?: return
        val procName = ctx.adapter.getProcessName(pr) ?: return
        val userId = ctx.adapter.getProcessUserId(pr)

        // Notify foreground UI whenever ANY process of a managed package dies (even main process)
        val pkg = ctx.adapter.getProcessPackageName(pr)
        if (pkg != null && snap.matchPackage(userId, pkg).isNotEmpty()) {
            ctx.observerHub.notifyEvent(IpcProtocol.OBSERVER_EVENT_PROCESS)
        }

        val rule = snap.matchProcess(userId, procName) ?: return

        if (!rule.allowResurrect || rule.wakeType == WakeType.KEEP_ALIVE_ONLY || rule.wakeComponent.isNullOrBlank()) {
            return
        }

        val now = System.currentTimeMillis()
        val dedupeKey = "${rule.processKey}:${ctx.adapter.getProcessPid(pr)}"
        val prev = recentDeathDedupe.put(dedupeKey, now)
        if (prev != null && (now - prev) < 1_000L) {
            return
        }
        if (recentDeathDedupe.size > 128) {
            recentDeathDedupe.entries.removeIf { (now - it.value) > 10_000L }
        }

        val delayMs = snap.globalSettings.resurrectDelayMs
        ctx.audit.record(
            type = AuditEventType.RESURRECT_SCHEDULED,
            pkg = rule.pkg,
            processName = procName,
            userId = userId,
            detail = "via=$source delay=${delayMs}ms"
        )
        ctx.workerHandler.postDelayed({ executeResurrection(rule) }, delayMs)
    }

    /**
     * Explicit user-initiated wake directly inside `system_server` (zero Root needed).
     */
    fun wakeRuleByKey(ruleKey: String): Pair<Boolean, String> {
        val snap = ctx.currentSnapshot()
        val rule = snap.bundle.processRules[ruleKey]
            ?: return false to "未找到对应的保活策略 ($ruleKey)"
        val wakeComponent = rule.wakeComponent
        if (wakeComponent.isNullOrBlank()) {
            return false to "该应用未配置唤醒组件"
        }
        val context = ctx.adapter.getAmsContext()
            ?: return false to "系统服务尚未就绪"
        val token = Binder.clearCallingIdentity()
        return try {
            grantTempPowerAllowlist(rule.pkg, rule.userId, durationMs = 10_000L)
            val comp = ComponentName.unflattenFromString(wakeComponent)
            val intent = Intent().apply {
                if (comp != null) component = comp else setPackage(rule.pkg)
                if (!rule.wakeAction.isNullOrBlank()) action = rule.wakeAction
            }
            val userHandle = ctx.adapter.userHandleOf(rule.userId)
            val ok = when (rule.wakeType) {
                WakeType.SERVICE, WakeType.KEEP_ALIVE_ONLY -> startServiceCompat(context, intent, userHandle)
                WakeType.BROADCAST -> sendBroadcastCompat(context, intent, userHandle)
            }
            ctx.audit.record(
                type = if (ok) AuditEventType.RESURRECT_OK else AuditEventType.RESURRECT_FAILED,
                pkg = rule.pkg,
                processName = rule.targetProcess,
                userId = rule.userId,
                detail = "manual_wake type=${rule.wakeType} comp=$wakeComponent"
            )
            ctx.observerHub.notifyEvent(IpcProtocol.OBSERVER_EVENT_PROCESS or IpcProtocol.OBSERVER_EVENT_AUDIT)
            if (ok) true to "OK" else false to "唤醒服务启动被系统拒绝"
        } catch (t: Throwable) {
            false to "${t.javaClass.simpleName}: ${t.message}"
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    private fun executeResurrection(rule: ProcessRule): Boolean {
        val snap = ctx.currentSnapshot()
        val liveRule = snap.matchProcess(rule.userId, rule.targetProcess) ?: return true
        val wakeComponent = liveRule.wakeComponent
        if (!liveRule.allowResurrect || wakeComponent.isNullOrBlank()) return true

        val context = ctx.adapter.getAmsContext() ?: return false
        val token = Binder.clearCallingIdentity()
        try {
            // 1. Respect explicit user Force Stop (FLAG_STOPPED)
            if (isPackageForceStopped(context, liveRule.pkg, liveRule.userId)) {
                ctx.audit.record(
                    type = AuditEventType.RESURRECT_SKIPPED_STOPPED,
                    pkg = liveRule.pkg,
                    processName = liveRule.targetProcess,
                    userId = liveRule.userId,
                    detail = "FLAG_STOPPED set"
                )
                return true
            }

            // 2. Secondary liveness check
            if (ctx.adapter.isProcessAliveInAms(liveRule.userId, liveRule.targetProcess)) {
                ctx.audit.record(
                    type = AuditEventType.RESURRECT_SKIPPED_ALIVE,
                    pkg = liveRule.pkg,
                    processName = liveRule.targetProcess,
                    userId = liveRule.userId,
                    detail = "Process already revived"
                )
                return true
            }

            // 3. Sliding-window circuit breaker
            val gs = snap.globalSettings
            val decision = ctx.breaker.tryAcquire(
                processKey = liveRule.processKey,
                nowMs = System.currentTimeMillis(),
                windowMs = gs.breakerWindowMs,
                threshold = gs.breakerThreshold,
                cooldownMs = gs.breakerCooldownMs
            )
            if (decision != com.synapse.kernel.core.ResurrectionBreaker.Decision.ALLOW) {
                if (decision == com.synapse.kernel.core.ResurrectionBreaker.Decision.OPENED_NOW) {
                    ctx.audit.record(
                        type = AuditEventType.BREAKER_OPEN,
                        pkg = liveRule.pkg,
                        processName = liveRule.targetProcess,
                        userId = liveRule.userId,
                        detail = "threshold=${gs.breakerThreshold}/${gs.breakerWindowMs}ms cooldown=${gs.breakerCooldownMs}ms"
                    )
                }
                return true
            }

            // 4. Grant temporary 10s Doze & background start allowlist via DeviceIdleInternal
            grantTempPowerAllowlist(liveRule.pkg, liveRule.userId, durationMs = 10_000L)

            // 5. Dispatch explicit Service or Broadcast to target user
            val comp = ComponentName.unflattenFromString(wakeComponent)
            val intent = Intent().apply {
                if (comp != null) component = comp else setPackage(liveRule.pkg)
                if (!liveRule.wakeAction.isNullOrBlank()) action = liveRule.wakeAction
            }
            val userHandle = ctx.adapter.userHandleOf(liveRule.userId)

            val ok = when (liveRule.wakeType) {
                WakeType.SERVICE -> startServiceCompat(context, intent, userHandle)
                WakeType.BROADCAST -> sendBroadcastCompat(context, intent, userHandle)
                WakeType.KEEP_ALIVE_ONLY -> false
            }

            ctx.audit.record(
                type = if (ok) AuditEventType.RESURRECT_OK else AuditEventType.RESURRECT_FAILED,
                pkg = liveRule.pkg,
                processName = liveRule.targetProcess,
                userId = liveRule.userId,
                detail = "type=${liveRule.wakeType} comp=${liveRule.wakeComponent}"
            )
            ctx.observerHub.notifyEvent(IpcProtocol.OBSERVER_EVENT_PROCESS or IpcProtocol.OBSERVER_EVENT_AUDIT)
            return ok
        } catch (t: Throwable) {
            ctx.audit.record(
                type = AuditEventType.RESURRECT_FAILED,
                pkg = liveRule.pkg,
                processName = liveRule.targetProcess,
                userId = liveRule.userId,
                detail = "${t.javaClass.simpleName}: ${t.message}"
            )
            ctx.bridge.logError(name, "Resurrection failed for ${liveRule.processKey}", t)
            return false
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    private fun isPackageForceStopped(context: Context, pkg: String, userId: Int): Boolean {
        return runCatching {
            val pm = context.packageManager
            val appInfo = runCatching {
                val m = pm.javaClass.getMethod(
                    "getApplicationInfoAsUser",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                )
                m.invoke(pm, pkg, 0, userId) as? ApplicationInfo
            }.getOrNull() ?: pm.getApplicationInfo(pkg, 0)
            (appInfo.flags and ApplicationInfo.FLAG_STOPPED) != 0
        }.getOrDefault(true) // Fail-safe: if unreadable, do not resurrect
    }

    private fun grantTempPowerAllowlist(pkg: String, userId: Int, durationMs: Long) {
        val localIdle = ctx.adapter.getLocalDeviceIdleController() ?: return
        runCatching {
            // Try modern Android 12-17 signature first (7/8 params with tempAllowListType & reasonCode)
            val methods = localIdle.javaClass.methods.filter { it.name == "addPowerSaveTempWhitelistApp" }
            for (m in methods) {
                val p = m.parameterTypes
                when (p.size) {
                    8 -> {
                        // (int callingUid, String packageName, long durationMs, int tempAllowListType, int userId, boolean sync, int reasonCode, String reason)
                        m.invoke(localIdle, 1000, pkg, durationMs, 0, userId, false, 300, "synapse-guard")
                        return
                    }
                    7 -> {
                        // (int callingUid, String packageName, long durationMs, int userId, boolean sync, int reasonCode, String reason)
                        m.invoke(localIdle, 1000, pkg, durationMs, userId, false, 300, "synapse-guard")
                        return
                    }
                    6 -> {
                        // Android 11: (int callingUid, String packageName, long durationMs, int userId, boolean sync, String reason)
                        m.invoke(localIdle, 1000, pkg, durationMs, userId, false, "synapse-guard")
                        return
                    }
                }
            }
        }
    }

    private fun startServiceCompat(context: Context, intent: Intent, userHandle: UserHandle?): Boolean {
        return runCatching {
            if (userHandle != null) {
                val m = context.javaClass.getMethod("startServiceAsUser", Intent::class.java, UserHandle::class.java)
                m.invoke(context, intent, userHandle)
            } else {
                context.startService(intent)
            }
            true
        }.getOrDefault(false)
    }

    private fun sendBroadcastCompat(context: Context, intent: Intent, userHandle: UserHandle?): Boolean {
        return runCatching {
            if (userHandle != null) {
                val m = context.javaClass.getMethod("sendBroadcastAsUser", Intent::class.java, UserHandle::class.java)
                m.invoke(context, intent, userHandle)
            } else {
                context.sendBroadcast(intent)
            }
            true
        }.getOrDefault(false)
    }

    private fun findProcessRecordArg(args: Array<Any?>): Any? {
        val prClass = ctx.adapter.processRecordClass ?: return null
        for (i in args.indices) {
            val arg = args[i]
            if (arg != null && prClass.isInstance(arg)) return arg
        }
        return null
    }
}
