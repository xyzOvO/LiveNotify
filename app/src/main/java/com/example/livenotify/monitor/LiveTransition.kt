package com.example.livenotify.monitor

/**
 * 单次开播状态变化的结果。
 *
 * 独立成纯函数，避免 Android/Room 依赖，方便把「换场次、连续确认、同场去重」
 * 这些最容易回归的边界锁进单元测试。
 */
internal data class LiveTransition(
    val streak: Int,
    val confirmedLive: Boolean,
    val notificationCandidate: Boolean
)

internal fun evaluateLiveTransition(
    wasLive: Boolean,
    previousStreak: Int,
    previousSessionKey: Long,
    sessionKey: Long,
    lastNotifiedLiveStart: Long,
    nowLive: Boolean,
    confirmTimes: Int
): LiveTransition {
    if (!nowLive) return LiveTransition(streak = 0, confirmedLive = false, notificationCandidate = false)

    val hasSessionKey = sessionKey > 0L
    val sessionChanged = hasSessionKey &&
        previousSessionKey > 0L &&
        sessionKey != previousSessionKey

    // 未确认前 isLive 仍是 false；如果场次 key 没变，下一轮应继续累计，而不是从 1 重来。
    val continuesUnconfirmedObservation = !wasLive &&
        previousStreak > 0 &&
        (!hasSessionKey || previousSessionKey == sessionKey)

    val continuesCurrentSession = !sessionChanged &&
        (wasLive || continuesUnconfirmedObservation)

    val streak = if (continuesCurrentSession) {
        if (previousStreak == Int.MAX_VALUE) Int.MAX_VALUE else previousStreak + 1
    } else {
        1
    }
    val threshold = confirmTimes.coerceAtLeast(1)
    val confirmedLive = if (sessionChanged) {
        streak >= threshold
    } else {
        wasLive || streak >= threshold
    }
    val alreadyNotified = hasSessionKey && lastNotifiedLiveStart == sessionKey
    val notificationCandidate = (sessionChanged || !wasLive) &&
        streak >= threshold &&
        !alreadyNotified

    return LiveTransition(
        streak = streak,
        confirmedLive = confirmedLive,
        notificationCandidate = notificationCandidate
    )
}
