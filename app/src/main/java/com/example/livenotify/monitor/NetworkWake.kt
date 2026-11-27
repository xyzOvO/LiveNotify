package com.example.livenotify.monitor

/**
 * 网络恢复唤醒的节流判定（纯函数，便于 JVM 单测）。
 *
 * 服务层的轮询循环用不可打断的 delay 等待下个周期：飞行模式切回、Wi-Fi 重连后
 * 要等满一个周期（真机 10 分钟，省电档更久）才会重新检测。网络恢复时提前唤醒
 * 能显著缩短这段「无网→有网」的盲区，但网络切换/抖动会在几秒内连续触发多次
 * onAvailable，若不节流就会疯狂打接口。两个门槛：
 *
 * - [MIN_SINCE_LAST_RUN_MS]：距上一次任意检测太近就不唤醒——刚跑完没意义；
 * - [WAKE_COOLDOWN_MS]：两次「网络唤醒」之间至少间隔这么久——挡住连续抖动。
 *
 * 时间回拨时按「允许唤醒」处理（和 PollSchedule.cooldownPassed 口径一致），
 * 避免把系统时间往回调的用户永久卡在冷却里。
 */
object NetworkWake {
    /** 两次网络唤醒检测之间至少 60 秒 */
    const val WAKE_COOLDOWN_MS = 60_000L

    /** 距上一次任意检测至少 15 秒；刚跑完就因网络抖动重跑没有意义 */
    const val MIN_SINCE_LAST_RUN_MS = 15_000L

    fun shouldWake(lastRunAt: Long, lastWakeAt: Long, now: Long): Boolean {
        if (lastRunAt > 0L) {
            val sinceRun = now - lastRunAt
            if (sinceRun in 0L until MIN_SINCE_LAST_RUN_MS) return false
        }
        if (lastWakeAt > 0L) {
            val sinceWake = now - lastWakeAt
            if (sinceWake in 0L until WAKE_COOLDOWN_MS) return false
        }
        return true
    }
}
