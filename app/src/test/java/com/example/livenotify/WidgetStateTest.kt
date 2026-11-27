package com.example.livenotify

import com.example.livenotify.data.StreamerEntity
import com.example.livenotify.monitor.WidgetLayout
import com.example.livenotify.monitor.WidgetState
import com.example.livenotify.monitor.WidgetStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 桌面小组件纯函数层单测。不依赖 Android，全部跑在 JVM。 */
class WidgetStateTest {

    private fun streamer(
        id: Long,
        nickname: String = "主播$id",
        live: Boolean = false,
        enabled: Boolean = true,
        missing: Boolean = false,
        errorCount: Int = 0,
        lastCheckedAt: Long = 0L,
        title: String = "标题$id",
        platformId: String = "bilibili",
        avatarUrl: String? = null,
    ) = StreamerEntity(
        id = id,
        platformId = platformId,
        anchorId = "anchor$id",
        nickname = nickname,
        avatarUrl = avatarUrl,
        title = title,
        roomUrl = "https://example.com/$id",
        isLive = live,
        enabled = enabled,
        missing = missing,
        errorCount = errorCount,
        lastCheckedAt = lastCheckedAt,
    )

    @Test
    fun 状态判定按优先级() {
        val now = 1_000_000_000L
        // 未开启优先级最高
        assertEquals(
            WidgetStatus.NOT_RUNNING,
            WidgetState.widgetStatus(false, false, false, false, 0L, now)
        )
        // 服务在跑但无网
        assertEquals(
            WidgetStatus.WAIT_NETWORK,
            WidgetState.widgetStatus(true, false, false, false, 0L, now)
        )
        // 仅 WiFi 下检测但当前是流量
        assertEquals(
            WidgetStatus.WAIT_WIFI,
            WidgetState.widgetStatus(true, true, true, false, 0L, now)
        )
        // 从没成功检测过
        assertEquals(
            WidgetStatus.FETCHING,
            WidgetState.widgetStatus(true, true, false, false, 0L, now)
        )
        // 成功过但超过阈值
        assertEquals(
            WidgetStatus.STALE,
            WidgetState.widgetStatus(
                true, true, false, false,
                now - WidgetState.STALE_THRESHOLD_MS - 1, now
            )
        )
        // 正常
        assertEquals(
            WidgetStatus.NORMAL,
            WidgetState.widgetStatus(true, true, false, false, now - 60_000L, now)
        )
    }

    @Test
    fun 布局按尺寸选择() {
        assertEquals(WidgetLayout.COMPACT, WidgetState.pickLayout(150, 200))
        assertEquals(WidgetLayout.COMPACT, WidgetState.pickLayout(300, 100))
        assertEquals(WidgetLayout.EXPANDED, WidgetState.pickLayout(180, 110))
        assertEquals(WidgetLayout.EXPANDED, WidgetState.pickLayout(400, 300))
    }

    @Test
    fun 溢出提示() {
        assertNull(WidgetState.overflowText(2, 2))
        assertNull(WidgetState.overflowText(1, 2))
        assertEquals("还有 3 位正在直播", WidgetState.overflowText(5, 2))
        assertEquals("还有 1 位正在直播", WidgetState.overflowText(5, 4))
    }

    @Test
    fun 最近成功检测忽略失败与缺失() {
        val list = listOf(
            streamer(1, lastCheckedAt = 100L, errorCount = 0),
            streamer(2, lastCheckedAt = 500L, errorCount = 2),       // 失败，忽略
            streamer(3, lastCheckedAt = 400L, missing = true),       // 缺失，忽略
            streamer(4, lastCheckedAt = 300L, errorCount = 0),
        )
        assertEquals(300L, WidgetState.lastSuccessfulCheck(list))
    }

    @Test
    fun 全部检测失败时最近成功为零() {
        val list = listOf(
            streamer(1, lastCheckedAt = 100L, errorCount = 3),
            streamer(2, lastCheckedAt = 200L, missing = true),
        )
        assertEquals(0L, WidgetState.lastSuccessfulCheck(list))
    }

    @Test
    fun 构建数据只收启用的在播主播并按昵称排() {
        val now = 1_000_000L
        val list = listOf(
            streamer(1, nickname = "Zeta", live = true, lastCheckedAt = now),
            streamer(2, nickname = "alpha", live = true, lastCheckedAt = now),
            streamer(3, nickname = "屏蔽", live = true, enabled = false, lastCheckedAt = now),
            streamer(4, nickname = "不在播", live = false, lastCheckedAt = now),
            streamer(5, nickname = "丢失", live = true, missing = true, lastCheckedAt = now),
        )
        val data = WidgetState.buildData(
            streamers = list,
            running = true,
            netOnline = true,
            wifiOnly = false,
            onWifi = false,
            now = now,
        )
        // enabled 的 4 位（含不在播的 4）；在播且未 missing 的 2 位
        assertEquals(4, data.total)
        assertEquals(2, data.live.size)
        assertEquals(listOf("alpha", "Zeta"), data.live.map { it.nickname })
        assertEquals(WidgetStatus.NORMAL, data.status)
    }

    @Test
    fun 判重数据没变不刷新() {
        val now = 1_000_000L
        val a = WidgetState.buildData(
            listOf(streamer(1, live = true, lastCheckedAt = now)),
            true, true, false, false, now
        )
        val b = WidgetState.buildData(
            listOf(streamer(1, live = true, title = "标题1", lastCheckedAt = now + 1)),
            true, true, false, false, now + 1
        )
        assertFalse(WidgetState.needsRefresh(a, b)) // 只 lastCheckedAt 变，不刷新
        assertTrue(WidgetState.needsRefresh(null, a))
        val c = WidgetState.buildData(
            listOf(streamer(1, live = true, title = "换标题了", lastCheckedAt = now + 2)),
            true, true, false, false, now + 2
        )
        assertTrue(WidgetState.needsRefresh(b, c)) // 标题变，刷新
    }

    @Test
    fun 状态文案六态齐全() {
        WidgetStatus.values().forEach { status ->
            assertTrue(WidgetState.statusText(status).isNotBlank())
        }
    }
}
