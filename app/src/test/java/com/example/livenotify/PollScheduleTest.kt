package com.example.livenotify

import com.example.livenotify.data.Settings
import com.example.livenotify.monitor.NetworkKind
import com.example.livenotify.monitor.PollSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置页会把这个结果直接念给用户听（「现在每 6 分钟检测一次」），
 * 所以边界值必须钉住：倍数叠加、30 分钟上限、仅 Wi-Fi 暂停。
 */
class PollScheduleTest {

    private fun cfg(
        interval: Int = 120,
        factor: Int = 3,
        wifiOnly: Boolean = false
    ) = Settings(pollIntervalSec = interval, mobileFactor = factor, wifiOnly = wifiOnly)

    @Test
    fun `WiFi 下直接用基础间隔`() {
        val p = PollSchedule.plan(cfg(), NetworkKind.WIFI, quiet = false)
        assertEquals(120, p.effectiveSec)
        assertEquals(720, p.roundsPerDay)
        assertFalse(p.throttled)
        assertFalse(p.paused)
    }

    @Test
    fun `移动网络按倍数放大`() {
        val p = PollSchedule.plan(cfg(), NetworkKind.CELLULAR, quiet = false)
        assertEquals(360, p.effectiveSec)
        assertTrue(p.throttled)
        assertEquals(240, p.roundsPerDay)
    }

    @Test
    fun `免打扰再叠 5 倍`() {
        val p = PollSchedule.plan(cfg(), NetworkKind.WIFI, quiet = true)
        assertEquals(600, p.effectiveSec)
    }

    @Test
    fun `移动网络加免打扰不会突破 30 分钟上限`() {
        // 300s × 6 × 5 = 9000s，必须收敛到 1800s，否则界面会写出「每 2.5 小时」
        val p = PollSchedule.plan(
            cfg(interval = 300, factor = 6),
            NetworkKind.CELLULAR,
            quiet = true
        )
        assertEquals(1800, p.effectiveSec)
        assertTrue(p.clamped)
    }

    @Test
    fun `仅 WiFi 在移动网络下暂停但仍算出间隔`() {
        val p = PollSchedule.plan(cfg(wifiOnly = true), NetworkKind.CELLULAR, quiet = false)
        assertTrue(p.paused)
        assertEquals(360, p.effectiveSec)
    }

    @Test
    fun `判断不出网络类型时按非 WiFi 处理`() {
        val p = PollSchedule.plan(cfg(wifiOnly = true), NetworkKind.OTHER, quiet = false)
        assertTrue("未知网络也要暂停，不能在用户以为不耗流量时联网", p.paused)
    }

    @Test
    fun `间隔下限 30 秒`() {
        val p = PollSchedule.plan(cfg(interval = 10, factor = 1), NetworkKind.WIFI, quiet = false)
        assertEquals(30, p.effectiveSec)
        assertTrue(p.clamped)
    }

    @Test
    fun `免打扰区间跨零点`() {
        // 23:00 → 08:00
        assertTrue(PollSchedule.inQuietWindow(23 * 60, 8 * 60, 23 * 60 + 30))
        assertTrue(PollSchedule.inQuietWindow(23 * 60, 8 * 60, 7 * 60 + 59))
        assertFalse(PollSchedule.inQuietWindow(23 * 60, 8 * 60, 8 * 60))
        assertFalse(PollSchedule.inQuietWindow(23 * 60, 8 * 60, 12 * 60))
    }

    @Test
    fun `免打扰区间不跨零点`() {
        assertTrue(PollSchedule.inQuietWindow(1 * 60, 6 * 60, 2 * 60))
        assertFalse(PollSchedule.inQuietWindow(1 * 60, 6 * 60, 7 * 60))
    }

    @Test
    fun `起止相同视为不生效`() {
        assertFalse(PollSchedule.inQuietWindow(9 * 60, 9 * 60, 9 * 60))
    }

    // ── 固定周期：下一轮开始于「本轮开始 + 周期」，与检测耗时无关 ──

    private val period = 120_000L

    @Test
    fun `检测很快时下一轮仍是完整的周期`() {
        val start = 1_000_000L
        assertEquals(
            start + period,
            PollSchedule.nextTickAt(start, period, nowMs = start + 30_000L)
        )
    }

    @Test
    fun `检测耗时被算进周期里`() {
        val start = 1_000_000L
        // 检测用了 90 秒、100 秒、119 秒，下一轮开始时刻都是 start + 120 秒
        for (took in listOf(90_000L, 100_000L, 119_999L)) {
            assertEquals(
                "耗时 $took 不应影响下一轮开始时刻",
                start + period,
                PollSchedule.nextTickAt(start, period, nowMs = start + took)
            )
        }
    }

    @Test
    fun `单轮超过周期时跳过错过的那一轮而不是立刻补跑`() {
        val start = 1_000_000L
        // 检测用了 150 秒：start + 120 这个格点已经过去，下一轮应是 start + 240
        assertEquals(
            start + period * 2,
            PollSchedule.nextTickAt(start, period, nowMs = start + 150_000L)
        )
        // 用了 400 秒：跳过 3 个格点，直接对齐到 start + 480
        assertEquals(
            start + period * 4,
            PollSchedule.nextTickAt(start, period, nowMs = start + 400_000L)
        )
    }

    @Test
    fun `恰好用满一个周期时顺延到下一格点`() {
        val start = 1_000_000L
        assertEquals(
            start + period * 2,
            PollSchedule.nextTickAt(start, period, nowMs = start + period)
        )
    }

    @Test
    fun `系统时间被调回也不返回过去的时刻`() {
        val start = 1_000_000L
        // now 比 tickStart 还早（用户改了系统时间）：仍按 start + period 排下一轮
        assertEquals(
            start + period,
            PollSchedule.nextTickAt(start, period, nowMs = start - 600_000L)
        )
    }

    @Test
    fun `周期超出上下限时先收敛再排下一轮`() {
        val start = 1_000_000L
        // 1 秒的周期会被抬到 30 秒，否则会疯狂打接口
        assertEquals(
            start + PollSchedule.MIN_MS,
            PollSchedule.nextTickAt(start, periodMs = 1_000L, nowMs = start + 2_000L)
        )
        // 1 小时的周期会被压到 30 分钟
        assertEquals(
            start + PollSchedule.MAX_MS,
            PollSchedule.nextTickAt(start, periodMs = 3_600_000L, nowMs = start + 1_000L)
        )
    }

    // ── 推送冷却：系统时间被改动时不能把提醒卡死 ──

    @Test
    fun `冷却按分钟生效`() {
        val now = 10_000_000L
        assertFalse(PollSchedule.cooldownPassed(now - 5 * 60_000L, now, 10))
        assertTrue(PollSchedule.cooldownPassed(now - 10 * 60_000L, now, 10))
        assertTrue("0 分钟表示不限制", PollSchedule.cooldownPassed(now - 1L, now, 0))
        assertTrue("从未推送过", PollSchedule.cooldownPassed(0L, now, 10))
    }

    @Test
    fun `系统时间被调回时按已冷却处理`() {
        val now = 10_000_000L
        // 上次推送的时间戳落在「未来」：差值负数。若不加处理会一直不提醒
        assertTrue(PollSchedule.cooldownPassed(now + 3 * 3_600_000L, now, 10))
    }
}
