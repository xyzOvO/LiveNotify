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

/** 「更新包 2.5 MB · 发布于 2026-11-19 · 覆盖安装，关注列表不会丢」；缺哪项就省略哪项 */
internal fun packageLine(sizeBytes: Long, publishedAt: String = ""): String {
    val parts = mutableListOf<String>()
    val size = Format.size(sizeBytes)
    if (size.isNotEmpty()) parts.add("更新包 $size")
    val date = publishDate(publishedAt)
    if (date.isNotEmpty()) parts.add("发布于 $date")
    parts.add("覆盖安装，关注列表不会丢")
    return parts.joinToString(" · ")
}

/**
 * 把 GitHub 的 ISO 时间（`2026-11-19T16:05:47Z`）转成本地日期。
 *
 * 兜底路径（Releases 页 302）拿不到发布时间，传空串返回空串；
 * 解析失败也返回空串而不是把半截 ISO 字符串糊到界面上。
 */
internal fun publishDate(iso: String): String {
    if (iso.isBlank()) return ""
    return try {
        java.time.OffsetDateTime.parse(iso)
            .atZoneSameInstant(java.time.ZoneId.systemDefault())
            .toLocalDate()
            .toString()
    } catch (t: Throwable) {
        val head = iso.take(10)
        if (Regex("""\d{4}-\d{2}-\d{2}""").matches(head)) head else ""
    }
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
