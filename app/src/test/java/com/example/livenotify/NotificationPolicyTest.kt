package com.example.livenotify

import com.example.livenotify.monitor.NotificationPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 开播提醒 / 测试通知的 id 规则。
 *
 * 回归对象：issue #3 中「通知栏更新出 XX主播正在直播，但没有铃声」。
 * Android 对相同 id 的 notify() 按“更新已有通知”处理，不保证再次 alert；
 * 因此同一主播的新场次必须换新 id，同一场次的补发重试才复用 id。
 *
 * 数据全部虚构，不放真实主播信息。
 */
class NotificationPolicyTest {

    @Test
    fun `同一主播不同场次使用不同开播通知 id`() {
        val first = NotificationPolicy.liveNotificationId(
            streamerId = 42L,
            sessionSeed = 1_700_000_000L
        )
        val second = NotificationPolicy.liveNotificationId(
            streamerId = 42L,
            sessionSeed = 1_700_000_120L
        )

        assertNotEquals(first, second)
    }

    @Test
    fun `同一主播同一场次重试保持同一个开播通知 id`() {
        val first = NotificationPolicy.liveNotificationId(
            streamerId = 42L,
            sessionSeed = 1_700_000_000L
        )
        val retry = NotificationPolicy.liveNotificationId(
            streamerId = 42L,
            sessionSeed = 1_700_000_000L
        )

        assertEquals(first, retry)
    }

    @Test
    fun `开播通知 id 不会占用常驻通知 id`() {
        val id = NotificationPolicy.liveNotificationId(
            streamerId = 1L,
            sessionSeed = 1_700_000_000L
        )

        assertNotEquals(1, id)
    }

    @Test
    fun `开播通知 id 恒为正数`() {
        // 低 32 位最高位为 1 的种子：旧实现用 `and Long.MAX_VALUE` 再 toInt 会溢出成负数
        val seeds = listOf(1_700_000_000L, 0x80000000L, 0xFFFFFFFFL, 4_294_967_296L)
        for (seed in seeds) {
            val id = NotificationPolicy.liveNotificationId(streamerId = 42L, sessionSeed = seed)
            assertTrue("id 必须为正数，实际 $id（seed=$seed）", id > 0)
            assertNotEquals(1, id)
        }
    }

    @Test
    fun `确认下播才撤通知 未知或未确认不撤`() {
        // 真在播 → 确认下播，且发过通知：撤
        assertTrue(
            NotificationPolicy.shouldCancelLiveNotification(
                wasLive = true, confirmedLive = false, lastNotifiedSeed = 1_700_000_000L
            )
        )
        // 单次失败/Unknown：confirmedLive 仍为 true，不撤（正在播）
        org.junit.Assert.assertFalse(
            NotificationPolicy.shouldCancelLiveNotification(
                wasLive = true, confirmedLive = true, lastNotifiedSeed = 1_700_000_000L
            )
        )
        // 本来就没在播：不撤
        org.junit.Assert.assertFalse(
            NotificationPolicy.shouldCancelLiveNotification(
                wasLive = false, confirmedLive = false, lastNotifiedSeed = 1_700_000_000L
            )
        )
        // 从没发过通知（种子为 0）：没有可撤的，不撤
        org.junit.Assert.assertFalse(
            NotificationPolicy.shouldCancelLiveNotification(
                wasLive = true, confirmedLive = false, lastNotifiedSeed = 0L
            )
        )
    }

    @Test
    fun `虎牙开播时刻为零 兜底种子大于零时撤销条件成立`() {
        // Bug1 回归：虎牙 liveStartAt=0 → sessionKey=0。通知用兜底种子( now )能响铃，
        // 但旧实现把 sessionKey=0 落库，撤销时 lastNotifiedSeed=0 → 撤不掉。
        // 修复后落库用 notifySeed（兜底后 >0），shouldCancel 三条件成立。
        val sessionKey = 0L
        val now = 1_800_000_999L
        val seeded = NotificationPolicy.notifySeed(
            sessionKey = sessionKey, retrySince = 0L, now = now
        )
        assertTrue("兜底种子必须 >0", seeded > 0L)
        assertTrue(
            "落库兜底种子后确认下播应撤通知",
            NotificationPolicy.shouldCancelLiveNotification(
                wasLive = true, confirmedLive = false, lastNotifiedSeed = seeded
            )
        )
        // 旧落库值 0 依然撤不掉——锁死「必须落兜底种子而非 sessionKey」这条
        org.junit.Assert.assertFalse(
            NotificationPolicy.shouldCancelLiveNotification(
                wasLive = true, confirmedLive = false, lastNotifiedSeed = sessionKey
            )
        )
    }

    @Test
    fun `连续测试通知使用不同 id`() {
        val first = NotificationPolicy.testNotificationId(1_700_000_000L)
        val second = NotificationPolicy.testNotificationId(1_700_000_001L)

        assertNotEquals(first, second)
    }

    @Test
    fun `测试通知 id 不会占用常驻通知 id`() {
        val id = NotificationPolicy.testNotificationId(1_700_000_000L)

        assertNotEquals(1, id)
    }

    @Test
    fun `场次种子优先用平台开播时刻`() {
        assertEquals(
            1_700_000_000L,
            NotificationPolicy.notifySeed(
                sessionKey = 1_700_000_000L,
                retrySince = 1_800_000_111L,
                now = 1_800_000_999L
            )
        )
    }

    @Test
    fun `平台不给开播时刻时补发沿用首次失败时刻`() {
        // 同一场重试必须稳定，否则每轮补发都换 id，用户会被反复通知
        assertEquals(
            1_800_000_111L,
            NotificationPolicy.notifySeed(sessionKey = 0L, retrySince = 1_800_000_111L, now = 1_800_000_999L)
        )
    }

    @Test
    fun `平台不给开播时刻时新场次用本轮时刻且不会退化成零`() {
        val now = 1_800_000_999L
        val seed = NotificationPolicy.notifySeed(sessionKey = 0L, retrySince = 0L, now = now)

        assertEquals(now, seed)
        assertNotEquals(0L, seed)
    }

    @Test
    fun `平台不给开播时刻时下一场的通知 id 仍然不同`() {
        // issue #3 的极端场景：平台返回 liveStartAt=0，旧实现两场都算 id=42*31+0 → 第二条不响
        val firstSeed = NotificationPolicy.notifySeed(sessionKey = 0L, retrySince = 0L, now = 1_800_000_000_000L)
        val secondSeed = NotificationPolicy.notifySeed(sessionKey = 0L, retrySince = 0L, now = 1_800_003_600_000L)

        assertNotEquals(
            NotificationPolicy.liveNotificationId(42L, firstSeed),
            NotificationPolicy.liveNotificationId(42L, secondSeed)
        )
    }
}
