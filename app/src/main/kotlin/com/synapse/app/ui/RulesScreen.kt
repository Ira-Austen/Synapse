package com.synapse.app.ui

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.synapse.protocol.ProcessRule

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RulesScreen(
    state: SynapseUiState,
    onLoadInstalledApps: () -> Unit,
    onInspectApp: (pkg: String, userTriggeredProbe: Boolean) -> Unit,
    onLaunchApp: (String) -> Unit,
    onClearInspection: () -> Unit,
    onAddRule: (ProcessRule) -> Unit,
    onToggleRule: (ProcessRule, Boolean) -> Unit,
    onDeleteRule: (ProcessRule) -> Unit,
    onJumpToProcesses: () -> Unit
) {
    val rules = state.policyBundle.processRules.values.toList()
    val managedPkgs = remember(rules) { rules.map { it.pkg }.toSet() }
    val aliveByKey = remember(state.processSnapshot) {
        state.processSnapshot.groups.associate { it.rule.ruleKey to it.aliveCount }
    }

    var showSheet by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<ProcessRule?>(null) }
    var pendingDeleteRule by remember { mutableStateOf<ProcessRule?>(null) }

    if (showSheet) {
        AddOrEditRuleBottomSheet(
            state = state,
            initialRule = editingRule,
            managedPkgs = managedPkgs,
            onEnsureAppsLoaded = onLoadInstalledApps,
            onInspectApp = onInspectApp,
            onLaunchApp = onLaunchApp,
            onSaveRule = onAddRule,
            onDismiss = {
                showSheet = false
                editingRule = null
                onClearInspection()
            }
        )
    }

    pendingDeleteRule?.let { toDelete ->
        val label = state.appLabelCache[toDelete.pkg] ?: toDelete.appName
        AlertDialog(
            onDismissRequest = { pendingDeleteRule = null },
            title = { Text("取消保活「$label」？") },
            text = { Text("移除后将立即停止对该应用的后台保护，并恢复为系统默认管理方式。") },
            confirmButton = {
                Button(onClick = {
                    onDeleteRule(toDelete)
                    pendingDeleteRule = null
                }) { Text("确认移除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteRule = null }) { Text("取消") }
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "保活应用管理",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "已添加 ${rules.size} 个 · 正在保护 ${rules.count { it.enabled }} 个",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(onClick = {
                    editingRule = null
                    showSheet = true
                }) {
                    Icon(SynapseIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("添加应用")
                }
            }
        }

        if (rules.isEmpty()) {
            item(key = "empty") {
                SynapseCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "还没有添加保活应用",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "点击「添加应用」从手机已安装列表中选出想收消息的软件，程序会自动帮你识别推送子进程。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        Button(onClick = {
                            editingRule = null
                            showSheet = true
                        }) {
                            Icon(SynapseIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("立即选择应用")
                        }
                    }
                }
            }
        } else {
            items(rules, key = { it.ruleKey }) { rule ->
                val label = state.appLabelCache[rule.pkg] ?: rule.appName
                val aliveCount = aliveByKey[rule.ruleKey]
                val shortTarget = if (rule.targetProcess == rule.pkg) {
                    "主进程"
                } else {
                    "推送子进程：${rule.targetProcess.substringAfter(':')}"
                }

                SynapseCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateItem()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            AppIconAvatar(pkg = rule.pkg, label = label, size = 44.dp)
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = if (rule.userId == 0) label else "$label (分身 ${rule.userId})",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (aliveCount != null) {
                                        StatusPill(
                                            text = if (aliveCount > 0) "${aliveCount}进程存活" else "未运行",
                                            containerColor = if (aliveCount > 0) SynapseColors.EmeraldContainer else SynapseColors.AmberContainer,
                                            contentColor = if (aliveCount > 0) SynapseColors.EmeraldOnContainer else SynapseColors.AmberOnContainer
                                        )
                                    }
                                }
                                Text(
                                    text = shortTarget,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Switch(
                                checked = rule.enabled,
                                onCheckedChange = { onToggleRule(rule, it) }
                            )
                        }

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val strengthLabel = when {
                                rule.keepAliveAdj <= 120 -> "强力保活"
                                rule.keepAliveAdj <= 250 -> "标准省电"
                                else -> "轻度保活"
                            }
                            StatusPill(
                                text = strengthLabel,
                                containerColor = SynapseColors.SkyContainer,
                                contentColor = SynapseColors.SkyOnContainer
                            )
                            if (rule.allowResurrect) {
                                StatusPill(
                                    text = "断线自动拉起",
                                    containerColor = SynapseColors.EmeraldContainer,
                                    contentColor = SynapseColors.EmeraldOnContainer
                                )
                            }
                            if (rule.exemptAospFreezer || rule.exemptOemFreezer) {
                                StatusPill(
                                    text = "防冻结",
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                            if (rule.memoryDozeBypass) {
                                StatusPill(
                                    text = "息屏不断网",
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    editingRule = rule
                                    showSheet = true
                                }
                            ) { Text("调整设置") }
                            OutlinedButton(onClick = onJumpToProcesses) {
                                Text("查看进程")
                            }
                            TextButton(onClick = { pendingDeleteRule = rule }) {
                                Text("移除", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(14.dp)) }
    }
}
