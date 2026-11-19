package com.example.livenotify

import com.example.livenotify.update.ReleaseInfo
import com.example.livenotify.update.UpdateSchedule
import com.example.livenotify.update.UpdateSharer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 分享文本与自动检查节流：两条都是纯函数，边界情况必须锁住。 */
class UpdateShareTest {

    private val apk = "https://github.com/xyzOvO/LiveNotify/releases/download/v1.1.0/app-release.apk"

    private fun info(url: String = apk, notes: String = "") = ReleaseInfo(
        tagName = "v1.1.0",
        versionName = "1.1.0",
        versionCode = 10100,
        apkUrl = url,
        assetName = "app-release.apk",
        sizeBytes = 2_700_000,
        notes = notes,
        publishedAt = "2026-11-19T23:00:00Z"
    )

    // ── 分享文本 ────────────────────────────────────────────

    @Test
    fun `分享文本包含应用名版本号和下载地址`() {
        val text = UpdateSharer.shareText(info(), "开播提醒")
        assertTrue(text!!.contains("开播提醒"))
        assertTrue(text.contains("v1.1.0"))
        assertTrue(text.contains(apk))
    }

    @Test
    fun `更新日志为空时不生成空标题`() {
        val text = UpdateSharer.shareText(info(notes = ""), "开播提醒")!!
        assertFalse("没有日志就不该出现「更新内容」标题", text.contains("更新内容"))
        // 也不该留下多余连续空行
        assertFalse(text.contains("\n\n\n"))
    }

    @Test
    fun `更新日志有内容时清理 Markdown 并附带`() {
        val text = UpdateSharer.shareText(
            info(notes = "## 新增\n- 分组管理\n- 修复返回层级"),
            "开播提醒"
        )!!
        assertTrue(text.contains("更新内容"))
        assertTrue(text.contains("分组管理"))
        assertFalse("Markdown 标记要去掉", text.contains("##"))
        assertFalse(text.contains("- 分组管理"))
    }

    @Test
    fun `非 HTTPS 地址拒绝分享`() {
        assertNull(UpdateSharer.shareText(info(url = "http://example.com/app.apk"), "开播提醒"))
        assertNull(UpdateSharer.shareText(info(url = ""), "开播提醒"))
        assertNull(UpdateSharer.shareText(info(url = "https://"), "开播提醒"))
        assertNull(UpdateSharer.shareText(info(url = "ftp://example.com/app.apk"), "开播提醒"))
    }

    @Test
    fun `分享地址必须是服务器给的公开链接而不是本地路径`() {
        // file:// / content:// 一律不合法：换台设备就打不开
        assertFalse(UpdateSharer.isHttps("file:///data/user/0/app/files/a.apk"))
        assertFalse(UpdateSharer.isHttps("content://com.example.fileprovider/updates/a.apk"))
        assertTrue(UpdateSharer.isHttps(apk))
    }

    // ── 自动检查节流 ────────────────────────────────────────

    private val hour = 60L * 60 * 1000

    @Test
    fun `从未检查过就查一次`() {
        assertTrue(UpdateSchedule.shouldAutoCheck(0L, System.currentTimeMillis()))
    }

    @Test
    fun `不足24小时不重复检查`() {
        val now = 1_700_000_000_000L
        assertFalse(UpdateSchedule.shouldAutoCheck(now - 23 * hour, now))
        assertFalse(UpdateSchedule.shouldAutoCheck(now - 1, now))
    }

    @Test
    fun `满24小时才再次检查`() {
        val now = 1_700_000_000_000L
        assertTrue(UpdateSchedule.shouldAutoCheck(now - 24 * hour, now))
        assertTrue(UpdateSchedule.shouldAutoCheck(now - 48 * hour, now))
    }

    @Test
    fun `时钟回拨不应触发检查`() {
        // 用户改时间让 now 小于上次记录时，不能算成「已经过了很久」
        val now = 1_700_000_000_000L
        assertFalse(UpdateSchedule.shouldAutoCheck(now + hour, now))
    }
}
