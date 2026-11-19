package com.example.livenotify.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.livenotify.data.Settings
import kotlin.math.roundToInt

/**
 * 检测详细设置：四项节奏参数的逐项调节。
 *
 * 入口在设置页「检测」卡的「详细设置」。这里不做档位选择——那是上一级的事，
 * 只负责单点微调；改完任意一项，上一级会自动显示成「自定义」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetectDetailScreen(
    vm: MainViewModel,
    onBack: () -> Unit
) {
    val settings by vm.settings.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("检测详细设置") },
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
                SettingsCard {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(
                            "当前：${presetLabel(settings)}",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "下面任意一项改动后，就变成「自定义」——上一级不会再高亮某个档位。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        TextButton(
                            onClick = { vm.setPreset(MonitorPreset.DEFAULT) },
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("恢复默认档")
                        }
                    }
                }
            }

            item { GroupTitle("检测节奏") }
            item { DetectTuningCard(settings = settings, vm = vm) }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

/** 四个参数合在一张卡里，顺序与「越靠前影响越大」一致 */
@Composable
private fun DetectTuningCard(settings: Settings, vm: MainViewModel) {
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
