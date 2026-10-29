package com.example.livenotify.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.livenotify.monitor.NotificationHelper
import com.example.livenotify.platform.PlatformRegistry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current

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
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(onClick = { requestIgnoreBattery(context) }) {
                        Text("关闭电池优化（保活）")
                    }
                    Text(
                        "国产 ROM 需在「自启动 / 后台运行」中手动放行本应用，否则进程被杀后无法检测。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
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

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    ElevatedCard(
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

private fun requestIgnoreBattery(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm != null && !pm.isIgnoringBatteryOptimizations(context.packageName)) {
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${context.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }
}
