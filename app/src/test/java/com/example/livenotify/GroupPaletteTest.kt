package com.example.livenotify

import com.example.livenotify.ui.groupColorArgb
import com.example.livenotify.ui.groupColorIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 分组自动配色：同名稳定、不同名尽量分散、索引不越界。 */
class GroupPaletteTest {

    @Test
    fun `同名颜色稳定`() {
        assertEquals(groupColorIndex("游戏"), groupColorIndex("游戏"))
        assertEquals(groupColorArgb("游戏"), groupColorArgb("游戏"))
    }

    @Test
    fun `索引始终在预设范围内`() {
        for (name in listOf("", "a", "游戏", "主播很多的长分组名称", "😀", "A", "B", "C")) {
            val i = groupColorIndex(name)
            assertTrue("索引越界: $i", i in 0 until 6)
        }
        assertEquals(0, groupColorIndex(""))
    }

    @Test
    fun `常见分组名不会全挤同一色`() {
        val names = listOf("游戏", "音乐", "学习", "二次元", "体育", "生活")
        val distinct = names.map { groupColorIndex(it) }.toSet()
        assertTrue("6 个常见组名应至少用到 3 种颜色，实际 ${distinct.size}", distinct.size >= 3)
    }
}
