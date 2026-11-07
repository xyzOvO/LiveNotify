package com.example.livenotify.monitor

import com.example.livenotify.data.StreamerEntity
import com.example.livenotify.platform.LiveState
import com.example.livenotify.platform.Snapshot

/**
 * 「恢复关注」时把状态初始化成当前快照的基准。
 *
 * 纯函数，方便锁住最容易回归的边界：暂停期间主播可能下播又重开，
 * 若沿用暂停前的 liveStartAt / lastNotifiedLiveStart，引擎下一轮会因为
 * 场次 key 变化判定「新场次」，立刻补推一条早就开播的提醒。
 *
 * 因此恢复后必须把当前场次同时写进 liveStartAt 与 lastNotifiedLiveStart
 * （= 本场已算通知过），并重置冷却时间。用户要的是「从现在起继续关注」，
 * 不是「把暂停期间发生的事补报一遍」。
 */
internal fun resumeBaseline(
    s: StreamerEntity,
    snap: Snapshot,
    now: Long,
    /** 导入备份时沿用备份里的暂停状态，默认用于「恢复关注」 */
    enabled: Boolean = true
): StreamerEntity {
    val sessionKey = snap.liveStartAt
    val nowLive = snap.state == LiveState.LIVE
    return s.copy(
        enabled = enabled,
        nickname = snap.nickname.ifBlank { s.nickname },
        avatarUrl = snap.avatarUrl ?: s.avatarUrl,
        title = snap.title.ifBlank { s.title },
        note = snap.note?.takeIf { it.isNotBlank() } ?: "",
        roomUrl = snap.roomUrl,
        displayId = snap.displayId,
        isLive = nowLive,
        liveStreak = if (nowLive) 1 else 0,
        liveStartAt = if (nowLive) sessionKey else 0L,
        lastLiveStartAt = if (sessionKey > 0L) sessionKey else s.lastLiveStartAt,
        lastNotifiedLiveStart = sessionKey,
        lastNotifiedAt = now,
        // 恢复关注 / 导入时不该把暂停期间攒下的待办补报一遍
        pendingNotifySession = 0L,
        pendingNotifySince = 0L,
        lastCheckedAt = now,
        missing = false,
        errorCount = 0,
        lastError = null
    )
}
