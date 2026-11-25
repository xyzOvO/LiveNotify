package com.example.livenotify.monitor

import com.example.livenotify.data.StreamerEntity

/**
 * 开播历史会话的状态变化判定（零 Android/Room 依赖，单测可跑）。
 *
 * 引擎据此决定建会话 / 收口会话。抽成纯函数是为了锁住「换场」这一最易回归的边界：
 * 换场必须先把旧会话收口再建新会话，否则一场直播会被并成一条、时长全错。
 */
internal enum class SessionEvent {
    /** 保持现状，不动历史 */
    NONE,
    /** 开播：新建一条会话 */
    OPEN,
    /** 下播：收口当前会话 */
    CLOSE,
    /** 换场：先收口旧会话，再新建一条 */
    SWITCH
}

/**
 * 收口某场历史会话时该写入的标题。
 *
 * 不能无条件拿本轮快照的标题：
 * - **换场**时快照已经是「下一场」的，写进去会把上一场的历史标题覆盖成新标题。
 *   用户可见症状：以前的直播标题全变成当前标题，前一场的原标题凭空消失；
 * - **下播**时离线页返回的仍是这一场的标题，可作为会话标题为空时的兜底
 *   （例如开播那一刻标题正好为空）。
 *
 * 直播中标题由 `LiveSessionDao.updateTitle` 逐轮跟进，收口这里只负责别写坏。
 */
internal fun closingSessionTitle(
    sessionTitle: String,
    snapshotTitle: String,
    event: SessionEvent
): String = when {
    // 换场：快照属于下一场，一个字都不能回填
    event == SessionEvent.SWITCH -> sessionTitle
    sessionTitle.isNotBlank() -> sessionTitle
    else -> snapshotTitle
}

internal fun sessionEvent(
    before: StreamerEntity,
    afterIsLive: Boolean,
    sessionKey: Long
): SessionEvent {
    val wasLive = before.isLive
    val switched = afterIsLive && wasLive && sessionKey > 0L &&
        before.liveStartAt > 0L && before.liveStartAt != sessionKey
    return when {
        switched -> SessionEvent.SWITCH
        afterIsLive && !wasLive -> SessionEvent.OPEN
        !afterIsLive && wasLive -> SessionEvent.CLOSE
        else -> SessionEvent.NONE
    }
}
