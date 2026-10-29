package com.example.livenotify.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.livenotify.R
import com.example.livenotify.data.EasterEggs
import com.example.livenotify.data.StreamerEntity
import com.example.livenotify.monitor.NotificationHelper
import com.example.livenotify.platform.PlatformRegistry
import com.example.livenotify.util.ShareText
import com.example.livenotify.util.Format
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 各平台官方图标（圆形徽标：iTunes 512px App 图标 / 官方 favicon 生成） */
@DrawableRes
fun platformIconRes(platformId: String): Int? = when (platformId) {
    "bilibili" -> R.drawable.ic_platform_bilibili
    "douyin" -> R.drawable.ic_platform_douyin
    "douyu" -> R.drawable.ic_platform_douyu
    "huya" -> R.drawable.ic_platform_huya
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun MainScreen(vm: MainViewModel, onOpenSettings: () -> Unit) {
    val list by vm.streamers.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val lastCheck by vm.lastCheckedAt.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val egg by vm.egg.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    var showAdd by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<StreamerEntity?>(null) }
    var actionFor by remember { mutableStateOf<StreamerEntity?>(null) }

    // 相对时间自刷新，避免「已播 5 分钟」卡住不动
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(20_000)
            now = System.currentTimeMillis()
        }
    }

    LaunchedEffect(toast) {
        toast?.let {
            snackbar.showSnackbar(it)
            vm.consumeToast()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("添加关注") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { vm.checkNow() },
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 96.dp)
            ) {
                item {
                    HeroHeader(
                        total = list.size,
                        liveCount = list.count { it.isLive },
                        monitoring = settings.monitorEnabled,
                        refreshing = refreshing,
                        onRefresh = { vm.checkNow() },
                        onOpenSettings = onOpenSettings
                    )
                }

                item {
                    MonitorBar(
                        enabled = settings.monitorEnabled,
                        intervalSec = settings.pollIntervalSec,
                        lastCheck = lastCheck,
                        onToggle = vm::setMonitorEnabled
                    )
                }

                if (!NotificationHelper.canNotify(context)) {
                    item {
                        NoticeCard(
                            text = "通知权限未开启，开播提醒送不到你手机上。",
                            action = "去开启",
                            onAction = { NotificationHelper.openNotificationSettings(context) }
                        )
                    }
                }

                if (list.isEmpty()) {
                    item { EmptyState() }
                } else {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "关注的主播",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.outline
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                "长按可管理",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
                            )
                        }
                    }
                    items(list, key = { it.id }) { s ->
                        StreamerCard(
                            s = s,
                            now = now,
                            onClick = { vm.openRoom(s) },
                            onLongClick = { actionFor = s }
                        )
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddStreamerDialog(onDismiss = { showAdd = false }, vm = vm)
    }

    // 关注问候：命中配置的主播时弹一句专属台词
    egg?.let { e ->
        EasterEggDialog(e = e, onDismiss = { vm.consumeEgg() })
    }

    // 长按操作面板
    actionFor?.let { s ->
        StreamerActionDialog(
            s = s,
            onOpen = { vm.openRoom(s); actionFor = null },
            onCopy = {
                val cm = context.getSystemService(ClipboardManager::class.java)
                cm?.setPrimaryClip(ClipData.newPlainText("直播间", s.roomUrl))
                actionFor = null
                vm.notify("已复制直播间链接")
            },
            onRemove = { actionFor = null; pendingDelete = s },
            onDismiss = { actionFor = null }
        )
    }

    pendingDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("移除关注") },
            text = { Text("确定不再关注「${s.nickname}」吗？") },
            confirmButton = {
                TextButton(onClick = { vm.remove(s); pendingDelete = null }) { Text("移除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } }
        )
    }
}

/** 顶部渐变区：标题 + 概览 + 刷新/设置 */
@Composable
private fun HeroHeader(
    total: Int,
    liveCount: Int,
    monitoring: Boolean,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(cs.primaryContainer.copy(alpha = 0.85f), cs.background)
                )
            )
            .padding(start = 20.dp, end = 6.dp, top = 14.dp, bottom = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "开播提醒",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = cs.onPrimaryContainer
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(color = if (monitoring) okColor() else cs.outline, size = 7)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        buildString {
                            append(if (monitoring) "监控中" else "已暂停")
                            append(" · ")
                            if (total == 0) append("暂无关注")
                            else if (liveCount > 0) append("$liveCount / $total 位正在直播")
                            else append("$total 位都很安静")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onPrimaryContainer.copy(alpha = 0.72f)
                    )
                }
            }
            IconButton(onClick = onRefresh, enabled = !refreshing) {
                if (refreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = cs.onPrimaryContainer
                    )
                } else {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "立即检测",
                        tint = cs.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                }
            }
            IconButton(onClick = onOpenSettings) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "设置",
                    tint = cs.onPrimaryContainer.copy(alpha = 0.8f)
                )
            }
        }
    }
}

/** 单行监控条 */
@Composable
private fun MonitorBar(
    enabled: Boolean,
    intervalSec: Int,
    lastCheck: Long,
    onToggle: (Boolean) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surfaceVariant.copy(alpha = 0.55f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (enabled) "后台检测已开启" else "后台检测已关闭",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "每 ${intervalSec.coerceAtLeast(1)} 秒 · 上次 ${Format.relative(lastCheck)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.outline
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = cs.primary
                )
            )
        }
    }
}

@Composable
private fun NoticeCard(text: String, action: String, onAction: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.09f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            TextButton(onClick = onAction) {
                Text(action, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun EmptyState() {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 56.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(84.dp)
                .clip(CircleShape)
                .background(cs.primaryContainer.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Notifications,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = cs.primary.copy(alpha = 0.85f)
            )
        }
        Spacer(Modifier.height(18.dp))
        Text("还没有关注的主播", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            "点右下角「添加关注」，粘贴 B站 / 抖音 / 斗鱼 / 虎牙\n的直播间链接或房间号就行",
            style = MaterialTheme.typography.bodySmall,
            color = cs.outline,
            textAlign = TextAlign.Center,
            lineHeight = 19.sp
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StreamerCard(
    s: StreamerEntity,
    now: Long,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val live = s.isLive && !s.missing
    val shape = RoundedCornerShape(18.dp)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .clip(shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = if (live) LiveAccent.copy(alpha = 0.055f) else cs.surfaceVariant.copy(alpha = 0.4f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = if (live) androidx.compose.foundation.BorderStroke(1.dp, LiveAccent.copy(alpha = 0.22f)) else null
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AvatarWithBadge(s = s, live = live)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        s.nickname,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    StatusPill(s)
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    buildString {
                        append(PlatformRegistry.get(s.platformId)?.displayName ?: s.platformId)
                        append(" · ")
                        append(s.displayId)
                        if (s.missing) append(" · 已失效")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (s.title.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        s.title,
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurface.copy(alpha = 0.86f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(6.dp))
                TimeLine(s = s, now = now)
            }
        }
    }
}

/** 时间信息行：开播中显示已播时长；未开播显示距上次开播多久 + 具体时刻 */
@Composable
private fun TimeLine(s: StreamerEntity, now: Long) {
    val cs = MaterialTheme.colorScheme
    when {
        s.missing -> Text(
            "⚠ ${s.lastError ?: "主播或直播间不存在"}",
            style = MaterialTheme.typography.labelSmall,
            color = cs.error
        )

        s.errorCount >= 3 -> Text(
            "⚠ ${s.lastError ?: "检测异常"}（连续 ${s.errorCount} 次）",
            style = MaterialTheme.typography.labelSmall,
            color = cs.error
        )

        s.isLive -> {
            val playedMs = if (s.liveStartAt > 0) now - s.liveStartAt * 1000L else 0L
            val showSpan = playedMs in 0..(20L * 3_600_000L)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (showSpan) "已播 " + Format.span(playedMs) else "直播中",
                    style = MaterialTheme.typography.labelSmall,
                    color = LiveAccent,
                    fontWeight = FontWeight.Medium
                )
                if (s.liveStartAt > 0) {
                    Text(
                        "  ·  ${Format.clock(s.liveStartAt * 1000L)} 开播",
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.outline
                    )
                }
            }
        }

        s.lastLiveStartAt > 0 -> {
            val rel = Format.sinceSec(s.lastLiveStartAt)
            val abs = Format.dateTime(s.lastLiveStartAt * 1000L)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "上次开播 $rel",
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.outline
                )
                if (rel != abs) {
                    Text(
                        "  ·  $abs",
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.outline.copy(alpha = 0.62f)
                    )
                }
            }
        }

        else -> Text(
            "暂无开播记录",
            style = MaterialTheme.typography.labelSmall,
            color = cs.outline
        )
    }
}

/** 头像 + 平台官方 logo 徽标 + 开播呼吸光环 */
@Composable
private fun AvatarWithBadge(s: StreamerEntity, live: Boolean) {
    val cs = MaterialTheme.colorScheme
    val pulse = if (live) {
        val transition = rememberInfiniteTransition(label = "pulse")
        val a by transition.animateFloat(
            initialValue = 0.25f,
            targetValue = 0.75f,
            animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
            label = "alpha"
        )
        a
    } else 0f

    Box {
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(cs.surface)
                .then(
                    if (live) Modifier.border(2.dp, LiveAccent.copy(alpha = pulse), CircleShape)
                    else Modifier.border(1.dp, cs.outlineVariant, CircleShape)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (!s.avatarUrl.isNullOrBlank()) {
                AsyncImage(
                    model = s.avatarUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                )
            } else {
                Text(
                    s.nickname.take(1),
                    style = MaterialTheme.typography.titleMedium,
                    color = cs.outline
                )
            }
        }
        // 平台官方 logo 徽标
        val iconRes = platformIconRes(s.platformId)
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(20.dp)
                .clip(CircleShape)
                .background(cs.background)
                .border(2.dp, cs.background, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (iconRes != null) {
                Image(
                    painter = painterResource(iconRes),
                    contentDescription = PlatformRegistry.get(s.platformId)?.displayName,
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(platformAccent(s.platformId))
                )
            }
        }
    }
}

@Composable
private fun StatusPill(s: StreamerEntity) {
    val cs = MaterialTheme.colorScheme
    val (color, text) = when {
        s.missing -> cs.outline to "失效"
        s.isLive -> LiveAccent to "直播中"
        s.errorCount >= 3 -> cs.error to "异常"
        else -> cs.outline to "未开播"
    }
    Surface(color = color.copy(alpha = 0.12f), shape = RoundedCornerShape(50)) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Dot(color = color, size = 5)
            Spacer(Modifier.width(4.dp))
            Text(text, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun Dot(color: Color, size: Int) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(color)
    )
}

/** 长按卡片后的操作面板 */
@Composable
private fun StreamerActionDialog(
    s: StreamerEntity,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val platform = PlatformRegistry.get(s.platformId)?.displayName ?: s.platformId
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.nickname, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "$platform · ${s.displayId}",
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.outline
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
                    Text("打开直播间", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
                }
                TextButton(onClick = onCopy, modifier = Modifier.fillMaxWidth()) {
                    Text("复制直播间链接", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
                }
                TextButton(onClick = onRemove, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "移除关注",
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Start,
                        color = cs.error
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddStreamerDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var forced by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加关注") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = null },
                    label = { Text("链接 / 直播间号 / 分享文案") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("可直接粘贴App分享出来的整段文案") }
                )
                // 实时识别预览：粘贴后立刻知道会被解析成哪个平台，粘错马上能看到
                val detectedName = remember(text) {
                    val t = text.trim()
                    if (t.isBlank()) null
                    else PlatformRegistry.detect(ShareText.extract(t))?.displayName
                }
                if (detectedName != null) {
                    Text(
                        "已识别：$detectedName",
                        style = MaterialTheme.typography.labelSmall,
                        color = okColor()
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // 「自动」单独一行：最常用，放最上面
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PlatformChip("自动", forced == null) { forced = null }
                    }
                    // 手动指定平台换行显示在「自动」下面
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PlatformChip("B站", forced == "bilibili") { forced = "bilibili" }
                        PlatformChip("抖音", forced == "douyin") { forced = "douyin" }
                        PlatformChip("斗鱼", forced == "douyu") { forced = "douyu" }
                        PlatformChip("虎牙", forced == "huya") { forced = "huya" }
                    }
                }
                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("正在获取主播信息…", style = MaterialTheme.typography.labelSmall)
                    }
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank() && !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        error = null
                        when (val r = vm.add(text.trim(), forced)) {
                            is MainViewModel.AddResult.Success -> onDismiss()
                            is MainViewModel.AddResult.Error -> error = r.message
                        }
                        busy = false
                    }
                }
            ) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun PlatformChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelMedium) }
    )
}

/** 关注问候弹窗：一句台词 + 落款 */
@Composable
private fun EasterEggDialog(e: EasterEggs.Egg, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(cs.secondaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text("✦", style = MaterialTheme.typography.labelMedium, color = cs.onSecondaryContainer)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "专属问候",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "「${e.text}」",
                    style = MaterialTheme.typography.bodyLarge,
                    lineHeight = 27.sp,
                    textAlign = TextAlign.Center,
                    color = cs.onSurface
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "— ${e.nickname}",
                    style = MaterialTheme.typography.labelMedium,
                    color = cs.outline
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("收下") }
        }
    )
}
