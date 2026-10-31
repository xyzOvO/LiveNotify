package com.example.livenotify

import com.example.livenotify.monitor.evaluateLiveTransition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTransitionTest {

    @Test
    fun `同一场直播不重复通知`() {
        val r = evaluateLiveTransition(
            wasLive = true,
            previousStreak = 8,
            previousSessionKey = 100L,
            sessionKey = 100L,
            lastNotifiedLiveStart = 100L,
            nowLive = true,
            confirmTimes = 1
        )
        assertFalse(r.notificationCandidate)
        assertTrue(r.confirmedLive)
        assertEquals(9, r.streak)
    }

    @Test
    fun `轮询之间换场次仍能识别并通知`() {
        val r = evaluateLiveTransition(
            wasLive = true,
            previousStreak = 6,
            previousSessionKey = 100L,
            sessionKey = 200L,
            lastNotifiedLiveStart = 100L,
            nowLive = true,
            confirmTimes = 1
        )
        assertTrue(r.notificationCandidate)
        assertTrue(r.confirmedLive)
        assertEquals(1, r.streak)
    }

    @Test
    fun `新场次需要重新连续确认`() {
        val first = evaluateLiveTransition(
            wasLive = true,
            previousStreak = 6,
            previousSessionKey = 100L,
            sessionKey = 200L,
            lastNotifiedLiveStart = 100L,
            nowLive = true,
            confirmTimes = 2
        )
        assertFalse(first.notificationCandidate)
        assertFalse(first.confirmedLive)
        assertEquals(1, first.streak)

        val second = evaluateLiveTransition(
            wasLive = false,
            previousStreak = first.streak,
            previousSessionKey = 200L,
            sessionKey = 200L,
            lastNotifiedLiveStart = 100L,
            nowLive = true,
            confirmTimes = 2
        )
        assertTrue(second.notificationCandidate)
        assertTrue(second.confirmedLive)
        assertEquals(2, second.streak)
    }

    @Test
    fun `未达到确认次数时下一轮继续累计`() {
        val first = evaluateLiveTransition(
            wasLive = false,
            previousStreak = 0,
            previousSessionKey = 0L,
            sessionKey = 300L,
            lastNotifiedLiveStart = 0L,
            nowLive = true,
            confirmTimes = 3
        )
        assertFalse(first.confirmedLive)
        assertEquals(1, first.streak)

        val second = evaluateLiveTransition(
            wasLive = false,
            previousStreak = first.streak,
            previousSessionKey = 300L,
            sessionKey = 300L,
            lastNotifiedLiveStart = 0L,
            nowLive = true,
            confirmTimes = 3
        )
        assertEquals(2, second.streak)
        assertFalse(second.notificationCandidate)
    }

    @Test
    fun `下播后重新开播会重置确认次数`() {
        val offline = evaluateLiveTransition(
            wasLive = true,
            previousStreak = 4,
            previousSessionKey = 100L,
            sessionKey = 0L,
            lastNotifiedLiveStart = 100L,
            nowLive = false,
            confirmTimes = 2
        )
        assertEquals(0, offline.streak)
        assertFalse(offline.confirmedLive)

        val next = evaluateLiveTransition(
            wasLive = false,
            previousStreak = offline.streak,
            previousSessionKey = 0L,
            sessionKey = 400L,
            lastNotifiedLiveStart = 100L,
            nowLive = true,
            confirmTimes = 2
        )
        assertEquals(1, next.streak)
        assertFalse(next.notificationCandidate)
    }

    @Test
    fun `无场次时间的平台仍按开播跳变通知`() {
        val r = evaluateLiveTransition(
            wasLive = false,
            previousStreak = 0,
            previousSessionKey = 0L,
            sessionKey = 0L,
            lastNotifiedLiveStart = 0L,
            nowLive = true,
            confirmTimes = 1
        )
        assertTrue(r.notificationCandidate)
        assertTrue(r.confirmedLive)
    }
}
