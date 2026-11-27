package com.example.livenotify

import com.example.livenotify.monitor.NetworkWake
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkWakeTest {
    private val now = 1_700_000_000L

    @Test
    fun `首次网络恢复且距上次检测够久则唤醒`() {
        assertTrue(
            NetworkWake.shouldWake(
                lastRunAt = now - 60_000L,
                lastWakeAt = 0L,
                now = now
            )
        )
    }

    @Test
    fun `距上次检测太近不唤醒`() {
        assertFalse(
            NetworkWake.shouldWake(
                lastRunAt = now - 5_000L, // 刚跑完 5 秒
                lastWakeAt = 0L,
                now = now
            )
        )
    }

    @Test
    fun `两次网络唤醒间隔不足冷却不唤醒`() {
        assertFalse(
            NetworkWake.shouldWake(
                lastRunAt = now - 120_000L, // 距检测够久
                lastWakeAt = now - 10_000L, // 但距上次唤醒才 10 秒
                now = now
            )
        )
    }

    @Test
    fun `冷却过后再次唤醒`() {
        assertTrue(
            NetworkWake.shouldWake(
                lastRunAt = now - 120_000L,
                lastWakeAt = now - NetworkWake.WAKE_COOLDOWN_MS, // 刚好到冷却
                now = now
            )
        )
    }

    @Test
    fun `时间回拨按允许唤醒处理`() {
        // 系统时间被往回调，差值变负——不能把用户永久卡在冷却里
        assertTrue(
            NetworkWake.shouldWake(
                lastRunAt = now + 100_000L,
                lastWakeAt = now + 100_000L,
                now = now
            )
        )
    }

    @Test
    fun `从未检测过允许唤醒`() {
        assertTrue(NetworkWake.shouldWake(lastRunAt = 0L, lastWakeAt = 0L, now = now))
    }
}
