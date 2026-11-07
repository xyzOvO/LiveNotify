package com.example.livenotify

import com.example.livenotify.data.Settings
import com.example.livenotify.data.StreamerEntity
import com.example.livenotify.util.BackupFormat
import com.example.livenotify.util.BackupFormat.BadBackup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupFormatTest {

    private fun streamer(id: Long, enabled: Boolean = true) = StreamerEntity(
        id = id,
        platformId = "bilibili",
        anchorId = "anchor-$id",
        nickname = "主播$id",
        roomUrl = "https://live.bilibili.com/$id",
        enabled = enabled,
        // 这些是本机运行产物，不该出现在备份里
        isLive = true,
        liveStartAt = 555L,
        lastNotifiedAt = 999L,
        lastNotifiedLiveStart = 555L,
        errorCount = 7,
        lastError = "旧错误"
    )

    private fun settings() = Settings(douyinCookie = "SECRET-COOKIE", pollIntervalSec = 180)

    @Test
    fun `导出只带平台 主播 暂停状态与白名单设置`() {
        val json = BackupFormat.encode(
            BackupFormat.snapshotOf(listOf(streamer(1), streamer(2, enabled = false)), settings(), 1234L)
        )

        assertFalse("不能把 Cookie 写进备份", json.contains("SECRET-COOKIE"))
        assertFalse(json.contains("isLive"))
        assertFalse(json.contains("lastNotifiedAt"))
        assertFalse(json.contains("lastNotifiedLiveStart"))
        assertFalse(json.contains("errorCount"))
        assertFalse(json.contains("lastError"))
        assertFalse(json.contains("nickname"))
        assertFalse(json.contains("roomUrl"))

        val d = BackupFormat.decode(json)
        assertEquals(1, d.version)
        assertEquals(1234L, d.exportedAt)
        assertEquals(2, d.entries.size)
        assertEquals("anchor-1", d.entries[0].anchorId)
        assertTrue(d.entries[0].enabled)
        assertFalse(d.entries[1].enabled)   // 暂停状态是用户意图，要保留
        assertEquals(180, d.prefs.pollIntervalSec)
    }

    @Test
    fun `往返后数据一致`() {
        val d = BackupFormat.snapshotOf(listOf(streamer(9, enabled = false)), settings(), 42L)
        val back = BackupFormat.decode(BackupFormat.encode(d))
        assertEquals(d.entries, back.entries)
        assertEquals(d.prefs, back.prefs)
        assertEquals(d.exportedAt, back.exportedAt)
    }

    @Test
    fun `非本应用的 JSON 被拒绝`() {
        try {
            BackupFormat.decode("""{"app":"NotUs","version":1,"streamers":[]}""")
            fail("应当拒绝")
        } catch (e: BadBackup) {
            assertTrue(e.message!!.contains("不是 LiveNotify"))
        }
    }

    @Test
    fun `更高版本被拒绝 低版本可导入`() {
        try {
            BackupFormat.decode("""{"app":"LiveNotify","version":99,"streamers":[]}""")
            fail("应当拒绝")
        } catch (e: BadBackup) {
            assertTrue(e.message!!.contains("不兼容"))
        }
    }

    @Test
    fun `坏条目被跳过 重复条目去重 缺失字段走默认值`() {
        val raw = """
            {"app":"LiveNotify","version":1,
             "streamers":[
               {"platformId":"bilibili","anchorId":"a1"},
               {"platformId":"bilibili","anchorId":"a1","enabled":false},
               {"platformId":"","anchorId":"a2"},
               {"platformId":"douyin","anchorId":"a3","enabled":false}
             ]}
        """.trimIndent()
        val d = BackupFormat.decode(raw)
        assertEquals(2, d.entries.size)             // a1 去重、空平台跳过
        assertTrue(d.entries[0].enabled)            // 首条缺省 enabled 视为 true
        assertFalse(d.entries[1].enabled)
        assertEquals(120, d.prefs.pollIntervalSec)  // prefs 缺失走默认
    }

    @Test
    fun `JSON null 不会变成字符串 null`() {
        val d = BackupFormat.decode(
            """{"app":"LiveNotify","version":1,"streamers":[{"platformId":null,"anchorId":"a"}]}"""
        )
        assertTrue(d.entries.isEmpty())
    }

    @Test
    fun `损坏的 JSON 直接拒绝而不是导进半份`() {
        for (bad in listOf("", "{", """{"app":"LiveNotify",,}""", "<html></html>")) {
            try {
                BackupFormat.decode(bad)
                fail("应当拒绝：$bad")
            } catch (e: BadBackup) {
                assertTrue(e.message!!.isNotBlank())
            }
        }
    }

    @Test
    fun `缺少字段或版本非法的备份不会崩`() {
        // 版本 0 / 负数都当作不支持；streamers 缺失时按空列表处理
        for (v in listOf(0, -1)) {
            try {
                BackupFormat.decode("""{"app":"LiveNotify","version":$v}""")
                fail("版本 $v 应当拒绝")
            } catch (e: BadBackup) {
                assertTrue(e.message!!.contains("不兼容"))
            }
        }
        val noList = BackupFormat.decode("""{"app":"LiveNotify","version":1}""")
        assertTrue(noList.entries.isEmpty())
    }
}
