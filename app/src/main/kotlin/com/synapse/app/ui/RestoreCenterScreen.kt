package com.synapse.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.synapse.agent.llm.LlmConfig

@Composable
fun RestoreCenterScreen(
    state: SynapseUiState,
    onCheckRoot: () -> Unit,
    onSaveLlmConfig: (LlmConfig) -> Unit,
    onToggleLauncherAlias: (Boolean) -> Unit,
    onRollbackPackage: (pkg: String, userId: Int) -> Unit,
    onCleanRestoreAll: () -> Unit
) {
    var baseUrl by rememberSaveable(state.llmConfig.baseUrl) { mutableStateOf(state.llmConfig.baseUrl) }
    var apiKey by rememberSaveable(state.llmConfig.apiKey) { mutableStateOf(state.llmConfig.apiKey) }
    var model by rememberSaveable(state.llmConfig.model) { mutableStateOf(state.llmConfig.model) }
    var showResetConfirm by remember { mutableStateOf(false) }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("确认清空所有配置？") },
            text = { Text("将清除所有已添加的保活应用规则、本地推送历史并还原所有记录，清空后可直接卸载本应用，不留残留。") },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        onCleanRestoreAll()
                        showResetConfirm = false
                    }
                ) { Text("确认清空还原") }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text("取消") }
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            val kernelOnline = state.kernelHealth != null
            val (rootText, rootBg, rootFg, rootDot) = when (state.rootGranted) {
                true -> listOf("已授权", SynapseColors.EmeraldContainer, SynapseColors.EmeraldOnContainer, SynapseColors.Emerald)
                false -> listOf("未授权", SynapseColors.AmberContainer, SynapseColors.AmberOnContainer, SynapseColors.Amber)
                null -> listOf("未检测", SynapseColors.SkyContainer, SynapseColors.SkyOnContainer, SynapseColors.Sky)
            }
            SynapseCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("权限与运行环境", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("微内核保活引擎（LSPosed）", style = MaterialTheme.typography.bodyMedium)
                        StatusPill(
                            text = if (kernelOnline) "运行中" else "未激活",
                            containerColor = if (kernelOnline) SynapseColors.EmeraldContainer else SynapseColors.RoseContainer,
                            contentColor = if (kernelOnline) SynapseColors.EmeraldOnContainer else SynapseColors.RoseOnContainer,
                            dotColor = if (kernelOnline) SynapseColors.Emerald else SynapseColors.Rose
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("系统事件直推通道", style = MaterialTheme.typography.bodyMedium)
                        StatusPill(
                            text = if (state.eventPushConnected) "已连接" else "待重启激活",
                            containerColor = if (state.eventPushConnected) SynapseColors.EmeraldContainer else SynapseColors.AmberContainer,
                            contentColor = if (state.eventPushConnected) SynapseColors.EmeraldOnContainer else SynapseColors.AmberOnContainer
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Root 权限（可选）", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = "仅「实测长连接」与「AI 排查」需要；日常保活与进程监控免 Root",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        StatusPill(
                            text = rootText as String,
                            containerColor = rootBg as androidx.compose.ui.graphics.Color,
                            contentColor = rootFg as androidx.compose.ui.graphics.Color,
                            dotColor = rootDot as androidx.compose.ui.graphics.Color
                        )
                    }
                    OutlinedButton(onClick = onCheckRoot) {
                        Text("检测 Root 权限")
                    }
                }
            }
        }

        item {
            SynapseCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("桌面图标与隐身", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("在桌面显示应用图标", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = "关闭后可隐藏桌面图标（仍可从 LSPosed 模块列表中打开）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = state.launcherAliasEnabled,
                            onCheckedChange = onToggleLauncherAlias
                        )
                    }
                }
            }
        }

        item {
            SynapseCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("AI 助手模型配置（可选）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        text = "仅在使用「AI 助手」自动排查疑难应用时需要配置，支持 OpenAI / DeepSeek 等兼容接口。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text("接口地址 (Base URL)") },
                        placeholder = { Text("https://api.openai.com/v1") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        label = { Text("模型名称") },
                        placeholder = { Text("gpt-4o-mini / deepseek-chat") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("API Key（仅保存在本机）") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            onSaveLlmConfig(
                                state.llmConfig.copy(
                                    baseUrl = baseUrl.trim(),
                                    apiKey = apiKey.trim(),
                                    model = model.trim()
                                )
                            )
                        }
                    ) { Text("保存模型配置") }
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
                        Text(
                            text = "无痕还原与安全卸载",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        StatusPill(
                            text = if (state.ledgerRecords.isEmpty()) "零系统残留" else "${state.ledgerRecords.size} 项可还原",
                            containerColor = if (state.ledgerRecords.isEmpty()) SynapseColors.EmeraldContainer else SynapseColors.AmberContainer,
                            contentColor = if (state.ledgerRecords.isEmpty()) SynapseColors.EmeraldOnContainer else SynapseColors.AmberOnContainer
                        )
                    }
                    Text(
                        text = "本工具所有保活规则均在内存中运行，不会篡改手机系统设置；不想使用时，在 LSPosed 取消勾选并重启即可 100% 恢复原厂状态。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        onClick = { showResetConfirm = true }
                    ) { Text("一键清空配置并安全还原") }
                }
            }
        }

        if (state.ledgerRecords.isNotEmpty()) {
            item { SectionHeader(title = "可撤销的系统改动记录", trailing = "${state.ledgerRecords.size} 条") }
            items(state.ledgerRecords, key = { it.id }) { rec ->
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "${state.appLabelCache[rec.targetPkg] ?: rec.targetPkg} — ${rec.mutationType}",
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "${rec.key}: '${rec.originalValue}' → '${rec.appliedValue}'",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(onClick = { onRollbackPackage(rec.targetPkg, rec.userId) }) {
                            Text("还原此应用")
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(12.dp)) }
    }
}
