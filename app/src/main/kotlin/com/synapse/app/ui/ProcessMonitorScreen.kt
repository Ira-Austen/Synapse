package com.synapse.app.ui

import androidx.compose.animation.animateContentSize
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.synapse.app.data.LiveProcessInfo
import com.synapse.app.data.ManagedAppProcessGroup
import com.synapse.protocol.ProcessRule
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Calm, silky, event-driven background process monitor with native pull-to-refresh.
 * Reads directly from system_server memory (<1ms, zero Root needed) and updates
 * instantaneously when managed processes start, exit, or change priority.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProcessMonitorScreen(
    state: SynapseUiState,
    onPullRefresh: () -> Unit,
    onWakeRule: (ProcessRule) -> Unit,
    onLaunchApp: (String) -> Unit,
    onOpenAddApp: () -> Unit,
    onViewAppNotifications: (String) -> Unit
) {
    val snapshot = state.processSnapshot
    val groups = snapshot.groups

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
            item(key = "summary") {
                SynapseCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "后台进程状态",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "进程变动自动同步 · 下拉可刷新最新内存",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            StatusPill(
                                text = if (groups.isEmpty()) "待添加" else "${snapshot.runningAppsCount}/${groups.size} 在线",
                                containerColor = if (snapshot.runningAppsCount > 0) SynapseColors.EmeraldContainer else SynapseColors.SkyContainer,
                                contentColor = if (snapshot.runningAppsCount > 0) SynapseColors.EmeraldOnContainer else SynapseColors.SkyOnContainer,
                                dotColor = if (snapshot.runningAppsCount > 0) SynapseColors.Emerald else SynapseColors.Sky
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                AnimatedCountTile(
                                    count = snapshot.runningAppsCount,
                                    label = "运行中应用",
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                AnimatedCountTile(
                                    count = snapshot.totalAliveProcesses,
                                    label = "存活进程",
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                AnimatedCountTile(
                                    count = snapshot.totalRssMb.roundToInt(),
                                    label = "合计内存",
                                    suffix = " MB",
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }

                        if (!snapshot.errorMessage.isNullOrBlank()) {
                            Surface(
                                color = SynapseColors.AmberContainer,
                                contentColor = SynapseColors.AmberOnContainer,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = snapshot.errorMessage,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }

                        if (groups.isEmpty()) {
                            Button(onClick = onOpenAddApp) {
                                Text("去添加保活应用")
                            }
                        }
                    }
                }
            }

            if (groups.isEmpty()) {
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
                                text = "前往「应用」页选择需要及时收消息的软件后，这里就能清楚看到每个应用当前存活的后台进程数量和状态。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(groups, key = { it.rule.ruleKey }) { group ->
                    ManagedProcessGroupCard(
                        group = group,
                        onWake = { onWakeRule(group.rule) },
                        onLaunchApp = { onLaunchApp(group.rule.pkg) },
                        onViewNotifs = { onViewAppNotifications(group.rule.pkg) },
                        modifier = Modifier.animateItem()
                    )
                }
            }

            item { Spacer(Modifier.height(14.dp)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ManagedProcessGroupCard(
    group: ManagedAppProcessGroup,
    onWake: () -> Unit,
    onLaunchApp: () -> Unit,
    onViewNotifs: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rule = group.rule
    val (statusText, statusBg, statusFg, dotColor) = when {
        !rule.enabled -> Quadruple(
            "已暂停保活",
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            MaterialTheme.colorScheme.outline
        )
        group.aliveCount == 0 -> Quadruple(
            "0 进程 · 未运行",
            SynapseColors.AmberContainer,
            SynapseColors.AmberOnContainer,
            SynapseColors.Amber
        )
        group.targetAlive && group.aliveCount == 1 -> Quadruple(
            "1 进程 · 仅推送",
            SynapseColors.EmeraldContainer,
            SynapseColors.EmeraldOnContainer,
            SynapseColors.Emerald
        )
        group.targetAlive -> Quadruple(
            "${group.aliveCount} 进程 · 推送在线",
            SynapseColors.EmeraldContainer,
            SynapseColors.EmeraldOnContainer,
            SynapseColors.Emerald
        )
        else -> Quadruple(
            "${group.aliveCount} 进程 · 待就绪",
            SynapseColors.AmberContainer,
            SynapseColors.AmberOnContainer,
            SynapseColors.Amber
        )
    }

    SynapseCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AppIconAvatar(pkg = rule.pkg, label = group.appLabel, size = 44.dp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = group.appLabel,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (group.aliveCount > 0) {
                            String.format(Locale.US, "%.1f MB", group.totalRssMb)
                        } else {
                            "未运行"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                StatusPill(
                    text = statusText,
                    containerColor = statusBg,
                    contentColor = statusFg,
                    dotColor = dotColor
                )
            }

            if (group.processes.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        group.processes.forEachIndexed { idx, proc ->
                            ProcessDetailRow(proc)
                            if (idx < group.processes.lastIndex) {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                                )
                            }
                        }
                    }
                }
            } else {
                Text(
                    text = "应用尚未启动或已被手动强停；点击下方按钮即可在后台静默唤醒推送通道。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (!group.targetAlive && rule.enabled && !rule.wakeComponent.isNullOrBlank()) {
                    Button(onClick = onWake) {
                        Icon(SynapseIcons.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("立即唤醒推送")
                    }
                }
                if (group.aliveCount == 0) {
                    OutlinedButton(onClick = onLaunchApp) {
                        Text("打开应用")
                    }
                }
                OutlinedButton(onClick = onViewNotifs) {
                    Text("查看推送记录")
                }
            }
        }
    }
}

@Composable
private fun ProcessDetailRow(proc: LiveProcessInfo) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = if (proc.isMainProcess) "主进程" else proc.shortName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (proc.isProtectedTarget) {
                    StatusPill(
                        text = "保活目标",
                        containerColor = SynapseColors.EmeraldContainer,
                        contentColor = SynapseColors.EmeraldOnContainer
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = String.format(Locale.US, "%.1f MB", proc.rssMb),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = buildString {
                    append(proc.priorityLabel)
                    if (proc.oomScoreAdj != null) {
                        append(" (优先级 ${proc.oomScoreAdj})")
                    }
                    append(" · PID ${proc.pid}")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            StatusPill(
                text = if (proc.isFrozen) "已冻结" else "正常运行",
                containerColor = if (proc.isFrozen) SynapseColors.AmberContainer else SynapseColors.SkyContainer,
                contentColor = if (proc.isFrozen) SynapseColors.AmberOnContainer else SynapseColors.SkyOnContainer
            )
        }
    }
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
