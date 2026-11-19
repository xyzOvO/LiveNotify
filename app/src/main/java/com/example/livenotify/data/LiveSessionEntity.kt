package com.example.livenotify.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一场直播的历史记录（开播 → 下播）。
 *
 * 与 [StreamerEntity] 的「当前是否开播」不同，这里存的是长期留档的一场场会话：
 * 开播时建一条，下播时补下播时刻，之后可回看。昵称/标题是当时的快照——主播可能改名或
 * 改标题，历史记录应保持「当时的样子」，不跟着当前状态变。
 */
@Entity(
    tableName = "live_sessions",
    indices = [
        Index(value = ["anchorId", "platformId"]),
        Index(value = ["endAtMs"])
    ]
)
data class LiveSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val platformId: String,
    val anchorId: String,
    val nickname: String,
    /** 平台给出的本场开播时刻（秒）；0 表示平台没给 */
    val startAtSec: Long = 0L,
    /** 本地首次发现开播的时刻（毫秒），用于排序与时长兜底 */
    val startedAtMs: Long,
    /** 下播时刻（毫秒）；0 表示仍在进行中 */
    val endAtMs: Long = 0L,
    /** 本场最后观察到的标题 */
    val title: String = "",
    /** 是否已成功推送开播提醒 */
    val notified: Boolean = false,
    /** 是否发生过推送失败（权限/渠道被关），用于历史里标注「未送达」 */
    val notifyFailed: Boolean = false
) {
    /** 直播时长（毫秒）；下播用 end-start，进行中用 now-start。用本地时刻兜底 startAtSec=0 的情况 */
    fun durationMs(now: Long): Long {
        val end = if (endAtMs > 0L) endAtMs else now
        return (end - startedAtMs).coerceAtLeast(0L)
    }
}
