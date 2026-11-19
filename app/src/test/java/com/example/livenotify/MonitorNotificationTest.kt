package com.example.livenotify

import com.example.livenotify.monitor.MonitorEngine
import com.example.livenotify.monitor.NotificationHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 常驻通知文案 / 渠道的纯函数规则。
 *
 * 起因是一次真机 BUG：onCreate 写死「正在检测 0 位主播 / 准备中…」且固定普通渠道，
 * 后续更新改投静默渠道后旧通知残留，通知栏永久卡在「0 位 / 准备中…」。
 * 这些规则把「文案不写死数量」「首轮前说『准备中』有期限」「渠道只有一个出口」锁住。
 */
class MonitorNotificationTest {

    @Test
    fun `标题按隐藏与暂停状态区分且带上真实数量`() {
        assertEquals("正在检测 6 位主播", NotificationHelper.monitorTitle(6, false, false))
        assertEquals("正在后台检测 6 位主播", NotificationHelper.monitorTitle(6, true, false))
        assertEquals("已暂停检测 · 6 位主播", NotificationHelper.monitorTitle(6, false, true))
        // 暂停优先于隐藏，避免开着隐藏时暂停被显示成「后台检测」
        assertEquals("已暂停检测 · 6 位主播", NotificationHelper.monitorTitle(6, true, true))
    }

    @Test
    fun `渠道选择只有隐藏状态一个出口`() {
        assertEquals(NotificationHelper.CHANNEL_MONITOR, NotificationHelper.monitorChannelId(false))
        assertEquals(
            NotificationHelper.CHANNEL_MONITOR_SILENT,
            NotificationHelper.monitorChannelId(true)
        )
    }

    @Test
    fun `还没跑第一轮时说准备中但带上真实数量`() {
        val text = NotificationHelper.monitorDetail(count = 6, lastTime = null)
        assertEquals("正在准备首次检测 · 共 6 位", text)
        // 关键回归：不再出现写死的「0 位」
        assertFalse(text.contains("0 位"))
    }

    @Test
    fun `首轮之后显示最近检测时间与数量`() {
        assertEquals(
            "最近检测 20:31:05 · 共 6 位",
            NotificationHelper.monitorDetail(count = 6, lastTime = "20:31:05")
        )
    }

    @Test
    fun `有异常时把异常数带上`() {
        assertEquals(
            "最近检测 20:31:05 · 共 6 位 · 2 项异常",
            NotificationHelper.monitorDetail(count = 6, lastTime = "20:31:05", errors = 2)
        )
    }

    @Test
    fun `没有关注时不写检测时间以外的东西`() {
        assertEquals("暂无关注", NotificationHelper.monitorDetail(count = 0, lastTime = null))
        assertEquals(
            "暂无关注，最近检测 20:31:05",
            NotificationHelper.monitorDetail(count = 0, lastTime = "20:31:05")
        )
    }

    @Test
    fun `跳过检测时说等待且带上最近检测时刻`() {
        assertEquals(
            "等待 Wi-Fi（当前不是 Wi-Fi） · 最近检测 20:31:05",
            NotificationHelper.monitorDetail(
                count = 6, lastTime = "20:31:05", skip = MonitorEngine.SkipReason.WIFI_ONLY
            )
        )
        assertEquals(
            "等待网络（无可用连接）",
            NotificationHelper.monitorDetail(
                count = 6, lastTime = null, skip = MonitorEngine.SkipReason.NO_NETWORK
            )
        )
    }
}
