package com.synapse.kernel.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import com.synapse.kernel.compat.OtaSafeguard
import com.synapse.kernel.compat.SystemAdapter
import com.synapse.kernel.plugins.MemoryDozePlugin
import com.synapse.kernel.plugins.NotificationAuditPlugin
import com.synapse.kernel.plugins.OemHansPlugin
import com.synapse.kernel.plugins.ProcessGuardPlugin
import com.synapse.kernel.plugins.StealthHidePlugin
import com.synapse.protocol.AuditEventType
import com.synapse.protocol.KernelHealthReport
import com.synapse.protocol.PluginHealth
import com.synapse.protocol.PolicyCodec
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `system_server` micro-kernel bootstrap coordinator.
 */
class MicroKernel private constructor(
    override val classLoader: ClassLoader,
    override val bridge: HookBridge,
    override val magicCode: Int,
    override val headerMagic: Int,
    override val expectedSignatureSha256: String
) : KernelContext {

    private val sharedWorkerThread by lazy {
        HandlerThread("synapse-kernel-io", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
    }
    override val workerHandler: Handler by lazy { Handler(sharedWorkerThread.looper) }

    override val audit = AuditRingBuffer(AuditRingBuffer.DEFAULT_CAPACITY)
    override val notifications = NotificationRingBuffer(NotificationRingBuffer.DEFAULT_CAPACITY)
    override val observerHub = KernelObserverHub { workerHandler }
    override val breaker = ResurrectionBreaker()
    override val adapter = SystemAdapter(classLoader, bridge)
    override val policyManager = PolicyManager(bridge, audit)
    override val otaSafeguard = OtaSafeguard(
        currentFingerprint = Build.FINGERPRINT ?: ""
    )

    private val bootTimeMs = System.currentTimeMillis()
    private val plugins = ArrayList<KernelPlugin>(5)
    private lateinit var processGuardPlugin: ProcessGuardPlugin
    private lateinit var ipcBridge: IpcBridge

    private fun start() {
        // 1. Load persisted rules from RemotePreferences (Direct Boot tolerant).
        policyManager.tryLoadFromRemotePreferences()
        policyManager.registerRemotePreferencesWatcher()

        val cachedFp = policyManager.snapshot.bundle.romFingerprint
        otaSafeguard.evaluate(cachedFp)
        if (otaSafeguard.otaChanged) {
            audit.record(
                type = AuditEventType.OTA_BYPASSED,
                detail = "OTA fingerprint changed: cached=${cachedFp.take(48)} current=${otaSafeguard.currentFingerprint.take(48)}"
            )
        }

        // 2. Register core plugins.
        processGuardPlugin = ProcessGuardPlugin()
        plugins.add(processGuardPlugin)
        plugins.add(OemHansPlugin())
        plugins.add(MemoryDozePlugin())
        plugins.add(StealthHidePlugin())
        plugins.add(NotificationAuditPlugin())

        for (plugin in plugins) {
            val h = runCatching { plugin.install(this) }.getOrElse { t ->
                bridge.logError("MicroKernel", "Plugin ${plugin.name} install failed", t)
                PluginHealth(
                    name = plugin.name,
                    status = com.synapse.protocol.PluginStatus.HOOK_FAILED,
                    hookCount = 0,
                    message = "${t.javaClass.simpleName}: ${t.message}"
                )
            }
            bridge.logInfo("MicroKernel", "Plugin ${h.name} => ${h.status} (${h.hookCount} hooks): ${h.message}")
        }

        // 3. Forward policy hot-updates to plugins.
        policyManager.addListener { snap ->
            for (plugin in plugins) {
                runCatching { plugin.onPolicyUpdated(snap) }
                    .onFailure { t -> bridge.logError("MicroKernel", "${plugin.name} onPolicyUpdated error", t) }
            }
        }

        // 4. Install AMS Magic Binder RPC bridge.
        ipcBridge = IpcBridge(
            ctx = this,
            onHealthRequest = { PolicyCodec.encodeHealth(buildHealthReport()) },
            onResetBreaker = { target -> breaker.reset(target) },
            onQueryProcesses = { pkgFilter ->
                val pairs = policyManager.snapshot.bundle.processRules.values
                    .filter { pkgFilter.isNullOrBlank() || it.pkg == pkgFilter }
                    .map { it.userId to it.pkg }
                    .toSet()
                PolicyCodec.encodeLiveProcesses(adapter.snapshotManagedProcesses(pairs))
            },
            onWakeRule = { ruleKey -> processGuardPlugin.wakeRuleByKey(ruleKey) }
        )
        ipcBridge.install()

        // 5. Hook AMS systemReady to wire Direct Boot user-unlock policy reload & package replacement cache invalidation.
        hookSystemReadyForDirectBootFallback()

        audit.record(
            type = AuditEventType.KERNEL_BOOT,
            detail = "v=$KERNEL_VERSION api=${Build.VERSION.SDK_INT} fw=${bridge.frameworkName}/${bridge.frameworkApiVersion} oom=${adapter.oomAdjusterImplName}"
        )
    }

    private fun hookSystemReadyForDirectBootFallback() {
        val amsClass = adapter.amsClass ?: return
        val systemReady = amsClass.declaredMethods.firstOrNull { it.name == "systemReady" } ?: return
        SafeHook.hookAfter(bridge, audit, "MicroKernel", systemReady) { call, _ ->
            adapter.captureAmsInstance(call.thisObject)
            val snap = policyManager.snapshot
            for (plugin in plugins) {
                runCatching { plugin.onPolicyUpdated(snap) }
            }
            val context = adapter.getAmsContext(call.thisObject) ?: return@hookAfter
            registerUnlockAndPackageReceiver(context)
        }
    }

    private fun notifyUserUnlocked(userId: Int) {
        if (!policyManager.loadedFromRemotePrefs) {
            policyManager.tryLoadFromRemotePreferences()
        }
        for (plugin in plugins) {
            runCatching { plugin.onUserUnlocked(userId) }
                .onFailure { t -> bridge.logError("MicroKernel", "${plugin.name} onUserUnlocked error", t) }
        }
    }

    private fun registerUnlockAndPackageReceiver(context: Context) {
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_USER_UNLOCKED)
                addAction(Intent.ACTION_BOOT_COMPLETED)
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    when (intent?.action) {
                        Intent.ACTION_USER_UNLOCKED, Intent.ACTION_BOOT_COMPLETED -> {
                            val uid = intent.getIntExtra("android.intent.extra.user_handle", 0).coerceAtLeast(0)
                            notifyUserUnlocked(uid)
                        }
                        Intent.ACTION_PACKAGE_ADDED,
                        Intent.ACTION_PACKAGE_REPLACED,
                        Intent.ACTION_PACKAGE_REMOVED -> {
                            val changedPkg = intent.data?.schemeSpecificPart
                            if (changedPkg == bridge.modulePackageName) {
                                ipcBridge.invalidateCallerCache()
                            }
                            for (plugin in plugins) {
                                runCatching { plugin.onPackagesChanged() }
                            }
                        }
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }
        } catch (t: Throwable) {
            bridge.logError("MicroKernel", "Failed registering unlock receiver", t)
        }
    }

    fun buildHealthReport(): KernelHealthReport {
        val snap = policyManager.snapshot
        return KernelHealthReport(
            kernelVersion = KERNEL_VERSION,
            apiLevel = Build.VERSION.SDK_INT,
            frameworkName = bridge.frameworkName,
            frameworkApiVersion = bridge.frameworkApiVersion,
            oomAdjusterImpl = adapter.oomAdjusterImplName,
            currentFingerprint = otaSafeguard.currentFingerprint,
            cachedFingerprint = otaSafeguard.cachedFingerprint,
            otaChanged = otaSafeguard.otaChanged,
            policyRevision = snap.bundle.revision,
            activeRuleCount = snap.activeRuleCount,
            auditCount = audit.totalRecorded(),
            uptimeMs = (System.currentTimeMillis() - bootTimeMs).coerceAtLeast(0L),
            plugins = plugins.map { it.health() }
        )
    }

    companion object {
        const val KERNEL_VERSION = "3.1.1"
        private val booted = AtomicBoolean(false)

        @Volatile
        var instance: MicroKernel? = null
            private set

        fun boot(
            classLoader: ClassLoader,
            bridge: HookBridge,
            magicCode: Int,
            headerMagic: Int,
            expectedSignatureSha256: String = ""
        ): MicroKernel? {
            if (!booted.compareAndSet(false, true)) return instance
            return try {
                MicroKernel(
                    classLoader = classLoader,
                    bridge = bridge,
                    magicCode = magicCode,
                    headerMagic = headerMagic,
                    expectedSignatureSha256 = expectedSignatureSha256
                ).also {
                    instance = it
                    it.start()
                }
            } catch (t: Throwable) {
                bridge.logError("MicroKernel", "Fatal boot failure (isolated)", t)
                null
            }
        }
    }
}
