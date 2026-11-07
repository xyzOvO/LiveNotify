package com.example.livenotify

import com.example.livenotify.monitor.MonitorEngine
import com.example.livenotify.monitor.NetSnapshot
import com.example.livenotify.monitor.NetStatus
import com.example.livenotify.monitor.NetworkKind
import com.example.livenotify.ui.homeStatusLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主页顶部状态行的文案规则。
 *
 * 锁住的关键行为：**跳过检测时绝不能说「都很安静」**——那会让用户以为
 * 已经检测过了，实际一次请求都没发（手动检测的 toast 曾有同类问题）。
 */
class HomeStatusTest {

    @Test
    fun `仅 WiFi 下移动网络要改口等待`() {
        val line = homeStatusLine(
            monitoring = true, total = 3, liveCount = 0, pausedCount = 0,
            skip = MonitorEngine.SkipReason.WIFI_ONLY
        )
        assertEquals("监控中 · 等待 Wi-Fi · 3 位待检测", line)
        assertFalse(line.contains("安静"))
    }

    @Test
    fun `没有网络要改口等待`() {
        val line = homeStatusLine(
            monitoring = true, total = 20, liveCount = 0, pausedCount = 0,
            skip = MonitorEngine.SkipReason.NO_NETWORK
        )
        assertEquals("监控中 · 等待网络 · 20 位待检测", line)
        assertFalse(line.contains("安静"))
    }

    @Test
    fun `服务没跑时不显示等待`() {
        // 「已暂停」本身就是原因；这时再说「等待网络」没有意义
        val line = homeStatusLine(
            monitoring = false, total = 3, liveCount = 0, pausedCount = 0,
            skip = MonitorEngine.SkipReason.NO_NETWORK
        )
        assertEquals("已暂停 · 3 位都很安静", line)
    }

    @Test
    fun `正常运行且没人开播`() {
        assertEquals(
            "监控中 · 3 位都很安静",
            homeStatusLine(true, 3, 0, 0, skip = null)
        )
    }

    @Test
    fun `有人正在直播`() {
        assertEquals(
            "监控中 · 1 / 3 位正在直播",
            homeStatusLine(true, 3, 1, 0, skip = null)
        )
    }

    @Test
    fun `没有任何关注`() {
        assertEquals(
            "监控中 · 暂无关注",
            homeStatusLine(true, 0, 0, 0, skip = null)
        )
    }

    @Test
    fun `全部已暂停不再重复计数`() {
        val line = homeStatusLine(true, 0, 0, 3, skip = null)
        assertEquals("监控中 · 全部已暂停", line)
        // 旧实现会拼出「全部已暂停 · 3 位已暂停」
        assertEquals(1, line.split("已暂停").size - 1)
    }

    @Test
    fun `部分暂停单独提示`() {
        assertEquals(
            "监控中 · 3 位都很安静 · 1 位已暂停",
            homeStatusLine(true, 3, 0, 1, skip = null)
        )
    }

    @Test
    fun `跳过时暂停数仍要带上`() {
        val line = homeStatusLine(
            monitoring = true, total = 4, liveCount = 0, pausedCount = 1,
            skip = MonitorEngine.SkipReason.NO_NETWORK
        )
        assertTrue(line.contains("等待网络 · 4 位待检测"))
        assertTrue(line.contains("1 位已暂停"))
        assertFalse(line.contains("安静"))
    }

    @Test
    fun `skipReason 与引擎同一判据同一优先级`() {
        // 「仅 Wi-Fi」是用户自己的选择，优先于「没有网络」这种故障说明
        assertEquals(
            MonitorEngine.SkipReason.WIFI_ONLY,
            MonitorEngine.skipReason(true, NetSnapshot(NetworkKind.CELLULAR, NetStatus.ONLINE))
        )
        assertEquals(
            MonitorEngine.SkipReason.NO_NETWORK,
            MonitorEngine.skipReason(false, NetSnapshot(NetworkKind.OTHER, NetStatus.NO_NETWORK))
        )
        // 连上了但没通过验证（强制门户）也算没网
        assertEquals(
            MonitorEngine.SkipReason.NO_NETWORK,
            MonitorEngine.skipReason(false, NetSnapshot(NetworkKind.WIFI, NetStatus.NO_INTERNET))
        )
        assertEquals(
            null,
            MonitorEngine.skipReason(false, NetSnapshot(NetworkKind.WIFI, NetStatus.ONLINE))
        )
        assertEquals(
            null,
            MonitorEngine.skipReason(true, NetSnapshot(NetworkKind.WIFI, NetStatus.ONLINE))
        )
        // 移动网络、没开仅 Wi-Fi：正常检测
        assertEquals(
            null,
            MonitorEngine.skipReason(false, NetSnapshot(NetworkKind.CELLULAR, NetStatus.ONLINE))
        )
    }
}
