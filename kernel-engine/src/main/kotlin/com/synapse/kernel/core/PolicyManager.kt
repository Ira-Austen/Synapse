package com.synapse.kernel.core

import com.synapse.protocol.AuditEventType
import com.synapse.protocol.IpcProtocol
import com.synapse.protocol.KernelPolicyBundle
import com.synapse.protocol.PolicyCodec
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Manages the active immutable [PolicySnapshot] with atomic `@Volatile` swaps.
 * Loads initial state from read-only `RemotePreferences` (with Direct Boot fallback)
 * and accepts runtime pushes from [IpcBridge].
 */
class PolicyManager(
    private val bridge: HookBridge,
    private val audit: AuditRingBuffer
) {
    @Volatile
    var snapshot: PolicySnapshot = PolicySnapshot.EMPTY
        private set

    @Volatile
    var loadedFromRemotePrefs: Boolean = false
        private set

    private val listeners = CopyOnWriteArrayList<(PolicySnapshot) -> Unit>()

    fun addListener(listener: (PolicySnapshot) -> Unit) {
        listeners.add(listener)
    }

    fun tryLoadFromRemotePreferences(): Boolean {
        return try {
            val raw = bridge.readRemotePrefsJson(
                IpcProtocol.PREFS_NAME,
                IpcProtocol.PREFS_KEY_BUNDLE_JSON
            )
            if (!raw.isNullOrBlank()) {
                val bundle = PolicyCodec.decodeBundle(raw)
                applyBundle(bundle, source = "RemotePreferences")
                loadedFromRemotePrefs = true
                true
            } else {
                false
            }
        } catch (t: Throwable) {
            bridge.logError("PolicyManager", "DirectBoot/RemotePreferences initial read deferred", t)
            false
        }
    }

    fun registerRemotePreferencesWatcher() {
        try {
            bridge.registerRemotePrefsListener(
                IpcProtocol.PREFS_NAME,
                IpcProtocol.PREFS_KEY_BUNDLE_JSON
            ) { raw ->
                if (!raw.isNullOrBlank()) {
                    runCatching { PolicyCodec.decodeBundle(raw) }
                        .onSuccess { bundle ->
                            applyBundle(bundle, source = "RemotePrefsWatcher")
                            loadedFromRemotePrefs = true
                        }
                        .onFailure { t ->
                            bridge.logError("PolicyManager", "Failed decoding RemotePrefs update", t)
                        }
                }
            }
        } catch (t: Throwable) {
            bridge.logError("PolicyManager", "Failed registering RemotePrefs listener", t)
        }
    }

    fun applyJson(json: String, source: String = "MagicBinder"): PolicySnapshot {
        val bundle = PolicyCodec.decodeBundle(json)
        return applyBundle(bundle, source)
    }

    fun applyBundle(bundle: KernelPolicyBundle, source: String): PolicySnapshot {
        val next = PolicySnapshot.from(bundle)
        snapshot = next
        audit.record(
            type = AuditEventType.POLICY_APPLIED,
            detail = "source=$source rev=${bundle.revision} activeRules=${next.activeRuleCount}"
        )
        for (listener in listeners) {
            runCatching { listener(next) }
                .onFailure { t -> bridge.logError("PolicyManager", "Policy listener error", t) }
        }
        return next
    }
}
