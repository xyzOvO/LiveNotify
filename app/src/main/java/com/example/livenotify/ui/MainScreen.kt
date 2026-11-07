package com.example.livenotify.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.livenotify.R
import com.example.livenotify.data.EasterEggs
import com.example.livenotify.data.StreamerEntity
import com.example.livenotify.monitor.MonitorEngine
import com.example.livenotify.monitor.NotificationHelper
import com.example.livenotify.platform.PlatformRegistry
import com.example.livenotify.update.ReleaseNotes
import com.example.livenotify.update.UpdateApi
import com.example.livenotify.util.ShareText
import com.example.livenotify.util.Format
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
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
fun MainScreen(
    vm: MainViewModel,
    onOpenSettings: () -> Unit,
    /** 通知权限授权结果的自增计数：授权是一次性回调，用它驱动重新读取权限状态 */
    permissionRevision: Int = 0,
    /** 打开 B 站数据页（传入该主播的 uid） */
    onOpenStats: (Long) -> Unit = {},
    /** 系统分享带进来的文案；只用于预填添加框，消费后由调用方置空 */
    sharedText: String? = null,
    onConsumeSharedText: () -> Unit = {}
) {
    val list by vm.streamers.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val lastCheck by vm.lastCheckedAt.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val egg by vm.egg.collectAsStateWithLifecycle()
    val updateState by vm.update.collectAsStateWithLifecycle()
    // 界面显示的是「服务真的在跑」，不是「设置里开着」
    val monitorRunning by vm.monitorRunning.collectAsStateWithLifecycle()
    // 实时网络快照：断网 / 「仅 Wi-Fi」下走流量时，主页状态行要立刻改口，
    // 不能等下一轮轮询（那中间的几分钟界面等于在撒谎）
    val net = rememberNetSnapshot()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 通知权限状态必须在两个时机重读：① 从系统通知设置页返回；② 运行时权限弹窗点了允许。
    // Compose 不会因 Activity onResume 自动重组，不刷新的话提示条会一直挂着。
    var canNotify by remember { mutableStateOf(NotificationHelper.canNotify(context)) }
    LaunchedEffect(permissionRevision) {
        canNotify = NotificationHelper.canNotify(context)
    }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                canNotify = NotificationHelper.canNotify(context)
                // 服务被系统/ROM 回收后回到前台，顺手补一次启动
                vm.ensureMonitorRunning()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ── 搜索与筛选：纯 UI 派生，保持 vm.streamers 是完整列表（后台检测与常驻通知都靠它）
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var platformFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var liveOnly by rememberSaveable { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val searchFocus = remember { FocusRequester() }
    val visible = remember(list, query, platformFilter, liveOnly) {
        list.filter { s ->
            matchesSearchQuery(query, s.nickname, s.displayId, s.anchorId, s.note) &&
                (platformFilter == null || s.platformId == platformFilter) &&
                (!liveOnly || (s.enabled && s.isLive && !s.missing))
        }
    }
    val hasFilter = query.isNotBlank() || platformFilter != null || liveOnly

    // 关闭搜索 = 结束这一轮筛选：条件和面板一起清掉，避免「关了搜索但列表还是筛过的」
    val closeSearch: () -> Unit = {
        query = ""
        platformFilter = null
        liveOnly = false
        searchOpen = false
        keyboard?.hide()
    }
    // 展开后自动聚焦并弹键盘；先等一帧，确保输入框已经进入组合
    LaunchedEffect(searchOpen) {
        if (searchOpen) {
            withFrameNanos { }
            runCatching { searchFocus.requestFocus() }
            keyboard?.show()
        }
    }
    // 搜索展开时返回键只收搜索，别直接退出 App
    BackHandler(enabled = searchOpen) { closeSearch() }

    val listState = rememberLazyListState()
    // 一滚列表就收键盘：搜索时键盘占掉半屏，翻结果时它只会挡路
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .filter { it }
            .collect { keyboard?.hide() }
    }

    var showAdd by remember { mutableStateOf(false) }
    /** 从系统分享带进来的文案，用于预填添加框；null 表示手工点开的空白框 */
    var addInitialText by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<StreamerEntity?>(null) }
    var actionFor by remember { mutableStateOf<StreamerEntity?>(null) }
    /** 恢复关注要联网取一次快照，期间锁住按钮避免重复点击 */
    var enabledBusy by remember { mutableStateOf(false) }

    // 分享文案只在这里预填，绝不自动落库：仍然由用户看到识别结果后点「添加」
    LaunchedEffect(sharedText) {
        val incoming = sharedText
        if (!incoming.isNullOrBlank()) {
            addInitialText = incoming
            showAdd = true
            onConsumeSharedText()
        }
    }

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

    // 启动时静默检查一次新版本（设置里可关）；有更新才弹窗
    LaunchedEffect(Unit) { vm.autoCheckUpdate() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            // 搜索/筛选时用不到添加，收起来免得键盘弹出后压住结果
            if (!searchOpen) {
                ExtendedFloatingActionButton(
                    onClick = { addInitialText = null; showAdd = true },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("添加关注") },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            }
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
                state = listState,
                modifier = Modifier.fillMaxSize(),
                // 搜索时不显示 FAB，底部留白也收掉，别让结果区凭空空一截
                contentPadding = PaddingValues(bottom = if (searchOpen) 24.dp else 96.dp)
            ) {
                item {
                    HeroHeader(
                        total = list.count { it.enabled },
                        liveCount = list.count { it.enabled && it.isLive },
                        pausedCount = list.count { !it.enabled },
                        monitoring = settings.monitorEnabled && monitorRunning,
                        skip = MonitorEngine.skipReason(settings.wifiOnly, net),
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
                        // 当前处于失败状态的主播数：让「一直在重试」这件事看得见
                        failedCount = list.count { it.enabled && it.errorCount > 0 },
                        onToggle = vm::setMonitorEnabled
                    )
                }

                if (!canNotify) {
                    item {
                        NoticeCard(
                            text = "通知权限未开启，开播提醒送不到你手机上。",
                            action = "去开启",
                            onAction = { NotificationHelper.openNotificationSettings(context) }
                        )
                    }
                }

                // 设置里开着、服务却没跑：以前这种情况界面仍显示「监控中」，属于静默失败
                if (settings.monitorEnabled && !monitorRunning) {
                    item {
                        NoticeCard(
                            text = "后台检测没有在运行，可能收不到开播提醒（部分系统会拦截后台启动）。",
                            action = "重启",
                            onAction = { vm.setMonitorEnabled(true) }
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
                                .padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "关注的主播",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.outline
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                if (searchOpen && hasFilter) "${visible.size} / ${list.size} 位"
                                else "点击或长按管理",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
                            )
                            if (!searchOpen) {
                                IconButton(onClick = { searchOpen = true }) {
                                    Icon(
                                        Icons.Default.Search,
                                        contentDescription = "搜索关注列表",
                                        tint = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                        }
                    }
                    if (searchOpen) {
                        // 吸顶：往下翻结果时搜索框和「取消」始终在，不用滚回顶部才能退出
                        stickyHeader {
                            Surface(color = MaterialTheme.colorScheme.background) {
                                Column(Modifier.padding(start = 16.dp, end = 8.dp, bottom = 4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(
                                        value = query,
                                        onValueChange = { query = it },
                                        placeholder = { Text("搜索昵称 / 房间号 / 短号") },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.Search,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.outline,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        },
                                        trailingIcon = {
                                            IconButton(
                                                onClick = {
                                                    if (query.isNotEmpty()) query = "" else closeSearch()
                                                }
                                            ) {
                                                Icon(
                                                    Icons.Default.Close,
                                                    contentDescription = if (query.isNotEmpty()) "清空输入" else "关闭搜索",
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        },
                                        singleLine = true,
                                        shape = RoundedCornerShape(12.dp),
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                                        modifier = Modifier
                                            .weight(1f)
                                            .focusRequester(searchFocus)
                                    )
                                    TextButton(onClick = closeSearch) { Text("取消") }
                                }
                                // 平台多、屏窄，chips 单行横向滚动，别让筛选区吃掉半屏
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    FilterChip(
                                        selected = platformFilter == null,
                                        onClick = { platformFilter = null },
                                        label = { Text("全部平台") }
                                    )
                                    PlatformRegistry.all().forEach { p ->
                                        FilterChip(
                                            selected = platformFilter == p.id,
                                            onClick = {
                                                platformFilter = if (platformFilter == p.id) null else p.id
                                            },
                                            label = { Text(p.displayName) }
                                        )
                                    }
                                    FilterChip(
                                        selected = liveOnly,
                                        onClick = { liveOnly = !liveOnly },
                                        label = { Text("只看直播中") }
                                    )
                                }
                            }
                            }
                        }
                    }
                    if (visible.isEmpty()) {
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 40.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    "没有匹配的主播",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.outline
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "换个关键词，或放宽筛选条件",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
                                )
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(onClick = closeSearch) { Text("清除筛选") }
                                    // 搜不到也可能是根本没关注：给一个直接去添加的口子
                                    TextButton(
                                        onClick = {
                                            closeSearch()
                                            addInitialText = null
                                            showAdd = true
                                        }
                                    ) { Text("添加关注") }
                                }
                            }
                        }
                    } else {
                        items(visible, key = { it.id }) { s ->
                            StreamerCard(
                                s = s,
                                now = now,
                                // 卡片本身不再直接跳直播间：手指一滑就弹浏览器，回来还得重新找。
                                // 点击与长按都只开操作面板，真正跳转只留面板里的「打开直播间」。
                                onClick = { actionFor = s },
                                onLongClick = { actionFor = s }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddStreamerDialog(
            vm = vm,
            initialText = addInitialText,
            onDismiss = { showAdd = false; addInitialText = null }
        )
    }

    // 关注问候：命中配置的主播时弹一句专属台词
    egg?.let { e ->
        EasterEggDialog(e = e, onDismiss = { vm.consumeEgg() })
    }

    // 新版本提示：下载 → 进度 → 失败留在弹窗里可重试。
    // 弹窗的开关由本地状态控制而不是只看 UpdateState：失败后状态是 Failed，
    // 若仍按 Available 渲染，弹窗会在下载失败的一瞬间消失，用户什么提示都看不到。
    var updateDialogOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(updateState) {
        when (updateState) {
            is MainViewModel.UpdateState.Available -> updateDialogOpen = true
            is MainViewModel.UpdateState.Idle,
            is MainViewModel.UpdateState.UpToDate -> updateDialogOpen = false
            else -> Unit
        }
    }
    if (updateDialogOpen) {
        when (val st = updateState) {
            is MainViewModel.UpdateState.Available -> UpdateDialog(
                state = st,
                onDownload = { vm.downloadUpdate(st.info) },
                onDismiss = {
                    updateDialogOpen = false
                    vm.dismissUpdate()
                }
            )
            is MainViewModel.UpdateState.Failed -> UpdateFailedDialog(
                state = st,
                onRetry = { vm.retryUpdate() },
                onOpenReleases = { openLink(context, UpdateApi.RELEASES_PAGE) },
                onOpenBackup = {
                    updateDialogOpen = false
                    vm.dismissUpdate()
                    onOpenSettings()
                },
                onDismiss = {
                    updateDialogOpen = false
                    vm.dismissUpdate()
                }
            )
            else -> Unit
        }
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
            onStats = if (s.platformId == "bilibili") {
                {
                    actionFor = null
                    onOpenStats(s.anchorId.toLongOrNull() ?: 0L)
                }
            } else null,
            enabledBusy = enabledBusy,
            onToggleEnabled = {
                scope.launch {
                    enabledBusy = true
                    val err = vm.setStreamerEnabled(s, !s.enabled)
                    enabledBusy = false
                    actionFor = null
                    vm.notify(
                        err ?: if (s.enabled) "已暂停「${s.nickname}」，不再检测与提醒"
                        else "已恢复「${s.nickname}」"
                    )
                }
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
                TextButton(
                    onClick = {
                        vm.remove(s)
                        pendingDelete = null
                        // 误删成本不低（要重新解析添加），给一次后悔机会
                        scope.launch {
                            val r = snackbar.showSnackbar(
                                message = "已移除「${s.nickname}」",
                                actionLabel = "撤销",
                                duration = SnackbarDuration.Short
                            )
                            if (r == SnackbarResult.ActionPerformed) vm.restore(s)
                        }
                    }
                ) { Text("移除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } }
        )
    }
}

/**
 * 平台附注里可搜索的号码。
 *
 * B 站短号（如「短号 510」）既不在 displayId（真实房间号）也不在 anchorId（uid）里，
 * 只存在 note 中；而 note 同时装着「轮播中」、斗鱼分区名等说明，照单全搜会让
 * 「影之刃零」这类词把整组主播翻出来。所以只认「短号 <纯数字>」这一段。
 */
internal fun shortIdFromNote(note: String): String? =
    note.trim()
        .takeIf { it.startsWith("短号 ") }
        ?.removePrefix("短号 ")
        ?.trim()
        ?.takeIf { it.isNotBlank() && it.all { c -> c.isDigit() } }

/** 搜索命中：昵称 / 房间号 / 平台内标识 / B 站短号，任一子串命中即可。 */
internal fun matchesSearchQuery(
    query: String,
    nickname: String,
    displayId: String,
    anchorId: String,
    note: String
): Boolean {
    val q = query.trim()
    if (q.isBlank()) return true
    return nickname.contains(q, ignoreCase = true) ||
        displayId.contains(q, ignoreCase = true) ||
        anchorId.contains(q, ignoreCase = true) ||
        (shortIdFromNote(note)?.contains(q, ignoreCase = true) == true)
}

/**
 * 主页顶部的一句状态。
 *
 * 跳过检测时（没网 / 「仅 Wi-Fi」下不是 Wi-Fi）这里原来仍写「N 位都很安静」，
 * 用户会以为已经检测过、只是没人开播——实际一次请求都没发。
 * 所以跳过原因存在时改口「等待网络 / 等待 Wi-Fi」，且绝不说「安静」。
 */
internal fun homeStatusLine(
    monitoring: Boolean,
    total: Int,
    liveCount: Int,
    pausedCount: Int,
    skip: MonitorEngine.SkipReason?
): String = buildString {
    append(if (monitoring) "监控中" else "已暂停")
    append(" · ")
    when {
        // 服务没跑时「等待网络」没有意义，「已暂停」本身就是原因
        monitoring && skip == MonitorEngine.SkipReason.WIFI_ONLY ->
            append("等待 Wi-Fi · $total 位待检测")
        monitoring && skip == MonitorEngine.SkipReason.NO_NETWORK ->
            append("等待网络 · $total 位待检测")
        total == 0 && pausedCount == 0 -> append("暂无关注")
        total == 0 -> append("全部已暂停")
        liveCount > 0 -> append("$liveCount / $total 位正在直播")
        else -> append("$total 位都很安静")
    }
    // 「全部已暂停」已经说明了一切，别再拼一遍「N 位已暂停」
    if (pausedCount > 0 && total > 0) append(" · $pausedCount 位已暂停")
}

/** 顶部渐变区：标题 + 概览 + 刷新/设置 */
@Composable
private fun HeroHeader(
    total: Int,
    liveCount: Int,
    /** 已暂停的关注数，单独提示，避免和「正在监控」混在一起 */
    pausedCount: Int = 0,
    monitoring: Boolean,
    /** 最近一轮被跳过的原因；非空说明检测没真正跑，状态行要改口 */
    skip: MonitorEngine.SkipReason? = null,
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
                    // 跳过时检测实际没在跑，绿点会误导；用灰点 + 文字说明
                    Dot(
                        color = if (monitoring && skip == null) okColor() else cs.outline,
                        size = 7
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        homeStatusLine(monitoring, total, liveCount, pausedCount, skip),
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
    failedCount: Int,
    onToggle: (Boolean) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = cardSurface(0.55f)),
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
                    // 「基础间隔」而不是「每 N 秒」：移动网络会按倍数拉长、免打扰时段再放大 5 倍
                    "基础间隔 ${intervalSec.coerceAtLeast(1)} 秒 · 上次 ${Format.relative(lastCheck)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.outline
                )
                if (enabled && failedCount > 0) {
                    Spacer(Modifier.height(3.dp))
                    // 说清楚「还在重试」和「不会提醒」的区别，别让用户以为只是网慢
                    Text(
                        "$failedCount 位主播信息获取失败，正在自动重试；连续失败会标为「失效」",
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.error.copy(alpha = 0.85f)
                    )
                }
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
    // 暂停的卡片一律不显示「直播中」高亮，避免看起来还在被监控
    val live = s.enabled && s.isLive && !s.missing
    val shape = RoundedCornerShape(18.dp)
    val haptic = LocalHapticFeedback.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .clip(shape)
            .alpha(if (s.enabled) 1f else 0.62f)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    // 长按要等 500ms 才弹面板，中间没有触觉反馈会让人以为没按到
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                }
            ),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = if (live) LiveAccent.copy(alpha = 0.055f) else cardSurface(0.4f)
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
                        // 平台附注（B 站「轮播中」、斗鱼分区名）：不显示的话，
                        // 轮播直播间会被当成「未开播」，用户看不出为什么
                        if (s.note.isNotBlank()) {
                            append(" · ")
                            append(s.note)
                        }
                        if (s.missing) append(" · 已失效")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (s.note.isNotBlank() && !s.isLive) cs.onSurface.copy(alpha = 0.7f) else cs.outline,
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
    Column {
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

        // 失败 1~2 次时状态仍是「未开播」，不补一句用户会以为一切正常（3 次以上上面已标红）
        if (!s.missing && s.errorCount in 1..2) {
            Spacer(Modifier.height(2.dp))
            Text(
                "上次检测失败 ${s.errorCount} 次 · ${s.lastError ?: "网络异常"}",
                style = MaterialTheme.typography.labelSmall,
                color = cs.error.copy(alpha = 0.7f)
            )
        }
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
            AvatarImage(url = s.avatarUrl, name = s.nickname, size = 48.dp)
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
        !s.enabled -> cs.outline to "已暂停"
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
    /** 仅 B 站主播有数据可看，其余平台传 null 时不显示入口 */
    onStats: (() -> Unit)?,
    enabledBusy: Boolean,
    onToggleEnabled: () -> Unit,
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
                if (onStats != null) {
                    TextButton(onClick = onStats, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "粉丝走势与数据",
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Start
                        )
                    }
                }
                TextButton(
                    onClick = onToggleEnabled,
                    enabled = !enabledBusy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (enabledBusy) "正在处理…"
                        else if (s.enabled) "暂停关注" else "恢复关注",
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Start,
                        color = if (s.enabled) cs.onSurface else okColor()
                    )
                }
                if (!s.enabled) {
                    Text(
                        "已暂停：保留卡片，但不再检测、不再提醒",
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.outline
                    )
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
private fun AddStreamerDialog(
    vm: MainViewModel,
    onDismiss: () -> Unit,
    /** 非空表示来自系统分享：预填文案并显示提取结果，仍要用户确认才添加 */
    initialText: String? = null
) {
    var text by remember { mutableStateOf(initialText.orEmpty()) }
    var forced by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    /** 非空 = 已识别出主播，切到确认界面；此时还没有写库 */
    var ready by remember { mutableStateOf<MainViewModel.AddResult.Ready?>(null) }
    val scope = rememberCoroutineScope()
    // 对话框已经开着时又收到一次分享：换成新文案并退回输入步骤
    LaunchedEffect(initialText) {
        if (!initialText.isNullOrBlank()) {
            text = initialText
            ready = null
            error = null
        }
    }

    val pending = ready
    AlertDialog(
        // 请求进行中点外部/返回键会让协程被取消、添加静默中断，busy 时直接忽略
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (pending == null) "添加关注" else "确认添加") },
        text = {
            if (pending == null) {
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
                    val extracted = remember(text) {
                        val t = text.trim()
                        if (t.isBlank()) "" else ShareText.extract(t)
                    }
                    val detectedName = remember(extracted) {
                        if (extracted.isBlank()) null
                        else PlatformRegistry.detect(extracted)?.displayName
                    }
                    if (initialText != null) {
                        Text(
                            "来自分享，已自动提取；确认无误再点「识别主播」",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (detectedName != null) {
                        Text(
                            "已识别：$detectedName" +
                                if (extracted != text.trim()) " · $extracted" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = okColor(),
                            maxLines = 2
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        // 「自动」单独一行：最常用，放最上面
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            PlatformChip("自动", forced == null) { forced = null }
                        }
                        // 手动指定平台换行显示在「自动」下面。用 FlowRow 而不是 Row：
                        // 窄屏（≤360dp）四个 chip 一行放不下，Row 会把尾部直接裁掉。
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
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
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ReadyBody(pending)
                    error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            if (pending == null) {
                TextButton(
                    enabled = text.isNotBlank() && !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            error = null
                            when (val r = vm.resolve(text.trim(), forced)) {
                                is MainViewModel.AddResult.Ready -> ready = r
                                is MainViewModel.AddResult.Error -> error = r.message
                                else -> Unit
                            }
                            busy = false
                        }
                    }
                ) { Text(if (busy) "识别中…" else "识别主播") }
            } else {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            error = null
                            when (val r = vm.confirmAdd(pending.entity)) {
                                is MainViewModel.AddResult.Success -> onDismiss()
                                is MainViewModel.AddResult.Error -> {
                                    // 落库被拒（多半是别处已经加过）就退回输入步骤并说明原因
                                    error = r.message
                                    ready = null
                                }
                                else -> Unit
                            }
                            busy = false
                        }
                    }
                ) { Text(if (busy) "添加中…" else "确认添加") }
            }
        },
        dismissButton = {
            if (pending == null) {
                TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") }
            } else {
                TextButton(enabled = !busy, onClick = { ready = null }) { Text("返回修改") }
            }
        }
    )
}

/** 确认添加时的「这是谁」卡片：平台、昵称、ID、当前状态，最后才是确认按钮 */
@Composable
private fun ReadyBody(r: MainViewModel.AddResult.Ready) {
    val cs = MaterialTheme.colorScheme
    // 确认页展示的是 displayId（B 站/斗鱼/虎牙为房间号、抖音为抖音号），
    // B 站的 anchorId 才是 UID——两者不是一个号，别把房间号标成 UID。
    val idLabel = when (r.entity.platformId) {
        "bilibili" -> "房间号"
        "douyin" -> "抖音号"
        else -> "房间号"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            r.platformName,
            style = MaterialTheme.typography.labelLarge,
            color = cs.primary,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.width(8.dp))
        Surface(
            color = cs.primaryContainer,
            contentColor = cs.onPrimaryContainer,
            shape = RoundedCornerShape(50)
        ) {
            Text(
                "识别成功",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        AvatarImage(
            url = r.entity.avatarUrl,
            name = r.entity.nickname,
            size = 46.dp,
            bg = cs.surfaceVariant
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                r.entity.nickname,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                buildString {
                    append("$idLabel：${r.displayId}")
                    r.roomNo?.let { append(" · 房间号：$it") }
                },
                style = MaterialTheme.typography.labelSmall,
                color = cs.outline,
                maxLines = 2
            )
        }
    }
    Text(
        when {
            r.isLive && r.liveTitle.isNotBlank() -> "正在直播：${r.liveTitle}"
            r.isLive -> "正在直播"
            else -> "当前未开播"
        },
        style = MaterialTheme.typography.bodySmall,
        color = if (r.isLive) LiveAccent else cs.outline,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
    Text(
        "将关注该主播，后台开始检测开播状态。",
        style = MaterialTheme.typography.labelSmall,
        color = cs.outline
    )
}

/**
 * 圆形头像。底层先画昵称首字，图片没地址或加载失败时自然露出来，
 * 不会再出现「网络一差就是一块空白圆」。
 */
@Composable
private fun AvatarImage(
    url: String?,
    name: String,
    size: Dp,
    bg: Color = Color.Transparent,
    textStyle: TextStyle? = null
) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center
    ) {
        Text(
            name.take(1),
            style = textStyle ?: MaterialTheme.typography.titleMedium,
            color = cs.outline
        )
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
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

/** 新版本提示：版本对照 + 更新日志；点「立即更新」后原地变成下载进度 */
@Composable
private fun UpdateDialog(
    state: MainViewModel.UpdateState.Available,
    onDownload: () -> Unit,
    onDismiss: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val info = state.info
    AlertDialog(
        // 下载中不允许关掉，避免协程被取消后留下半截文件
        onDismissRequest = { if (!state.downloading) onDismiss() },
        title = { Text(if (state.downloading) "正在更新" else "发现新版本") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "当前 v${com.example.livenotify.BuildConfig.VERSION_NAME} → v${info.versionName}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.primary
                )
                if (state.downloading) {
                    LinearProgressIndicator(
                        progress = { state.percent / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        progressText(state.percent, state.doneBytes, state.totalBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.outline
                    )
                } else {
                    NotesBox(ReleaseNotes.format(info.notes))
                    Text(
                        packageLine(info.sizeBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.outline
                    )
                    Text(
                        "点「以后再说」后，这个版本不再自动提醒；仍可在设置页手动检查。",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.outline
                    )
                }
            }
        },
        confirmButton = {
            if (!state.downloading) TextButton(onClick = onDownload) { Text("立即更新") }
        },
        dismissButton = {
            if (!state.downloading) TextButton(onClick = onDismiss) { Text("以后再说") }
        }
    )
}

/**
 * 更新失败。
 *
 * 失败原因决定给哪条出路：网络/校验问题重试就够；签名不一致重试一百次也装不上，
 * 只能先导备份再卸载重装，所以按钮换成「去导出备份」。
 */
@Composable
private fun UpdateFailedDialog(
    state: MainViewModel.UpdateState.Failed,
    onRetry: () -> Unit,
    onOpenReleases: () -> Unit,
    onOpenBackup: () -> Unit,
    onDismiss: () -> Unit
) {
    val signature = state.failure == MainViewModel.UpdateFailure.Signature
    val canRetry = state.failure.retryable && state.info != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (signature) "无法覆盖安装" else "更新没有完成") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state.message, style = MaterialTheme.typography.bodyMedium)
                if (signature) {
                    Text(
                        "卸载重装后，把刚导出的备份导入，关注列表与设置都会恢复。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        },
        confirmButton = {
            when {
                signature -> TextButton(onClick = onOpenBackup) { Text("去导出备份") }
                canRetry -> TextButton(onClick = onRetry) { Text("重试") }
                else -> TextButton(onClick = onOpenReleases) { Text("打开 Releases") }
            }
        },
        dismissButton = {
            Row {
                if (signature) TextButton(onClick = onOpenReleases) { Text("打开 Releases") }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    )
}

/** 设置页/弹窗里的外链跳转：交给系统选浏览器或 GitHub 客户端 */
private fun openLink(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
