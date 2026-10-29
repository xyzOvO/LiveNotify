package com.example.livenotify.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.livenotify.monitor.MonitorService
import com.example.livenotify.monitor.NotificationHelper
import com.example.livenotify.platform.PlatformRegistry
import com.example.livenotify.update.ApkInstaller
import com.example.livenotify.update.ReleaseInfo
import com.example.livenotify.update.UpdateApi
import com.example.livenotify.util.AutoStartHelper

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
    val context = LocalContext.current

    // 从系统设置页返回时，服务/电池优化的状态可能已经变了，进页面重读一次
    var resumeTick by remember { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            item {
                Section("检测频率") {
                    LabeledSlider(
                        label = "轮询间隔",
                        value = settings.pollIntervalSec.toFloat(),
                        range = 60f..300f,
                        steps = 11,
                        display = "${settings.pollIntervalSec} 秒",
                        onChange = { vm.setPollInterval(it.toInt()) }
                    )
                    Text(
                        "1~3 分钟能兼顾及时性与耗电。检测走前台服务，间隔低于 15 分钟时 WorkManager 无法满足，这是本方案使用常驻服务的原因。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LabeledSlider(
                        label = "移动网络间隔倍数",
                        value = settings.mobileFactor.toFloat(),
                        range = 1f..6f,
                        steps = 4,
                        display = "×${settings.mobileFactor}",
                        onChange = { vm.setMobileFactor(it.toInt()) }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    SwitchRow(
                        label = "仅在 WiFi 下检测",
                        checked = settings.wifiOnly,
                        onCheckedChange = { vm.setWifiOnly(it) }
                    )
                }
            }

            item {
                Section("免打扰") {
                    SwitchRow(
                        label = "启用免打扰时段",
                        checked = settings.quietEnabled,
                        onCheckedChange = { vm.setQuiet(it, settings.quietStartMin, settings.quietEndMin, settings.quietNoNotify) }
                    )
                    if (settings.quietEnabled) {
                        Spacer(modifier = Modifier.height(8.dp))
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
                        Spacer(modifier = Modifier.height(4.dp))
                        // 起止相同时区间为空，免打扰实际不会生效——不提示的话用户会以为设置成功了
                        if (settings.quietStartMin == settings.quietEndMin) {
                            Text(
                                "开始与结束时间相同，免打扰不会生效",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        SwitchRow(
                            label = "该时段内不推送（仍检测）",
                            checked = settings.quietNoNotify,
                            onCheckedChange = { vm.setQuiet(true, settings.quietStartMin, settings.quietEndMin, it) }
                        )
                        Text(
                            "免打扰时段内轮询间隔自动放大 5 倍，进一步省电。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }

            item {
                Section("防误报 / 防重复") {
                    LabeledSlider(
                        label = "连续确认次数",
                        value = settings.confirmTimes.toFloat(),
                        range = 1f..3f,
                        steps = 1,
                        display = "${settings.confirmTimes} 次",
                        onChange = { vm.setConfirmTimes(it.toInt()) }
                    )
                    Text(
                        "连续 N 次检测到开播才推送，可过滤接口抖动造成的误报；设为 2 时推送延迟约一个轮询周期。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LabeledSlider(
                        label = "同一主播推送冷却",
                        value = settings.cooldownMin.toFloat(),
                        range = 0f..60f,
                        steps = 11,
                        display = "${settings.cooldownMin} 分钟",
                        onChange = { vm.setCooldownMin(it.toInt()) }
                    )
                }
            }

            item {
                Section("开播提醒方式") {
                    SwitchRow(
                        label = "响铃",
                        checked = settings.notifySound,
                        onCheckedChange = { vm.setNotifySound(it) }
                    )
                    SwitchRow(
                        label = "震动",
                        checked = settings.notifyVibrate,
                        onCheckedChange = { vm.setNotifyVibrate(it) }
                    )
                    Text(
                        "Android 8 起声音与震动由系统通知渠道控制，改动后会重建「开播提醒」渠道，之前在系统里对该渠道的手动设置会被重置。想彻底静音请到系统的通知渠道里关闭。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(onClick = {
                        // 权限没开时通知发不出去，必须说出来，否则点了像坏了
                        if (!NotificationHelper.testNotification(context)) {
                            vm.notify("通知权限未开启，先点下面的「通知权限设置」")
                        }
                    }) {
                        Text("发送测试通知")
                    }
                }
            }

            item {
                Section("常驻通知") {
                    SwitchRow(
                        label = "隐藏常驻通知",
                        checked = settings.hideMonitorNotification,
                        onCheckedChange = { vm.setHideMonitorNotification(it) }
                    )
                    Text(
                        "Android 要求前台服务必须有一条通知，无法从应用内彻底移除。开启后通知会被降到最低可见度（静音、不弹窗、不显示角标）；若仍觉得碍眼，可在下面的系统设置里把「后台检测」渠道整个关掉，检测不受影响。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            NotificationHelper.openChannelSettings(
                                context,
                                if (settings.hideMonitorNotification)
                                    NotificationHelper.CHANNEL_MONITOR_SILENT
                                else NotificationHelper.CHANNEL_MONITOR
                            )
                        }) {
                            Text("常驻通知设置")
                        }
                        OutlinedButton(onClick = {
                            NotificationHelper.openChannelSettings(
                                context,
                                NotificationHelper.CHANNEL_LIVE
                            )
                        }) {
                            Text("开播提醒设置")
                        }
                    }
                }
            }

            item {
                Section("平台接口状态") {
                    PlatformRegistry.all().forEach { p ->
                        Column(modifier = Modifier.padding(vertical = 6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(p.displayName, style = MaterialTheme.typography.titleSmall)
                                Spacer(modifier = Modifier.weight(1f))
                                Surface(
                                    color = if (p.usesOfficialApi) okContainer()
                                    else MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(50)
                                ) {
                                    Text(
                                        if (p.usesOfficialApi) "官方接口" else "降级方案",
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (p.usesOfficialApi) okColor()
                                        else MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                            Text(
                                p.degradeNote,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }

            item {
                Section("B站数据") {
                    Text(
                        "粉丝数历史曲线、月度互动观众与付费趋势、生涯统计；也可以填任意 UID 查看他的互动记录。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(onClick = onOpenStats) { Text("查看 B站数据") }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "数据来自 danmakus.com 的公开统计（社区采集，非 B 站官方），只发送 uid，不需要登录。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.8f)
                    )
                }
            }

            item {
                Section("抖音 Cookie（可选）") {
                    var cookie by remember(settings.douyinCookie) { mutableStateOf(settings.douyinCookie) }
                    var cookieVisible by remember { mutableStateOf(false) }
                    OutlinedTextField(
                        value = cookie,
                        onValueChange = { cookie = it },
                        label = { Text("Cookie") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 5,
                        // Cookie 是会话凭据，默认遮蔽，避免截图/旁边有人时泄露
                        visualTransformation = if (cookieVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            TextButton(onClick = { cookieVisible = !cookieVisible }) {
                                Text(if (cookieVisible) "隐藏" else "显示")
                            }
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(onClick = { vm.setDouyinCookie(cookie) }) {
                        Text("保存")
                    }
                    Text(
                        "风控严重时，用浏览器登录 live.douyin.com 后复制 Cookie 粘贴到这里。Cookie 仅保存在本机，仅用于请求抖音公开页面。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            item {
                Section("系统与通知") {
                    OutlinedButton(onClick = { NotificationHelper.openNotificationSettings(context) }) {
                        Text("通知权限设置")
                    }
                    Text(
                        "开播提醒依赖通知权限；被系统关掉后只会在 App 内提示，不会弹通知。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            item {
                Section("后台运行") {
                    val running = remember(resumeTick, settings.monitorEnabled) {
                        settings.monitorEnabled && MonitorService.isRunning
                    }
                    val ignoringBattery = remember(resumeTick) {
                        AutoStartHelper.isIgnoringBatteryOptimizations(context)
                    }
                    StatusLine(
                        label = "后台检测",
                        value = when {
                            !settings.monitorEnabled -> "已关闭"
                            running -> "运行中"
                            else -> "未运行"
                        },
                        ok = running
                    )
                    StatusLine(
                        label = "电池优化",
                        value = if (ignoringBattery) "已忽略，不受限制" else "未忽略，可能被系统限流",
                        ok = ignoringBattery
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                val hit = AutoStartHelper.openAutoStartSettings(context)
                                context.toast(
                                    if (hit) "在列表里允许本应用自启动"
                                    else "已打开应用详情，在「自启动 / 后台运行」里允许本应用"
                                )
                            }
                        ) { Text("自启动管理") }
                        OutlinedButton(
                            onClick = {
                                if (!AutoStartHelper.requestIgnoreBatteryOptimizations(context)) {
                                    context.toast("已忽略电池优化，无需再设置")
                                }
                            }
                        ) { Text("电池优化") }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "国产 ROM 默认禁止第三方应用自启动，重启手机后不会自动检测。" +
                            "在「自启动管理」里允许本应用，并把省电策略设为「无限制」，检测才能长期在后台运行。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            item {
                Section("检查更新") {
                    val st = updateState
                    Text(
                        "当前版本 v${com.example.livenotify.BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            enabled = st !is MainViewModel.UpdateState.Checking &&
                                (st as? MainViewModel.UpdateState.Available)?.downloading != true,
                            onClick = { vm.checkUpdate() }
                        ) { Text(if (st is MainViewModel.UpdateState.Checking) "检查中…" else "检查更新") }
                        OutlinedButton(onClick = { openReleases(context) }) {
                            Text("全部版本")
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    UpdateStatusBlock(vm, st)
                    SwitchRow(
                        label = "启动时自动检查",
                        checked = settings.autoUpdateCheck,
                        onCheckedChange = { vm.setAutoUpdateCheck(it) }
                    )
                    Text(
                        "更新包来自本仓库的 GitHub Releases，下载后会拉起系统安装页，点「安装」即可覆盖升级，关注列表不会丢。若提示「安装被阻止」，先在下方授权允许安装未知应用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    if (!ApkInstaller.canInstall(context)) {
                        Spacer(modifier = Modifier.height(8.dp))
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

            item {
                Section("关于") {
                    Text(
                        "开播提醒 v${com.example.livenotify.BuildConfig.VERSION_NAME}（build ${com.example.livenotify.BuildConfig.VERSION_CODE}）",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "关注列表与设置只保存在本机，不上传任何数据；检测直连各平台公开接口，中间不经过第三方服务器。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }
}

/** 设置页里的更新状态：检查结果、更新说明、下载进度都就地展示，不弹窗 */
@Composable
private fun UpdateStatusBlock(vm: MainViewModel, state: MainViewModel.UpdateState) {
    val cs = MaterialTheme.colorScheme
    when (state) {
        is MainViewModel.UpdateState.Idle -> Unit
        is MainViewModel.UpdateState.Checking -> {
            Text("正在检查…", style = MaterialTheme.typography.bodySmall, color = cs.outline)
        }
        is MainViewModel.UpdateState.UpToDate -> {
            Text("已是最新版本", style = MaterialTheme.typography.bodySmall, color = cs.primary)
        }
        is MainViewModel.UpdateState.Failed -> {
            Text(state.message, style = MaterialTheme.typography.bodySmall, color = cs.error)
        }
        is MainViewModel.UpdateState.Available -> {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "发现新版本 v${state.info.versionName}",
                    style = MaterialTheme.typography.titleSmall,
                    color = cs.primary
                )
                if (state.downloading) {
                    LinearProgressIndicator(
                        progress = { state.percent / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "正在下载 ${state.percent}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.outline
                    )
                } else {
                    val notes = state.info.notes
                    if (notes.isNotBlank()) {
                        Text(
                            notes.take(200),
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.outline
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.downloadUpdate(state.info) }) { Text("下载并安装") }
                        OutlinedButton(onClick = { vm.dismissUpdate() }) { Text("以后再说") }
                    }
                }
            }
        }
    }
}

/** 一行状态：左侧标签，右侧结论 + 圆点颜色（ok=true 绿、false 橙） */
@Composable
private fun StatusLine(label: String, value: String, ok: Boolean) {
    val cs = MaterialTheme.colorScheme
    val color = if (ok) okColor() else cs.error
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

/** 设置页的轻提示。用系统 Toast 而不是 Snackbar：这些操作常常伴随跳转到系统页 */
private fun Context.toast(msg: String) {
    android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    display: String,
    onChange: (Float) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.weight(1f))
        Text(display, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
    Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
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

private fun openReleases(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(UpdateApi.RELEASES_PAGE))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
