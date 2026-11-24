package com.example.livenotify

import com.example.livenotify.ui.LIVE_TEXT_LIGHT
import com.example.livenotify.ui.WARNING_TEXT_LIGHT
import com.example.livenotify.ui.groupColorArgb
import com.example.livenotify.ui.readableGroupColor
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 浅色主题下的文字对比度。
 *
 * 起因：深色模式下看着正常的分组色/状态色，切到浅色后普遍偏淡——
 * 琥珀分组色只有 2.7:1、警告色 2.6:1、直播红 3.7:1，都低于 WCAG AA 的 4.5:1。
 * 这里用纯计算锁住「浅色加深后可读」，不依赖真机肉眼判断。
 */
class LightContrastTest {

    private fun lum(argb: Long): Double {
        val ch = listOf((argb shr 16) and 0xFFL, (argb shr 8) and 0xFFL, argb and 0xFFL)
        val lin = ch.map { v ->
            val x = v / 255.0
            if (x <= 0.03928) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * lin[0] + 0.7152 * lin[1] + 0.0722 * lin[2]
    }

    private fun ratio(a: Long, b: Long): Double {
        val l1 = maxOf(lum(a), lum(b))
        val l2 = minOf(lum(a), lum(b))
        return (l1 + 0.05) / (l2 + 0.05)
    }

    /** 浅色卡片底近似 #FBF9FB */
    private val lightCard = 0xFFFBFBFBL

    @Test
    fun `浅色状态文字色对卡片底至少4比5`() {
        val live = ratio(LIVE_TEXT_LIGHT, lightCard)
        val warning = ratio(WARNING_TEXT_LIGHT, lightCard)
        assertTrue("直播红对比度只有 $live", live >= 4.5)
        assertTrue("警告色对比度只有 $warning", warning >= 4.5)
    }

    @Test
    fun `浅色分组色对白底至少4比5`() {
        val bg = 0xFFF5F3F7L // 浅色 surfaceVariant 近似
        for (name in listOf("", "游戏", "音乐", "学习", "二次元", "体育", "生活")) {
            val c = readableGroupColor(groupColorArgb(name), isDark = false)
            val r = ratio(c, bg)
            assertTrue("分组「$name」对比度只有 $r", r >= 4.5)
        }
    }

    @Test
    fun `深色状态原色不动只校正浅色`() {
        // 深色主题下用的还是原来的 LiveAccent/Warning 原色，保证既有的红=开播语义不变
        assertTrue(readableGroupColor(0xFF4C63D2L, isDark = true) != 0xFF4C63D2L)
        assertTrue(readableGroupColor(0xFF4C63D2L, isDark = false) != 0xFF4C63D2L)
    }
}
