package com.example.livenotify

import com.example.livenotify.ui.matchesSearchQuery
import com.example.livenotify.ui.shortIdFromNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索命中规则：B 站短号存在 note（「短号 510」）里，不在 displayId / anchorId 中。
 * 用例里的号码取自真机：uid 7706705 → 真实房间号 80397、短号 510。
 */
class SearchQueryTest {

    private fun hit(
        q: String,
        note: String = "短号 510",
        nickname: String = "阿梓从小就很可爱",
        displayId: String = "80397",
        anchorId: String = "7706705"
    ) = matchesSearchQuery(q, nickname, displayId, anchorId, note)

    @Test
    fun `短号完整命中`() {
        assertTrue(hit("510"))
    }

    @Test
    fun `短号支持部分匹配`() {
        assertTrue(hit("51"))
    }

    @Test
    fun `原有三个字段仍能搜`() {
        assertTrue(hit("阿梓"))
        assertTrue(hit("80397"))
        assertTrue(hit("7706705"))
        assertTrue(hit("AZI", nickname = "Azi"))
    }

    @Test
    fun `空白 query 不做过滤`() {
        assertTrue(hit(""))
        assertTrue(hit("   "))
    }

    @Test
    fun `不存在的号码不命中`() {
        assertFalse(hit("999"))
        assertFalse(hit("80398"))
    }

    @Test
    fun `轮播中时短号不参与搜索`() {
        // note 被「轮播中」占用，短号本来就没写进去，不能凭空命中
        assertFalse(hit("510", note = "轮播中"))
    }

    @Test
    fun `斗鱼分区名不参与搜索`() {
        assertFalse(matchesSearchQuery("影之刃零", "寅子", "71415", "71415", "影之刃零"))
    }

    @Test
    fun `短号提取只认纯数字前缀`() {
        assertEquals("510", shortIdFromNote("短号 510"))
        assertEquals("510", shortIdFromNote("  短号 510  "))
        assertNull(shortIdFromNote(""))
        assertNull(shortIdFromNote("短号 "))
        assertNull(shortIdFromNote("短号 abc"))
        assertNull(shortIdFromNote("轮播中"))
        assertNull(shortIdFromNote("影之刃零"))
    }
}
