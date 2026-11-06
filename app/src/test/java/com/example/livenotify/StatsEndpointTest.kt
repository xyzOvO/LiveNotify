package com.example.livenotify.bili

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsEndpointTest {

    @Test
    fun defaultOrderStartsWithPrimary() {
        val chain = StatsEndpoint.ordered(null)
        assertEquals(StatsEndpoint.PRIMARY, chain.first())
        assertEquals(StatsEndpoint.ORIGINS.size, chain.size)
        assertEquals(chain.size, chain.distinct().size)
    }

    @Test
    fun preferredOriginComesFirstWithoutDuplicates() {
        val backup = StatsEndpoint.ORIGINS[1]
        val chain = StatsEndpoint.ordered(backup)
        assertEquals(backup, chain.first())
        assertEquals(StatsEndpoint.ORIGINS.size, chain.size)
        assertEquals(chain.size, chain.distinct().size)
    }

    @Test
    fun unknownPreferredOriginIsIgnored() {
        assertEquals(StatsEndpoint.ordered(null), StatsEndpoint.ordered("https://evil.example.com"))
        assertEquals(StatsEndpoint.ordered(null), StatsEndpoint.ordered(""))
    }

    @Test
    fun rewriteOnlyTouchesPrimaryPrefix() {
        val backup = StatsEndpoint.ORIGINS[1]
        val url = "${StatsEndpoint.PRIMARY}/api/v2/channel?uId=42"
        assertEquals("$backup/api/v2/channel?uId=42", StatsEndpoint.rewrite(url, StatsEndpoint.PRIMARY, backup))
        assertEquals(url, StatsEndpoint.rewrite(url, StatsEndpoint.PRIMARY, StatsEndpoint.PRIMARY))
        val other = "https://api.live.bilibili.com/room/v1/Room/get_info"
        assertEquals(other, StatsEndpoint.rewrite(other, StatsEndpoint.PRIMARY, backup))
    }

    @Test
    fun docsUrlFollowsCurrentOrigin() {
        assertTrue(StatsEndpoint.docsUrl(StatsEndpoint.PRIMARY).endsWith("/swagger/index.html"))
        assertTrue(StatsEndpoint.docsUrl(StatsEndpoint.ORIGINS[1]).startsWith(StatsEndpoint.ORIGINS[1]))
        assertEquals("主节点", StatsEndpoint.label(StatsEndpoint.PRIMARY))
        assertEquals("备用节点", StatsEndpoint.label(StatsEndpoint.ORIGINS[1]))
    }
}
