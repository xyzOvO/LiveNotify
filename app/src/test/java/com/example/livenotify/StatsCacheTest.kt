package com.example.livenotify

import com.example.livenotify.bili.Affinity
import com.example.livenotify.bili.AreaItem
import com.example.livenotify.bili.CareerStats
import com.example.livenotify.bili.ChannelStats
import com.example.livenotify.bili.DayStat
import com.example.livenotify.bili.EventTypeStat
import com.example.livenotify.bili.FansPoint
import com.example.livenotify.bili.Insights
import com.example.livenotify.bili.Payment
import com.example.livenotify.bili.StatsCache
import com.example.livenotify.bili.StatsCacheStore
import com.example.livenotify.bili.Summary
import com.example.livenotify.bili.TrendMonth
import com.example.livenotify.bili.UserAnalysis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 缓存是「网络出问题时唯一还能看的东西」，编解码必须完整往返：
 * 少一个字段，界面上就是一片 0 或空白，比加载失败更让人误会。
 */
class StatsCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun sampleChannel(uid: Long = 42L) = ChannelStats(
        uid = uid,
        name = "测试主播",
        roomId = 9527L,
        faceUrl = "https://example.com/face.jpg",
        isLiving = true,
        title = "今天的直播",
        sign = "签名里有 \"引号\" 和 \\ 反斜杠",
        fansCount = 12345,
        followCount = 678,
        totalDanmaku = 9_000_000L,
        totalIncome = 12345.67,
        liveCount = 321L,
        liveSeconds = 456789L,
        lastLiveAt = 1_700_000_000_000L,
        lastStatAt = 1_700_000_100_000L,
        fansHistory = listOf(FansPoint(1_700_000_000_000L, 12000), FansPoint(1_700_100_000_000L, 12345))
    )

    private fun sampleAnalysis() = UserAnalysis(
        names = listOf("旧昵称", "新昵称"),
        summary = Summary(
            messages = 100, interactions = 90, payment = 66.6, blockCount = 1,
            activeDays = 12, appearanceLives = 30, interactiveLives = 20, paidLives = 5
        ),
        insights = Insights(
            repeatIndex = 1.5, maxRepeatedText = "哈哈", maxRepeatedCount = 20,
            emojiRatio = 0.25, nightOwlRatio = 0.5, avgLength = 7.5, uniqueRatio = 0.8
        ),
        days = listOf(
            DayStat("2026-10-01", 10, 9, 1.5, 2),
            DayStat("2026-10-02", 20, 18, 0.0, 3)
        ),
        eventTypes = listOf(EventTypeStat("弹幕", 100, 0.0), EventTypeStat("礼物", 5, 66.6)),
        affinities = listOf(
            Affinity(1L, "甲主播", 11L, "f1", 50, 30.0, 5, 6, 0.9),
            Affinity(2L, "乙主播", 22L, "f2", 20, 10.0, 3, 4, 0.5)
        ),
        topPayments = listOf(Payment("礼物", 30.0, "甲主播", 1_700_000_000_000L)),
        areas = listOf(AreaItem("虚拟主播", 8)),
        rhythm = listOf(listOf(1, 2, 3), listOf(4, 5, 6)),
        wordCloud = listOf("哈哈" to 20, "好耶" to 12)
    )

    @Test
    fun `主播数据完整往返`() {
        val e = StatsCache.ChannelEntry(
            savedAt = 1_700_000_200_000L,
            channel = sampleChannel(),
            trend = listOf(TrendMonth("2026-10", 100, 20, 30, 10, 2, 55.5)),
            career = CareerStats(321, 100, 456789L, 9_000_000L, 12345.67, "2020-01-01", "2026-10-30")
        )
        val back = StatsCache.decodeChannel(StatsCache.encodeChannel(e))
        assertNotNull(back)
        assertEquals(e, back)
    }

    @Test
    fun `没有生涯数据时也能往返`() {
        val e = StatsCache.ChannelEntry(123L, sampleChannel(), emptyList(), career = null)
        val back = StatsCache.decodeChannel(StatsCache.encodeChannel(e))
        assertEquals(e, back)
    }

    @Test
    fun `分析结果完整往返`() {
        val e = StatsCache.AnalysisEntry(
            userId = 42L, rangeDays = 90, savedAt = 1_700_000_000_000L,
            fromMs = 1L, toMs = 2L, data = sampleAnalysis()
        )
        val back = StatsCache.decodeAnalysis(StatsCache.encodeAnalysis(e))
        assertNotNull(back)
        assertEquals(e, back)
    }

    @Test
    fun `没有画像时也能往返`() {
        val e = StatsCache.AnalysisEntry(
            userId = 42L, rangeDays = 30, savedAt = 1_700_000_000_000L,
            fromMs = 1L, toMs = 2L,
            data = sampleAnalysis().copy(insights = null, rhythm = emptyList(), wordCloud = emptyList())
        )
        val back = StatsCache.decodeAnalysis(StatsCache.encodeAnalysis(e))
        assertEquals(e, back)
    }

    @Test
    fun `坏文件当作没有缓存而不是崩溃`() {
        assertNull(StatsCache.decodeChannel("{不是 json"))
        assertNull(StatsCache.decodeChannel(""))
        assertNull(StatsCache.decodeChannel("{}"))
        assertNull(StatsCache.decodeAnalysis("[]"))
    }

    @Test
    fun `版本更高的缓存不认`() {
        val text = """{"version": 99, "savedAt": 1, "channel": {"uid": 42}}"""
        assertNull("新版本写的数据结构可能对不上，宁可当没有缓存", StatsCache.decodeChannel(text))
    }

    @Test
    fun `超过 6 小时算旧数据`() {
        val now = 10_000_000_000L
        assertFalse(StatsCache.isStale(now - 60_000L, now))
        assertFalse(StatsCache.isStale(now - StatsCache.STALE_AFTER_MS, now))
        assertTrue(StatsCache.isStale(now - StatsCache.STALE_AFTER_MS - 1L, now))
        assertTrue("没有时间戳也应当作旧数据", StatsCache.isStale(0L, now))
    }

    @Test
    fun `落盘后能读回来`() {
        val store = StatsCacheStore(tmp.root)
        val e = StatsCache.ChannelEntry(1_700_000_000_000L, sampleChannel(), emptyList(), null)
        store.writeChannel(e)
        assertEquals(e, store.readChannel(42L))
        assertNull("没写过的主播不该凭空出现", store.readChannel(43L))
    }

    @Test
    fun `分析缓存按 uid 加时间范围区分`() {
        val store = StatsCacheStore(tmp.root)
        val e90 = StatsCache.AnalysisEntry(42L, 90, 1L, 1L, 2L, sampleAnalysis())
        val e30 = e90.copy(rangeDays = 30, savedAt = 2L)
        store.writeAnalysis(e90)
        store.writeAnalysis(e30)
        assertEquals(90, store.readAnalysis(42L, 90)?.rangeDays)
        assertEquals(30, store.readAnalysis(42L, 30)?.rangeDays)
        assertNull(store.readAnalysis(42L, 365))
    }

    @Test
    fun `缓存文件数量有上限`() {
        val store = StatsCacheStore(tmp.root)
        repeat(6) { i ->
            store.writeAnalysis(
                StatsCache.AnalysisEntry(100L + i, 30, i.toLong(), 1L, 2L, sampleAnalysis())
            )
        }
        store.prune(keepChannels = 20, keepAnalyses = 3)
        val left = tmp.root.listFiles()
            ?.first { it.name == "stats-cache" }
            ?.listFiles()
            ?.count { it.name.startsWith("analysis-") && it.name.endsWith(".json") }
        assertEquals(3, left)
    }
}
