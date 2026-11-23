package com.synapse.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.synapse.protocol.ProcessRule
import com.synapse.protocol.PushMode
import com.synapse.protocol.WakeType

/**
 * Silky two-step Material 3 ModalBottomSheet for adding or editing a kept-alive app:
 * Step 1: Pick from installed apps (with search, real icons, user/system filter).
 * Step 2: Confirm auto-detected push subprocess, wake service, and friendly protection toggles.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddOrEditRuleBottomSheet(
    state: SynapseUiState,
    initialRule: ProcessRule?,
    managedPkgs: Set<String>,
    onEnsureAppsLoaded: () -> Unit,
    onInspectApp: (pkg: String, userTriggeredProbe: Boolean) -> Unit,
    onLaunchApp: (String) -> Unit,
    onSaveRule: (ProcessRule) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var selectedPkg by rememberSaveable(initialRule?.ruleKey) { mutableStateOf(initialRule?.pkg.orEmpty()) }
    var selectedAppName by rememberSaveable(initialRule?.ruleKey) {
        mutableStateOf(
            initialRule?.let { state.appLabelCache[it.pkg] ?: it.appName }.orEmpty()
        )
    }
    var targetProc by rememberSaveable(initialRule?.ruleKey) { mutableStateOf(initialRule?.targetProcess.orEmpty()) }
    var userId by rememberSaveable(initialRule?.ruleKey) { mutableStateOf((initialRule?.userId ?: 0).toString()) }
    var adjPreset by rememberSaveable(initialRule?.ruleKey) { mutableIntStateOf(initialRule?.keepAliveAdj ?: 200) }
    var customAdj by rememberSaveable(initialRule?.ruleKey) { mutableStateOf((initialRule?.keepAliveAdj ?: 200).toString()) }
    var wakeType by rememberSaveable(initialRule?.ruleKey) { mutableStateOf(initialRule?.wakeType ?: WakeType.SERVICE) }
    var wakeComponent by rememberSaveable(initialRule?.ruleKey) { mutableStateOf(initialRule?.wakeComponent.orEmpty()) }
    var wakeAction by rememberSaveable(initialRule?.ruleKey) { mutableStateOf(initialRule?.wakeAction.orEmpty()) }
    var exemptAospFreezer by rememberSaveable(initialRule?.ruleKey) { mutableStateOf(initialRule?.exemptAospFreezer ?: true) }
    var exemptOemFreezer by rememberSaveable(initialRule?.ruleKey) { mutableStateOf(initialRule?.exemptOemFreezer ?: true) }
    var memoryDozeBypass by rememberSaveable(initialRule?.ruleKey) { mutableStateOf(initialRule?.memoryDozeBypass ?: true) }
    var allowResurrect by rememberSaveable(initialRule?.ruleKey) { mutableStateOf(initialRule?.allowResurrect ?: true) }
    var isEditingExisting by rememberSaveable(initialRule?.ruleKey) { mutableStateOf(initialRule != null) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showSystemApps by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        onEnsureAppsLoaded()
        if (initialRule != null) {
            onInspectApp(initialRule.pkg, false)
        }
    }

    LaunchedEffect(state.inspectedApp) {
        val inspected = state.inspectedApp ?: return@LaunchedEffect
        if (inspected.pkg == selectedPkg && !isEditingExisting) {
            selectedAppName = inspected.label
            val recProc = inspected.recommendedProcess
            if (recProc != null) {
                targetProc = recProc.processName
                val recWake = recProc.wakeCandidates.firstOrNull()
                if (recWake != null) {
                    wakeType = recWake.wakeType
                    wakeComponent = recWake.componentFlattened
                    allowResurrect = true
                } else {
                    wakeType = WakeType.KEEP_ALIVE_ONLY
                    wakeComponent = ""
                    allowResurrect = false
                }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        AnimatedContent(
            targetState = selectedPkg.isNotBlank(),
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "sheet_step",
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        ) { inConfigStep ->
            if (!inConfigStep) {
                // Step 1: Searchable app list
                val filteredApps = remember(state.installedApps, searchQuery, showSystemApps) {
                    val q = searchQuery.trim().lowercase()
                    state.installedApps.filter { app ->
                        if (!showSystemApps && app.isSystemApp) return@filter false
                        if (q.isNotEmpty()) {
                            app.label.lowercase().contains(q) || app.pkg.lowercase().contains(q)
                        } else {
                            true
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column {
                        Text(
                            text = "选择要保活的应用",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "点选应用后会自动识别推送子进程，无需手动输入包名",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("搜索应用名称或包名...") },
                        leadingIcon = {
                            Icon(SynapseIcons.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(SynapseIcons.Close, contentDescription = "清除", modifier = Modifier.size(16.dp))
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = !showSystemApps,
                            onClick = { showSystemApps = false },
                            label = { Text("仅用户应用") }
                        )
                        FilterChip(
                            selected = showSystemApps,
                            onClick = { showSystemApps = true },
                            label = { Text("包含系统应用") }
                        )
                    }

                    if (state.appsLoading && state.installedApps.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp))
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = "正在读取应用列表...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 220.dp, max = 440.dp)
                        ) {
                            items(filteredApps, key = { it.pkg }) { app ->
                                val isManaged = managedPkgs.contains(app.pkg)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            isEditingExisting = false
                                            selectedPkg = app.pkg
                                            selectedAppName = app.label
                                            targetProc = app.pkg
                                            userId = "0"
                                            adjPreset = 200
                                            customAdj = "200"
                                            wakeType = WakeType.KEEP_ALIVE_ONLY
                                            wakeComponent = ""
                                            wakeAction = ""
                                            exemptAospFreezer = true
                                            exemptOemFreezer = true
                                            memoryDozeBypass = true
                                            allowResurrect = true
                                            showAdvanced = false
                                            onInspectApp(app.pkg, false)
                                        }
                                        .padding(vertical = 11.dp, horizontal = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    AppIconAvatar(pkg = app.pkg, label = app.label, size = 40.dp)
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = app.label,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false)
                                            )
                                            if (isManaged) {
                                                StatusPill(
                                                    text = "已保活",
                                                    containerColor = SynapseColors.EmeraldContainer,
                                                    contentColor = SynapseColors.EmeraldOnContainer
                                                )
                                            }
                                        }
                                        Text(
                                            text = app.pkg,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                                )
                            }
                        }
                    }
                }
            } else {
                // Step 2: Auto-detected push process & friendly options
                val inspected = state.inspectedApp?.takeIf { it.pkg == selectedPkg }
                val selectedProcCandidate = inspected?.processCandidates?.firstOrNull { it.processName == targetProc }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 580.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 20.dp)
                        .animateContentSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        AppIconAvatar(pkg = selectedPkg, label = selectedAppName.ifBlank { selectedPkg }, size = 44.dp)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = selectedAppName.ifBlank { selectedPkg },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = selectedPkg,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        TextButton(onClick = { selectedPkg = "" }) {
                            Text("重选应用")
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "选择要保活的推送进程",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            if (inspected != null && !inspected.anyProcessRunning) {
                                TextButton(onClick = { onLaunchApp(selectedPkg) }) {
                                    Text("打开应用")
                                }
                            }
                            TextButton(
                                enabled = !state.inspectingApp,
                                onClick = { onInspectApp(selectedPkg, true) }
                            ) {
                                Text(if (state.inspectingApp) "探测中..." else "实测连接")
                            }
                        }
                    }

                    // Detection source banner: Live Socket Proof vs Root Missing Warning vs Offline Prediction
                    if (inspected != null) {
                        val (bannerBg, bannerFg, bannerText) = when {
                            inspected.anyLiveSocketVerified -> Triple(
                                SynapseColors.EmeraldContainer,
                                SynapseColors.EmeraldOnContainer,
                                "已实测命中活跃长连接 · 自动优先选中真实连着服务器的推送进程"
                            )
                            !state.inspectingApp && !inspected.rootProbeAvailable -> Triple(
                                SynapseColors.AmberContainer,
                                SynapseColors.AmberOnContainer,
                                "未授予 Root 权限，暂无法探测实时长连接 · 已用免 Root 机型预判（在 KernelSU / Magisk 授权后可实测）"
                            )
                            inspected.anyProcessRunning -> Triple(
                                SynapseColors.SkyContainer,
                                SynapseColors.SkyOnContainer,
                                "应用运行中，暂未检测到长连接 · 已结合本机（${inspected.deviceBrand.displayName}）与主流推送 SDK 预判"
                            )
                            else -> Triple(
                                MaterialTheme.colorScheme.surfaceContainerLow,
                                MaterialTheme.colorScheme.onSurfaceVariant,
                                "离线清单预判（已适配 ${inspected.deviceBrand.displayName}）· 点右上角「打开应用」运行一次后再点「实测连接」可 100% 实锤"
                            )
                        }
                        Surface(
                            color = bannerBg,
                            contentColor = bannerFg,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = bannerText,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }

                    if (state.inspectingApp && inspected == null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(
                                text = "正在识别推送通道与实时长连接...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else if (inspected != null && inspected.processCandidates.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            inspected.processCandidates.take(6).forEach { cand ->
                                val isSelected = targetProc == cand.processName
                                Surface(
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerLow
                                    },
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            targetProc = cand.processName
                                            val topWake = cand.wakeCandidates.firstOrNull()
                                            if (topWake != null) {
                                                wakeType = topWake.wakeType
                                                wakeComponent = topWake.componentFlattened
                                                allowResurrect = true
                                            } else {
                                                wakeType = WakeType.KEEP_ALIVE_ONLY
                                                wakeComponent = ""
                                                allowResurrect = false
                                            }
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(3.dp)
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Text(
                                                    text = cand.shortLabel,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.SemiBold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f, fill = false)
                                                )
                                                if (cand.recommended) {
                                                    StatusPill(
                                                        text = if (cand.verifiedByLiveSocket) "实测推荐" else "推荐",
                                                        containerColor = SynapseColors.EmeraldContainer,
                                                        contentColor = SynapseColors.EmeraldOnContainer
                                                    )
                                                }
                                            }
                                            if (cand.verifiedByLiveSocket) {
                                                val totalConn = cand.liveTcpCount + cand.liveUdpCount
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    StatusPill(
                                                        text = "活跃长连接 · ${totalConn}条",
                                                        containerColor = SynapseColors.EmeraldContainer,
                                                        contentColor = SynapseColors.EmeraldOnContainer,
                                                        dotColor = SynapseColors.Emerald
                                                    )
                                                    if (cand.livePortsSummary.isNotBlank()) {
                                                        Text(
                                                            text = cand.livePortsSummary,
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = SynapseColors.EmeraldOnContainer,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                }
                                            } else if (cand.isRunning) {
                                                StatusPill(
                                                    text = "当前运行中",
                                                    containerColor = SynapseColors.SkyContainer,
                                                    contentColor = SynapseColors.SkyOnContainer
                                                )
                                            }
                                            Text(
                                                text = cand.hintTag,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (cand.isDormantOem) {
                                                    SynapseColors.AmberOnContainer
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                }
                                            )
                                        }
                                        if (isSelected) {
                                            StatusPill(
                                                text = "已选",
                                                containerColor = MaterialTheme.colorScheme.primary,
                                                contentColor = MaterialTheme.colorScheme.onPrimary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Text(
                        text = "断线后自动拉起的服务",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )

                    val wakeOptions = selectedProcCandidate?.wakeCandidates.orEmpty()
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        wakeOptions.take(4).forEachIndexed { idx, cand ->
                            val selected = allowResurrect && wakeComponent == cand.componentFlattened
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    allowResurrect = true
                                    wakeType = cand.wakeType
                                    wakeComponent = cand.componentFlattened
                                },
                                label = {
                                    Text(
                                        text = if (idx == 0) "${cand.shortName} (推荐)" else cand.shortName,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            )
                        }
                        FilterChip(
                            selected = !allowResurrect || wakeComponent.isBlank(),
                            onClick = {
                                allowResurrect = false
                                wakeType = WakeType.KEEP_ALIVE_ONLY
                                wakeComponent = ""
                            },
                            label = { Text("仅保活不拉起") }
                        )
                    }

                    Text(
                        text = "保活强度与防护项",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(200 to "标准省电 (推荐)", 100 to "强力保活", 300 to "轻度保活").forEach { (v, label) ->
                            FilterChip(
                                selected = adjPreset == v,
                                onClick = {
                                    adjPreset = v
                                    customAdj = v.toString()
                                },
                                label = { Text(label) }
                            )
                        }
                        FilterChip(
                            selected = exemptAospFreezer,
                            onClick = { exemptAospFreezer = !exemptAospFreezer },
                            label = { Text("防系统冻结") }
                        )
                        FilterChip(
                            selected = exemptOemFreezer,
                            onClick = { exemptOemFreezer = !exemptOemFreezer },
                            label = { Text("防厂商清理") }
                        )
                        FilterChip(
                            selected = memoryDozeBypass,
                            onClick = { memoryDozeBypass = !memoryDozeBypass },
                            label = { Text("息屏不断网") }
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showAdvanced = !showAdvanced }
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "高级参数（一般无需修改）",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Icon(
                            imageVector = if (showAdvanced) SynapseIcons.ExpandLess else SynapseIcons.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    if (showAdvanced) {
                        OutlinedTextField(
                            value = targetProc,
                            onValueChange = { targetProc = it },
                            label = { Text("目标进程完整名称") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = wakeComponent,
                            onValueChange = { wakeComponent = it },
                            label = { Text("唤醒组件 (包名/类名)") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = userId,
                                onValueChange = { userId = it.filter(Char::isDigit) },
                                label = { Text("分身用户ID") },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = customAdj,
                                onValueChange = {
                                    customAdj = it.filter(Char::isDigit)
                                    customAdj.toIntOrNull()?.let { v -> adjPreset = v }
                                },
                                label = { Text("优先级 (100-800)") },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(0.35f)
                        ) { Text("取消") }
                        Button(
                            onClick = {
                                val cleanPkg = selectedPkg.trim()
                                if (cleanPkg.contains('.')) {
                                    val proc = targetProc.trim().ifBlank { cleanPkg }
                                    val safeAdj = (customAdj.toIntOrNull() ?: adjPreset)
                                        .coerceIn(ProcessRule.MIN_SAFE_ADJ, ProcessRule.MAX_SAFE_ADJ)
                                    val comp = wakeComponent.trim().ifBlank { null }
                                    val act = wakeAction.trim().ifBlank { null }
                                    val canWake = allowResurrect && comp != null
                                    val effectiveWakeType = if (canWake) {
                                        if (wakeType == WakeType.KEEP_ALIVE_ONLY) WakeType.SERVICE else wakeType
                                    } else {
                                        WakeType.KEEP_ALIVE_ONLY
                                    }
                                    onSaveRule(
                                        ProcessRule(
                                            pkg = cleanPkg,
                                            userId = userId.toIntOrNull() ?: 0,
                                            appName = selectedAppName.ifBlank { state.appLabelCache[cleanPkg] ?: cleanPkg },
                                            enabled = true,
                                            pushMode = if (proc.contains(':')) PushMode.ISOLATED_PUSH else PushMode.KEEP_ALIVE_ONLY,
                                            targetProcess = proc,
                                            wakeComponent = comp,
                                            wakeType = effectiveWakeType,
                                            wakeAction = act,
                                            keepAliveAdj = safeAdj,
                                            protectProcState = true,
                                            exemptAospFreezer = exemptAospFreezer,
                                            exemptOemFreezer = exemptOemFreezer,
                                            memoryDozeBypass = memoryDozeBypass,
                                            allowResurrect = canWake
                                        )
                                    )
                                    onDismiss()
                                }
                            },
                            modifier = Modifier.weight(0.65f)
                        ) { Text("保存并立即生效") }
                    }
                }
            }
        }
    }
}
