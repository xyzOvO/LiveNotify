package com.example.livenotify

import com.example.livenotify.platform.DouyinParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 抖音移动端落地页解析回归测试。
 *
 * 用例里的字符串刻意保留页面真实的转义形态（`\"`），确保 unescape 与锚点逻辑一起被覆盖。
 */
class DouyinParserTest {

    private val DOLLAR = "${'$'}"

    /** 与真实页面同构的最小片段：room 对象 + owner 对象 + 访客 user 对象（干扰项） */
    private fun page(status: Int): String = """
        <html><body><script>self.__rsc_f.push([1,"5:[{\"data\":{
        \"room\":{\"id\":7701909481856634000,\"idStr\":\"7701909481856633636\",\"status\":$status,
        \"ownerUserId\":61608272917,\"title\":\"测试标题\",\"userCount\":58589,\"createTime\":1793240555},
        \"user\":{\"id\":0,\"nickname\":\"${DOLLAR}undefined\",\"status\":0},
        \"owner\":{\"id\":61608272917,\"shortId\":629458933,\"nickname\":\"测试主播\",
        \"avatarThumb\":{\"urlList\":[\"https://p3.douyinpic.com/a.jpeg\"]}}
        }}"])</script></body></html>
    """.trimIndent()

    @Test fun `直播中：读出状态与主播信息`() {
        val p = DouyinParser.parseReflow(page(2), "7701909481856633636")
        assertNotNull(p)
        assertEquals(2, p!!.status)
        assertEquals("7701909481856633636", p.webRid)
        assertEquals("测试主播", p.nickname)
        assertEquals("测试标题", p.title)
        assertEquals(1793240555L, p.createTime)
        assertEquals("https://p3.douyinpic.com/a.jpeg", p.avatar)
    }

    @Test fun `未开播：状态为 4 且不算房间失效`() {
        val p = DouyinParser.parseReflow(page(4), "7701909481856633636")
        assertNotNull(p)
        assertEquals(4, p!!.status)
        assertEquals(false, p.notFound)
        // 未开播也要拿到主播名，列表里不能变成「抖音主播 123」
        assertEquals("测试主播", p.nickname)
    }

    @Test fun `昵称取主播而非访客占位值`() {
        // 访客对象的 nickname 是 $undefined，不能被当成主播名
        val p = DouyinParser.parseReflow(page(2), "x")
        assertEquals("测试主播", p!!.nickname)
    }

    @Test fun `空壳页不猜未开播`() {
        // 没有 room 数据只代表本次没读到状态，不能改写成「已下播」
        val shell = "<html><head><title>抖音</title></head><body><div id=\"root\"></div></body></html>"
        val p = DouyinParser.parseReflow(shell, "629458933")
        assertNull(p)
        assertTrue(DouyinParser.lastReason.contains("未返回房间状态"))
    }

    @Test fun `空响应返回 null`() {
        assertNull(DouyinParser.parseReflow("", "1"))
    }
}
