package com.example.livenotify

import com.example.livenotify.monitor.FailureBackoff
import com.example.livenotify.monitor.PollSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 单主播连续失败退避：阈值前不跳过、失败越多间隔越长、上限封顶、时间回拨不卡死。 */
class FailureBackoffTest {

    private val base = PollSchedule.MIN_MS // 用 30s 作基础间隔便于换算

    @Test
    fun `阈值以下从不跳过`() {
        for (n in 0 until FailureBackoff.THRESHOLD) {
            assertFalse(FailureBackoff.shouldSkip(n, lastCheckedAt = 1_000L, now = 1_001L, base))
        }
    }

    @Test
    fun `达到阈值后间隔内跳过 间隔到了恢复`() {
        val n = FailureBackoff.THRESHOLD
        val last = 1_000L
        // n=阈值 → steps=1 → gap=base(30s)；29 秒内跳过，30 秒后恢复
        assertTrue(FailureBackoff.shouldSkip(n, last, last + base - 1, base))
        assertFalse(FailureBackoff.shouldSkip(n, last, last + base, base))
        // 失败次数越多间隔越长：n=阈值+1 → 2*base
        assertTrue(FailureBackoff.shouldSkip(n + 1, last, last + base, base))
        assertFalse(FailureBackoff.shouldSkip(n + 1, last, last + 2 * base, base))
    }

    @Test
    fun `退避间隔有上限`() {
        val huge = FailureBackoff.THRESHOLD + 500
        // 即使失败几百次，间隔也封顶 30 分钟：刚查完 1 分钟仍会跳过，但 31 分钟后一定恢复
        assertTrue(
            FailureBackoff.shouldSkip(
                huge, lastCheckedAt = 0L + 1L,
                now = 1L + 60_000L, baseIntervalMs = base
            )
        )
        assertFalse(
            FailureBackoff.shouldSkip(
                huge, lastCheckedAt = 1L,
                now = 1L + FailureBackoff.MAX_GAP_MS + 1, baseIntervalMs = base
            )
        )
    }

    @Test
    fun `从未查过与时间回拨都不卡死`() {
        assertFalse(FailureBackoff.shouldSkip(99, lastCheckedAt = 0L, now = 100L, base))
        // 系统时间被往回调：elapsed<0，应放行重试而不是永远退避
        assertFalse(FailureBackoff.shouldSkip(99, lastCheckedAt = 5_000L, now = 1_000L, base))
    }

    @Test
    fun `后台异常退避指数增长且封顶`() {
        assertEquals(PollSchedule.ERROR_BACKOFF_BASE_MS, PollSchedule.errorBackoffMs(0))
        assertEquals(60_000L, PollSchedule.errorBackoffMs(1))
        assertEquals(120_000L, PollSchedule.errorBackoffMs(2))
        assertEquals(240_000L, PollSchedule.errorBackoffMs(3))
        assertEquals(PollSchedule.ERROR_BACKOFF_MAX_MS, PollSchedule.errorBackoffMs(4))
        assertEquals(PollSchedule.ERROR_BACKOFF_MAX_MS, PollSchedule.errorBackoffMs(100))
    }
}
