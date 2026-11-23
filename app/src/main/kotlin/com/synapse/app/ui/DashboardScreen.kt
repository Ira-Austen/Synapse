package com.synapse.app.ui

import android.os.Build
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.synapse.app.BuildConfig
import com.synapse.app.xposed.XposedServiceState
import com.synapse.protocol.AuditEvent
import com.synapse.protocol.AuditEventType
import com.synapse.protocol.PluginHealth
import com.synapse.protocol.PluginStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    state: SynapseUiState,
    xposedState: XposedServiceState,
    onPullRefresh: () -> Unit,
    onResetBreaker: () -> Unit,
    onRequestSystemScope: () -> Unit,
    onNavigateTab: (Int) -> Unit
) {
    val health = state.kernelHealth
    val online = health != null
    val activePlugins = health?.plugins?.count { it.status == PluginStatus.ACTIVE } ?: 0
    val totalPlugins = health?.plugins?.size ?: 0
    val managedCount = state.policyBundle.processRules.count { it.value.enabled }
    val aliveProcesses = state.processSnapshot.totalAliveProcesses
    val needsSystemScope = BuildConfig.IS_MODERN_XPOSED &&
        xposedState.connected &&
        !xposedState.scopes.contains("system")

    var showTechDetails by rememberSaveable { mutableStateOf(false) }

    PullToRefreshBox(
        isRefreshing = state.isPullRefreshing,
        onRefresh = onPullRefresh,
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "hero") {
                SynapseCard(
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = if (online) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    },
                    borderAlpha = 0f
                ) {
                    Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (online) "后台保活保护运行中" else "保护引擎尚未激活",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (online) {
                                        "已启用 $activePlugins 项系统级防护 · 零系统设置改动"
                                    } else {
                                        "请在 LSPosed 中勾选「系统框架」并重启手机"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            StatusPill(
                                text = if (online) "保护中" else "未启动",
                                containerColor = if (online) SynapseColors.EmeraldContainer else SynapseColors.RoseContainer,
                                contentColor = if (online) SynapseColors.EmeraldOnContainer else SynapseColors.RoseOnContainer,
                                dotColor = if (online) SynapseColors.Emerald else SynapseColors.Rose
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AnimatedCountTile(
                                count = managedCount,
                                label = "保活应用",
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { onNavigateTab(1) }
                            )
                            AnimatedCountTile(
                                count = aliveProcesses,
                                label = "存活进程",
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { onNavigateTab(2) }
                            )
                            AnimatedCountTile(
                                count = state.notificationHistory.size,
                                label = "推送记录",
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { onNavigateTab(3) }
                            )
                        }

                        if (health?.otaChanged == true) {
                            Surface(
                                color = SynapseColors.AmberContainer,
                                contentColor = SynapseColors.AmberOnContainer,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "检测到手机系统已更新，系统已自动完成安全兼容校验。",
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        if (needsSystemScope) {
                            Surface(
                                color = SynapseColors.RoseContainer,
                                contentColor = SynapseColors.RoseOnContainer,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "还未授权「系统框架 (system)」作用域，点击下方按钮申请并重启手机即可生效。",
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (needsSystemScope) {
                                Button(onClick = onRequestSystemScope) { Text("申请系统作用域") }
                            } else if (managedCount == 0) {
                                Button(onClick = { onNavigateTab(1) }) {
                                    Icon(SynapseIcons.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("添加保活应用")
                                }
                            } else {
                                Button(onClick = { onNavigateTab(1) }) {
                                    Icon(SynapseIcons.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("管理应用", maxLines = 1)
                                }
                                OutlinedButton(onClick = { onNavigateTab(2) }) {
                                    Text("存活进程", maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }

            if (managedCount == 0) {
                item(key = "onboarding") {
                    SynapseCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "新手快速上手",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "1. 点击底部「应用」→「添加应用」，从手机软件列表选出要及时收消息的应用，系统会自动识别推送子进程。\n" +
                                    "2. 切到底部「进程」可一眼看到每个应用有几个进程存活，在「推送」可回看历史通知。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item(key = "activity_header") {
                SectionHeader(
                    title = "最近保护动态",
                    trailing = if (state.auditEvents.isNotEmpty()) "共 ${state.auditEvents.size} 条" else null
                )
            }

            if (state.auditEvents.isEmpty()) {
                item(key = "activity_empty") {
                    SynapseCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "暂无保护记录。添加保活应用并切到后台后，系统拦截冻结、保持联网等动态会自动出现在这里。",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                item(key = "activity_list") {
                    SynapseCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .padding(14.dp)
                                .animateContentSize(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            val collapsed = collapseAuditEvents(state.auditEvents.asReversed()).take(12)
                            collapsed.forEachIndexed { idx, (ev, count) ->
                                FriendlyAuditRow(
                                    ev = ev,
                                    repeatCount = count,
                                    appName = state.appLabelCache[ev.pkg] ?: ev.pkg
                                )
                                if (idx < collapsed.lastIndex) {
                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item(key = "tech_details") {
                SynapseCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .padding(16.dp)
                            .animateContentSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showTechDetails = !showTechDetails },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "高级状态与引擎模块",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (online) {
                                        "${xposedState.frameworkName} ${xposedState.frameworkVersion} · Android ${Build.VERSION.RELEASE} · $activePlugins/$totalPlugins 在线"
                                    } else {
                                        "展开查看底层模块挂载状态"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                imageVector = if (showTechDetails) SynapseIcons.ExpandLess else SynapseIcons.ExpandMore,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        if (showTechDetails) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                            if (health != null) {
                                Text(
                                    text = "微内核 v${health.kernelVersion} · ${health.oomAdjusterImpl} · r${health.policyRevision} · ${health.plugins.sumOf { it.hookCount }} hooks · 事件推送${if (state.eventPushConnected) "已连接" else "待重启启用"}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                health.plugins.forEach { plugin ->
                                    FriendlyPluginRow(plugin)
                                }
                                OutlinedButton(onClick = onResetBreaker) {
                                    Text("重置异常熔断状态")
                                }
                            } else {
                                Text(
                                    text = "请在 LSPosed v2.0+ / Vector 管理器中启用本模块，并确认勾选「系统框架 (system)」，然后重启手机。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(14.dp)) }
        }
    }
}

@Composable
private fun FriendlyPluginRow(plugin: PluginHealth) {
    val (title, desc) = when (plugin.name) {
        "ProcessGuardPlugin" -> "后台防杀与断线拉起" to "防止推送进程被清理或冻结，断线后自动拉起"
        "OemHansPlugin" -> "OPPO / 一加墓碑防冻" to "适配 ColorOS 专属冻结机制（其他机型自动跳过）"
        "MemoryDozePlugin" -> "息屏网络防断联" to "息屏省电模式下保持消息通道不断网"
        "StealthHidePlugin" -> "模块隐身防检测" to "防止普通应用读取或检测本工具的存在"
        "NotificationAuditPlugin" -> "推送通知历史记录" to "自动保存应用发出的推送，无需通知监听权限"
        else -> plugin.name to plugin.message
    }
    val (statusLabel, bg, fg, dot) = when (plugin.status) {
        PluginStatus.ACTIVE -> Quad("运行中", SynapseColors.EmeraldContainer, SynapseColors.EmeraldOnContainer, SynapseColors.Emerald)
        PluginStatus.UNSUPPORTED_ROM -> Quad("当前机型无需", SynapseColors.AmberContainer, SynapseColors.AmberOnContainer, SynapseColors.Amber)
        PluginStatus.DISABLED -> Quad("已关闭", SynapseColors.AmberContainer, SynapseColors.AmberOnContainer, SynapseColors.Amber)
        else -> Quad("已安全跳过", SynapseColors.RoseContainer, SynapseColors.RoseOnContainer, SynapseColors.Rose)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            Text(
                text = desc,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        StatusPill(text = statusLabel, containerColor = bg, contentColor = fg, dotColor = dot)
    }
}

private fun collapseAuditEvents(events: List<AuditEvent>): List<Pair<AuditEvent, Int>> {
    if (events.isEmpty()) return emptyList()
    val out = ArrayList<Pair<AuditEvent, Int>>(events.size.coerceAtMost(24))
    for (ev in events) {
        val last = out.lastOrNull()
        val sameGroup = last != null &&
            last.first.type == ev.type &&
            last.first.pkg == ev.pkg &&
            last.first.processName == ev.processName
        if (sameGroup) {
            out[out.lastIndex] = last!!.first to (last.second + 1)
        } else {
            out.add(ev to 1)
        }
    }
    return out
}

@Composable
private fun FriendlyAuditRow(
    ev: AuditEvent,
    repeatCount: Int,
    appName: String
) {
    val (baseLabel, bg, fg) = when (ev.type) {
        AuditEventType.ADJ_CLAMPED -> Triple("已保护后台优先级", SynapseColors.EmeraldContainer, SynapseColors.EmeraldOnContainer)
        AuditEventType.PROC_STATE_PROTECTED -> Triple("已维持活跃状态", SynapseColors.EmeraldContainer, SynapseColors.EmeraldOnContainer)
        AuditEventType.FREEZE_BLOCKED -> Triple("已拦截系统冻结", SynapseColors.EmeraldContainer, SynapseColors.EmeraldOnContainer)
        AuditEventType.HANS_BYPASSED -> Triple("已拦截厂商冻结", SynapseColors.EmeraldContainer, SynapseColors.EmeraldOnContainer)
        AuditEventType.DOZE_BPF_BYPASS -> Triple("已放行息屏网络", SynapseColors.EmeraldContainer, SynapseColors.EmeraldOnContainer)
        AuditEventType.RESURRECT_OK -> Triple("已自动重新拉起", SynapseColors.EmeraldContainer, SynapseColors.EmeraldOnContainer)
        AuditEventType.RESURRECT_SCHEDULED -> Triple("准备自动拉起", SynapseColors.SkyContainer, SynapseColors.SkyOnContainer)
        AuditEventType.RESURRECT_SKIPPED_STOPPED -> Triple("用户已强停，跳过", SynapseColors.AmberContainer, SynapseColors.AmberOnContainer)
        AuditEventType.RESURRECT_SKIPPED_ALIVE -> Triple("进程已在线", SynapseColors.SkyContainer, SynapseColors.SkyOnContainer)
        AuditEventType.STEALTH_FILTERED -> Triple("已隐藏防检测", SynapseColors.SkyContainer, SynapseColors.SkyOnContainer)
        AuditEventType.POLICY_APPLIED -> Triple("规则已更新生效", SynapseColors.SkyContainer, SynapseColors.SkyOnContainer)
        AuditEventType.KERNEL_BOOT -> Triple("保护引擎启动", SynapseColors.SkyContainer, SynapseColors.SkyOnContainer)
        AuditEventType.BREAKER_RESET -> Triple("已恢复自动拉起", SynapseColors.SkyContainer, SynapseColors.SkyOnContainer)
        AuditEventType.BREAKER_OPEN -> Triple("触发防循环暂停", SynapseColors.RoseContainer, SynapseColors.RoseOnContainer)
        AuditEventType.RESURRECT_FAILED -> Triple("自动拉起失败", SynapseColors.RoseContainer, SynapseColors.RoseOnContainer)
        AuditEventType.OTA_BYPASSED -> Triple("系统更新安全跳过", SynapseColors.AmberContainer, SynapseColors.AmberOnContainer)
        AuditEventType.HOOK_ERROR -> Triple("已隔离兼容异常", SynapseColors.AmberContainer, SynapseColors.AmberOnContainer)
    }
    val label = if (repeatCount > 1) "$baseLabel ×$repeatCount" else baseLabel

    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusPill(text = label, containerColor = bg, contentColor = fg)
            FriendlyTimeText(timestampMs = ev.timestampMs)
        }
        val subtitle = when {
            ev.pkg.isNotBlank() -> buildString {
                append(appName)
                if (ev.processName.contains(':')) {
                    append("（")
                    append(ev.processName.substringAfter(':'))
                    append("）")
                }
            }
            ev.type == AuditEventType.STEALTH_FILTERED -> "已阻止外部应用扫描本工具"
            ev.type == AuditEventType.KERNEL_BOOT -> "系统级微内核保活服务就绪"
            ev.type == AuditEventType.POLICY_APPLIED -> "最新保活规则已即时应用"
            else -> ev.detail
        }
        if (subtitle.isNotBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
