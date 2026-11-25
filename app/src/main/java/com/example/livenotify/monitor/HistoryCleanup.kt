package com.example.livenotify.monitor

/**
 * 历史会话清理的节流口径。
 *
 * 以前只在 MainViewModel 冷启动时清一次：服务常驻、App 长期不重启时，
 * 90 天前的会话会一直留在库里。改由每轮检测顺带触发，再用 [INTERVAL_MS]
 * 节流，避免每 60s 一轮都发一次 DELETE。
 */
internal object HistoryCleanup {
    /** 保留多久：90 天（与历史页口径一致） */
    const val RETENTION_MS = 90L * 24 * 60 * 60 * 1000

    /** 两次清理的最小间隔：6 小时 */
    const val INTERVAL_MS = 6L * 60 * 60 * 1000

    /**
     * 是否到了该清理的时刻。
     * lastAt<=0（从未清理）或距上次清理已达 [INTERVAL_MS] 时为 true。
     * now 回拨（< lastAt）返回 false，避免系统时间调整导致反复清理。
     */
    fun due(lastAt: Long, now: Long): Boolean =
        lastAt <= 0L || (now - lastAt) >= INTERVAL_MS
}
