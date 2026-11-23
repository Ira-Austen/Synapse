package com.synapse.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.synapse.protocol.NotificationHistoryRecord

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NotificationHistoryScreen(
    state: SynapseUiState,
    initialFilterPkg: String? = null,
    onConsumeInitialFilter: () -> Unit = {},
    onPullRefresh: () -> Unit,
    onClear: () -> Unit
) {
    var onlyManaged by rememberSaveable { mutableStateOf(false) }
    var hideOngoing by rememberSaveable { mutableStateOf(true) }
    var query by rememberSaveable { mutableStateOf("") }
    var showClearConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(initialFilterPkg) {
        if (!initialFilterPkg.isNullOrBlank()) {
            query = state.appLabelCache[initialFilterPkg] ?: initialFilterPkg
            onlyManaged = false
            onConsumeInitialFilter()
        }
    }

    val managedPkgs = remember(state.policyBundle) {
        state.policyBundle.processRules.values
            .filter { it.enabled }
            .map { it.pkg }
            .toSet()
    }

    val filtered = remember(state.notificationHistory, managedPkgs, onlyManaged, hideOngoing, query, state.appLabelCache) {
        val q = query.trim().lowercase()
        state.notificationHistory.filter { rec ->
            if (onlyManaged && !managedPkgs.contains(rec.pkg)) return@filter false
            if (hideOngoing && rec.isOngoing) return@filter false
            if (q.isNotEmpty()) {
                val appName = state.appLabelCache[rec.pkg].orEmpty().lowercase()
                val hit = rec.pkg.lowercase().contains(q) ||
                    appName.contains(q) ||
                    rec.title.lowercase().contains(q) ||
                    rec.text.lowercase().contains(q)
                if (!hit) return@filter false
            }
            true
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清空推送历史？") },
            text = { Text("将清除本地保存的 ${state.notificationHistory.size} 条历史通知记录，不影响系统通知栏。") },
            confirmButton = {
                Button(onClick = {
                    onClear()
                    showClearConfirm = false
                }) { Text("确认清空") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
            }
        )
    }

    PullToRefreshBox(
        isRefreshing = state.isPullRefreshing,
        onRefresh = onPullRefresh,
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "filter_card") {
                SynapseCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "应用推送记录",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "收到新推送即时同步 · 无需通知监听权限",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (state.notificationHistory.isNotEmpty()) {
                                TextButton(onClick = { showClearConfirm = true }) {
                                    Text("清空", color = MaterialTheme.colorScheme.error)
                                }
                            } else {
                                StatusPill(
                                    text = "${filtered.size} 条",
                                    containerColor = SynapseColors.SkyContainer,
                                    contentColor = SynapseColors.SkyOnContainer
                                )
                            }
                        }

                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("搜索应用、标题或正文...") },
                            leadingIcon = {
                                Icon(SynapseIcons.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                            },
                            trailingIcon = {
                                if (query.isNotEmpty()) {
                                    IconButton(onClick = { query = "" }) {
                                        Icon(SynapseIcons.Close, contentDescription = "清除", modifier = Modifier.size(16.dp))
                                    }
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            FilterChip(
                                selected = !onlyManaged,
                                onClick = { onlyManaged = false },
                                label = { Text("全部 (${state.notificationHistory.size})") }
                            )
                            FilterChip(
                                selected = onlyManaged,
                                onClick = { onlyManaged = true },
                                label = { Text("仅保活应用 (${managedPkgs.size})") }
                            )
                            FilterChip(
                                selected = hideOngoing,
                                onClick = { hideOngoing = !hideOngoing },
                                label = { Text("过滤常驻通知") }
                            )
                        }
                    }
                }
            }

            if (filtered.isEmpty()) {
                item(key = "empty") {
                    SynapseCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = if (state.notificationHistory.isEmpty()) "暂时还没有收到推送记录" else "没有符合当前筛选条件的消息",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "手机上的应用收到新通知后，会在这里自动留存并实时滑入展示。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(filtered, key = { "${it.seq}_${it.timestampMs}_${it.pkg}" }) { rec ->
                    NotificationRecordCard(
                        record = rec,
                        appLabel = state.appLabelCache[rec.pkg] ?: rec.pkg,
                        isManaged = managedPkgs.contains(rec.pkg),
                        modifier = Modifier.animateItem()
                    )
                }
            }

            item { Spacer(Modifier.height(14.dp)) }
        }
    }
}

@Composable
private fun NotificationRecordCard(
    record: NotificationHistoryRecord,
    appLabel: String,
    isManaged: Boolean,
    modifier: Modifier = Modifier
) {
    val accent = if (isManaged) SynapseColors.Emerald else MaterialTheme.colorScheme.outlineVariant

    SynapseCard(modifier = modifier.fillMaxWidth(), cornerRadius = 18.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(accent)
            )
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                AppIconAvatar(pkg = record.pkg, label = appLabel, size = 38.dp)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
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
                                text = appLabel,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
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
                            if (record.isOngoing) {
                                StatusPill(
                                    text = "常驻",
                                    containerColor = SynapseColors.AmberContainer,
                                    contentColor = SynapseColors.AmberOnContainer
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        FriendlyTimeText(timestampMs = record.timestampMs)
                    }

                    if (record.title.isNotBlank()) {
                        Text(
                            text = record.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (record.text.isNotBlank()) {
                        Text(
                            text = record.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
