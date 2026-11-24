package com.example.livenotify

import com.example.livenotify.monitor.NotificationHelper
import com.example.livenotify.platform.biliPlaceholderName
import com.example.livenotify.platform.isPlaceholderNickname
import com.example.livenotify.platform.pickStoredNickname
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 昵称兜底不得顶替真名。
 *
 * 起因：B 站批量接口偶发不返回 uname，代码用「B站主播 <房间号>」兜底；
 * 这个字符串非空，ifBlank 拦不住，被当成真名写回库并长期出现在开播通知标题里。
 * 这里锁住「假名可识别、不落库、通知不用它」三条规则。
 */
class NicknameFallbackTest {

    @Test
    fun `兜底名格式由唯一来源生成且能被识别`() {
        val name = biliPlaceholderName("1947277414")
        assertEquals("B站主播 1947277414", name)
        assertTrue(isPlaceholderNickname(name))
    }

    @Test
    fun `四个平台的兜底名都识别为真名以外的占位`() {
        assertTrue(isPlaceholderNickname("B站主播 1947277414"))
        assertTrue(isPlaceholderNickname("抖音主播 123456789"))
        assertTrue(isPlaceholderNickname("斗鱼主播 123"))
        assertTrue(isPlaceholderNickname("虎牙主播 99"))
    }

    @Test
    fun `真实昵称和非数字后缀不当作占位名`() {
        assertFalse(isPlaceholderNickname(""))
        assertFalse(isPlaceholderNickname("泽音Melody"))
        assertFalse(isPlaceholderNickname("B站主播 "))          // 缺 ID
        assertFalse(isPlaceholderNickname("B站主播 小号码"))      // 后缀不是纯数字
        assertFalse(isPlaceholderNickname("B站主播 123 的直播"))  // 后缀带文字
        assertFalse(isPlaceholderNickname("主播 123"))            // 没有平台前缀
    }

    @Test
    fun `兜底假名绝不覆盖库里已有真名`() {
        assertEquals("泽音Melody", pickStoredNickname("B站主播 1947277414", "泽音Melody"))
        // 接口真名则正常覆盖旧值，主播改名才能生效
        assertEquals("新名字", pickStoredNickname("新名字", "泽音Melody"))
        // 接口给空串时保留旧值
        assertEquals("泽音Melody", pickStoredNickname("", "泽音Melody"))
    }

    @Test
    fun `库里已被污染时真名仍能救回来`() {
        assertEquals("泽音Melody", pickStoredNickname("泽音Melody", "B站主播 1947277414"))
    }

    @Test
    fun `补发时标题说正在直播即使不足三分钟`() {
        val name = "泽音Melody"
        // 实时提醒说「开播了」
        assertEquals("泽音Melody 开播了", NotificationHelper.liveHeadline(name, 0L))
        // 补发哪怕只有 30 秒，也是补发，不能说「刚开播」
        assertEquals("泽音Melody 正在直播", NotificationHelper.liveHeadline(name, 30_000L))
        assertEquals("泽音Melody 正在直播", NotificationHelper.liveHeadline(name, 600_000L))
    }

    @Test
    fun `通知落地文案跟URL类型一致`() {
        assertEquals("点击前往直播间", NotificationHelper.openUrlHint("https://live.bilibili.com/1947277414"))
        assertEquals("点击前往主播主页", NotificationHelper.openUrlHint("https://space.bilibili.com/1203217682"))
    }

    @Test
    fun `通知标题优先用本轮真名其次用库里真名`() {
        val fake = biliPlaceholderName("1947277414")
        // 本轮真名优先
        assertEquals("泽音Melody", NotificationHelper.pickDisplayName("泽音Melody", fake))
        // 本轮缺字段时用库里保留的真名
        assertEquals("泽音Melody", NotificationHelper.pickDisplayName(fake, "泽音Melody"))
        // 本轮空串也用库里的
        assertEquals("泽音Melody", NotificationHelper.pickDisplayName("", "泽音Melody"))
        // 从没拿到真名时只能兜底，不许抛异常或给空标题
        assertEquals(fake, NotificationHelper.pickDisplayName(fake, fake))
        assertEquals(fake, NotificationHelper.pickDisplayName("", fake))
    }
}
