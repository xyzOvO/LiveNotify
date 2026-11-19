package com.example.livenotify.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.livenotify.data.LiveSessionEntity
import com.example.livenotify.platform.PlatformRegistry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 开播历史：每场一条会话，按日期倒序分组。
 * 支持平台/主播/日期/关键词筛选、场次详情、进行中标识；平台真实开播时间与观测时间分开标注。
 * 数据口径不变：只记录观测到的「未开播→开播」跳变，不补造覆盖安装/恢复关注时的在播场次。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(vm: HistoryViewModel, onBack: () -> Unit) {
    val allSessions by vm.sessions.collectAsStateWithLifecycle()
    val streamers by vm.streamers.collectAsStateWithLifecycle()
    val cs = MaterialTheme.colorScheme

    var query by rememberSaveable { mutableStateOf("") }
    var platformFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var anchorFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var dayFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var detailFor by remember { mutableStateOf<LiveSessionEntity?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    val platforms = remember(allSessions) {
        allSessions.map { it.platformId }.distinct().sorted()
    }
    // 可选日期（从真实会话派生，不虚构）
    val days = remember(allSessions) {
        allSessions.map { dayKeyOf(it.startedAtMs) }.distinct().sortedDescending()
    }

    val visible = remember(allSessions, query, platformFilter, anchorFilter, dayFilter) {
        filterSessions(allSessions, platformFilter, anchorFilter, dayFilter, query)
    }
    val sections = remember(visible) { groupByDate(visible) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("开播历史") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (allSessions.isNotEmpty()) {
                        IconButton(onClick = { confirmClear = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "清空历史")
                        }
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = Dimens.SpaceXL)
        ) {
            item {
                Column(Modifier.padding(horizontal = Dimens.SpaceL, vertical = Dimens.SpaceS)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("搜索主播或直播标题") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = Dimens.ShapeSmall
                    )
                    Spacer(Modifier.height(Dimens.SpaceS))
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = platformFilter == null,
                            onClick = { platformFilter = null },
                            label = { Text("全部平台") }
                        )
                        platforms.forEach { pid ->
                            FilterChip(
                                selected = platformFilter == pid,
                                onClick = { platformFilter = if (platformFilter == pid) null else pid },
                                label = { Text(PlatformRegistry.get(pid)?.displayName ?: pid) }
                            )
                        }
                    }
                    Spacer(Modifier.height(Dimens.SpaceXS))
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = anchorFilter == null,
                            onClick = { anchorFilter = null },
                            label = { Text("全部主播") }
                        )
                        streamers.forEach { s ->
                            FilterChip(
                                selected = anchorFilter == "${s.platformId}:${s.anchorId}",
                                onClick = {
                                    val key = "${s.platformId}:${s.anchorId}"
                                    anchorFilter = if (anchorFilter == key) null else key
                                },
                                label = { Text(s.nickname, maxLines = 1) }
                            )
                        }
                    }
                    Spacer(Modifier.height(Dimens.SpaceXS))
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = dayFilter == null,
                            onClick = { dayFilter = null },
                            label = { Text("全部日期") }
                        )
                        days.forEach { d ->
                            FilterChip(
                                selected = dayFilter == d,
                                onClick = { dayFilter = if (dayFilter == d) null else d },
                                label = { Text(d) }
                            )
                        }
                    }
                }
            }

            if (sections.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 60.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            if (allSessions.isEmpty()) "还没有开播记录"
                            else if (visible.isEmpty()) "没有符合条件的记录" else "还没有开播记录",
                            style = MaterialTheme.typography.bodyMedium,
                            color = cs.outline
                        )
                        Spacer(Modifier.height(Dimens.SpaceXS))
                        Text(
                            "开播后会自动记录，保存最近 90 天",
                            style = MaterialTheme.typography.labelSmall,
                            color = StatusColors.muted()
                        )
                    }
                }
            } else {
                sections.forEach { (day, items) ->
                    item(key = "day:$day") {
                        Text(
                            day,
                            style = MaterialTheme.typography.labelLarge,
                            color = cs.primary,
                            modifier = Modifier.padding(horizontal = Dimens.SpaceL, vertical = Dimens.SpaceS)
                        )
                    }
                    items(items, key = { "s:${it.id}" }) { session ->
                        SessionCard(session, onClick = { detailFor = session })
                    }
                }
            }
        }
    }

    detailFor?.let { SessionDetailDialog(it, onDismiss = { detailFor = null }) }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空开播历史") },
            text = { Text("将删除全部 ${allSessions.size} 条记录，此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearAll()
                    confirmClear = false
                }) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } }
        )
    }
}

/** 进行中状态徽标：与通知状态区分，独立醒目 */
@Composable
private fun LiveBadge(ongoing: Boolean) {
    if (!ongoing) return
    val live = StatusColors.live()
    Text(
        "● 直播中",
        style = MaterialTheme.typography.labelSmall,
        color = live,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(Dimens.ShapePill)
            .background(live.copy(alpha = 0.12f))
            .padding(horizontal = Dimens.SpaceS, vertical = Dimens.SpaceXS)
    )
}

@Composable
private fun SessionCard(session: LiveSessionEntity, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val fmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val start = fmt.format(Date(session.startedAtMs))
    val end = if (session.endAtMs > 0L) fmt.format(Date(session.endAtMs)) else "进行中"
    val duration = durationLabel(session.durationMs(System.currentTimeMillis()))
    val platform = PlatformRegistry.get(session.platformId)?.displayName ?: session.platformId
    val ongoing = session.endAtMs <= 0L

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.SpaceL, vertical = Dimens.SpaceXS)
            .clip(Dimens.ShapeMedium)
            .clickable(onClick = onClick),
        shape = Dimens.ShapeMedium,
        colors = CardDefaults.cardColors(
            containerColor = if (ongoing) StatusColors.live().copy(alpha = 0.05f) else cardSurface(0.5f)
        )
    ) {
        Column(Modifier.padding(Dimens.SpaceM)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    session.nickname,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (ongoing) LiveBadge(ongoing = true) else NotifyBadge(session)
            }
            Spacer(Modifier.height(Dimens.SpaceXS))
            Text(
                "$platform · $start — $end",
                style = MaterialTheme.typography.labelMedium,
                color = cs.outline
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "时长 $duration",
                style = MaterialTheme.typography.labelMedium,
                color = cs.outline
            )
            if (session.title.isNotBlank()) {
                Spacer(Modifier.height(Dimens.SpaceS))
                Text(
                    session.title,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** 场次详情：完整信息 + 时间口径说明 */
@Composable
private fun SessionDetailDialog(session: LiveSessionEntity, onDismiss: () -> Unit) {
    val full = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    val ongoing = session.endAtMs <= 0L
    val duration = durationLabel(session.durationMs(System.currentTimeMillis()))
    val platform = PlatformRegistry.get(session.platformId)?.displayName ?: session.platformId
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(session.nickname, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                InfoLine("平台", platform)
                if (session.title.isNotBlank()) InfoLine("直播标题", session.title)
                InfoLine("观测开播", full.format(Date(session.startedAtMs)))
                InfoLine("下播时间", if (ongoing) "进行中" else full.format(Date(session.endAtMs)))
                InfoLine("直播时长", duration)
                if (session.startAtSec > 0L) {
                    InfoLine("平台开播时刻", full.format(Date(session.startAtSec * 1000L)))
                    Spacer(Modifier.height(Dimens.SpaceXS))
                    Text(
                        "注：平台开播时刻来自接口，观测开播是本 App 首次发现的时间；两者可能不同。",
                        style = MaterialTheme.typography.labelSmall,
                        color = StatusColors.muted()
                    )
                } else {
                    Spacer(Modifier.height(Dimens.SpaceXS))
                    Text(
                        "本场无平台开播时间来源，上方「观测开播」为本 App 首次发现的时间，非平台真实开播时刻。",
                        style = MaterialTheme.typography.labelSmall,
                        color = StatusColors.warning()
                    )
                }
                Spacer(Modifier.height(Dimens.SpaceS))
                InfoLine("提醒状态", notifyLabel(session))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.weight(0.35f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(0.65f)
        )
    }
}

private fun notifyLabel(s: LiveSessionEntity): String = when {
    s.notified -> "已提醒"
    s.notifyFailed -> "未送达（通知权限或渠道被关）"
    else -> "未提醒"
}

@Composable
private fun NotifyBadge(session: LiveSessionEntity) {
    val cs = MaterialTheme.colorScheme
    val (label, color) = when {
        session.notified -> "已提醒" to StatusColors.success()
        session.notifyFailed -> "未送达" to cs.error
        else -> "未提醒" to cs.outline
    }
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .clip(Dimens.ShapeSmall)
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = Dimens.SpaceS, vertical = Dimens.SpaceXS)
    )
}
