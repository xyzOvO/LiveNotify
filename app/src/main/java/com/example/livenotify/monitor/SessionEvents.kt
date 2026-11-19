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
