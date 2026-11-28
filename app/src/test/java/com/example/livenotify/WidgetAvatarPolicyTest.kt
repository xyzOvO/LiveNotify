package com.example.livenotify

import com.example.livenotify.monitor.WidgetAvatarPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 小组件头像下载与缓存判据单测（纯 JVM）。
 * 回归锁：缓存容量单位错配曾把 2.25MB 预算缩水成 64KB。
 */
class WidgetAvatarPolicyTest {

    @Test
    fun `缓存预算按字节计能容纳约六十张头像`() {
        // 单张 96×96 ARGB = 36864 字节；64 张 ≈ 2.25MB。
        assertEquals(36864, WidgetAvatarPolicy.avatarBytes())
        assertEquals(2_359_296, WidgetAvatarPolicy.CACHE_MAX_BYTES)
        val capacity = WidgetAvatarPolicy.cacheCapacityInAvatars()
        assertTrue("缓存至少应容纳 60 张头像，实际 $capacity", capacity >= 60)
    }

    @Test
    fun `只接受二xx响应与未知或合理长度`() {
        assertTrue(WidgetAvatarPolicy.acceptResponse(200, 100_000L))
        assertTrue(WidgetAvatarPolicy.acceptResponse(204, -1L)) // 未知长度，交给读取上限兜底
        assertFalse(WidgetAvatarPolicy.acceptResponse(302, 100L))
        assertFalse(WidgetAvatarPolicy.acceptResponse(404, 100L))
        assertFalse(WidgetAvatarPolicy.acceptResponse(500, 100L))
        assertFalse(WidgetAvatarPolicy.acceptResponse(200, WidgetAvatarPolicy.MAX_DOWNLOAD_BYTES + 1))
    }

    @Test
    fun `读取上限按累计字节判定`() {
        assertTrue(WidgetAvatarPolicy.withinReadLimit(0L))
        assertTrue(WidgetAvatarPolicy.withinReadLimit(WidgetAvatarPolicy.MAX_DOWNLOAD_BYTES))
        assertFalse(WidgetAvatarPolicy.withinReadLimit(WidgetAvatarPolicy.MAX_DOWNLOAD_BYTES + 1))
    }

    @Test
    fun `解码尺寸须有像素且不过大`() {
        assertTrue(WidgetAvatarPolicy.reasonableSize(96, 96))
        assertTrue(WidgetAvatarPolicy.reasonableSize(512, 300))
        assertFalse(WidgetAvatarPolicy.reasonableSize(0, 96))
        assertFalse(WidgetAvatarPolicy.reasonableSize(96, 0))
        assertFalse(WidgetAvatarPolicy.reasonableSize(513, 96))
        assertFalse(WidgetAvatarPolicy.reasonableSize(96, 10000))
    }
}
