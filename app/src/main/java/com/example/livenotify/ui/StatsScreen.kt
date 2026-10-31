package com.example.livenotify.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.livenotify.bili.BiliStatsApi
import com.example.livenotify.bili.CareerStats
import com.example.livenotify.bili.ChannelStats
import com.example.livenotify.bili.DayStat
import com.example.livenotify.bili.FansPoint
import com.example.livenotify.bili.TrendMonth
import com.example.livenotify.bili.UserAnalysis
import com.example.livenotify.bili.UserEvent
import com.example.livenotify.data.StreamerEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * B 站数据页。
 *
 * 两块内容：
 *  - 粉丝走势：主播的粉丝数历史 + 月度互动观众/付费趋势 + 生涯统计
 *  - 互动事件：分析某个用户（观众）在各直播间的互动行为
 *
 * 数据全部来自第三方公开统计，不涉及任何账号登录态。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    vm: StatsViewModel,
    initialUid: Long,
    onBack: () -> Unit
) {
    val streamers by vm.biliStreamers.collectAsStateWithLifecycle()
    val ui by vm.channelUi.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var selectedUid by rememberSaveable { mutableStateOf(initialUid) }
    var showUidDialog by rememberSaveable { mutableStateOf(false) }

    // 进入时带了具体主播就切过去；没带则默认看列表里第一个。
    // rememberSaveable 会保留上次离开时的选择，所以这里必须跟参数同步，
    // 否则从 A 主播退出后再长按 B 进来，看到的还是 A。
    LaunchedEffect(initialUid, streamers) {
        if (initialUid > 0L) {
            if (selectedUid != initialUid) selectedUid = initialUid
        } else if (selectedUid <= 0L) {
            val first = streamers.firstOrNull()?.anchorId?.toLongOrNull() ?: 0L
            if (first > 0L) selectedUid = first
        }
    }
    LaunchedEffect(selectedUid) {
        if (selectedUid > 0L) {
            vm.load(selectedUid)
            // 换人后旧的分析结果会对不上号，直接清掉
            vm.clearAnalysis()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("B站数据") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = { showUidDialog = true }) { Text("输入 UID") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            if (streamers.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(streamers, key = { it.id }) { s ->
                        val uid = s.anchorId.toLongOrNull() ?: 0L
                        PickerChip(
                            text = s.nickname,
                            selected = uid == selectedUid,
                            onClick = { if (uid > 0L) selectedUid = uid }
                        )
                    }
                }
            }

            TabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("粉丝走势") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("互动事件") })
            }

            when (tab) {
                0 -> ChannelTab(ui = ui, onRetry = { vm.reload() }, onOpenUid = { showUidDialog = true })
                else -> UserTab(vm = vm, defaultUid = selectedUid)
            }
        }
    }

    if (showUidDialog) {
        UidDialog(
            onDismiss = { showUidDialog = false },
            onConfirm = { uid ->
                showUidDialog = false
                selectedUid = uid
                vm.load(uid)
            }
        )
    }
}

// ── Tab 1：粉丝走势 ─────────────────────────────────────────────────────

@Composable
private fun ChannelTab(
    ui: StatsViewModel.ChannelUi,
    onRetry: () -> Unit,
    onOpenUid: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    when {
        ui.loading -> CenterBox { CircularProgressIndicator() }

        ui.channel == null -> Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                ui.error ?: "没有取到数据",
                style = MaterialTheme.typography.bodyMedium,
                color = cs.outline
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRetry) { Text("重试") }
                OutlinedButton(onClick = onOpenUid) { Text("换个 UID") }
            }
        }

        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { ChannelHead(ui.channel, ui.career?.firstLiveAt.orEmpty()) }
            item { FansCard(ui.channel.fansHistory, ui.channel.fansCount) }
            if (ui.trend.isNotEmpty()) {
                item { TrendCard(ui.trend) }
            }
            ui.career?.let { c -> item { CareerCard(c) } }
            item {
                Text(
                    BiliStatsApi.SOURCE_NOTE,
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.outline.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                )
            }
        }
    }
}

@Composable
private fun ChannelHead(ch: ChannelStats, firstLiveAt: String) {
    val cs = MaterialTheme.colorScheme
    val dayFmt = remember { SimpleDateFormat("MM-dd", Locale.getDefault()) }
    // 粉丝数是实时值，取最后一次采样点当作「截至」的时刻
    val snapAt = remember(ch.fansHistory) {
        ch.fansHistory.lastOrNull()?.at?.let { dayFmt.format(Date(it)) }
    }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (ch.faceUrl.isNotBlank()) {
                AsyncImage(
                    model = ch.faceUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(cs.surfaceVariant)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(cs.surfaceVariant)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        ch.name.ifBlank { "UID ${ch.uid}" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (ch.isLiving) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "直播中",
                            style = MaterialTheme.typography.labelSmall,
                            color = LiveAccent,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                Text(
                    "UID ${ch.uid}" + if (ch.roomId > 0) " · 房间 ${ch.roomId}" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.outline
                )
                if (ch.sign.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        ch.sign,
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.outline.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCell(
                "粉丝",
                fmtCount(ch.fansCount.toLong()),
                Modifier.weight(1f),
                caption = snapAt?.let { "截至 $it" } ?: "当前值"
            )
            StatCell("场次", fmtCount(ch.liveCount), Modifier.weight(1f), caption = "生涯累计")
            StatCell("总收入", fmtMoney(ch.totalIncome), Modifier.weight(1f), caption = "生涯累计")
        }
        // 数字不标口径等于没说：这里讲清楚「生涯」从哪天开始算
        val firstLive = firstLiveAt.take(10)
        if (firstLive.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "场次与总收入为生涯累计，自 $firstLive 首播起算",
                style = MaterialTheme.typography.labelSmall,
                color = cs.outline.copy(alpha = 0.85f)
            )
        }
    }
}

@Composable
private fun FansCard(points: List<FansPoint>, current: Int) {
    val cs = MaterialTheme.colorScheme
    // 带年份：粉丝历史可能跨年，只给 MM-dd 会看不出是哪一年
    val fmt = remember { SimpleDateFormat("yy-MM-dd", Locale.getDefault()) }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("粉丝走势", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            if (points.size >= 2) {
                val delta = points.last().value - points.first().value
                Text(
                    "区间 " + (if (delta >= 0) "+" else "") + fmtCount(delta.toLong()),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (delta >= 0) okColor() else cs.outline
                )
            }
        }
        if (points.size >= 2) {
            val spanDays = ((points.last().at - points.first().at) / 86_400_000L).coerceAtLeast(0L)
            Text(
                "社区采集区间：共 ${points.size} 个采样点、跨 $spanDays 天（非平台完整历史）",
                style = MaterialTheme.typography.labelSmall,
                color = cs.outline
            )
        }
        Spacer(Modifier.height(10.dp))
        if (points.size < 2) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "这个主播还没有粉丝历史采样",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.outline
                )
            }
        } else {
            FansChart(
                points = points,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
            )
            Spacer(Modifier.height(6.dp))
            Row {
                Text(
                    fmt.format(Date(points.first().at)),
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.outline
                )
                Spacer(Modifier.weight(1f))
                Text(
                    fmtCount(points.first().value.toLong()) + " 起",
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.outline
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    fmtCount(current.toLong()) + " 现",
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.outline
                )
                Spacer(Modifier.weight(1f))
                Text(
                    fmt.format(Date(points.last().at)),
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.outline
                )
            }
        }
    }
}

@Composable
private fun TrendCard(months: List<TrendMonth>) {
    val cs = MaterialTheme.colorScheme
    val recent = remember(months) { months.takeLast(12) }
    SectionCard {
        Text("月度互动观众", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "每月在直播间发过言、送过礼或上舰的独立观众数",
            style = MaterialTheme.typography.labelSmall,
            color = cs.outline
        )
        if (recent.size >= 2) {
            Text(
                "统计最近 ${recent.size} 个月：${recent.first().month} ~ ${recent.last().month}",
                style = MaterialTheme.typography.labelSmall,
                color = cs.outline.copy(alpha = 0.8f)
            )
        }
        Spacer(Modifier.height(12.dp))
        BarsChart(
            values = recent.map { it.viewers.toFloat() },
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
        )
        Spacer(Modifier.height(6.dp))
        Row {
            Text(recent.firstOrNull()?.month?.takeLast(5) ?: "", style = MaterialTheme.typography.labelSmall, color = cs.outline)
            Spacer(Modifier.weight(1f))
            Text(recent.lastOrNull()?.month?.takeLast(5) ?: "", style = MaterialTheme.typography.labelSmall, color = cs.outline)
        }
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = cs.outline.copy(alpha = 0.15f))
        Spacer(Modifier.height(8.dp))
        recent.reversed().take(6).forEach { m ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(m.month, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(64.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "观众 ${fmtCount(m.viewers.toLong())}",
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant
                    )
                    Text(
                        "新 ${fmtCount(m.newViewers.toLong())} · 回访 ${fmtCount(m.returning.toLong())}",
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.outline
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "付费 ${fmtCount(m.payers.toLong())}",
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant
                    )
                    Text(
                        (if (m.guards > 0) "上舰 ${m.guards} · " else "") + fmtMoney(m.paidYuan),
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.outline
                    )
                }
            }
        }
    }
}

@Composable
private fun CareerCard(c: CareerStats) {
    val cs = MaterialTheme.colorScheme
    val first = c.firstLiveAt.take(10)
    val last = c.lastLiveAt.take(10)
    SectionCard {
        Text("生涯统计", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        // 这一行就是口径：下面是整段直播生涯的累计，不是某段时间
        Text(
            buildString {
                if (first.isNotBlank()) append("$first 首播起的累计值")
                else append("生涯累计值")
                if (last.isNotBlank()) append(" · 最近一场 $last")
            },
            style = MaterialTheme.typography.labelSmall,
            color = cs.outline
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCell("开播场次", fmtCount(c.liveCount.toLong()), Modifier.weight(1f))
            StatCell("活跃天数", fmtCount(c.activeDays.toLong()), Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCell("总时长", fmtDuration(c.liveSeconds), Modifier.weight(1f))
            StatCell("弹幕总量", fmtCount(c.danmaku), Modifier.weight(1f))
        }
    }
}

// ── Tab 2：互动事件 ─────────────────────────────────────────────────────

@Composable
private fun UserTab(vm: StatsViewModel, defaultUid: Long) {
    val analysis by vm.analysis.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val cs = MaterialTheme.colorScheme
    var uidText by rememberSaveable { mutableStateOf(if (defaultUid > 0) defaultUid.toString() else "") }
    var rangeDays by rememberSaveable { mutableIntStateOf(90) }
    val currentUid = uidText.toLongOrNull() ?: 0L
    // 输入或时间范围变化后，旧请求/旧结果不能再冒充当前查询对象。
    val visibleAnalysis = analysis.takeIf { it.belongsTo(currentUid, rangeDays) }
    val visibleRecent = recent.takeIf { it.belongsTo(currentUid) }

    LaunchedEffect(defaultUid) {
        if (defaultUid > 0L && uidText.isBlank()) uidText = defaultUid.toString()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SectionCard {
                Text("用户互动", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "填一个 B 站 UID（你自己的或任意观众），统计他在各直播间的发言、送礼、上舰与活跃轨迹。",
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.outline
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = uidText,
                    onValueChange = { v -> uidText = v.filter { it.isDigit() }.take(12) },
                    label = { Text("用户 UID") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 不提供「全部」：跨度拉到 5 年以上时服务端会 504，2 年是实测能稳定返回的上限
                    listOf(30 to "30 天", 90 to "90 天", 365 to "1 年", 730 to "2 年").forEach { (d, label) ->
                        PickerChip(text = label, selected = rangeDays == d, onClick = { rangeDays = d })
                    }
                }
                Spacer(Modifier.height(12.dp))
                when (val a = visibleAnalysis) {
                    is StatsViewModel.AnalysisUi.Running -> {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    a.message.ifBlank { "分析中…" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = cs.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            BarsChart(
                                values = List(1) { a.percent.toFloat() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp),
                                rounded = true
                            )
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = { vm.cancelAnalysis() }) { Text("取消") }
                        }
                    }

                    is StatsViewModel.AnalysisUi.Failed -> {
                        Text(a.message, style = MaterialTheme.typography.bodySmall, color = cs.error)
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                val uid = uidText.toLongOrNull() ?: 0L
                                if (uid > 0L) vm.analyze(uid, rangeDays)
                            }
                        ) { Text("重试") }
                    }

                    else -> {
                        OutlinedButton(
                            enabled = (uidText.toLongOrNull() ?: 0L) > 0L,
                            onClick = {
                                val uid = uidText.toLongOrNull() ?: 0L
                                // 已有结果时按钮是「重新分析」，必须绕过缓存重新请求
                                if (uid > 0L) {
                                    vm.analyze(uid, rangeDays, force = visibleAnalysis is StatsViewModel.AnalysisUi.Done)
                                }
                            }
                        ) {
                            Text(if (visibleAnalysis is StatsViewModel.AnalysisUi.Done) "重新分析" else "开始分析")
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    BiliStatsApi.SOURCE_NOTE,
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.outline.copy(alpha = 0.7f)
                )
            }
        }

        // 最近的互动：轻查询，独立于分析任务渲染；分析失败也照样能看
        when (val r = visibleRecent) {
            is StatsViewModel.RecentUi.Loading -> item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("取最近的互动…", style = MaterialTheme.typography.labelSmall, color = cs.outline)
                }
            }

            is StatsViewModel.RecentUi.Failed -> item {
                Text("最近的互动取不到：${r.message}", style = MaterialTheme.typography.labelSmall, color = cs.outline)
            }

            is StatsViewModel.RecentUi.Done -> if (r.events.isNotEmpty()) {
                item { RecentEventsCard(r.events) }
            }

            else -> {}
        }

        (visibleAnalysis as? StatsViewModel.AnalysisUi.Done)?.let { done ->
            val d = done.data
            if (d.summary.messages == 0 && d.summary.interactions == 0) {
                item {
                    SectionCard {
                        Text(
                            "这个 UID 在所选时间段内没有互动记录。可能原因：uid 填错、该用户不看直播，或这段时间没有采样。",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.outline
                        )
                    }
                }
            } else {
                if (d.names.size > 1) {
                    item {
                        Text(
                            "用过的昵称：" + d.names.joinToString(" / "),
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.outline
                        )
                    }
                }
                item { SummaryCard(d, done.fromMs, done.toMs) }
                if (d.days.isNotEmpty()) item { DailyCard(d.days) }
                if (d.eventTypes.isNotEmpty()) item { EventTypeCard(d) }
                if (d.affinities.isNotEmpty()) item { AffinityCard(d) }
                if (d.topPayments.isNotEmpty()) item { PaymentCard(d) }
                if (d.rhythm.isNotEmpty()) item { RhythmCard(d) }
                if (d.wordCloud.isNotEmpty()) item { WordCloudCard(d) }
            }
        }
    }
}

/**
 * 最近的互动：逐条记录，按时间倒序（最近在前）。
 *
 * 每行的时间、类型、内容、直播间来自同一条服务端记录，用固定宽度的时间列
 * 对齐，内容再长也不会把时间挤歪——这是「不错位」的关键。
 */
@Composable
private fun RecentEventsCard(events: List<UserEvent>) {
    val cs = MaterialTheme.colorScheme
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("最近的互动", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text("最近在前 · 共 ${events.size} 条", style = MaterialTheme.typography.labelSmall, color = cs.outline)
        }
        Text(
            "该用户发出的弹幕 / 礼物 / 上舰 / 醒目留言，不含仅进场",
            style = MaterialTheme.typography.labelSmall,
            color = cs.outline.copy(alpha = 0.7f)
        )
        Spacer(Modifier.height(8.dp))
        events.forEach { e ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.Top
            ) {
                // 固定宽度时间列：整列对齐，不随内容长度漂移
                Text(
                    fmt.format(Date(e.ts)),
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.outline,
                    maxLines = 1,
                    modifier = Modifier.width(86.dp)
                )
                Text(
                    eventLabel(e.type.toString()),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = platformAccent("bilibili"),
                    maxLines = 1,
                    modifier = Modifier.width(64.dp)
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        e.text.ifBlank { "（无文本）" },
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3
                    )
                    val where = buildList {
                        if (e.channelName.isNotBlank()) add(e.channelName)
                        if (e.liveTitle.isNotBlank()) add(e.liveTitle)
                    }.joinToString(" · ")
                    if (where.isNotBlank()) {
                        Text(
                            where,
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.outline.copy(alpha = 0.7f),
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(d: UserAnalysis, fromMs: Long, toMs: Long) {
    val cs = MaterialTheme.colorScheme
    val s = d.summary
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    SectionCard {
        Text("互动概要", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        if (fromMs > 0L && toMs > 0L) {
            Text(
                "统计区间 ${fmt.format(Date(fromMs))} ~ ${fmt.format(Date(toMs))}",
                style = MaterialTheme.typography.labelSmall,
                color = cs.outline
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCell("发言", fmtCount(s.messages.toLong()), Modifier.weight(1f))
            StatCell("互动", fmtCount(s.interactions.toLong()), Modifier.weight(1f))
            StatCell("打赏", fmtMoney(s.payment), Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCell("活跃天", s.activeDays.toString(), Modifier.weight(1f))
            StatCell("出现场次", s.appearanceLives.toString(), Modifier.weight(1f))
            StatCell("付费场次", s.paidLives.toString(), Modifier.weight(1f))
        }
        d.insights?.let { i ->
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = cs.outline.copy(alpha = 0.15f))
            Spacer(Modifier.height(8.dp))
            Text(
                buildList {
                    add("夜猫子 ${pct(i.nightOwlRatio)}")
                    add("重复率 ${String.format(Locale.US, "%.2f", i.repeatIndex)}")
                    add("平均 ${String.format(Locale.US, "%.1f", i.avgLength)} 字")
                    if (i.maxRepeatedText.isNotBlank()) add("最常说「${i.maxRepeatedText}」×${i.maxRepeatedCount}")
                }.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = cs.outline
            )
        }
    }
}

@Composable
private fun DailyCard(days: List<DayStat>) {
    val cs = MaterialTheme.colorScheme
    val recent = remember(days) { days.takeLast(30) }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("活跃走势", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            // takeLast(30) 取的是最近 30 个「有记录的日子」，不是自然日，别写成「近 30 天」
            Text("最近 ${recent.size} 个活跃日", style = MaterialTheme.typography.labelSmall, color = cs.outline)
        }
        Spacer(Modifier.height(10.dp))
        BarsChart(
            values = recent.map { it.interactions.toFloat() },
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp)
        )
        Spacer(Modifier.height(6.dp))
        Row {
            Text(recent.firstOrNull()?.date?.takeLast(5) ?: "", style = MaterialTheme.typography.labelSmall, color = cs.outline)
            Spacer(Modifier.weight(1f))
            Text(recent.lastOrNull()?.date?.takeLast(5) ?: "", style = MaterialTheme.typography.labelSmall, color = cs.outline)
        }
    }
}

@Composable
private fun EventTypeCard(d: UserAnalysis) {
    val cs = MaterialTheme.colorScheme
    val max = remember(d) { d.eventTypes.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1 }
    SectionCard {
        Text("事件类型", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        d.eventTypes.take(8).forEach { e ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    eventLabel(e.name),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.width(64.dp)
                )
                Box(
                    Modifier
                        .weight(1f)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(cs.surfaceVariant)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(e.count.toFloat() / max)
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(platformAccent("bilibili"))
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    fmtCount(e.count.toLong()),
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.width(48.dp)
                )
            }
        }
    }
}

@Composable
private fun AffinityCard(d: UserAnalysis) {
    val cs = MaterialTheme.colorScheme
    SectionCard {
        Text("常去的直播间", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        d.affinities.take(6).forEach { a ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (a.faceUrl.isNotBlank()) {
                    AsyncImage(
                        model = a.faceUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(cs.surfaceVariant)
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        a.name.ifBlank { a.uid.toString() },
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "发言 ${fmtCount(a.messages.toLong())} · ${a.activeDays} 天 · ${a.liveCount} 场",
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.outline
                    )
                }
                Text(
                    fmtMoney(a.payment),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (a.payment > 0) cs.onSurfaceVariant else cs.outline
                )
            }
        }
    }
}

@Composable
private fun PaymentCard(d: UserAnalysis) {
    val cs = MaterialTheme.colorScheme
    SectionCard {
        Text("打赏记录", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        d.topPayments.take(6).forEach { p ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        p.name.ifBlank { "—" },
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        (if (p.channelName.isNotBlank()) "在 ${p.channelName} · " else "") + fmtDate(p.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.outline
                    )
                }
                Text(
                    fmtMoney(p.amount),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun RhythmCard(d: UserAnalysis) {
    val cs = MaterialTheme.colorScheme
    val max = remember(d) { d.rhythm.flatten().maxOrNull()?.coerceAtLeast(1) ?: 1 }
    val accent = platformAccent("bilibili")
    val weekdays = listOf("一", "二", "三", "四", "五", "六", "日")
    SectionCard {
        Text("活跃时段", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text("按周几 × 小时统计发言量", style = MaterialTheme.typography.labelSmall, color = cs.outline)
        Spacer(Modifier.height(10.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp)
        ) {
            val rows = d.rhythm.size.coerceAtMost(7)
            if (rows <= 0) return@Canvas
            val cols = 24
            val cellW = size.width / cols
            val cellH = size.height / rows
            val gap = 1.5f
            for (r in 0 until rows) {
                val row = d.rhythm.getOrNull(r) ?: continue
                for (c in 0 until cols) {
                    val v = row.getOrNull(c) ?: 0
                    val alpha = if (v <= 0) 0.06f else (0.15f + 0.85f * (v.toFloat() / max))
                    drawRect(
                        color = if (v <= 0) cs.outline.copy(alpha = alpha) else accent.copy(alpha = alpha),
                        topLeft = Offset(c * cellW, r * cellH),
                        size = Size(cellW - gap, cellH - gap)
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row {
            Text("", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(20.dp))
            Text("0 时", style = MaterialTheme.typography.labelSmall, color = cs.outline)
            Spacer(Modifier.weight(1f))
            Text("12 时", style = MaterialTheme.typography.labelSmall, color = cs.outline)
            Spacer(Modifier.weight(1f))
            Text("23 时", style = MaterialTheme.typography.labelSmall, color = cs.outline)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "行序：" + weekdays.joinToString(" "),
            style = MaterialTheme.typography.labelSmall,
            color = cs.outline.copy(alpha = 0.7f)
        )
    }
}

@Composable
private fun WordCloudCard(d: UserAnalysis) {
    val cs = MaterialTheme.colorScheme
    SectionCard {
        Text("常用词", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            d.wordCloud.chunked(4).take(6).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { (w, n) ->
                        Text(
                            "$w ×$n",
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.onSurfaceVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(cs.surfaceVariant.copy(alpha = 0.6f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

// ── 图表 ────────────────────────────────────────────────────────────────

/** 粉丝数折线：面积填充 + 折线 + 末点高亮 */
@Composable
private fun FansChart(points: List<FansPoint>, modifier: Modifier = Modifier) {
    val accent = LiveAccent
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
    val values = remember(points) { points.map { it.value } }
    Canvas(modifier) {
        val top = 10.dp.toPx()
        val bottom = 8.dp.toPx()
        val w = size.width
        val h = size.height - top - bottom
        if (w <= 0f || h <= 0f || values.size < 2) return@Canvas

        val minV = values.min()
        val maxV = values.max()
        val span = (maxV - minV).coerceAtLeast(1)

        repeat(3) { i ->
            val y = top + h * i / 2f
            drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }

        fun x(i: Int) = w * i / (values.size - 1).toFloat()
        fun y(v: Int) = top + h * (1f - (v - minV).toFloat() / span)

        val line = Path().apply {
            moveTo(x(0), y(values[0]))
            for (i in 1 until values.size) lineTo(x(i), y(values[i]))
        }
        val area = Path().apply {
            addPath(line)
            lineTo(x(values.size - 1), top + h)
            lineTo(x(0), top + h)
            close()
        }
        drawPath(
            area,
            Brush.verticalGradient(
                listOf(accent.copy(alpha = 0.30f), accent.copy(alpha = 0.02f)),
                startY = top,
                endY = top + h
            )
        )
        drawPath(
            line,
            accent,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        drawCircle(accent, radius = 3.5f.dp.toPx(), center = Offset(x(values.size - 1), y(values.last())))
    }
}

/** 通用柱状图（[rounded] 为 true 时按单条进度条画） */
@Composable
private fun BarsChart(
    values: List<Float>,
    modifier: Modifier = Modifier,
    rounded: Boolean = false
) {
    val accent = if (rounded) okColor() else LiveAccent
    val cs = MaterialTheme.colorScheme
    Canvas(modifier) {
        if (values.isEmpty()) return@Canvas
        val maxV = values.max().coerceAtLeast(1f)
        if (rounded) {
            drawRoundRect(
                color = cs.surfaceVariant,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f)
            )
            val frac = (values.first() / 100f).coerceIn(0f, 1f)
            if (frac > 0f) {
                drawRoundRect(
                    color = accent,
                    size = Size(size.width * frac, size.height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f)
                )
            }
            return@Canvas
        }
        val n = values.size
        val gap = if (n > 20) 1f else size.width / n * 0.28f
        val barW = (size.width - gap * (n - 1)) / n
        values.forEachIndexed { i, v ->
            val bh = (size.height * (v / maxV)).coerceAtLeast(if (v > 0f) 2f else 0f)
            drawRoundRect(
                color = if (v > 0f) accent.copy(alpha = 0.85f) else cs.outline.copy(alpha = 0.15f),
                topLeft = Offset(i * (barW + gap), size.height - bh),
                size = Size(barW.coerceAtLeast(1f), bh),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW.coerceAtMost(4f) / 2f)
            )
        }
    }
}

// ── 通用小组件 ──────────────────────────────────────────────────────────

@Composable
private fun SectionCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(cardSurface(alphaInLight = 0.55f))
            .padding(14.dp),
        content = content
    )
}

@Composable
private fun StatCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    /** 统计口径（截至哪天 / 是否生涯累计），没有就不占位 */
    caption: String? = null
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(cs.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = cs.outline, maxLines = 1)
        if (caption != null) {
            Text(
                caption,
                fontSize = 10.sp,
                lineHeight = 13.sp,
                color = cs.outline.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun PickerChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) cs.onPrimary else cs.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) cs.primary else cs.surfaceVariant.copy(alpha = 0.6f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    )
}

@Composable
private fun CenterBox(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun UidDialog(onDismiss: () -> Unit, onConfirm: (Long) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("输入 B 站 UID") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { v -> text = v.filter { it.isDigit() }.take(12) },
                    label = { Text("数字 UID，例如 2132180406") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "在主播主页地址 space.bilibili.com/ 后面那串数字就是 UID。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = (text.toLongOrNull() ?: 0L) > 0L,
                onClick = { text.toLongOrNull()?.let(onConfirm) }
            ) { Text("查看") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// ── 格式化 ──────────────────────────────────────────────────────────────

private fun fmtCount(n: Long): String = when {
    n >= 100_000_000L -> String.format(Locale.US, "%.1f亿", n / 100_000_000.0)
    n >= 10_000L -> String.format(Locale.US, "%.1f万", n / 10_000.0)
    else -> n.toString()
}

private fun fmtMoney(v: Double): String = when {
    v >= 10_000 -> String.format(Locale.US, "%.1f万元", v / 10_000.0)
    v >= 1 -> String.format(Locale.US, "%.0f元", v)
    else -> "0元"
}

private fun fmtDuration(seconds: Long): String {
    val h = seconds / 3600
    return when {
        // 原先 4721 小时会显示成「5 千时」：单位不明，还把精度抹掉了。万位以内直接给小时数。
        h >= 10_000 -> String.format(Locale.US, "%.1f 万时", h / 10_000.0)
        h >= 1 -> "$h 时"
        else -> "${seconds / 60} 分"
    }
}

private fun fmtDate(ms: Long): String {
    if (ms <= 0L) return "时间未知"
    val real = if (ms < 100_000_000_000L) ms * 1000 else ms
    return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(real))
}

private fun pct(v: Double): String = String.format(Locale.US, "%.0f%%", v * 100)

/**
 * 事件类型的中文名。
 *
 * 服务端实际返回的 key 是枚举序号（0/1/2/3/4…），接口文档里写的枚举名反倒没出现，
 * 所以数字和名称两种形态都要认——只认其中一种就会在界面上显示成 "0" "4"。
 */
private fun eventLabel(name: String): String = when (name) {
    "0", "Message" -> "弹幕"
    "1", "Gift" -> "礼物"
    "2", "Guard" -> "上舰"
    "3", "SC" -> "醒目留言"
    "4", "Enter" -> "进场"
    "5", "Title" -> "标题变更"
    "6", "Area" -> "分区变更"
    "7", "Pause_Stop" -> "直播中止"
    "8", "Pause_Continue" -> "直播恢复"
    "9", "Block" -> "被禁言"
    "10", "Warning" -> "被警告"
    "11", "Record_Start" -> "开始录制"
    "12", "Record_Interrupt" -> "录制中断"
    else -> name
}
