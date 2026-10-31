package com.example.livenotify

import com.example.livenotify.bili.Summary
import com.example.livenotify.bili.UserAnalysis
import com.example.livenotify.ui.StatsViewModel
import com.example.livenotify.ui.analysisRange
import com.example.livenotify.ui.belongsTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class StatsVisibilityTest {

    private val emptyAnalysis = UserAnalysis(
        names = emptyList(),
        summary = Summary(0, 0, 0.0, 0, 0, 0, 0, 0),
        insights = null,
        days = emptyList(),
        eventTypes = emptyList(),
        affinities = emptyList(),
        topPayments = emptyList(),
        areas = emptyList(),
        rhythm = emptyList(),
        wordCloud = emptyList()
    )

    @Test
    fun `分析结果只匹配当前用户和范围`() {
        val done = StatsViewModel.AnalysisUi.Done(42L, 90, emptyAnalysis, 1L, 2L)
        assertTrue(done.belongsTo(42L, 90))
        assertFalse(done.belongsTo(43L, 90))
        assertFalse(done.belongsTo(42L, 30))
    }

    @Test
    fun `运行中和失败状态也绑定用户与范围`() {
        val running = StatsViewModel.AnalysisUi.Running(42L, 20, "分析中", 30)
        assertTrue(running.belongsTo(42L, 30))
        assertFalse(running.belongsTo(42L, 90))

        val failed = StatsViewModel.AnalysisUi.Failed(42L, "失败", 30)
        assertTrue(failed.belongsTo(42L, 30))
        assertFalse(failed.belongsTo(43L, 30))
    }

    @Test
    fun `最近互动只匹配当前用户`() {
        val loading = StatsViewModel.RecentUi.Loading(42L)
        assertTrue(loading.belongsTo(42L))
        assertFalse(loading.belongsTo(43L))

        val done = StatsViewModel.RecentUi.Done(42L, emptyList())
        assertTrue(done.belongsTo(42L))
        assertFalse(done.belongsTo(43L))

        val failed = StatsViewModel.RecentUi.Failed(42L, "失败")
        assertTrue(failed.belongsTo(42L))
        assertFalse(failed.belongsTo(43L))
    }

    @Test
    fun `近30天覆盖30个自然日而不是31天`() {
        val now = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 1, 12, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val (from, to) = analysisRange(30, now)
        assertEquals(now, to)

        // 起点必须是 10-03 00:00：今天(11-01) + 前 29 天
        val expectedFrom = Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, -29)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertEquals(expectedFrom, from)

        val fromCal = Calendar.getInstance().apply { timeInMillis = from }
        assertEquals(0, fromCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, fromCal.get(Calendar.MINUTE))
        assertEquals(3, fromCal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `近1天从今天零点开始`() {
        val now = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 1, 9, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val (from, _) = analysisRange(1, now)
        val fromCal = Calendar.getInstance().apply { timeInMillis = from }
        assertEquals(1, fromCal.get(Calendar.DAY_OF_MONTH))
        assertEquals(0, fromCal.get(Calendar.HOUR_OF_DAY))
    }
}
