package com.example.livenotify

import com.example.livenotify.monitor.WidgetPalette
import com.example.livenotify.monitor.WidgetStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 小组件主题配色：纯函数，不依赖 Android。 */
class WidgetPaletteTest {

    @Test
    fun 三态主题归一为明暗() {
        assertFalse(WidgetPalette.isDark("LIGHT", systemDark = true))
        assertTrue(WidgetPalette.isDark("DARK", systemDark = false))
        assertTrue(WidgetPalette.isDark("SYSTEM", systemDark = true))
        assertFalse(WidgetPalette.isDark("SYSTEM", systemDark = false))
        // 未知值跟随系统，不擅自强制
        assertTrue(WidgetPalette.isDark("???", systemDark = true))
    }

    @Test
    fun 浅色配色文字与底色足够区分() {
        val light = WidgetPalette.colors(dark = false)
        assertEquals("#FFFFFF", light.background)
        val darkOfLightText = light.name
        assertFalse(darkOfLightText.equals(light.background, ignoreCase = true))
        // 浅底上的告警色要比深底版更深，避免糊成一片
        assertTrue(light.statusWarn != WidgetPalette.colors(dark = true).statusWarn)
        assertTrue(light.pill != WidgetPalette.colors(dark = true).pill)
    }

    @Test
    fun 六态状态色在深浅两套里都齐全且区分() {
        WidgetStatus.values().forEach { status ->
            val dark = WidgetPalette.statusTint(status, dark = true)
            val light = WidgetPalette.statusTint(status, dark = false)
            assertEquals(7, dark.length)
            assertEquals(7, light.length)
            assertTrue(dark.startsWith("#"))
            assertTrue(light.startsWith("#"))
        }
        val normalDark = WidgetPalette.statusTint(WidgetStatus.NORMAL, dark = true)
        assertFalse(WidgetPalette.statusTint(WidgetStatus.NOT_RUNNING, true) == normalDark)
        assertFalse(WidgetPalette.statusTint(WidgetStatus.WAIT_NETWORK, false) ==
            WidgetPalette.statusTint(WidgetStatus.NORMAL, false))
    }

    @Test
    fun 正常色是绿且六态保留细分() {
        listOf(true, false).forEach { dark ->
            val normal = WidgetPalette.statusTint(WidgetStatus.NORMAL, dark)
            // 用户口径：正常=绿。深底用亮绿、浅底用深绿，两套都是绿色系
            assertTrue("dark=$dark 正常色应为绿", normal.equals("#4ADE80", true) ||
                normal.equals("#2E7D32", true))
            // 其余五态不跟正常同色，且仍按六态细分（不是一刀切全红）
            WidgetStatus.values().filter { it != WidgetStatus.NORMAL }.forEach { status ->
                assertFalse("dark=$dark status=$status 不应与正常同色",
                    normal == WidgetPalette.statusTint(status, dark))
            }
            assertNotEquals(
                WidgetPalette.statusTint(WidgetStatus.NOT_RUNNING, dark),
                WidgetPalette.statusTint(WidgetStatus.WAIT_NETWORK, dark),
            )
        }
    }

    private fun assertNotEquals(a: String, b: String) {
        org.junit.Assert.assertFalse("两态不应同色: $a / $b", a == b)
    }
}
