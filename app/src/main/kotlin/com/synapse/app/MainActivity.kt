package com.synapse.app

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.synapse.app.ui.AgentTerminalScreen
import com.synapse.app.ui.DashboardScreen
import com.synapse.app.ui.LocalNowMs
import com.synapse.app.ui.NotificationHistoryScreen
import com.synapse.app.ui.ProcessMonitorScreen
import com.synapse.app.ui.RestoreCenterScreen
import com.synapse.app.ui.RulesScreen
import com.synapse.app.ui.SynapseColors
import com.synapse.app.ui.SynapseIcons
import com.synapse.app.ui.SynapseTheme
import com.synapse.app.ui.SynapseViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SynapseTheme {
                SynapseRootScreen()
            }
        }
    }
}

private data class NavTab(val icon: ImageVector, val label: String)

private val NAV_TABS = listOf(
    NavTab(SynapseIcons.Shield, "概览"),
    NavTab(SynapseIcons.Apps, "应用"),
    NavTab(SynapseIcons.Processes, "进程"),
    NavTab(SynapseIcons.Notifications, "推送"),
    NavTab(SynapseIcons.Settings, "更多")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SynapseRootScreen(vm: SynapseViewModel = viewModel()) {
    val state by vm.uiState.collectAsState()
    val xposedState by vm.xposedServiceState.collectAsState()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var moreSubTab by rememberSaveable { mutableIntStateOf(0) } // 0 = 设置与安全还原, 1 = AI 智能排查
    var pendingNotifFilterPkg by rememberSaveable { mutableStateOf<String?>(null) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.toastMessage) {
        val msg = state.toastMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            vm.clearToast()
        }
    }

    // Pure event-driven foreground subscription (zero timers on kernel/IPC side)
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            vm.subscribeForegroundEvents()
        }
    }

    // Relative-time clock ONLY runs while actively viewing 概览(0) or 推送(3) in foreground
    LaunchedEffect(lifecycleOwner, selectedTab) {
        if (selectedTab == 0 || selectedTab == 3) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    nowMs = System.currentTimeMillis()
                    delay(15_000L)
                }
            }
        }
    }

    // Refresh relevant snapshot once when switching tabs (<1ms via Magic Binder)
    LaunchedEffect(selectedTab) {
        when (selectedTab) {
            1, 2 -> vm.refreshManagedProcesses()
            3 -> vm.refreshNotificationsOnly(silent = true)
        }
    }

    CompositionLocalProvider(LocalNowMs provides nowMs) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp
        val online = state.kernelHealth != null

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    ),
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(9.dp)
                                    .clip(CircleShape)
                                    .background(if (online) SynapseColors.Emerald else SynapseColors.Rose)
                            )
                            Column {
                                Text(
                                    text = "Synapse 保活助手",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (online) {
                                        "系统级保活已开启 · Android ${Build.VERSION.RELEASE}"
                                    } else {
                                        "保护引擎未启动 · Android ${Build.VERSION.RELEASE}"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                if (!useRail) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        NAV_TABS.forEachIndexed { idx, tab ->
                            NavigationBarItem(
                                selected = selectedTab == idx,
                                onClick = { selectedTab = idx },
                                icon = {
                                    Icon(
                                        imageVector = tab.icon,
                                        contentDescription = tab.label,
                                        modifier = Modifier.size(22.dp)
                                    )
                                },
                                label = { Text(tab.label) }
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useRail) {
                    NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                        NAV_TABS.forEachIndexed { idx, tab ->
                            NavigationRailItem(
                                selected = selectedTab == idx,
                                onClick = { selectedTab = idx },
                                icon = {
                                    Icon(
                                        imageVector = tab.icon,
                                        contentDescription = tab.label,
                                        modifier = Modifier.size(22.dp)
                                    )
                                },
                                label = { Text(tab.label) }
                            )
                        }
                    }
                }
                Box(modifier = Modifier.weight(1f)) {
                    Crossfade(
                        targetState = selectedTab,
                        animationSpec = tween(durationMillis = 180),
                        label = "main_tab_fade"
                    ) { tab ->
                        when (tab) {
                            0 -> DashboardScreen(
                                state = state,
                                xposedState = xposedState,
                                onPullRefresh = vm::pullRefreshDashboard,
                                onResetBreaker = vm::resetKernelBreaker,
                                onRequestSystemScope = vm::requestSystemScope,
                                onNavigateTab = { selectedTab = it }
                            )
                            1 -> RulesScreen(
                                state = state,
                                onLoadInstalledApps = { vm.loadInstalledAppsIfNeeded() },
                                onInspectApp = { pkg, userTriggered -> vm.inspectAppForRule(pkg, userTriggered) },
                                onLaunchApp = vm::launchTargetApp,
                                onClearInspection = vm::clearInspectedApp,
                                onAddRule = vm::upsertRule,
                                onToggleRule = vm::toggleRuleEnabled,
                                onDeleteRule = vm::removeRule,
                                onJumpToProcesses = { selectedTab = 2 }
                            )
                            2 -> ProcessMonitorScreen(
                                state = state,
                                onPullRefresh = vm::pullRefreshProcesses,
                                onWakeRule = vm::wakeRuleProcess,
                                onLaunchApp = vm::launchTargetApp,
                                onOpenAddApp = { selectedTab = 1 },
                                onViewAppNotifications = { pkg ->
                                    pendingNotifFilterPkg = pkg
                                    selectedTab = 3
                                }
                            )
                            3 -> NotificationHistoryScreen(
                                state = state,
                                initialFilterPkg = pendingNotifFilterPkg,
                                onConsumeInitialFilter = { pendingNotifFilterPkg = null },
                                onPullRefresh = vm::pullRefreshNotifications,
                                onClear = vm::clearNotificationHistory
                            )
                            4 -> Column(modifier = Modifier.fillMaxSize()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    FilterChip(
                                        selected = moreSubTab == 0,
                                        onClick = { moreSubTab = 0 },
                                        label = { Text("设置与安全还原") }
                                    )
                                    FilterChip(
                                        selected = moreSubTab == 1,
                                        onClick = { moreSubTab = 1 },
                                        label = { Text("AI 智能排查") }
                                    )
                                }
                                Crossfade(
                                    targetState = moreSubTab,
                                    animationSpec = tween(durationMillis = 160),
                                    label = "more_subtab_fade",
                                    modifier = Modifier.weight(1f)
                                ) { sub ->
                                    if (sub == 0) {
                                        RestoreCenterScreen(
                                            state = state,
                                            onCheckRoot = { vm.checkRootAccess(showToast = true) },
                                            onSaveLlmConfig = vm::saveLlmConfig,
                                            onToggleLauncherAlias = vm::setLauncherAliasEnabled,
                                            onRollbackPackage = vm::rollbackSinglePackage,
                                            onCleanRestoreAll = vm::cleanRestoreForUninstall
                                        )
                                    } else {
                                        AgentTerminalScreen(
                                            state = state,
                                            onLoadInstalledApps = { vm.loadInstalledAppsIfNeeded() },
                                            onStartSingle = vm::startSingleDiagnosis,
                                            onStartBatch = vm::startBatchHosting,
                                            onStop = vm::stopAgent,
                                            onCheckRoot = { vm.checkRootAccess(showToast = true) },
                                            onOpenLlmSettings = { moreSubTab = 0 }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    }
}
