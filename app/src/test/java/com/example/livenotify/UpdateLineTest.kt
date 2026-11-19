package com.example.livenotify

import com.example.livenotify.ui.packageLine
import com.example.livenotify.ui.publishDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 更新包信息行的拼装：缺字段时不能留下「· ·」这种空段。 */
class UpdateLineTest {

    @Test
    fun `大小和发布日期都有时都展示`() {
        val line = packageLine(2_772_623, "2026-11-19T16:05:47Z")
        assertTrue(line.contains("更新包"))
        assertTrue(line.contains("发布于"))
        assertTrue(line.contains("覆盖安装"))
    }

    @Test
    fun `兜底路径没有发布日期时省略该段`() {
        val line = packageLine(2_772_623, "")
        assertTrue(line.contains("更新包"))
        assertFalse("没有日期就不该出现「发布于」", line.contains("发布于"))
        // 不能留下连续分隔符
        assertFalse(line.contains("· ·"))
    }

    @Test
    fun `拿不到大小时只留说明`() {
        val line = packageLine(0L, "")
        assertEquals("覆盖安装，关注列表不会丢", line)
    }

    @Test
    fun `ISO 时间转本地日期`() {
        // 只断言形如 yyyy-MM-dd，具体哪天取决于时区，不写死
        assertTrue(Regex("""\d{4}-\d{2}-\d{2}""").matches(publishDate("2026-11-19T16:05:47Z")))
    }

    @Test
    fun `时间解析失败不把半截字符串糊到界面`() {
        assertEquals("", publishDate(""))
        assertEquals("", publishDate("not-a-date"))
        // 只有日期没有时区也能兜底取出日期部分
        assertEquals("2026-11-19", publishDate("2026-11-19"))
    }
}
