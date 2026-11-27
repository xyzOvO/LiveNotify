package com.example.livenotify

import com.example.livenotify.monitor.WidgetPinHint
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「添加小组件到桌面」入口文案：纯函数，不依赖 Android。 */
class WidgetPinHintTest {

    @Test
    fun 支持时告诉用户系统会问放置位置() {
        val text = WidgetPinHint.text(supported = true, pinnedCount = 0)
        assertTrue(text.contains("系统会问"))
        assertTrue(text.contains("按钮"))
    }

    @Test
    fun 不支持时给出长按手动路径() {
        val text = WidgetPinHint.text(supported = false, pinnedCount = 0)
        assertTrue(text.contains("长按桌面"))
        assertTrue(text.contains("小组件"))
    }

    @Test
    fun 已添加时显示数量() {
        val text = WidgetPinHint.text(supported = true, pinnedCount = 2)
        assertTrue(text.contains("2 个"))
    }
}
