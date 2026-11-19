package com.example.livenotify

import com.example.livenotify.data.StreamerEntity
import com.example.livenotify.monitor.SessionEvent
import com.example.livenotify.monitor.sessionEvent
import org.junit.Assert.assertEquals
import org.junit.Test

/** 开播历史会话的开/收口判定，重点是换场不能被并成一场。 */
class SessionEventsTest {

    private fun streamer(isLive: Boolean, startAt: Long) = StreamerEntity(
        platformId = "bilibili", anchorId = "a1", nickname = "主播",
        roomUrl = "https://live.bilibili.com/1",
        isLive = isLive, liveStartAt = startAt
    )

    @Test
    fun `从未开播到开播建会话`() {
        assertEquals(
            SessionEvent.OPEN,
            sessionEvent(streamer(isLive = false, startAt = 0L), true, 100L)
        )
    }

    @Test
    fun `从开播到下播收口`() {
        assertEquals(
            SessionEvent.CLOSE,
            sessionEvent(streamer(isLive = true, startAt = 100L), false, 0L)
        )
    }

    @Test
    fun `换场先收口再新建`() {
        assertEquals(
            SessionEvent.SWITCH,
            sessionEvent(streamer(isLive = true, startAt = 100L), true, 200L)
        )
    }

    @Test
    fun `同场次持续直播不动历史`() {
        assertEquals(
            SessionEvent.NONE,
            sessionEvent(streamer(isLive = true, startAt = 100L), true, 100L)
        )
        assertEquals(
            SessionEvent.NONE,
            sessionEvent(streamer(isLive = false, startAt = 0L), false, 0L)
        )
    }

    @Test
    fun `平台没给场次 key 时换场识别不出来也不误判`() {
        // sessionKey=0 时无法判断换场，保持 NONE，避免把同一场误拆成多场
        assertEquals(
            SessionEvent.NONE,
            sessionEvent(streamer(isLive = true, startAt = 100L), true, 0L)
        )
    }
}
