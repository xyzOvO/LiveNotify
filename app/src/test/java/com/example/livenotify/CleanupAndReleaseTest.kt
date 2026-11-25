package com.example.livenotify

import com.example.livenotify.monitor.HistoryCleanup
import com.example.livenotify.update.StableRelease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P1-E/H 抽出来的两个纯函数的回归测试 */
class CleanupAndReleaseTest {

    // ---- 历史清理节流 ----

    @Test fun `历史清理 从未清理或跨过间隔时到期`() {
        assertTrue(HistoryCleanup.due(0L, 1_000L))
        assertTrue(HistoryCleanup.due(1_000L, 1_000L + HistoryCleanup.INTERVAL_MS))
        assertTrue(HistoryCleanup.due(1_000L, 2_000L + HistoryCleanup.INTERVAL_MS))
    }

    @Test fun `历史清理 间隔内或时间回拨时不到期`() {
        assertFalse(HistoryCleanup.due(1_000L, 1_000L + HistoryCleanup.INTERVAL_MS - 1))
        assertFalse(HistoryCleanup.due(10_000L, 5_000L))
    }

    @Test fun `历史清理 保留期口径为 90 天`() {
        assertEquals(90L * 24 * 60 * 60 * 1000, HistoryCleanup.RETENTION_MS)
    }

    // ---- 稳定版判定 ----

    @Test fun `稳定版 非草稿非预发布才放行`() {
        assertTrue(StableRelease.isStable(draft = false, prerelease = false))
    }

    @Test fun `稳定版 草稿与预发布一律挡掉`() {
        assertFalse(StableRelease.isStable(draft = true, prerelease = false))
        assertFalse(StableRelease.isStable(draft = false, prerelease = true))
        assertFalse(StableRelease.isStable(draft = true, prerelease = true))
    }
}
