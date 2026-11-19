package com.example.livenotify

import com.example.livenotify.data.StreamerEntity
import com.example.livenotify.ui.formatInterval
import com.example.livenotify.ui.groupLabel
import com.example.livenotify.ui.groupVisible
import org.junit.Assert.assertEquals
import org.junit.Test

/** 关注列表分组的纯函数规则：分段不丢人、未分组在前、折叠头显示名正确。 */
class GroupTest {

    private fun s(id: Long, group: String) = StreamerEntity(
        id = id,
        platformId = "bilibili",
        anchorId = "a$id",
        nickname = "主播$id",
        roomUrl = "https://live.bilibili.com/$id",
        group = group
    )

    @Test
    fun `空列表返回空分段`() {
        assertEquals(0, groupVisible(emptyList()).size)
    }

    @Test
    fun `全部未分组时只有一段`() {
        val sections = groupVisible(listOf(s(1, ""), s(2, "")))
        assertEquals(1, sections.size)
        assertEquals("", sections[0].name)
        assertEquals(2, sections[0].items.size)
    }

    @Test
    fun `按输入顺序切成多段且不丢主播`() {
        // 输入已由 observeAllGrouped 排好序：未分组为空串在前，其余按名升序
        val input = listOf(s(1, ""), s(2, "游戏"), s(3, "游戏"), s(4, "音乐"))
        val sections = groupVisible(input)
        assertEquals(listOf("", "游戏", "音乐"), sections.map { it.name })
        assertEquals(listOf(1L), sections[0].items.map { it.id })
        assertEquals(listOf(2L, 3L), sections[1].items.map { it.id })
        assertEquals(listOf(4L), sections[2].items.map { it.id })
        // 分段前后主播总数守恒
        assertEquals(input.size, sections.sumOf { it.items.size })
    }

    @Test
    fun `同组内非连续输入各自成段`() {
        // 正常情况下排序后不会这样，但纯函数要如实按「连续同组」分段，不偷偷合并
        val sections = groupVisible(listOf(s(1, "游戏"), s(2, "音乐"), s(3, "游戏")))
        assertEquals(3, sections.size)
    }

    @Test
    fun `分组头显示名空串转为默认`() {
        assertEquals("默认", groupLabel(""))
        assertEquals("游戏", groupLabel("游戏"))
    }

    @Test
    fun `检测间隔文案秒与分钟`() {
        assertEquals("30 秒", formatInterval(30))
        assertEquals("2 分钟", formatInterval(120))
        assertEquals("1 分 30 秒", formatInterval(90))
        assertEquals("2 分 30 秒", formatInterval(150))
        assertEquals("0 秒", formatInterval(0))
    }
}
