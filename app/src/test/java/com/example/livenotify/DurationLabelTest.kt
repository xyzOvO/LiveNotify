package com.example.livenotify

import com.example.livenotify.ui.durationLabel
import org.junit.Assert.assertEquals
import org.junit.Test

/** 开播历史里的时长文案边界。 */
class DurationLabelTest {

    @Test
    fun `不足一分钟显示秒`() {
        assertEquals("不足 1 分钟", durationLabel(0L))
        assertEquals("5 秒", durationLabel(5_000L))
        assertEquals("59 秒", durationLabel(59_999L))
    }

    @Test
    fun `整分钟显示分钟`() {
        assertEquals("1 分钟", durationLabel(60_000L))
        assertEquals("45 分钟", durationLabel(45 * 60_000L))
        assertEquals("59 分钟", durationLabel(59 * 60_000L + 30_000L))
    }

    @Test
    fun `跨小时显示小时与分`() {
        assertEquals("1 小时", durationLabel(60 * 60_000L))
        assertEquals("1 小时 5 分", durationLabel(65 * 60_000L))
        assertEquals("3 小时 20 分", durationLabel((3 * 60 + 20) * 60_000L))
    }
}
