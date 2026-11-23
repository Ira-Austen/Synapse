package com.synapse.kernel.core

import android.os.Handler
import com.synapse.kernel.compat.OtaSafeguard
import com.synapse.kernel.compat.SystemAdapter
import com.synapse.protocol.PluginHealth

interface KernelContext {
    val classLoader: ClassLoader
    val bridge: HookBridge
    val adapter: SystemAdapter
    val otaSafeguard: OtaSafeguard
    val policyManager: PolicyManager
    val audit: AuditRingBuffer
    val notifications: NotificationRingBuffer
    val observerHub: KernelObserverHub
    val workerHandler: Handler
    val breaker: ResurrectionBreaker
    val magicCode: Int
    val headerMagic: Int
    val expectedSignatureSha256: String

    fun currentSnapshot(): PolicySnapshot = policyManager.snapshot
}

interface KernelPlugin {
    val name: String
    fun install(ctx: KernelContext): PluginHealth
    fun onPolicyUpdated(snapshot: PolicySnapshot) {}
    fun onUserUnlocked(userId: Int) {}
    fun onPackagesChanged() {}
    fun health(): PluginHealth
}
