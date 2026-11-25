package com.example.livenotify

import com.example.livenotify.monitor.keepLatestLiveStart
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 回归锁：「上次开播」只前进不后退。
 *
 * 线上实际故障：抖音下播后接口把 createTime 退回上一场（更早），
 * 列表副标题从「刚刚」倒退成「18 小时前」，而历史记录里那条开播提醒还在。
 */
class LiveStartStampTest {

    @Test
    fun `平台回退旧场次时 保留已有记录`() {
        assertEquals(1000L, keepLatestLiveStart(previous = 1000L, observed = 200L))
    }

    @Test
    fun `平台给出更新场次时 采用新值`() {
        assertEquals(3000L, keepLatestLiveStart(previous = 1000L, observed = 3000L))
    }

    @Test
    fun `首次记录 采用观测值`() {
        assertEquals(1000L, keepLatestLiveStart(previous = 0L, observed = 1000L))
    }

    @Test
    fun `平台没给开播时刻 保留已有记录`() {
        assertEquals(1000L, keepLatestLiveStart(previous = 1000L, observed = 0L))
    }

    @Test
    fun `两边都为空 仍为空`() {
        assertEquals(0L, keepLatestLiveStart(previous = 0L, observed = 0L))
    }
}
