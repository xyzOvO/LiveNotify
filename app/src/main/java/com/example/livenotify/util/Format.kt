package com.example.livenotify.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Format {
    /** 毫秒时间戳 → "10-23 15:26" */
    fun dateTime(ts: Long): String =
        if (ts <= 0L) "—" else SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))

    /** 毫秒时间戳 → "15:26" */
    fun clock(ts: Long): String =
        if (ts <= 0L) "—" else SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))

    /** 相对时间（入参毫秒） */
    fun relative(ts: Long): String {
        if (ts <= 0L) return "尚未检测"
        val diff = System.currentTimeMillis() - ts
        return when {
            diff < 60_000 -> "刚刚"
            diff < 3_600_000 -> "${diff / 60_000} 分钟前"
            diff < 24 * 3_600_000 -> "${diff / 3_600_000} 小时前"
            diff < 48 * 3_600_000 -> "昨天 " + clock(ts)
            else -> dateTime(ts)
        }
    }

    /** 相对时间（入参秒，平台接口多为秒级时间戳） */
    fun relativeSec(sec: Long): String {
        if (sec <= 0L) return "暂无记录"
        return relative(sec * 1000L)
    }

    /**
     * 「上次开播」专用：始终给出"距今多久"，超过 30 天才退回绝对日期。
     * 与 [relative] 分开，是因为后者在 48 小时以上就返回日期串，会和并排显示的日期重复。
     */
    fun sinceSec(sec: Long): String {
        if (sec <= 0L) return "暂无记录"
        val diff = System.currentTimeMillis() - sec * 1000L
        return when {
            diff < 60_000L -> "刚刚"
            diff < 3_600_000L -> "${diff / 60_000L} 分钟前"
            diff < 24 * 3_600_000L -> "${diff / 3_600_000L} 小时前"
            diff < 30L * 24 * 3_600_000L -> "${diff / (24 * 3_600_000L)} 天前"
            else -> dateTime(sec * 1000L)
        }
    }

    /** 时长（毫秒差）→ "刚刚" / "23 分钟" / "1 小时 05 分" / "2 天 3 小时" */
    fun span(ms: Long): String {
        if (ms <= 0L) return "刚刚"
        val totalMin = ms / 60_000L
        return when {
            totalMin < 1 -> "刚刚"
            totalMin < 60 -> "$totalMin 分钟"
            totalMin < 24 * 60 -> {
                val h = totalMin / 60
                val m = totalMin % 60
                if (m == 0L) "$h 小时" else "$h 小时 $m 分"
            }
            else -> {
                val d = totalMin / (24 * 60)
                val h = (totalMin % (24 * 60)) / 60
                if (h == 0L) "$d 天" else "$d 天 $h 小时"
            }
        }
    }
}
