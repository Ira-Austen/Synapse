package com.synapse.app.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.synapse.agent.ledger.MutationLedger
import com.synapse.agent.ledger.MutationRecord
import com.synapse.agent.llm.LlmConfig
import com.synapse.agent.llm.OpenAiCompatibleLlmClient
import com.synapse.agent.orchestrator.AgentStreamEvent
import com.synapse.agent.orchestrator.BatchHostingItem
import com.synapse.agent.orchestrator.ReActOrchestrator
import com.synapse.agent.shell.PersistentRootShell
import com.synapse.agent.tools.AgentToolRegistry
import com.synapse.agent.tools.KernelBridgeGateway
import com.synapse.app.data.AppInspectionResult
import com.synapse.app.data.AppInspector
import com.synapse.app.data.InstalledAppItem
import com.synapse.app.data.LiveProcessInfo
import com.synapse.app.data.LiveSocketInspector
import com.synapse.app.data.ManagedAppProcessGroup
import com.synapse.app.data.ProcessInspector
import com.synapse.app.data.ProcessMonitorSnapshot
import com.synapse.app.ipc.KernelIpcClient
import com.synapse.app.xposed.XposedServiceBridge
import com.synapse.protocol.AuditEvent
import com.synapse.protocol.IpcProtocol
import com.synapse.protocol.KernelHealthReport
import com.synapse.protocol.KernelPolicyBundle
import com.synapse.protocol.LiveProcessSnapshot
import com.synapse.protocol.NotificationHistoryRecord
import com.synapse.protocol.PolicyCodec
import com.synapse.protocol.ProcessRule
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TerminalEntry(
    val tag: String,
    val body: String,
    val isError: Boolean = false
)

data class SynapseUiState(
    val kernelHealth: KernelHealthReport? = null,
    val kernelStatusMessage: String = "正在连接保护引擎...",
    val auditEvents: List<AuditEvent> = emptyList(),
    val notificationHistory: List<NotificationHistoryRecord> = emptyList(),
    val appLabelCache: Map<String, String> = emptyMap(),
    val installedApps: List<InstalledAppItem> = emptyList(),
    val appsLoading: Boolean = false,
    val inspectedApp: AppInspectionResult? = null,
    val inspectingApp: Boolean = false,
    val processSnapshot: ProcessMonitorSnapshot = ProcessMonitorSnapshot(emptyList(), rootAvailable = true),
    val isPullRefreshing: Boolean = false,
    val eventPushConnected: Boolean = false,
    val rootGranted: Boolean? = null,
    val policyBundle: KernelPolicyBundle = KernelPolicyBundle(),
    val ledgerRecords: List<MutationRecord> = emptyList(),
    val terminalLog: List<TerminalEntry> = emptyList(),
    val agentRunning: Boolean = false,
    val batchQueue: List<BatchHostingItem> = emptyList(),
    val llmConfig: LlmConfig = LlmConfig(),
    val launcherAliasEnabled: Boolean = true,
    val toastMessage: String? = null
)

class SynapseViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext: Context = application.applicationContext
    private val localPrefs = appContext.getSharedPreferences("synapse_manager_prefs", Context.MODE_PRIVATE)
    private val ledger = MutationLedger(File(appContext.filesDir, "mutation_ledger.json"))
    private val notifStoreFile = File(appContext.filesDir, "notification_history.json")
    private val labelMap = ConcurrentHashMap<String, String>()

    private val _uiState = MutableStateFlow(
        SynapseUiState(
            policyBundle = loadInitialBundle(),
            notificationHistory = loadSavedNotifications(),
            ledgerRecords = ledger.listAll(),
            llmConfig = loadLlmConfig(),
            launcherAliasEnabled = isLauncherAliasEnabled()
        )
    )
    val uiState: StateFlow<SynapseUiState> = _uiState.asStateFlow()
    val xposedServiceState = XposedServiceBridge.state

    private var activeAgentJob: Job? = null

    init {
        refreshAllOnResume()
        // Reconcile persisted rules once XposedService finishes async binding so rules stored in
        // RemotePreferences are never missed on cold start or overwrite install.
        viewModelScope.launch {
            XposedServiceBridge.state.collect { xState ->
                if (xState.connected) {
                    reconcilePolicyStorage()
                }
            }
        }
    }

    private suspend fun reconcilePolicyStorage() {
        val remoteJson = withContext(Dispatchers.IO) { XposedServiceBridge.readRemotePolicyJsonOnly() }
        val localJson = withContext(Dispatchers.IO) { XposedServiceBridge.readLocalPolicyJsonOnly(appContext) }
        val remoteBundle = remoteJson?.let { runCatching { PolicyCodec.decodeBundle(it) }.getOrNull() }
        val localBundle = localJson?.let { runCatching { PolicyCodec.decodeBundle(it) }.getOrNull() }
        val current = _uiState.value.policyBundle

        // Pick the richest/newest bundle across current memory, RemotePreferences, and local mirror
        val candidates = listOfNotNull(current, remoteBundle, localBundle)
        val best = candidates.maxWithOrNull(
            compareBy<KernelPolicyBundle> { it.processRules.isNotEmpty() }
                .thenBy { it.revision }
                .thenBy { it.processRules.size }
        ) ?: return

        val changed = best != current
        if (changed) {
            resolveLabelsForPackages(best.processRules.values.map { it.pkg })
            _uiState.update {
                it.copy(
                    policyBundle = best,
                    appLabelCache = HashMap(labelMap)
                )
            }
        }
        // Mirror best bundle back to both local & remote stores if either was missing/stale
        if (best.processRules.isNotEmpty() && (localBundle == null || remoteBundle == null || localBundle.revision < best.revision || remoteBundle.revision < best.revision)) {
            withContext(Dispatchers.IO) {
                XposedServiceBridge.writeRemotePolicyJson(appContext, PolicyCodec.encodeBundle(best))
            }
        }
        if (changed && best.processRules.isNotEmpty()) {
            KernelIpcClient.pushPolicy(best)
            refreshManagedProcessesSync()
        }
    }

    /**
     * Pure event-driven foreground subscription:
     * - Syncs once immediately when UI enters foreground (ON_RESUME).
     * - Registers a one-way IBinder observer with `system_server` (linkToDeath guarded).
     * - Zero periodic timers / zero polling loops; UI updates instantaneously when real events arrive.
     * - Automatically unregisters on ON_PAUSE.
     */
    suspend fun subscribeForegroundEvents() {
        refreshAllOnResume()
        val regRes = KernelIpcClient.registerObserver { mask ->
            handleKernelPushEvent(mask)
        }
        _uiState.update { it.copy(eventPushConnected = regRes.isSuccess) }
        try {
            awaitCancellation()
        } finally {
            _uiState.update { it.copy(eventPushConnected = false) }
            KernelIpcClient.unregisterObserver()
        }
    }

    private fun handleKernelPushEvent(mask: Int) {
        viewModelScope.launch {
            if ((mask and IpcProtocol.OBSERVER_EVENT_NOTIFICATION) != 0) {
                refreshNotificationsOnly(silent = true)
            }
            if ((mask and IpcProtocol.OBSERVER_EVENT_PROCESS) != 0) {
                refreshManagedProcesses(showPullIndicator = false)
            }
            if ((mask and IpcProtocol.OBSERVER_EVENT_AUDIT) != 0) {
                refreshKernelHealth(showPullIndicator = false)
            }
        }
    }

    fun refreshAllOnResume() {
        viewModelScope.launch {
            refreshKernelHealthSync()
            refreshManagedProcessesSync()
        }
    }

    fun pullRefreshDashboard() {
        viewModelScope.launch {
            _uiState.update { it.copy(isPullRefreshing = true) }
            refreshKernelHealthSync()
            refreshManagedProcessesSync()
            delay(180L)
            _uiState.update { it.copy(isPullRefreshing = false) }
        }
    }

    fun pullRefreshProcesses() {
        viewModelScope.launch {
            _uiState.update { it.copy(isPullRefreshing = true) }
            refreshManagedProcessesSync()
            delay(160L)
            _uiState.update { it.copy(isPullRefreshing = false) }
        }
    }

    fun pullRefreshNotifications() {
        viewModelScope.launch {
            _uiState.update { it.copy(isPullRefreshing = true) }
            refreshNotificationsSync(silent = true)
            delay(160L)
            _uiState.update { it.copy(isPullRefreshing = false) }
        }
    }

    fun refreshKernelHealth(showPullIndicator: Boolean = false) {
        viewModelScope.launch {
            if (showPullIndicator) _uiState.update { it.copy(isPullRefreshing = true) }
            refreshKernelHealthSync()
            if (showPullIndicator) _uiState.update { it.copy(isPullRefreshing = false) }
        }
    }

    private suspend fun refreshKernelHealthSync() {
        val healthRes = KernelIpcClient.getHealth()
        val auditRes = KernelIpcClient.drainAudit()
        val notifRes = KernelIpcClient.drainNotifications()

        val freshNotifs = notifRes.getOrDefault(emptyList())
        val mergedNotifs = if (freshNotifs.isNotEmpty()) {
            val combined = (_uiState.value.notificationHistory + freshNotifs)
                .distinctBy { "${it.timestampMs}|${it.pkg}|${it.title}|${it.text}" }
                .sortedByDescending { it.timestampMs }
                .take(MAX_STORED_NOTIFICATIONS)
            saveNotificationsAsync(combined)
            combined
        } else {
            _uiState.value.notificationHistory
        }

        resolveLabelsForPackages(
            mergedNotifs.map { it.pkg } + _uiState.value.policyBundle.processRules.values.map { it.pkg }
        )

        _uiState.update { st ->
            val mergedAudit = auditRes.getOrNull()?.let { fresh ->
                (st.auditEvents + fresh).takeLast(120)
            } ?: st.auditEvents
            if (healthRes.isSuccess) {
                st.copy(
                    kernelHealth = healthRes.getOrNull(),
                    kernelStatusMessage = "后台保活保护正常运行中",
                    auditEvents = mergedAudit,
                    notificationHistory = mergedNotifs,
                    appLabelCache = HashMap(labelMap)
                )
            } else {
                st.copy(
                    kernelHealth = null,
                    kernelStatusMessage = healthRes.exceptionOrNull()?.message ?: "未检测到保护引擎",
                    auditEvents = mergedAudit,
                    notificationHistory = mergedNotifs,
                    appLabelCache = HashMap(labelMap)
                )
            }
        }
    }

    fun refreshNotificationsOnly(silent: Boolean = true) {
        viewModelScope.launch {
            refreshNotificationsSync(silent)
        }
    }

    private suspend fun refreshNotificationsSync(silent: Boolean) {
        val res = KernelIpcClient.drainNotifications()
        if (res.isFailure) {
            if (!silent) setToast("暂时无法读取推送记录，请确认模块已启用")
            return
        }
        val fresh = res.getOrDefault(emptyList())
        val combined = (_uiState.value.notificationHistory + fresh)
            .distinctBy { "${it.timestampMs}|${it.pkg}|${it.title}|${it.text}" }
            .sortedByDescending { it.timestampMs }
            .take(MAX_STORED_NOTIFICATIONS)
        if (fresh.isNotEmpty()) {
            saveNotificationsAsync(combined)
        }
        resolveLabelsForPackages(combined.map { it.pkg })
        _uiState.update {
            it.copy(
                notificationHistory = combined,
                appLabelCache = HashMap(labelMap)
            )
        }
    }

    fun clearNotificationHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { notifStoreFile.delete() }
            _uiState.update { it.copy(notificationHistory = emptyList()) }
            setToast("已清空推送记录")
        }
    }

    fun loadInstalledAppsIfNeeded(forceReload: Boolean = false) {
        if (!forceReload && (_uiState.value.installedApps.isNotEmpty() || _uiState.value.appsLoading)) return
        viewModelScope.launch {
            _uiState.update { it.copy(appsLoading = true) }
            val apps = withContext(Dispatchers.IO) {
                AppInspector.loadInstalledApps(appContext)
            }
            for (app in apps) {
                labelMap[app.pkg] = app.label
            }
            _uiState.update {
                it.copy(
                    installedApps = apps,
                    appsLoading = false,
                    appLabelCache = HashMap(labelMap)
                )
            }
        }
    }

    fun inspectAppForRule(pkg: String, userTriggeredProbe: Boolean = false) {
        if (pkg.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(inspectingApp = true) }
            // Phase 1 (<30ms): instant static manifest + brand-aware Push SDK classification
            val staticResult = withContext(Dispatchers.IO) {
                AppInspector.inspectAppComponents(appContext, pkg, liveProbe = null)
            }
            labelMap[pkg] = staticResult.label
            _uiState.update {
                it.copy(
                    inspectedApp = staticResult,
                    appLabelCache = HashMap(labelMap)
                )
            }
            // Phase 2 (~100ms): overlay live ESTABLISHED TCP/UDP socket proof if app is running
            val liveProbe = withContext(Dispatchers.IO) {
                LiveSocketInspector.probePackageSockets(pkg)
            }
            val merged = withContext(Dispatchers.IO) {
                AppInspector.inspectAppComponents(appContext, pkg, liveProbe = liveProbe)
            }
            _uiState.update { st ->
                val updated = st.copy(
                    rootGranted = liveProbe.rootAvailable,
                    inspectingApp = false,
                    appLabelCache = HashMap(labelMap)
                )
                // Guard against user quickly switching to a different app while probing
                if (st.inspectedApp?.pkg == pkg) {
                    updated.copy(inspectedApp = merged)
                } else {
                    updated
                }
            }
            if (userTriggeredProbe) {
                when {
                    !liveProbe.rootAvailable ->
                        setToast("未获得 Root 权限，请在 KernelSU / Magisk 中授权后再实测长连接")
                    merged.anyLiveSocketVerified ->
                        setToast("实测成功：已定位活跃推送长连接")
                    merged.anyProcessRunning ->
                        setToast("应用运行中，但当前暂无活跃长连接（可能尚未联网或已断线）")
                    else ->
                        setToast("该应用当前未运行，请先点「打开应用」运行一次后再实测")
                }
            }
        }
    }

    fun checkRootAccess(showToast: Boolean = true) {
        viewModelScope.launch {
            val granted = withContext(Dispatchers.IO) {
                val shell = PersistentRootShell()
                try {
                    shell.verifyRootAccess()
                } finally {
                    shell.close()
                }
            }
            _uiState.update { it.copy(rootGranted = granted) }
            if (showToast) {
                setToast(
                    if (granted) "已获得 Root 权限，实测连接与 AI 排查均可用"
                    else "未获得 Root 权限，请在 KernelSU / Magisk 中为 System Calibrator 开启授权"
                )
            }
        }
    }

    fun launchTargetApp(pkg: String) {
        val intent = appContext.packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            setToast("该应用没有可打开的桌面入口")
            return
        }
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching {
            appContext.startActivity(intent)
        }.onFailure {
            setToast("无法打开应用：${it.message}")
        }
    }

    fun clearInspectedApp() {
        _uiState.update { it.copy(inspectedApp = null, inspectingApp = false) }
    }

    fun refreshManagedProcesses(showPullIndicator: Boolean = false) {
        viewModelScope.launch {
            if (showPullIndicator) _uiState.update { it.copy(isPullRefreshing = true) }
            refreshManagedProcessesSync()
            if (showPullIndicator) _uiState.update { it.copy(isPullRefreshing = false) }
        }
    }

    private suspend fun refreshManagedProcessesSync() {
        val rules = _uiState.value.policyBundle.processRules.values.toList()
        if (rules.isEmpty()) {
            _uiState.update {
                it.copy(processSnapshot = ProcessMonitorSnapshot(emptyList(), rootAvailable = true))
            }
            return
        }
        resolveLabelsForPackages(rules.map { it.pkg })

        // Fast path: <1ms direct memory query from system_server (zero Root needed!)
        val kernelRes = KernelIpcClient.queryProcesses()
        val snapshot = if (kernelRes.isSuccess) {
            buildSnapshotFromKernel(rules, kernelRes.getOrDefault(emptyList()))
        } else {
            // Graceful fallback for older pre-reboot kernel: Root shell snapshot
            withContext(Dispatchers.IO) {
                ProcessInspector.inspectManagedApps(rules) { pkg -> labelMap[pkg] ?: pkg }
            }
        }
        _uiState.update {
            it.copy(
                processSnapshot = snapshot,
                appLabelCache = HashMap(labelMap)
            )
        }
    }

    private fun buildSnapshotFromKernel(
        rules: List<ProcessRule>,
        raw: List<LiveProcessSnapshot>
    ): ProcessMonitorSnapshot {
        val groups = rules.map { rule ->
            val procs = raw
                .filter { it.pkg == rule.pkg && it.userId == rule.userId }
                .map { p ->
                    val isTarget = p.processName == rule.targetProcess
                    val isMain = p.processName == rule.pkg
                    val shortName = when {
                        isMain -> "主进程"
                        p.processName.contains(':') -> p.processName.substringAfter(':')
                        else -> p.processName
                    }
                    LiveProcessInfo(
                        pid = p.pid,
                        uid = p.uid,
                        processName = p.processName,
                        shortName = shortName,
                        rssKb = p.rssKb,
                        oomScoreAdj = p.curAdj,
                        isFrozen = p.frozen,
                        isProtectedTarget = isTarget,
                        isMainProcess = isMain
                    )
                }
                .sortedWith(
                    compareByDescending<LiveProcessInfo> { it.isProtectedTarget }
                        .thenByDescending { it.isMainProcess }
                        .thenBy { it.processName }
                )
            ManagedAppProcessGroup(
                rule = rule,
                appLabel = labelMap[rule.pkg] ?: rule.appName,
                processes = procs
            )
        }
        return ProcessMonitorSnapshot(groups = groups, rootAvailable = true)
    }

    fun wakeRuleProcess(rule: ProcessRule) {
        viewModelScope.launch {
            val label = resolveSingleLabel(rule.pkg)
            val res = wakeRuleInternal(rule)
            if (res.isSuccess) {
                // Wait for async process spawn and verify real liveness before reporting success
                delay(700L)
                refreshManagedProcessesSync()
                var group = _uiState.value.processSnapshot.groups
                    .firstOrNull { it.rule.ruleKey == rule.ruleKey }
                if (group == null || group.aliveCount == 0) {
                    delay(700L)
                    refreshManagedProcessesSync()
                    group = _uiState.value.processSnapshot.groups
                        .firstOrNull { it.rule.ruleKey == rule.ruleKey }
                }
                when {
                    group?.targetAlive == true ->
                        setToast("已唤醒 $label 的推送进程")
                    (group?.aliveCount ?: 0) > 0 ->
                        setToast("已唤醒 $label（存活 ${group!!.aliveCount} 个进程，推送子进程待就绪）")
                    else ->
                        setToast("已发送唤醒指令，但 $label 未拉起（可能被系统自启动拦截或所选服务会自动退出；请重启激活新版内核或在「调整设置」更换唤醒服务）")
                }
            } else {
                setToast("唤醒失败：${res.exceptionOrNull()?.message ?: "请先手动打开一次应用"}")
            }
        }
    }

    fun requestSystemScope() {
        XposedServiceBridge.requestSystemScope { _, msg ->
            setToast(msg)
        }
    }

    fun resetKernelBreaker() {
        viewModelScope.launch {
            val res = KernelIpcClient.resetBreaker()
            setToast(if (res.isSuccess) "已恢复自动拉起限制" else "操作失败：${res.exceptionOrNull()?.message}")
            refreshKernelHealth()
        }
    }

    fun upsertRule(rule: ProcessRule) {
        viewModelScope.launch {
            val resolvedName = resolveSingleLabel(rule.pkg)
            val enriched = if (rule.appName.isBlank() || rule.appName == rule.pkg) {
                rule.copy(appName = resolvedName)
            } else {
                rule
            }
            val res = persistAndPushRule(enriched)
            if (res.isFailure) {
                setToast("保存失败：${res.exceptionOrNull()?.message}")
                return@launch
            }

            // Refresh current live processes first to see if targetProcess is already running
            refreshManagedProcessesSync()
            val alreadyAlive = _uiState.value.processSnapshot.groups
                .firstOrNull { it.rule.ruleKey == enriched.ruleKey }
                ?.targetAlive == true

            if (enriched.enabled && !alreadyAlive && enriched.allowResurrect && !enriched.wakeComponent.isNullOrBlank()) {
                val wakeRes = wakeRuleInternal(enriched)
                if (wakeRes.isSuccess) {
                    delay(900L)
                    refreshManagedProcessesSync()
                    val wokeGroup = _uiState.value.processSnapshot.groups
                        .firstOrNull { it.rule.ruleKey == enriched.ruleKey }
                    if (wokeGroup?.targetAlive == true || (wokeGroup?.aliveCount ?: 0) > 0) {
                        setToast("已开启保活，并自动唤醒 ${enriched.appName} 推送进程")
                    } else {
                        setToast("规则已生效，但后台拉起被系统拦截或服务已退出（请重启激活新版内核或先手动打开一次 ${enriched.appName}）")
                    }
                } else {
                    val reason = wakeRes.exceptionOrNull()?.message.orEmpty()
                    if (reason.contains("Root")) {
                        setToast("规则已生效，但自动唤醒失败：未授予 Root 权限（或重启激活免 Root 内核唤醒）")
                    } else {
                        setToast("规则已生效，但未能后台拉起推送：$reason")
                    }
                }
            } else if (enriched.enabled && !alreadyAlive) {
                setToast("已开启保活：下次打开 ${enriched.appName} 后将自动在后台保护")
            } else {
                setToast("已生效：${enriched.appName} 保活规则已保存")
            }
        }
    }

    private suspend fun wakeRuleInternal(rule: ProcessRule): Result<String> {
        val kernelRes = KernelIpcClient.wakeRule(rule.ruleKey)
        if (kernelRes.isSuccess) return kernelRes
        val rootRes = withContext(Dispatchers.IO) { ProcessInspector.wakeRuleNow(rule) }
        if (rootRes.isFailure && rootRes.exceptionOrNull()?.message?.contains("Root") == true) {
            _uiState.update { it.copy(rootGranted = false) }
        }
        return rootRes
    }

    fun toggleRuleEnabled(rule: ProcessRule, enabled: Boolean) {
        upsertRule(rule.copy(enabled = enabled))
    }

    fun removeRule(rule: ProcessRule) {
        viewModelScope.launch {
            val current = _uiState.value.policyBundle
            val nextRules = current.processRules.toMutableMap().apply { remove(rule.ruleKey) }
            val nextBundle = current.copy(
                revision = current.revision + 1L,
                romFingerprint = Build.FINGERPRINT ?: current.romFingerprint,
                processRules = nextRules
            )
            persistAndPushBundle(nextBundle)

            if (ledger.listForPackage(rule.pkg, rule.userId).isNotEmpty()) {
                val shell = PersistentRootShell()
                try {
                    ledger.rollbackPackage(shell, rule.pkg, rule.userId)
                } finally {
                    shell.close()
                }
                _uiState.update { it.copy(ledgerRecords = ledger.listAll()) }
            }
            setToast("已取消对 ${resolveSingleLabel(rule.pkg)} 的保活")
            refreshManagedProcessesSync()
        }
    }

    fun startSingleDiagnosis(pkg: String, userId: Int, instruction: String) {
        if (pkg.isBlank()) {
            setToast("请先选择一个要诊断的应用")
            return
        }
        activeAgentJob?.cancel()
        activeAgentJob = viewModelScope.launch {
            runDiagnosisSession(pkg.trim(), userId.coerceAtLeast(0), instruction.trim())
        }
    }

    fun startBatchHosting(rawPackages: String) {
        val pkgs = rawPackages.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && it.contains('.') }
            .distinct()
            .toList()
        if (pkgs.isEmpty()) {
            setToast("请至少选择一个应用")
            return
        }
        activeAgentJob?.cancel()
        _uiState.update {
            it.copy(batchQueue = pkgs.map { p -> BatchHostingItem(pkg = p, status = "等待中") })
        }
        activeAgentJob = viewModelScope.launch {
            for (pkg in pkgs) {
                updateBatchItem(pkg, "分析中", "正在识别推送通道...")
                val summary = runDiagnosisSession(
                    pkg = pkg,
                    userId = 0,
                    instruction = "批量自动托管：分析后台推送子进程与长连接，安全验证后自动提交最优规则。"
                )
                updateBatchItem(pkg, "已完成", summary.take(120))
            }
            setToast("批量分析完成（共 ${pkgs.size} 个应用）")
        }
    }

    fun stopAgent() {
        activeAgentJob?.cancel()
        _uiState.update { it.copy(agentRunning = false) }
        appendTerminal("系统", "已停止当前 AI 分析")
    }

    fun saveLlmConfig(config: LlmConfig) {
        localPrefs.edit()
            .putString("llm_base_url", config.baseUrl)
            .putString("llm_api_key", config.apiKey)
            .putString("llm_model", config.model)
            .apply()
        _uiState.update { it.copy(llmConfig = config) }
        setToast("AI 模型配置已保存")
    }

    fun setLauncherAliasEnabled(enabled: Boolean) {
        val comp = ComponentName(appContext, "${appContext.packageName}.LauncherAlias")
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        runCatching {
            appContext.packageManager.setComponentEnabledSetting(
                comp,
                state,
                PackageManager.DONT_KILL_APP
            )
            _uiState.update { it.copy(launcherAliasEnabled = enabled) }
            setToast(if (enabled) "已在桌面显示图标" else "已隐藏桌面图标（可在 LSPosed 模块列表中打开）")
        }.onFailure { t ->
            setToast("切换桌面图标失败：${t.message}")
        }
    }

    fun rollbackSinglePackage(pkg: String, userId: Int) {
        viewModelScope.launch {
            val shell = PersistentRootShell()
            val hasRoot = withContext(Dispatchers.IO) { shell.verifyRootAccess() }
            _uiState.update { it.copy(rootGranted = hasRoot) }
            if (!hasRoot) {
                shell.close()
                setToast("未获得 Root 权限，无法执行单应用账本还原，请先在 KernelSU / Magisk 中授权")
                return@launch
            }
            val report = withContext(Dispatchers.IO) {
                try {
                    ledger.rollbackPackage(shell, pkg, userId)
                } finally {
                    shell.close()
                }
            }
            _uiState.update { it.copy(ledgerRecords = ledger.listAll()) }
            setToast("已还原：成功 ${report.succeeded}/${report.attempted} 项")
        }
    }

    fun cleanRestoreForUninstall() {
        viewModelScope.launch {
            val report = withContext(Dispatchers.IO) {
                val shell = PersistentRootShell()
                try {
                    ledger.rollbackAllForCleanUninstall(shell)
                } finally {
                    shell.close()
                }
            }
            val emptyBundle = KernelPolicyBundle(
                revision = _uiState.value.policyBundle.revision + 1L,
                romFingerprint = Build.FINGERPRINT ?: ""
            )
            KernelIpcClient.pushPolicy(emptyBundle)
            XposedServiceBridge.clearAllRemotePreferences(appContext)
            runCatching { notifStoreFile.delete() }
            _uiState.update {
                it.copy(
                    policyBundle = emptyBundle,
                    notificationHistory = emptyList(),
                    processSnapshot = ProcessMonitorSnapshot(emptyList(), rootAvailable = true),
                    ledgerRecords = ledger.listAll()
                )
            }
            setToast(
                if (report.allClean) {
                    "已清空全部保活策略与历史记录，现在可以安全卸载"
                } else {
                    "部分条目还原失败 (${report.failedIds.size})，请检查 Root 权限后重试"
                }
            )
        }
    }

    fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    private suspend fun runDiagnosisSession(pkg: String, userId: Int, instruction: String): String {
        val shell = PersistentRootShell()
        val hasRoot = withContext(Dispatchers.IO) { shell.verifyRootAccess() }
        _uiState.update { it.copy(rootGranted = hasRoot) }
        if (!hasRoot) {
            shell.close()
            val msg = "未获得 Root 权限：AI 智能排查需要 Root 权限读取应用进程与网络长连接，请先在 KernelSU / Magisk 中为 System Calibrator 开启授权。"
            appendTerminal("权限不足", msg, isError = true)
            setToast("未获得 Root 权限，请先在 KernelSU / Magisk 中授权")
            return msg
        }

        _uiState.update { it.copy(agentRunning = true) }
        val appLabel = resolveSingleLabel(pkg)
        appendTerminal("开始", "正在分析「$appLabel」($pkg)")

        var finalSummary = "分析结束"
        try {
            val gateway = object : KernelBridgeGateway {
                override suspend fun queryKernelAudit(pkg: String): List<AuditEvent> =
                    KernelIpcClient.drainAudit(pkg).getOrDefault(emptyList())

                override suspend fun commitRule(rule: ProcessRule): Result<String> =
                    persistAndPushRule(rule)

                override fun resolveApkBasePath(pkg: String): String? =
                    runCatching { appContext.packageManager.getApplicationInfo(pkg, 0).sourceDir }.getOrNull()

                override fun getApiLevel(): Int = Build.VERSION.SDK_INT
            }

            val tools = AgentToolRegistry(shell, gateway)
            val llm = OpenAiCompatibleLlmClient { _uiState.value.llmConfig }
            val orchestrator = ReActOrchestrator(llm, tools)

            orchestrator.diagnoseAndOptimize(pkg, userId, instruction).collect { ev ->
                when (ev) {
                    is AgentStreamEvent.StepStarted ->
                        appendTerminal("步骤", "正在进行第 ${ev.step}/${ev.maxSteps} 步排查...")
                    is AgentStreamEvent.AssistantThought ->
                        appendTerminal("AI 思考", ev.text)
                    is AgentStreamEvent.ToolInvoked ->
                        appendTerminal("检测中", "${ev.toolName}(${ev.argsJson})")
                    is AgentStreamEvent.ToolFinished ->
                        appendTerminal(
                            if (ev.success) "检测结果" else "检测异常",
                            "${ev.toolName}: ${ev.outputJson.take(400)}",
                            isError = !ev.success
                        )
                    is AgentStreamEvent.Completed -> {
                        finalSummary = ev.finalAnswer
                        appendTerminal("完成", "分析完成（共 ${ev.stepsUsed} 步）：${ev.finalAnswer}")
                    }
                    is AgentStreamEvent.Failed -> {
                        finalSummary = ev.error
                        appendTerminal("失败", ev.error, isError = true)
                    }
                }
            }
        } finally {
            shell.close()
            _uiState.update { it.copy(agentRunning = false) }
            refreshKernelHealthSync()
            refreshManagedProcessesSync()
        }
        return finalSummary
    }

    private suspend fun persistAndPushRule(rule: ProcessRule): Result<String> {
        val current = _uiState.value.policyBundle
        val nextRules = current.processRules.toMutableMap().apply { put(rule.ruleKey, rule) }
        val nextBundle = current.copy(
            revision = current.revision + 1L,
            romFingerprint = Build.FINGERPRINT ?: current.romFingerprint,
            processRules = nextRules
        )
        return persistAndPushBundle(nextBundle)
    }

    private suspend fun persistAndPushBundle(bundle: KernelPolicyBundle): Result<String> {
        val json = PolicyCodec.encodeBundle(bundle)
        XposedServiceBridge.writeRemotePolicyJson(appContext, json)
        _uiState.update { it.copy(policyBundle = bundle) }
        return KernelIpcClient.pushPolicy(bundle)
    }

    private fun loadInitialBundle(): KernelPolicyBundle {
        val raw = XposedServiceBridge.readPolicyJson(appContext)
        return if (raw.isNullOrBlank()) {
            KernelPolicyBundle(romFingerprint = Build.FINGERPRINT ?: "")
        } else {
            runCatching { PolicyCodec.decodeBundle(raw) }.getOrElse { KernelPolicyBundle() }
        }
    }

    private fun loadSavedNotifications(): List<NotificationHistoryRecord> {
        if (!notifStoreFile.exists()) return emptyList()
        return runCatching {
            val raw = notifStoreFile.readText(Charsets.UTF_8)
            PolicyCodec.decodeNotifications(raw)
                .sortedByDescending { it.timestampMs }
                .take(MAX_STORED_NOTIFICATIONS)
        }.getOrDefault(emptyList())
    }

    private fun saveNotificationsAsync(records: List<NotificationHistoryRecord>) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                notifStoreFile.parentFile?.mkdirs()
                notifStoreFile.writeText(PolicyCodec.encodeNotifications(records), Charsets.UTF_8)
            }
        }
    }

    private suspend fun resolveLabelsForPackages(pkgs: Collection<String>) = withContext(Dispatchers.IO) {
        val pm = appContext.packageManager
        for (pkg in pkgs) {
            if (pkg.isBlank() || labelMap.containsKey(pkg)) continue
            val label = runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(pkg)
            labelMap[pkg] = label
        }
    }

    private fun resolveSingleLabel(pkg: String): String {
        labelMap[pkg]?.let { return it }
        val label = runCatching {
            val pm = appContext.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
        labelMap[pkg] = label
        return label
    }

    private fun loadLlmConfig(): LlmConfig = LlmConfig(
        baseUrl = localPrefs.getString("llm_base_url", "https://api.openai.com/v1").orEmpty(),
        apiKey = localPrefs.getString("llm_api_key", "").orEmpty(),
        model = localPrefs.getString("llm_model", "gpt-4o-mini").orEmpty()
    )

    private fun isLauncherAliasEnabled(): Boolean {
        val comp = ComponentName(appContext, "${appContext.packageName}.LauncherAlias")
        val state = appContext.packageManager.getComponentEnabledSetting(comp)
        return state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }

    private fun appendTerminal(tag: String, body: String, isError: Boolean = false) {
        _uiState.update { st ->
            st.copy(terminalLog = (st.terminalLog + TerminalEntry(tag, body, isError)).takeLast(120))
        }
    }

    private fun updateBatchItem(pkg: String, status: String, summary: String) {
        _uiState.update { st ->
            st.copy(
                batchQueue = st.batchQueue.map {
                    if (it.pkg == pkg) it.copy(status = status, summary = summary) else it
                }
            )
        }
    }

    private fun setToast(msg: String) {
        _uiState.update { it.copy(toastMessage = msg) }
    }

    companion object {
        private const val MAX_STORED_NOTIFICATIONS = 300
    }
}
