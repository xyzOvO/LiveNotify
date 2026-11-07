package com.example.livenotify

import com.example.livenotify.update.ReleaseNotes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新日志直接展示给用户，`###` `**` 这类符号漏出来比不显示还难看。
 * 这里锁住去噪规则，避免以后改正则把正常文案也啃掉。
 */
class ReleaseNotesTest {

    @Test
    fun `标题与列表符号被去掉`() {
        val raw = """
            ### 新增
            - 设置页分组
            * 下载失败可重试
            + 版本号并排显示
        """.trimIndent()
        assertEquals(
            "新增\n· 设置页分组\n· 下载失败可重试\n· 版本号并排显示",
            ReleaseNotes.format(raw)
        )
    }

    @Test
    fun `加粗与行内代码被去掉`() {
        val raw = "修复 **签名比对** 在 `Android 10` 上的误报"
        assertEquals("修复 签名比对 在 Android 10 上的误报", ReleaseNotes.format(raw))
    }

    @Test
    fun `链接只保留文字`() {
        val raw = "见 [更新说明](https://github.com/xyzOvO/LiveNotify/releases)"
        assertEquals("见 更新说明", ReleaseNotes.format(raw))
    }

    @Test
    fun `自动生成的 Full Changelog 被丢弃`() {
        val raw = """
            修复若干问题

            **Full Changelog**: https://github.com/xyzOvO/LiveNotify/compare/v1.0.9...v1.0.10
        """.trimIndent()
        assertEquals("修复若干问题", ReleaseNotes.format(raw))
    }

    @Test
    fun `HTML 注释整段丢弃`() {
        val raw = """
            正文
            <!--
            这是多行注释
            -->
            结尾
        """.trimIndent()
        // 注释两侧各留一个换行，合并成一个空行——段落之间保留一行更易读
        assertEquals("正文\n\n结尾", ReleaseNotes.format(raw))
    }

    @Test
    fun `连续空行压成一个`() {
        val raw = "A\n\n\n\nB"
        assertEquals("A\n\nB", ReleaseNotes.format(raw))
    }

    @Test
    fun `超长内容按整行截断`() {
        val line = "这是一行说明"
        val raw = (1..40).joinToString("\n") { "$line$it" }
        val out = ReleaseNotes.format(raw, maxChars = 40)
        assertTrue("应保留截断标记", out.endsWith("…"))
        val body = out.removeSuffix("…").trimEnd()
        val lines = body.lines()
        assertTrue("截断点必须落在行尾，不能把一句切成半句", lines.all { Regex("""这是一行说明\d+""").matches(it) })
        assertTrue("40 字上限内最多放 5 行", lines.size <= 5)
    }

    @Test
    fun `空正文返回空串`() {
        assertEquals("", ReleaseNotes.format(""))
        assertEquals("", ReleaseNotes.format("   \n\n  "))
    }
}
