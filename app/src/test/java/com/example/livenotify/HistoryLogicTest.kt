package com.example.livenotify

import com.example.livenotify.data.LiveSessionEntity
import com.example.livenotify.ui.dayKeyOf
import com.example.livenotify.ui.filterSessions
import com.example.livenotify.ui.groupByDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** 开播历史的筛选与日期分组纯函数。 */
class HistoryLogicTest {

    private fun session(id: Long, started: Long, platform: String = "bilibili", anchor: String = "a$id", nickname: String = "主播$id", title: String = "标题$id") =
        LiveSessionEntity(
            id = id, platformId = platform, anchorId = anchor, nickname = nickname,
            startedAtMs = started, title = title
        )

    private fun ms(y: Int, m: Int, d: Int, h: Int = 12): Long =
        Calendar.getInstance().apply { set(y, m - 1, d, h, 0, 0) }.timeInMillis

    @Test
    fun `日期键格式`() {
        assertEquals("2026-11-19", dayKeyOf(ms(2026, 11, 19)))
    }

    @Test
    fun `按日期倒序分组且组内倒序`() {
        val sessions = listOf(
            session(1, ms(2026, 11, 18)),
            session(2, ms(2026, 11, 19, 10)),
            session(3, ms(2026, 11, 19, 20)),
            session(4, ms(2026, 11, 17))
        )
        val groups = groupByDate(sessions)
        assertEquals(listOf("2026-11-19", "2026-11-18", "2026-11-17"), groups.map { it.first })
        assertEquals(listOf(3L, 2L), groups[0].second.map { it.id }) // 当天内按开始时刻倒序
        assertEquals(4, groups.sumOf { it.second.size })
    }

    @Test
    fun `组合筛选平台主播日期关键词`() {
        val sessions = listOf(
            session(1, ms(2026, 11, 19), platform = "bilibili", nickname = "七海", title = "唱歌"),
            session(2, ms(2026, 11, 19), platform = "douyu", nickname = "寅子", title = "游戏"),
            session(3, ms(2026, 11, 18), platform = "bilibili", nickname = "阿梓", title = "聊天")
        )
        assertEquals(3, filterSessions(sessions, null, null, null, "").size)
        assertEquals(2, filterSessions(sessions, "bilibili", null, null, "").size)
        assertEquals(1, filterSessions(sessions, null, "bilibili:a1", null, "").size)
        assertEquals(2, filterSessions(sessions, null, null, "2026-11-19", "").size)
        assertEquals(1, filterSessions(sessions, null, null, null, "唱歌").size)
        assertEquals(1, filterSessions(sessions, null, null, null, "阿梓").size)
        assertEquals(0, filterSessions(sessions, "douyu", null, null, "唱歌").size)
    }

    @Test
    fun `搜索忽略大小写与空白`() {
        val sessions = listOf(session(1, ms(2026, 11, 19), nickname = "Nana7mi", title = "Hello"))
        assertTrue(filterSessions(sessions, null, null, null, "  nana  ").isNotEmpty())
        assertTrue(filterSessions(sessions, null, null, null, "HELLO").isNotEmpty())
    }

    @Test
    fun `空列表分组为空`() {
        assertTrue(groupByDate(emptyList()).isEmpty())
    }
}
