package com.example.livenotify.ui

import java.util.Calendar

/** 「全部」对应的回溯跨度：2100 天 ≈ 5.75 年，稳妥落在服务端可接受的下限之内 */
internal const val ALL_SPAN_MS = 2100L * 24L * 3600L * 1000L

/**
 * 把「近 N 天」换算成服务端要的毫秒时间窗口。
 *
 * 按自然日对齐：近 30 天 = 今天 + 前 29 天。以前是减 30 天，实际覆盖了 31 个自然日，
 * 页面上写着「近 30 天」而数字多算了一整天。
 *
 * days <= 0 表示「全部」。服务端对时间范围有下限（2020-01-01 起可用，startTime=0 会直接报错），
 * 所以这里用滚动的约 5.7 年窗口，既覆盖绝大部分历史，也不会随时间推移撞上边界。
 */
internal fun analysisRange(days: Int, now: Long = System.currentTimeMillis()): Pair<Long, Long> {
    if (days <= 0) return (now - ALL_SPAN_MS) to now
    val cal = Calendar.getInstance().apply {
        timeInMillis = now
        add(Calendar.DAY_OF_YEAR, -(days - 1).coerceAtLeast(0))
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return cal.timeInMillis to now
}
