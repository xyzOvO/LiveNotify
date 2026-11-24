package com.example.livenotify.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.livenotify.Graph
import com.example.livenotify.monitor.NotificationHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 平台健康诊断页：展示已有数据（各平台失败数、最近检测、网络），
 * 无数据来源的项明确标注「暂不支持」；一键复制的报告天然不含 Cookie/令牌。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticScreen(vm: MainViewModel, onBack: () -> Unit) {
    val list by vm.streamers.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val net = rememberNetSnapshot()
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    var showReport by remember { mutableStateOf(false) }

    val health = remember(list) { platformHealth(list) }
    val engine = Graph.engine
    val lastRun = engine.lastRunAt
    val duration = engine.lastDurationMs
    val skip = engine.lastSkip.collectAsStateWithLifecycle().value
    val networkLabel = when {
        settings.wifiOnly && net.kind != com.example.livenotify.monitor.NetworkKind.WIFI -> "等待 Wi-Fi"
        !net.online -> "离线"
        else -> when (net.kind) {
            com.example.livenotify.monitor.NetworkKind.WIFI -> "Wi-Fi"
            com.example.livenotify.monitor.NetworkKind.CELLULAR -> "移动网络"
            else -> "未知网络"
        }
    }
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val report = remember(list, lastRun, duration, skip, networkLabel) {
        buildDiagnosticReport(health, lastRun, duration, skip, networkLabel)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("平台健康诊断") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.SpaceL, vertical = Dimens.SpaceS),
                    shape = Dimens.ShapeMedium,
                    colors = CardDefaults.cardColors(containerColor = cardSurface(0.5f))
                ) {
                    Column(Modifier.padding(Dimens.SpaceM)) {
                        Text("运行状态", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(Dimens.SpaceS))
                        Info("最近检测", if (lastRun > 0) fmt.format(Date(lastRun)) else "尚未检测")
                        Info("上轮耗时", if (duration > 0) formatRound(duration) else "—")
                        Info("网络", networkLabel)
                        Info("检测跳过", when (skip) {
                            com.example.livenotify.monitor.MonitorEngine.SkipReason.WIFI_ONLY -> "等待 Wi-Fi"
                            com.example.livenotify.monitor.MonitorEngine.SkipReason.NO_NETWORK -> "等待网络"
                            null -> "否"
                        })
                        Info("通知权限", if (NotificationHelper.canNotify(context)) "已开启" else "未开启")
                    }
                }
            }
            item { Text("各平台状态", style = MaterialTheme.typography.labelLarge,
                color = cs.primary, modifier = Modifier.padding(horizontal = Dimens.SpaceL, vertical = Dimens.SpaceS)) }
            if (health.isEmpty()) {
                item { Text("暂无启用的关注", style = MaterialTheme.typography.bodySmall,
                    color = cs.outline, modifier = Modifier.padding(Dimens.SpaceL)) }
            } else {
                items(health, key = { it.platformId }) { h ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.SpaceL, vertical = Dimens.SpaceXS),
                        shape = Dimens.ShapeMedium,
                        colors = CardDefaults.cardColors(containerColor = cardSurface(0.5f))
                    ) {
                        Row(Modifier.padding(Dimens.SpaceM), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(h.displayName, style = MaterialTheme.typography.titleSmall)
                                Text("${h.streamers} 位 · 最近 ${if (h.lastCheckedAt > 0) fmt.format(Date(h.lastCheckedAt)) else "—"}",
                                    style = MaterialTheme.typography.labelSmall, color = cs.outline)
                            }
                            val color = if (h.failed > 0) StatusColors.warning() else StatusColors.success()
                            Text(
                                if (h.failed > 0) "${h.failed} 项失败" else "正常",
                                style = MaterialTheme.typography.labelSmall, color = color,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
            item {
                Column(Modifier.padding(Dimens.SpaceL)) {
                    OutlinedButton(onClick = { showReport = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("查看 / 复制诊断报告")
                    }
                    Spacer(Modifier.height(Dimens.SpaceS))
                    Text(
                        "「最近请求耗时」「错误类型」「下次预计重试时间」暂无独立数据来源，未虚构展示；报告已脱敏，不含 Cookie 与令牌。",
                        style = MaterialTheme.typography.labelSmall,
                        color = StatusColors.muted()
                    )
                }
            }
        }
    }

    if (showReport) {
        AlertDialog(
            onDismissRequest = { showReport = false },
            title = { Text("诊断报告（已脱敏）") },
            // 报告随启用平台数增长，不限高会把「复制/关闭」挤出屏幕
            text = { NotesBox(report, maxHeight = 320.dp) },
            confirmButton = {
                TextButton(onClick = {
                    val cm = context.getSystemService(ClipboardManager::class.java)
                    cm?.setPrimaryClip(ClipData.newPlainText("诊断报告", report))
                    // 复制后直接关窗，不给反馈用户不知道成没成
                    android.widget.Toast.makeText(context, "诊断报告已复制", android.widget.Toast.LENGTH_SHORT).show()
                    showReport = false
                }) { Text("复制") }
            },
            dismissButton = { TextButton(onClick = { showReport = false }) { Text("关闭") } }
        )
    }
}

@Composable
private fun Info(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.weight(0.4f))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.6f))
    }
}
