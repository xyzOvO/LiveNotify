package com.example.livenotify

import com.example.livenotify.data.StreamerEntity
import com.example.livenotify.monitor.evaluateLiveTransition
import com.example.livenotify.monitor.resumeBaseline
import com.example.livenotify.platform.LiveState
import com.example.livenotify.platform.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeBaselineTest {

    private fun paused() = StreamerEntity(
        id = 7,
        platformId = "bilibili",
        anchorId = "21452505",
        nickname = "某主播",
        roomUrl = "https://live.bilibili.com/21452505",
        enabled = false,
        // 暂停前那一场的旧基准
        isLive = true,
        liveStreak = 9,
        liveStartAt = 100L,
        lastNotifiedLiveStart = 100L,
        lastNotifiedAt = 1L,
        errorCount = 4,
        lastError = "旧错误"
    )

    private fun snap(live: Boolean, key: Long) = Snapshot(
        anchorId = "21452505",
        nickname = "某主播",
        avatarUrl = null,
        title = "标题",
        state = if (live) LiveState.LIVE else LiveState.OFF,
        liveStartAt = key,
        roomUrl = "https://live.bilibili.com/21452505"
    )

    @Test
    fun `恢复时正在直播 不会把当前场次当成新开播推送`() {
        val r = resumeBaseline(paused(), snap(live = true, key = 500L), now = 9000L)

        assertTrue(r.enabled)
        assertTrue(r.isLive)
        assertEquals(500L, r.liveStartAt)
        assertEquals(500L, r.lastNotifiedLiveStart)
        assertEquals(9000L, r.lastNotifiedAt)
        assertEquals(0, r.errorCount)

        // 下一轮仍在同一场次：引擎不得给通知候选
        val transition = evaluateLiveTransition(
            wasLive = r.isLive,
            previousStreak = r.liveStreak,
            previousSessionKey = r.liveStartAt,
            sessionKey = 500L,
            lastNotifiedLiveStart = r.lastNotifiedLiveStart,
            nowLive = true,
            confirmTimes = 1
        )
        assertFalse(transition.notificationCandidate)
    }

    @Test
    fun `暂停期间换了场次 恢复后也不补推旧消息`() {
        // 暂停时库里还是旧场次 100，恢复时实测已是新场次 900
        val r = resumeBaseline(paused(), snap(live = true, key = 900L), now = 9000L)

        assertEquals(900L, r.liveStartAt)
        assertEquals(900L, r.lastNotifiedLiveStart)
        val transition = evaluateLiveTransition(
            wasLive = r.isLive,
            previousStreak = r.liveStreak,
            previousSessionKey = r.liveStartAt,
            sessionKey = 900L,
            lastNotifiedLiveStart = r.lastNotifiedLiveStart,
            nowLive = true,
            confirmTimes = 1
        )
        assertFalse(transition.notificationCandidate)
    }

    @Test
    fun `恢复时未开播 之后真开播仍能正常通知`() {
        val r = resumeBaseline(paused(), snap(live = false, key = 0L), now = 9000L)

        assertFalse(r.isLive)
        assertEquals(0L, r.liveStartAt)
        // 场次 key 为 0 时，之后开播不会被判成「已通知过」
        val transition = evaluateLiveTransition(
            wasLive = r.isLive,
            previousStreak = r.liveStreak,
            previousSessionKey = r.liveStartAt,
            sessionKey = 1234L,
            lastNotifiedLiveStart = r.lastNotifiedLiveStart,
            nowLive = true,
            confirmTimes = 1
        )
        assertTrue(transition.notificationCandidate)
    }
}
