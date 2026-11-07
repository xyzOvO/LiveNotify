package com.example.livenotify.monitor

/**
 * 开播提醒「发送失败 → 待补发 → 成功或放弃」的判据。
 *
 * 为什么需要它：通知被拦下时（应用通知总开关关闭、或「开播提醒」这个渠道被单独关掉），
 * `notifyLive()` 返回 false，但这一轮检测已经把 `isLive` 写成 true 了。下一轮状态机看到
 * 「本来就在播、场次 key 没变」，按防重复规则不再给通知候选——于是这一场永远提醒不到，
 * 用户后来把权限打开也没用。所以要留一条**持久化**的待办，每轮补试。
 *
 * 有效期是刻意的：开播提醒的价值是「现在能去看」，隔了半小时再弹「主播开播了」更像打扰，
 * 用户点进去可能都已经下播了。过期就放弃，不再补。
 *
 * 纯函数，边界都锁在 `PendingNotifyTest` 里。
 */
internal object PendingNotify {

    /** 补发有效期：超过 30 分钟就放弃 */
    const val MAX_AGE_MS = 30 * 60_000L

    /** 短于这个时长不额外说明「已开播多久」，否则每句都带一句废话 */
    const val LATE_HINT_MS = 3 * 60_000L

    enum class Verdict {
        /** 没有待办 */
        NONE,

        /** 同一场、还没过期，可以再试一次 */
        RETRY,

        /** 换场了或已经过期，应该清掉 */
        STALE
    }

    /**
     * @param pendingSession 待办记下的场次 key（秒），0 表示平台不给开播时间
     * @param pendingSince 第一次发送失败的时刻（毫秒），0 表示没有待办
     * @param sessionKey 本轮检测到的场次 key（秒）
     */
    fun check(pendingSession: Long, pendingSince: Long, sessionKey: Long, now: Long): Verdict {
        if (pendingSince <= 0L) return Verdict.NONE
        // 平台不给开播时间时 sessionKey 恒为 0，没法用它分辨场次，只能靠有效期兜底
        val sameSession = pendingSession <= 0L || sessionKey <= 0L || pendingSession == sessionKey
        if (!sameSession) return Verdict.STALE
        val age = now - pendingSince
        // age < 0 说明系统时间被往回调过。继续重试会永远等不到过期，直接放弃更安全
        if (age < 0L || age > MAX_AGE_MS) return Verdict.STALE
        return Verdict.RETRY
    }

    /**
     * 补发时告诉用户「这条不是刚开播」，别让他以为主播刚刚上播。
     * 不足 [LATE_HINT_MS] 返回 null，正常实时提醒不该出现这句。
     */
    fun lateHint(lateMs: Long): String? {
        if (lateMs < LATE_HINT_MS) return null
        val minutes = (lateMs / 60_000L).coerceAtLeast(1L)
        return "已开播约 $minutes 分钟"
    }
}
