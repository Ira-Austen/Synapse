package com.synapse.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentTerminalScreen(
    state: SynapseUiState,
    onLoadInstalledApps: () -> Unit,
    onStartSingle: (pkg: String, userId: Int, instruction: String) -> Unit,
    onStartBatch: (rawPkgs: String) -> Unit,
    onStop: () -> Unit,
    onCheckRoot: () -> Unit,
    onOpenLlmSettings: () -> Unit
) {
    var targetPkg by rememberSaveable { mutableStateOf("") }
    var targetAppName by rememberSaveable { mutableStateOf("") }
    var instruction by rememberSaveable {
        mutableStateOf("自动分析这个应用的推送子进程与长连接，验证可以正常收消息后帮我提交省电保活规则。")
    }
    var showAppPicker by rememberSaveable { mutableStateOf(false) }
    var showBatch by rememberSaveable { mutableStateOf(false) }
    var batchInput by rememberSaveable { mutableStateOf("") }

    val managedPkgs = remember(state.policyBundle) {
        state.policyBundle.processRules.values.map { it.pkg }.toSet()
    }
    val hasApiKey = state.llmConfig.apiKey.isNotBlank()

    if (showAppPicker) {
        InstalledAppPickerDialog(
            title = "选择要由 AI 诊断的应用",
            apps = state.installedApps,
            loading = state.appsLoading,
            managedPkgs = managedPkgs,
            onEnsureLoaded = onLoadInstalledApps,
            onSelect = { app ->
                targetPkg = app.pkg
                targetAppName = app.label
                showAppPicker = false
            },
            onDismiss = { showAppPicker = false }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (state.rootGranted == false) {
            item {
                Surface(
                    color = SynapseColors.RoseContainer,
                    contentColor = SynapseColors.RoseOnContainer,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "未授予 Root 权限：AI 诊断需要 Root 读取进程与网络状态，请先在 KernelSU / Magisk 中授权。",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = onCheckRoot) {
                            Text("重检")
                        }
                    }
                }
            }
        }

        if (!hasApiKey) {
            item {
                Surface(
                    color = SynapseColors.AmberContainer,
                    contentColor = SynapseColors.AmberOnContainer,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "使用 AI 自动排查前，需先填写大模型 API Key（普通添加保活不需要）。",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = onOpenLlmSettings) {
                            Text("去填写")
                        }
                    }
                }
            }
        }

        item {
            SynapseCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "AI 智能排查助手",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "遇到收不到推送的应用，让 AI 自动查找推送通道并配置规则",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (state.agentRunning) {
                            StatusPill(
                                text = "分析中",
                                containerColor = SynapseColors.EmeraldContainer,
                                contentColor = SynapseColors.EmeraldOnContainer,
                                dotColor = SynapseColors.Emerald
                            )
                        }
                    }

                    // Visual App Picker Row
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showAppPicker = true }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (targetPkg.isNotBlank()) {
                                AppIconAvatar(
                                    pkg = targetPkg,
                                    label = targetAppName.ifBlank { targetPkg },
                                    size = 38.dp
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = targetAppName.ifBlank { targetPkg },
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = targetPkg,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text(
                                    text = "更换",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            } else {
                                Icon(
                                    imageVector = SynapseIcons.Apps,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp)
                                )
                                Text(
                                    text = "点击从已安装应用列表中选择...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    // One-tap preset beginner tasks
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val presets = listOf(
                            "自动配置推送保活" to "自动分析这个应用的推送子进程与长连接，验证可以正常收消息后帮我提交省电保活规则。",
                            "排查收不到通知" to "检查该应用后台进程是否被冻结、推送长连接是否在线，并告诉我为什么收不到通知。",
                            "只检查不改动" to "仅分析该应用的后台进程和网络连接情况，不要修改任何策略。"
                        )
                        presets.forEach { (chipLabel, promptText) ->
                            FilterChip(
                                selected = instruction == promptText,
                                onClick = { instruction = promptText },
                                label = { Text(chipLabel) }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = instruction,
                        onValueChange = { instruction = it },
                        label = { Text("告诉 AI 你想解决什么问题") },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = !state.agentRunning && targetPkg.isNotBlank(),
                            onClick = { onStartSingle(targetPkg, 0, instruction) }
                        ) {
                            Icon(SynapseIcons.Sparkle, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (state.agentRunning) "正在分析..." else "开始智能诊断")
                        }
                        if (state.agentRunning) {
                            OutlinedButton(onClick = onStop) { Text("停止") }
                        } else {
                            OutlinedButton(onClick = { showBatch = !showBatch }) {
                                Text(if (showBatch) "收起批量" else "批量分析")
                            }
                        }
                    }
                }
            }
        }

        if (showBatch || state.batchQueue.isNotEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    )
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("批量自动托管", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        OutlinedTextField(
                            value = batchInput,
                            onValueChange = { batchInput = it },
                            label = { Text("多个包名（每行一个）") },
                            minLines = 2,
                            maxLines = 4,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            enabled = !state.agentRunning,
                            onClick = { onStartBatch(batchInput) }
                        ) { Text("开始批量分析") }
                        for (item in state.batchQueue) {
                            Text(
                                text = "[${item.status}] ${state.appLabelCache[item.pkg] ?: item.pkg}：${item.summary}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }

        item {
            SectionHeader(
                title = "诊断进度与结果",
                trailing = if (state.terminalLog.isNotEmpty()) "${state.terminalLog.size} 条" else null
            )
        }

        if (state.terminalLog.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                    Text(
                        text = "提示：日常添加应用可以直接在底部「应用」页一键自动识别；如果个别软件比较特殊、收不到推送，再使用这里的 AI 智能排查即可。",
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(state.terminalLog) { entry ->
                val (bg, fg) = when {
                    entry.isError -> SynapseColors.RoseContainer to SynapseColors.RoseOnContainer
                    entry.tag == "完成" || entry.tag == "检测结果" ->
                        SynapseColors.EmeraldContainer to SynapseColors.EmeraldOnContainer
                    entry.tag == "检测中" -> SynapseColors.AmberContainer to SynapseColors.AmberOnContainer
                    else -> SynapseColors.SkyContainer to SynapseColors.SkyOnContainer
                }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
                    )
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        StatusPill(text = entry.tag, containerColor = bg, contentColor = fg)
                        Text(
                            text = entry.body,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        item { Spacer(Modifier.height(12.dp)) }
    }
}
