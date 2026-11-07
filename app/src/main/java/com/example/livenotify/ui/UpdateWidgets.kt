package com.example.livenotify.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.livenotify.util.Format

/**
 * 更新相关的两个小部件。
 * 主页弹窗和设置页都要用，放一份在这里，免得两边各写一遍、文案还慢慢跑偏。
 */

/** Release 说明：限高可滚动，几十行也不会把弹窗/页面顶长 */
@Composable
internal fun NotesBox(text: String, maxHeight: Dp = 220.dp) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(10.dp),
        // 浅色主题下 surfaceVariant 和卡片底几乎同色，不描一道边就看不出这是「引用块」
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    ) {
        Text(
            text.ifBlank { "暂无更新说明，可到 Releases 页查看完整内容。" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .heightIn(max = maxHeight)
                .verticalScroll(rememberScrollState())
                .padding(10.dp)
        )
    }
}

/** 「更新包 2.5 MB · 覆盖安装，关注列表不会丢」；兜底路径拿不到大小时只留后半句 */
internal fun packageLine(sizeBytes: Long): String {
    val size = Format.size(sizeBytes)
    return if (size.isEmpty()) "覆盖安装，关注列表不会丢" else "更新包 $size · 覆盖安装，关注列表不会丢"
}

/** 「正在下载 37% · 3.1 MB / 8.4 MB」；拿不到总大小时只说百分比 */
internal fun progressText(percent: Int, done: Long, total: Long): String {
    val doneText = Format.size(done)
    val totalText = Format.size(total)
    return if (doneText.isNotEmpty() && totalText.isNotEmpty()) {
        "正在下载 $percent% · $doneText / $totalText"
    } else {
        "正在下载 $percent%"
    }
}
