package com.example.livenotify

import com.example.livenotify.ui.groupColorArgb
import com.example.livenotify.ui.groupColorIndex
import com.example.livenotify.ui.readableGroupColor
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
    fun `分组色浅色加深深色提亮都保色相`() {
        val base = 0xFF4C63D2L
        fun r(c: Long) = (c shr 16) and 0xFFL
        fun g(c: Long) = (c shr 8) and 0xFFL
        fun b(c: Long) = c and 0xFFL
        val light = readableGroupColor(base, isDark = false)
        val dark = readableGroupColor(base, isDark = true)
        // 浅色三通道都变暗、深色都变亮，色相顺序保持（蓝 > 红）
        assertTrue(r(light) < r(base) && g(light) < g(base) && b(light) < b(base))
        assertTrue(r(dark) > r(base) && g(dark) > g(base) && b(dark) > b(base))
        assertTrue(b(light) > r(light) && b(dark) > r(dark))
        assertEquals(0xFFL, (light shr 24) and 0xFFL) // alpha 保持不透明
        assertEquals(0xFFL, (dark shr 24) and 0xFFL)
    }

    @Test
    fun `浅色分组色对白底对比度至少4比5`() {
        // 琥珀/青绿原色在浅色下只有 2.7 / 3.0，必须加深到可读
        fun lum(c: Long): Double {
            val ch = listOf((c shr 16) and 0xFFL, (c shr 8) and 0xFFL, c and 0xFFL)
            val lin = ch.map { v ->
                val x = v / 255.0
                if (x <= 0.03928) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * lin[0] + 0.7152 * lin[1] + 0.0722 * lin[2]
        }
        val bgLum = lum(0xFFF5F3F7L)
        for (name in listOf("", "游戏", "音乐", "学习", "二次元", "体育", "生活")) {
            val c = readableGroupColor(groupColorArgb(name), isDark = false)
            val l1 = maxOf(lum(c), bgLum)
            val l2 = minOf(lum(c), bgLum)
            val ratio = (l1 + 0.05) / (l2 + 0.05)
            assertTrue("$name 对比度只有 $ratio", ratio >= 4.5)
        }
    }

    @Test
    fun `常见分组名不会全挤同一色`() {
        val names = listOf("游戏", "音乐", "学习", "二次元", "体育", "生活")
        val distinct = names.map { groupColorIndex(it) }.toSet()
        assertTrue("6 个常见组名应至少用到 3 种颜色，实际 ${distinct.size}", distinct.size >= 3)
    }
}
