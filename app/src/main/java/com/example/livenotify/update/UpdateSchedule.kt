package com.example.livenotify.update

/**
 * 自动检查更新的节流规则。
 *
 * 需求是「启动后可以自动检查，但间隔 24 小时」——不是每次冷启动都打一次 GitHub 接口。
 * 手动点「检查更新」不受这个限制，直接查。
 */
object UpdateSchedule {

    /** 自动检查间隔：24 小时 */
    const val INTERVAL_MS = 24L * 60 * 60 * 1000

    /**
     * 这次启动要不要自动检查。
     *
     * @param lastCheckAt 上次检查成功的时刻；<=0 表示从未查过（装完第一次启动就查）
     * @param now 当前时刻
     */
    fun shouldAutoCheck(lastCheckAt: Long, now: Long): Boolean {
        if (lastCheckAt <= 0L) return true
        // 时钟回拨（用户改时间 / 时区跳变）会让 now - lastCheckAt 变成负数，
        // 这时候当成「还没到间隔」而不是「该查了」，否则会连着查好几遍。
        if (now < lastCheckAt) return false
        return now - lastCheckAt >= INTERVAL_MS
    }
}
