package com.example.livenotify

import com.example.livenotify.monitor.MonitorEngine
import com.example.livenotify.ui.PlatformHealth
import com.example.livenotify.ui.buildDiagnosticReport
import com.example.livenotify.ui.platformHealth
import com.example.livenotify.data.StreamerEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 诊断报告：只用真实数据、脱敏、不虚构无来源字段。 */
class DiagnosticReportTest {

    private fun s(pid: String, errors: Int, checked: Long) = StreamerEntity(
        platformId = pid, anchorId = "uid-123", nickname = "主播", roomUrl = "https://x/1",
        errorCount = errors, lastCheckedAt = checked
    )

    @Test
    fun `平台健康按失败数排序`() {
        val h = platformHealth(listOf(
            s("bilibili", 0, 100L),
            s("douyu", 3, 200L),
            s("douyu", 0, 150L)
        ))
        assertEquals("斗鱼", h[0].displayName)
        assertEquals(2, h[0].streamers)
        assertEquals(1, h[0].failed)
        assertEquals(200L, h[0].lastCheckedAt)
    }

    @Test
    fun `报告不含敏感串与虚构字段`() {
        val report = buildDiagnosticReport(
            health = platformHealth(listOf(s("bilibili", 2, 1000L))),
            lastRunAt = 1000L, lastDurationMs = 350L,
            skip = null, networkLabel = "Wi-Fi"
        )
        assertFalse("不能带主播标识/凭据值", report.contains("uid-123"))
        assertFalse("不能带 Cookie 赋值", report.contains("=") || report.contains("token"))
        assertTrue(report.contains("失败 1")) // 1 位主播 errorCount>0
        assertTrue(report.contains("Wi-Fi"))
        assertTrue(report.contains("350 ms"))
    }

    @Test
    fun `跳过原因如实写入`() {
        val report = buildDiagnosticReport(emptyList(), 0L, 0L, MonitorEngine.SkipReason.WIFI_ONLY, "移动网络")
        assertTrue(report.contains("等待 Wi-Fi"))
        assertTrue(report.contains("尚未检测"))
    }
}
