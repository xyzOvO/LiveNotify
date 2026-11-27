package com.example.livenotify.monitor

/**
 * 开播通知 / 测试通知的 id 与场次种子规则。
 *
 * Android 的 NotificationManager.notify(id, ...) 对同一个 id 是「更新已有通知」，
 * 不保证再次响铃/震动。开播提醒必须每场都是新通知，所以 id 由「主播 + 本场场次」
 * 共同决定：同一场重试复用 id，避免重复弹；不同场次换 id，保证必然 alert。
 *
 * 纯 Kotlin，无 Android 依赖，便于 JVM 单测。
 */
object NotificationPolicy {

    /** 常驻通知固定占用 id=1，这里所有通知 id 都必须避开。 */
    const val MONITOR_ID = 1

    /** 通知 id 取低 31 位，保证恒为正数（Android 允许负数，但没必要自找麻烦）。 */
    private const val POSITIVE_INT_MASK = 0x7FFFFFFFL

    /**
     * 开播通知 id。
     *
     * sessionSeed 是当前直播场次的稳定标识：优先用平台给的开播时刻，
     * 平台没给时由 [notifySeed] 兜底成本场的某个固定时刻。
     * 用正数 int，和 streamerId 混合后避开常驻 id，避免两条通知互相覆盖。
     */
    fun liveNotificationId(streamerId: Long, sessionSeed: Long): Int {
        val mixed = streamerId * 31L + sessionSeed
        val positive = (mixed and POSITIVE_INT_MASK).toInt()
        return if (positive == MONITOR_ID) MONITOR_ID + 1 else positive
    }

    /** 测试通知 id：用当前时间种子，每次点「发送测试通知」都是新 id，保证听得到。 */
    fun testNotificationId(seed: Long): Int {
        val positive = (seed and POSITIVE_INT_MASK).toInt()
        return if (positive == MONITOR_ID) MONITOR_ID + 1 else positive
    }

    /**
     * 场次种子：给 [liveNotificationId] 用的稳定值。
     *
     * - 平台给了开播时刻 → 直接用它，跨进程重启也一致；
     * - 平台没给（`liveStartAt <= 0`）→ 新场次用「本轮时刻」，补发重试沿用「首次失败时刻」，
     *   这样同一场重试仍是同一条通知，下一场必然换 id。**不能退化成 0**，
     *   否则同一主播第二场又会命中旧 id，只改文字不响铃（issue #3 的原始现象）。
     */
    fun notifySeed(sessionKey: Long, retrySince: Long, now: Long): Long = when {
        sessionKey > 0L -> sessionKey
        retrySince > 0L -> retrySince
        else -> now
    }

    /**
     * 确认下播后是否要主动撤掉开播通知。
     *
     * 只有「上一状态在播 + 本轮确认不在播 + 确实发过通知」三件同时成立才撤：
     * - 单次失败 / Unknown 时 confirmedLive 仍为 true，不会误撤正在播的通知；
     * - 从没发过通知（种子为 0）时没有可撤的条目。
     *
     * 纯函数，便于 JVM 单测锁边界。
     */
    fun shouldCancelLiveNotification(
        wasLive: Boolean,
        confirmedLive: Boolean,
        lastNotifiedSeed: Long
    ): Boolean = wasLive && !confirmedLive && lastNotifiedSeed > 0L
}
