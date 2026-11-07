package com.example.livenotify

import com.example.livenotify.monitor.PendingNotify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 待补发状态机的边界。
 *
 * 这组测试锁的是「通知被关掉一次之后，这一场还能不能提醒」：
 * 改造前只要 notifyLive() 返回 false，isLive 已经落库、场次去重又让下一轮不再是候选，
 * 于是一场直播永久漏提醒，用户后来打开权限也收不到。
 */
class PendingNotifyTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `没有待办时不补发`() {
        assertEquals(
            PendingNotify.Verdict.NONE,
            PendingNotify.check(pendingSession = 0L, pendingSince = 0L, sessionKey = 100L, now = now)
        )
    }

    @Test
    fun `同一场且未过期可以补发`() {
        assertEquals(
            PendingNotify.Verdict.RETRY,
            PendingNotify.check(
                pendingSession = 100L,
                pendingSince = now - 2 * 60_000L,
                sessionKey = 100L,
                now = now
            )
        )
    }

    @Test
    fun `超过有效期就放弃`() {
        assertEquals(
            PendingNotify.Verdict.STALE,
            PendingNotify.check(
                pendingSession = 100L,
                pendingSince = now - PendingNotify.MAX_AGE_MS - 1L,
                sessionKey = 100L,
                now = now
            )
        )
    }

    @Test
    fun `正好卡在有效期上仍可补发`() {
        // 差一秒就放弃，这一秒不该提前丢掉
        assertEquals(
            PendingNotify.Verdict.RETRY,
            PendingNotify.check(
                pendingSession = 100L,
                pendingSince = now - PendingNotify.MAX_AGE_MS,
                sessionKey = 100L,
                now = now
            )
        )
    }

    @Test
    fun `换了场次旧待办作废`() {
        // 上一场没发出去，但主播已经下播又重开：不能把上一场的待办补到新场次上
        assertEquals(
            PendingNotify.Verdict.STALE,
            PendingNotify.check(
                pendingSession = 100L,
                pendingSince = now - 60_000L,
                sessionKey = 200L,
                now = now
            )
        )
    }

    @Test
    fun `平台不给开播时间时也能补发`() {
        // 抖音等平台可能拿不到场次 key（恒为 0），不能因此认定「不是同一场」而放弃
        assertEquals(
            PendingNotify.Verdict.RETRY,
            PendingNotify.check(
                pendingSession = 0L,
                pendingSince = now - 60_000L,
                sessionKey = 0L,
                now = now
            )
        )
        // 待办里有场次、但这一轮拿不到：同样按同一场处理，靠有效期兜底
        assertEquals(
            PendingNotify.Verdict.RETRY,
            PendingNotify.check(
                pendingSession = 100L,
                pendingSince = now - 60_000L,
                sessionKey = 0L,
                now = now
            )
        )
    }

    @Test
    fun `系统时间往回调时不会永远重试`() {
        // 时间回拨后 age 是负数，若按「未过期」处理会一直补到真实时间追上来
        assertEquals(
            PendingNotify.Verdict.STALE,
            PendingNotify.check(
                pendingSession = 100L,
                pendingSince = now + 5 * 60_000L,
                sessionKey = 100L,
                now = now
            )
        )
    }

    @Test
    fun `补发超过三分钟才说明已开播多久`() {
        assertNull(PendingNotify.lateHint(0L))
        assertNull(PendingNotify.lateHint(PendingNotify.LATE_HINT_MS - 1L))
        assertEquals("已开播约 3 分钟", PendingNotify.lateHint(PendingNotify.LATE_HINT_MS))
        assertEquals("已开播约 12 分钟", PendingNotify.lateHint(12 * 60_000L))
    }
}
