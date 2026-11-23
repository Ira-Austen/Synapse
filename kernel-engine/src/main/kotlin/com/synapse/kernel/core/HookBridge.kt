package com.synapse.kernel.core

import com.synapse.protocol.AuditEventType
import java.lang.reflect.Member

/**
 * Framework-agnostic hook invocation context (bridges libxposed API 101/102 Chain and Legacy API 82).
 */
interface HookCall {
    val member: Member
    val thisObject: Any?
    val args: Array<Any?>
    fun proceed(): Any?
    fun proceed(vararg newArgs: Any?): Any?
}

fun interface HookInterceptor {
    @Throws(Throwable::class)
    fun intercept(call: HookCall): Any?
}

interface UnhookHandle {
    fun unhook()
}

/**
 * Framework-agnostic hooking & logging SPI implemented by `modern` (libxposed 101/102)
 * and `legacy` (API 82) entry modules.
 */
interface HookBridge {
    val frameworkName: String
    val frameworkApiVersion: Int
    val modulePackageName: String

    fun hook(member: Member, interceptor: HookInterceptor): UnhookHandle
    fun readRemotePrefsJson(prefsName: String, key: String): String?
    fun registerRemotePrefsListener(prefsName: String, key: String, onChanged: (String?) -> Unit)
    fun logInfo(tag: String, message: String)
    fun logError(tag: String, message: String, throwable: Throwable? = null)
}

/**
 * Safety isolation cabin for all `system_server` hooks:
 * - Never lets a Synapse bug propagate an uncaught exception into `system_server`.
 * - Records diagnostic audit events on hook failure.
 */
object SafeHook {

    fun hookBefore(
        bridge: HookBridge,
        audit: AuditRingBuffer,
        pluginName: String,
        member: Member,
        onBefore: (HookCall) -> Unit
    ): UnhookHandle = bridge.hook(member) { call ->
        try {
            onBefore(call)
        } catch (t: Throwable) {
            audit.record(
                type = AuditEventType.HOOK_ERROR,
                detail = "$pluginName before ${member.declaringClass.simpleName}.${member.name}: ${t.javaClass.simpleName}: ${t.message}"
            )
            bridge.logError(pluginName, "before hook error on ${member.name}", t)
        }
        call.proceed()
    }

    fun hookAfter(
        bridge: HookBridge,
        audit: AuditRingBuffer,
        pluginName: String,
        member: Member,
        onAfter: (HookCall, Any?) -> Unit
    ): UnhookHandle = bridge.hook(member) { call ->
        val result = call.proceed()
        try {
            onAfter(call, result)
        } catch (t: Throwable) {
            audit.record(
                type = AuditEventType.HOOK_ERROR,
                detail = "$pluginName after ${member.declaringClass.simpleName}.${member.name}: ${t.javaClass.simpleName}: ${t.message}"
            )
            bridge.logError(pluginName, "after hook error on ${member.name}", t)
        }
        result
    }

    fun hookReplaceable(
        bridge: HookBridge,
        audit: AuditRingBuffer,
        pluginName: String,
        member: Member,
        block: (HookCall) -> Any?
    ): UnhookHandle = bridge.hook(member) { call ->
        var proceeded = false
        val guarded = object : HookCall by call {
            override fun proceed(): Any? {
                proceeded = true
                return call.proceed()
            }

            override fun proceed(vararg newArgs: Any?): Any? {
                proceeded = true
                return call.proceed(*newArgs)
            }
        }
        try {
            block(guarded)
        } catch (t: Throwable) {
            if (proceeded) {
                // Exception originated from the target system method itself; propagate silently without false HOOK_ERROR.
                throw t
            }
            audit.record(
                type = AuditEventType.HOOK_ERROR,
                detail = "$pluginName intercept ${member.declaringClass.simpleName}.${member.name}: ${t.javaClass.simpleName}: ${t.message}"
            )
            bridge.logError(pluginName, "intercept error on ${member.name}", t)
            call.proceed()
        }
    }
}
