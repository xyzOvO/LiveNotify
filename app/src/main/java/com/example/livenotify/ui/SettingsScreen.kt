package com.example.livenotify.ui

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.livenotify.data.Settings
import com.example.livenotify.monitor.NetSnapshot
import com.example.livenotify.monitor.NetworkKind
import com.example.livenotify.monitor.NetworkState
import com.example.livenotify.monitor.NotificationHelper
import com.example.livenotify.monitor.PendingNotify
import com.example.livenotify.monitor.PollSchedule
import com.example.livenotify.platform.PlatformRegistry
import com.example.livenotify.update.ApkInstaller
import com.example.livenotify.update.ReleaseNotes
import com.example.livenotify.update.UpdateApi
import com.example.livenotify.util.AutoStartHelper
import com.example.livenotify.util.BackupFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 设置页。
 *
 * 这里刻意不做「一长串开关」：每一项都带一句「改它会怎样」，并且按
 * 检测 / 通知 / 后台权限 / 数据 分组——用户找的是「让提醒更及时」「别再漏提醒」
 * 这类目的，而不是某个具体字段名。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: MainViewModel,
    onBack: () -> Unit,
    /** 打开 B 站数据页（不指定主播，进去自己选） */
    onOpenStats: () -> Unit = {}
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val updateState by vm.update.collectAsStateWithLifecycle()
    val streamers by vm.streamers.collectAsStateWithLifecycle()
    val lastCheck by vm.lastCheckedAt.collectAsStateWithLifecycle()
    val running by vm.monitorRunning.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    // 从系统设置页返回时，权限 / 电池优化状态可能已经变了，进页面重读一次
    var resumeTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 设置页自己消费提示：主页面不在组合里，不在这里接就永远看不到
    // 「Cookie 已保存」「后台检测启动失败」这类反馈。
    LaunchedEffect(toast) {
        toast?.let {
            snackbar.showSnackbar(it)
            vm.consumeToast()
        }
    }

    val net = rememberNetSnapshot()
    val network = net.kind
    val quiet = remember(resumeTick, settings.quietEnabled, settings.quietStartMin, settings.quietEndMin) {
        isQuietNow(settings)
    }
    val plan = PollSchedule.plan(settings, network, quiet)
    val notifyAllowed = remember(resumeTick) { NotificationHelper.canNotify(context) }
    val batteryFree = remember(resumeTick) { AutoStartHelper.isIgnoringBatteryOptimizations(context) }
    val total = streamers.count { it.enabled }

    // 有开播提醒因为通知被关掉没送达：明确告诉用户「还能不能补」，
    // 而不是让他以为已经推过了、只是在别处错过了。
    val pendingNotices = remember(streamers, resumeTick) {
        val now = System.currentTimeMillis()
        streamers.count {
            it.enabled && PendingNotify.check(
                pendingSession = it.pendingNotifySession,
                pendingSince = it.pendingNotifySince,
                sessionKey = it.liveStartAt,
                now = now
            ) == PendingNotify.Verdict.RETRY
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            item {
                StatusCard(
                    settings = settings,
                    running = running,
                    plan = plan,
                    net = net,
                    total = total,
                    lastCheck = lastCheck,
                    lastRoundMs = remember(lastCheck, resumeTick) {
                        com.example.livenotify.Graph.engine.lastDurationMs
                    },
                    quiet = quiet,
                    onToggle = vm::setMonitorEnabled,
                    onRestart = { vm.setMonitorEnabled(true) }
                )
            }

            item { GroupTitle("检测") }
            item {
                SettingsCard {
                    SliderSetting(
                        title = "轮询间隔",
                        valueText = formatInterval(settings.pollIntervalSec),
                        value = settings.pollIntervalSec.coerceIn(MIN_POLL, MAX_POLL).toFloat(),
                        range = MIN_POLL.toFloat()..MAX_POLL.toFloat(),
                        steps = POLL_STEPS,
                        subtitle = "周期从本轮开始算，检测耗时已经含在里面；越短越及时，也越费电。" +
                            "当前约每天 ${86_400 / settings.pollIntervalSec.coerceAtLeast(1)} 轮（Wi-Fi 下），" +
                            "单轮超时会跳过下一次，不会补跑。",
                        onChange = { vm.setPollInterval(it.roundToInt()) }
                    )
                    ItemDivider()
                    SettingSwitch(
                        title = "仅 Wi-Fi 下检测",
                        subtitle = "开启后移动网络下完全停止检测、不产生流量；连上 Wi-Fi 自动继续。",
                        checked = settings.wifiOnly,
                        onChange = vm::setWifiOnly
                    )
                    ItemDivider()
                    SliderSetting(
                        title = "移动网络降频",
                        valueText = "×${settings.mobileFactor}",
                        value = settings.mobileFactor.toFloat(),
                        range = 1f..6f,
                        steps = 4,
                        subtitle = if (settings.wifiOnly) {
                            "已开启「仅 Wi-Fi 下检测」，移动网络下不检测，此项不生效。"
                        } else {
                            "移动网络下按「基础间隔 × ${settings.mobileFactor}」算，" +
                                "即每 ${formatInterval(settings.pollIntervalSec * settings.mobileFactor)}一次；" +
                                "Wi-Fi 下仍是 ${formatInterval(settings.pollIntervalSec)}。"
                        },
                        onChange = { vm.setMobileFactor(it.roundToInt()) }
                    )
                    ItemDivider()
                    SubHeader("推送判定")
                    SliderSetting(
                        title = "连续确认次数",
                        valueText = "${settings.confirmTimes} 次",
                        value = settings.confirmTimes.toFloat(),
                        range = 1f..3f,
                        steps = 1,
                        subtitle = if (settings.confirmTimes <= 1) {
                            "检测到开播就推送，最及时；接口抖动时可能误报。"
                        } else {
                            "连续 ${settings.confirmTimes} 次检测到开播才推送，能过滤抖动，" +
                                "代价是最多晚约 ${formatInterval(settings.pollIntervalSec * (settings.confirmTimes - 1))}。"
                        },
                        onChange = { vm.setConfirmTimes(it.roundToInt()) }
                    )
                    ItemDivider()
                    SliderSetting(
                        title = "同一主播提醒间隔",
                        valueText = if (settings.cooldownMin == 0) "不限制" else "${settings.cooldownMin} 分钟",
                        value = settings.cooldownMin.toFloat(),
                        range = 0f..60f,
                        steps = 11,
                        subtitle = "这段时间内同一位主播不会再次提醒；0 表示不限制。",
                        onChange = { vm.setCooldownMin(it.roundToInt()) }
                    )
                }
            }

            item { GroupTitle("通知") }
            item {
                SettingsCard {
                    SubHeader("开播提醒")
                    DualSwitchRow(
                        leftLabel = "响铃",
                        leftChecked = settings.notifySound,
                        onLeftChange = vm::setNotifySound,
                        rightLabel = "震动",
                        rightChecked = settings.notifyVibrate,
                        onRightChange = vm::setNotifyVibrate
                    )
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        OutlinedButton(onClick = {
                            if (!NotificationHelper.testNotification(context)) {
                                vm.notify("通知权限未开启，先在下面「后台权限」里允许通知")
                            }
                        }) { Text("发送测试通知") }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (!settings.notifySound && !settings.notifyVibrate) {
                                "当前是静默提醒：测试通知只会进通知栏，不响也不震。"
                            } else {
                                "按上面的响铃 / 震动设置发一条，不用等主播开播。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (!settings.notifySound && !settings.notifyVibrate) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.outline
                            }
                        )
                    }
                    ItemDivider()
                    SettingSwitch(
                        title = "免打扰时段",
                        subtitle = "该时段内不推送通知，但检测照常运行——只是间隔放宽到 ${PollSchedule.QUIET_FACTOR} 倍以省电。",
                        checked = settings.quietEnabled,
                        onChange = {
                            vm.setQuiet(it, settings.quietStartMin, settings.quietEndMin, settings.quietNoNotify)
                        }
                    )
                    if (settings.quietEnabled) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                HourField(
                                    label = "开始",
                                    value = settings.quietStartMin / 60,
                                    modifier = Modifier.weight(1f)
                                ) { vm.setQuiet(true, it * 60, settings.quietEndMin, settings.quietNoNotify) }
                                HourField(
                                    label = "结束",
                                    value = settings.quietEndMin / 60,
                                    modifier = Modifier.weight(1f)
                                ) { vm.setQuiet(true, settings.quietStartMin, it * 60, settings.quietNoNotify) }
                            }
                            if (settings.quietStartMin == settings.quietEndMin) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "开始与结束相同，免打扰不会生效",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "跨零点会自动识别，例如 23 → 8 表示当晚 23 点到次日 8 点。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("时段内不推送", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    if (settings.quietNoNotify) "关掉后只降频，提醒照常弹出。"
                                    else "已关：该时段只是降频，仍会推送。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                            Switch(
                                checked = settings.quietNoNotify,
                                onCheckedChange = {
                                    vm.setQuiet(true, settings.quietStartMin, settings.quietEndMin, it)
                                }
                            )
                        }
                    }
                    ItemDivider()
                    SettingSwitch(
                        title = "隐藏常驻通知",
                        subtitle = "Android 要求前台服务必须有一条通知。开启后降到最低可见度（不响、不弹、无角标），检测不受影响。",
                        checked = settings.hideMonitorNotification,
                        onChange = vm::setHideMonitorNotification
                    )
                    Row(
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(onClick = {
                            NotificationHelper.openChannelSettings(
                                context,
                                if (settings.hideMonitorNotification) {
                                    NotificationHelper.CHANNEL_MONITOR_SILENT
                                } else {
                                    NotificationHelper.CHANNEL_MONITOR
                                }
                            )
                        }) { Text("常驻通知设置") }
                        OutlinedButton(onClick = {
                            NotificationHelper.openChannelSettings(context, NotificationHelper.CHANNEL_LIVE)
                        }) { Text("开播提醒设置") }
                    }
                }
            }

            item { GroupTitle("后台权限") }
            item {
                SettingsCard {
                    PermissionRow(
                        label = "后台检测服务",
                        value = when {
                            !settings.monitorEnabled -> "已关闭"
                            running -> "运行中"
                            else -> "未运行"
                        },
                        ok = settings.monitorEnabled && running,
                        action = if (settings.monitorEnabled && !running) "重启" else null,
                        onAction = { vm.setMonitorEnabled(true) }
                    )
                    PermissionRow(
                        label = "通知权限",
                        value = if (notifyAllowed) "已允许" else "未允许",
                        ok = notifyAllowed,
                        action = "去设置",
                        onAction = { NotificationHelper.openNotificationSettings(context) }
                    )
                    if (pendingNotices > 0) {
                        val ttlMin = (PendingNotify.MAX_AGE_MS / 60_000L).toInt()
                        Text(
                            if (notifyAllowed) {
                                "有 $pendingNotices 条开播提醒待补发，下一轮检测会送达；超过 $ttlMin 分钟自动放弃。"
                            } else {
                                "有 $pendingNotices 条开播提醒因为通知被关掉没能送达。" +
                                    "允许通知后下一轮会自动补上，超过 $ttlMin 分钟自动放弃。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (notifyAllowed) {
                                MaterialTheme.colorScheme.outline
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp)
                        )
                    }
                    PermissionRow(
                        label = "电池优化",
                        value = if (batteryFree) "已忽略" else "未忽略",
                        ok = batteryFree,
                        action = "去设置",
                        onAction = {
                            if (!AutoStartHelper.requestIgnoreBatteryOptimizations(context)) {
                                context.toast("已忽略电池优化，无需再设置")
                            }
                        }
                    )
                    PermissionRow(
                        label = "自启动",
                        value = "需在系统里确认",
                        ok = null,
                        action = "去设置",
                        onAction = {
                            val hit = AutoStartHelper.openAutoStartSettings(context)
                            context.toast(
                                if (hit) "在列表里允许本应用自启动"
                                else "已打开应用详情，在「自启动 / 后台运行」里允许本应用"
                            )
                        }
                    )
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Text(
                            "国产 ROM 默认限制第三方应用后台运行。三项都放开，重启手机后检测才能自动跑起来；" +
                                "只有关掉页首的总开关才会真正停止检测。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }

            item { GroupTitle("数据") }
            item {
                SettingsCard {
                    BackupBlock(vm = vm, context = context)
                    ItemDivider()
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text("B 站数据", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "粉丝走势、月度互动与生涯统计；来自社区采集，非 B 站官方。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = onOpenStats) { Text("查看 B 站数据") }
                    }
                    ItemDivider()
                    DouyinCookieBlock(vm = vm, settings = settings)
                    ItemDivider()
                    PlatformStatusBlock()
                }
            }

            item { GroupTitle("其他") }
            item {
                SettingsCard {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text("检查更新", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(2.dp))
                        // 当前版本与最新版本并排列出，不用在两处文案之间对照
                        val latest = when (val st = updateState) {
                            is MainViewModel.UpdateState.Available -> st.info
                            is MainViewModel.UpdateState.Failed -> st.info
                            else -> null
                        }
                        val current = "v${com.example.livenotify.BuildConfig.VERSION_NAME}"
                        Text(
                            if (latest != null && latest.versionCode > com.example.livenotify.BuildConfig.VERSION_CODE) {
                                "当前版本 $current → 最新 v${latest.versionName}"
                            } else {
                                "当前版本 $current"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                enabled = updateState !is MainViewModel.UpdateState.Checking &&
                                    (updateState as? MainViewModel.UpdateState.Available)?.downloading != true,
                                onClick = { vm.checkUpdate() }
                            ) { Text(if (updateState is MainViewModel.UpdateState.Checking) "检查中…" else "检查更新") }
                            OutlinedButton(onClick = { openReleases(context) }) { Text("全部版本") }
                        }
                        Spacer(Modifier.height(6.dp))
                        UpdateStatusBlock(vm, updateState)
                    }
                    SettingSwitch(
                        title = "启动时自动检查",
                        subtitle = "只提示新版本，不会自动下载安装。",
                        checked = settings.autoUpdateCheck,
                        onChange = vm::setAutoUpdateCheck
                    )
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Text(
                            "更新包来自本仓库的 GitHub Releases，下载后校验 SHA-256 与签名，再由系统安装页覆盖升级；关注列表不会丢。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        if (!ApkInstaller.canInstall(context)) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "当前未授权「安装未知应用」，自动升级会失败。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                            OutlinedButton(onClick = { ApkInstaller.openInstallPermission(context) }) {
                                Text("去授权")
                            }
                        }
                    }
                }
            }
            item { AboutCard(context) }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

// ── 顶部状态卡 ─────────────────────────────────────────────

/** 一眼看到「现在有没有在检测、多久一次、为什么变慢」，而不是自己去几个开关之间推算 */
@Composable
private fun StatusCard(
    settings: Settings,
    running: Boolean,
    plan: PollSchedule.Plan,
    net: NetSnapshot,
    total: Int,
    lastCheck: Long,
    lastRoundMs: Long,
    quiet: Boolean,
    onToggle: (Boolean) -> Unit,
    onRestart: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val enabled = settings.monitorEnabled
    // 「仅 Wi-Fi」是用户自己的选择，优先说它；没网是客观状态，其次
    val paused = enabled && running && plan.paused
    val offline = enabled && running && !paused && !net.online
    val color = when {
        !enabled -> cs.outline
        !running -> cs.error
        paused -> cs.error
        offline -> cs.error
        else -> okColor()
    }
    val title = when {
        !enabled -> "后台检测已关闭"
        !running -> "检测未运行"
        paused -> "已暂停：等待 Wi-Fi"
        offline -> "已暂停：等待网络"
        else -> "正在后台检测"
    }
    val facts = buildList {
        if (!enabled) {
            add("打开后每 ${formatInterval(plan.baseSec)}检测一轮")
        } else {
            if (!paused && !offline) add("每 ${formatInterval(plan.effectiveSec)}")
            // 没网时说「网络未知」等于没说，直接讲清当前状态
            add(
                if (offline) "无可用网络" else when (plan.network) {
                    NetworkKind.WIFI -> "Wi-Fi"
                    NetworkKind.CELLULAR -> "移动网络"
                    NetworkKind.OTHER -> "网络未知"
                }
            )
            add(if (total > 0) "$total 位主播" else "暂无关注")
            if (quiet && !paused) add("免打扰中")
            if (lastCheck > 0L) add("最近 ${timeOf(lastCheck)}")
        }
    }.joinToString(" · ")

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Column(Modifier.padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(9.dp)
                        .background(color, CircleShape)
                )
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, color = color)
                Spacer(Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = onToggle)
            }
            Spacer(Modifier.height(4.dp))
            Text(facts, style = MaterialTheme.typography.bodySmall, color = cs.outline)
            // 周期是怎么算的，直接写在「多久一次」旁边，省得用户自己去猜
            if (enabled && running && !paused && !offline && lastRoundMs > 0L) {
                Spacer(Modifier.height(2.dp))
                Text(
                    if (lastRoundMs > plan.effectiveMs) {
                        "上一轮用时 ${formatRound(lastRoundMs)}，超过设定的周期，" +
                            "错过的那一轮已跳过（不补跑）"
                    } else {
                        "上一轮用时 ${formatRound(lastRoundMs)}，已包含在周期里"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (lastRoundMs > plan.effectiveMs) cs.error else cs.outline
                )
            }
            if (paused) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "「仅 Wi-Fi 下检测」已开启，移动网络下完全不会联网；连上 Wi-Fi 后自动继续。",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.outline
                )
            }
            if (offline) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "当前网络连不上外网，本轮不发请求、也不累计主播的检测错误；联网后自动继续。",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.outline
                )
            }
            if (enabled && !running) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "设置是开着的，但后台服务没起来——多半被系统清理了。",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.outline
                )
                TextButton(onClick = onRestart, contentPadding = PaddingValues(0.dp)) {
                    Text("立即重启检测")
                }
            }
        }
    }
}

// ── 检测 / 通知 / 权限 ─────────────────────────────────────

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Column(Modifier.padding(vertical = 2.dp), content = content)
    }
}

@Composable
private fun GroupTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 4.dp)
    )
}

/** 卡片内的小标题：把「轮询参数」和「推送判定」分开，不然五项挤在一起看不出层次 */
@Composable
private fun SubHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp)
    )
}

@Composable
private fun ItemDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 2.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    )
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** 响铃 / 震动并排，省一行高度；两者都是「提醒方式」，放一起比较也直观 */
@Composable
private fun DualSwitchRow(
    leftLabel: String,
    leftChecked: Boolean,
    onLeftChange: (Boolean) -> Unit,
    rightLabel: String,
    rightChecked: Boolean,
    onRightChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(leftLabel, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.weight(1f))
            Switch(checked = leftChecked, onCheckedChange = onLeftChange)
        }
        Spacer(Modifier.width(16.dp))
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(rightLabel, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.weight(1f))
            Switch(checked = rightChecked, onCheckedChange = onRightChange)
        }
    }
}

@Composable
private fun SliderSetting(
    title: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    subtitle: String,
    onChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(valueText, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

/**
 * 权限检查清单的一行：左侧项目名，右侧结论 + 可选操作。
 * [ok] 为 null 表示系统不提供可读状态（例如自启动），不能瞎标绿。
 */
@Composable
private fun PermissionRow(
    label: String,
    value: String,
    ok: Boolean?,
    action: String?,
    onAction: () -> Unit
) {
    val color = when (ok) {
        true -> okColor()
        false -> MaterialTheme.colorScheme.error
        null -> MaterialTheme.colorScheme.outline
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 有没有「去设置」按钮不应改变行高，否则四行会参差不齐
            .heightIn(min = 50.dp)
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, color = color)
        if (action != null) {
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

// ── 数据 ──────────────────────────────────────────────────

/**
 * 关注列表备份 / 恢复。
 *
 * 用系统文件选择器（SAF），不申请任何存储权限、不碰云服务。导出只写用户选的文件；
 * 导入会改数据库和设置，所以先解析出条数给用户看一眼，确认后才真的落库。
 */
@Composable
private fun BackupBlock(vm: MainViewModel, context: Context) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    /** 已读出但尚未确认的备份：确认前绝不写库 */
    var pending by remember { mutableStateOf<Pair<String, BackupFormat.Data>?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BackupFormat.MIME)
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            message = null
            val result = runCatching {
                val json = vm.exportBackup()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { os ->
                        os.write(json.toByteArray(Charsets.UTF_8))
                    } ?: error("无法写入所选文件")
                }
            }
            busy = false
            message = if (result.isSuccess) "已导出关注列表" else "导出失败：${result.exceptionOrNull()?.message}"
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            message = null
            val text = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            }
            busy = false
            if (text.isNullOrBlank()) {
                message = "读取失败：文件内容为空"
                return@launch
            }
            val parsed = runCatching { BackupFormat.decode(text) }
            if (parsed.isFailure) {
                message = parsed.exceptionOrNull()?.message ?: "备份文件无法解析"
                return@launch
            }
            // 只展示，不落库；用户在下面的对话框里决定
            pending = text to parsed.getOrThrow()
        }
    }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text("关注列表备份", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(2.dp))
        Text(
            "换手机或重装前导出一份 JSON；只含关注列表与设置，不含 Cookie、头像缓存和开播状态。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = !busy,
                onClick = {
                    val name = "LiveNotify-关注列表-" +
                        SimpleDateFormat("yyyyMMdd-HHmm", Locale.getDefault()).format(Date()) + ".json"
                    exportLauncher.launch(name)
                },
                modifier = Modifier.weight(1f)
            ) { Text("导出") }
            OutlinedButton(
                enabled = !busy,
                onClick = { importLauncher.launch(arrayOf(BackupFormat.MIME, "text/plain", "*/*")) },
                modifier = Modifier.weight(1f)
            ) { Text("导入") }
        }
        if (busy) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("处理中…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
        message?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }

    pending?.let { (raw, data) ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("导入这份备份？") },
            text = {
                Column {
                    Text("共 ${data.entries.size} 位主播，另含检测与提醒设置。")
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "已有的主播保持原样，不会覆盖；新主播会重新联网获取信息。" +
                            "导入后不会补发任何历史开播提醒。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pending = null
                        scope.launch {
                            busy = true
                            val r = vm.importBackup(raw)
                            busy = false
                            message = if (r.rolledBack) {
                                "导入失败：写入本地数据库时出错，已回滚，未新增任何主播"
                            } else buildString {
                                append("导入完成：新增 ${r.added} 位")
                                if (r.skipped > 0) append("，跳过已有 ${r.skipped} 位")
                                if (r.failed.isNotEmpty()) append("，失败 ${r.failed.size} 位")
                            }
                            if (r.failed.isNotEmpty()) {
                                context.toast(r.failed.take(2).joinToString("；"))
                            }
                        }
                    }
                ) { Text("确认导入") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("取消") } }
        )
    }
}

/**
 * 抖音 Cookie：先告诉用户「配没配」，再给配置入口。
 * 以前直接摆一个空输入框，用户不知道要不要填、填了会存到哪。
 */
@Composable
private fun DouyinCookieBlock(vm: MainViewModel, settings: Settings) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var draft by remember(settings.douyinCookie) { mutableStateOf(settings.douyinCookie) }
    var reveal by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val configured = settings.douyinCookie.isNotBlank()

    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("抖音 Cookie", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.weight(1f))
            Text(
                if (configured) "已配置" else "未配置",
                style = MaterialTheme.typography.bodySmall,
                color = if (configured) okColor() else MaterialTheme.colorScheme.outline
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            if (configured) {
                "仅保存在本机，只用于请求抖音公开页面；不随备份导出。Cookie 等同于登录凭据，别截图分享。"
            } else {
                "抖音没有公开的个人接口，默认走免登录解析；只有频繁失败时才需要配置。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.height(8.dp))
        if (!expanded) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { expanded = true }) {
                    Text(if (configured) "更换" else "配置 Cookie")
                }
                if (configured) {
                    OutlinedButton(onClick = { confirmClear = true }) { Text("清除") }
                }
            }
        } else {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text("粘贴 Cookie") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 5,
                visualTransformation = if (reveal) VisualTransformation.None
                else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { reveal = !reveal }) {
                        Text(if (reveal) "隐藏" else "显示")
                    }
                }
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    vm.setDouyinCookie(draft)
                    expanded = false
                }) { Text("保存") }
                OutlinedButton(onClick = {
                    draft = settings.douyinCookie
                    expanded = false
                }) { Text("取消") }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "在浏览器登录 live.douyin.com 后，从开发者工具复制请求头里的 Cookie。留空保存即清除。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清除抖音 Cookie？") },
            text = {
                Text("清除后抖音改回免登录解析；遇到风控时可能检测失败，可随时重新粘贴。")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    draft = ""
                    vm.setDouyinCookie("")
                }) { Text("清除") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } }
        )
    }
}

/** 平台接口状态：技术细节，默认收起，不占视线 */
@Composable
private fun PlatformStatusBlock() {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("平台接口状态", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.weight(1f))
            Text(
                if (expanded) "收起" else "展开",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline
            )
        }
        if (expanded) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp)) {
                PlatformRegistry.all().forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.displayName, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.weight(1f))
                        Surface(
                            color = if (p.usesOfficialApi) okContainer()
                            else MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                            shape = CircleShape
                        ) {
                            Text(
                                if (p.usesOfficialApi) "官方接口" else "降级方案",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (p.usesOfficialApi) okColor() else MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    Text(
                        p.degradeNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun AboutCard(context: Context) {
    SettingsCard {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("关于", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                "开播提醒 v${com.example.livenotify.BuildConfig.VERSION_NAME}" +
                    "（build ${com.example.livenotify.BuildConfig.VERSION_CODE}）",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "关注列表与设置只保存在本机，不上传任何数据；检测直连各平台公开接口，中间不经过第三方服务器。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "来自 xyzOvO",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            // 直接显示完整地址，方便用户看清来源；点击交给系统选择浏览器或 GitHub 客户端
            TextButton(onClick = { openProject(context) }, contentPadding = PaddingValues(0.dp)) {
                Text(UpdateApi.PROJECT_PAGE, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// ── 更新状态 ───────────────────────────────────────────────

/**
 * 设置页里的更新状态：检查结果、更新日志、下载进度、失败重试都就地展示，不弹窗。
 * 「当前版本 → 最新版本」在卡片标题下已经并排给出，这里不重复。
 */
@Composable
private fun UpdateStatusBlock(vm: MainViewModel, state: MainViewModel.UpdateState) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    when (state) {
        is MainViewModel.UpdateState.Idle -> Unit
        is MainViewModel.UpdateState.Checking -> {
            Text("正在检查…", style = MaterialTheme.typography.bodySmall, color = cs.outline)
        }
        is MainViewModel.UpdateState.UpToDate -> {
            Text("已是最新版本", style = MaterialTheme.typography.bodySmall, color = cs.primary)
        }
        is MainViewModel.UpdateState.Failed -> {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(state.message, style = MaterialTheme.typography.bodySmall, color = cs.error)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        // 签名不一致：重试无意义，只能去手动下载或先导备份
                        state.failure == MainViewModel.UpdateFailure.Signature ->
                            OutlinedButton(onClick = { openReleases(context) }) { Text("打开 Releases") }
                        state.failure.retryable && state.info != null ->
                            OutlinedButton(onClick = { vm.retryUpdate() }) { Text("重试") }
                    }
                    if (state.failure == MainViewModel.UpdateFailure.InstallPermission) {
                        OutlinedButton(onClick = { ApkInstaller.openInstallPermission(context) }) {
                            Text("去授权")
                        }
                    }
                }
            }
        }
        is MainViewModel.UpdateState.Available -> {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                    NotesBox(ReleaseNotes.format(state.info.notes), maxHeight = 200.dp)
                    Text(
                        packageLine(state.info.sizeBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.outline
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.downloadUpdate(state.info) }) { Text("下载并安装") }
                        OutlinedButton(onClick = { vm.dismissUpdate() }) { Text("以后再说") }
                    }
                }
            }
        }
    }
}

// ── 工具 ──────────────────────────────────────────────────

private const val MIN_POLL = 60
private const val MAX_POLL = 600
/** 60..600、步长 30：共 19 个刻度 */
private const val POLL_STEPS = 17

/** 把秒数说成人话：界面上再出现「120 秒」就太工程师了 */
private fun formatInterval(sec: Int): String {
    val s = sec.coerceAtLeast(0)
    if (s < 60) return "$s 秒"
    val m = s / 60
    val rest = s % 60
    return if (rest == 0) "$m 分钟" else "$m 分 $rest 秒"
}

/** 一轮检测的耗时：几百毫秒到几十秒都可能，别显示成「0 分钟」 */
private fun formatRound(ms: Long): String = when {
    ms < 1_000L -> "${ms.coerceAtLeast(0L)} 毫秒"
    ms < 60_000L -> String.format(Locale.US, "%.1f 秒", ms / 1000.0)
    else -> "${ms / 60_000L} 分 ${(ms % 60_000L) / 1000L} 秒"
}

private fun timeOf(ms: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

private fun isQuietNow(settings: Settings): Boolean {
    if (!settings.quietEnabled) return false
    val cal = Calendar.getInstance()
    val minuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    return PollSchedule.inQuietWindow(settings.quietStartMin, settings.quietEndMin, minuteOfDay)
}

// rememberNetSnapshot 已抽到 ui/NetSnapshot.kt，主页状态行共用同一份实时判断。
/** 设置页的轻提示。用系统 Toast 而不是 Snackbar：这些操作常常伴随跳转到系统页 */
private fun Context.toast(msg: String) {
    android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
}

@Composable
private fun HourField(
    label: String,
    value: Int,
    modifier: Modifier = Modifier,
    onChange: (Int) -> Unit
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            val filtered = input.filter { it.isDigit() }.take(2)
            text = filtered
            filtered.toIntOrNull()?.coerceIn(0, 23)?.let { onChange(it) }
        },
        label = { Text("$label (0-23 时)") },
        singleLine = true,
        modifier = modifier
    )
}

/** 关于里的项目主页：交给系统选择浏览器 / 已关联的 GitHub 客户端 */
private fun openProject(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(UpdateApi.PROJECT_PAGE))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

private fun openReleases(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(UpdateApi.RELEASES_PAGE))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
