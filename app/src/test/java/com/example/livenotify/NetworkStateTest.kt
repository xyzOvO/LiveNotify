package com.example.livenotify

import com.example.livenotify.monitor.NetSnapshot
import com.example.livenotify.monitor.NetStatus
import com.example.livenotify.monitor.NetworkKind
import com.example.livenotify.monitor.NetworkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 无网络时必须老老实实跳过：任何一项判据放水，都会变成「连着没有出口的 Wi-Fi
 * 也照样逐个主播请求，然后一起等到超时」。
 */
class NetworkStateTest {

    @Test
    fun `有网络且通过连通性验证才算在线`() {
        assertEquals(
            NetStatus.ONLINE,
            NetworkState.classify(hasActiveNetwork = true, internetCapability = true, validated = true)
        )
    }

    @Test
    fun `没有活动网络`() {
        assertEquals(
            NetStatus.NO_NETWORK,
            NetworkState.classify(hasActiveNetwork = false, internetCapability = false, validated = false)
        )
    }

    @Test
    fun `连上了但系统没验证通过当作没有出口`() {
        // 强制门户（酒店 / 机场 Wi-Fi）：关联成功，实际打不开任何网页
        assertEquals(
            NetStatus.NO_INTERNET,
            NetworkState.classify(hasActiveNetwork = true, internetCapability = true, validated = false)
        )
    }

    @Test
    fun `缺少 INTERNET 能力也算没有出口`() {
        assertEquals(
            NetStatus.NO_INTERNET,
            NetworkState.classify(hasActiveNetwork = true, internetCapability = false, validated = true)
        )
    }

    @Test
    fun `只有 ONLINE 才允许发请求`() {
        val online = NetSnapshot(NetworkKind.WIFI, NetStatus.ONLINE)
        val portal = NetSnapshot(NetworkKind.WIFI, NetStatus.NO_INTERNET)
        val none = NetSnapshot(NetworkKind.OTHER, NetStatus.NO_NETWORK)
        assertTrue(online.online)
        assertFalse(portal.online)
        assertFalse(none.online)
    }

    // ── VPN：两个方向都实测踩过，判定只看底层（真正承载流量的那张网） ──
    // ① 底层断开后 VPN 仍自称 VALIDATED → 不能信它自己的标志位
    // ② 不少代理类 VPN 永远没有 VALIDATED 位 → 不能因为它而把在线判成没网

    @Test
    fun `VPN 还在但底层已经没有网络`() {
        assertEquals(
            NetStatus.NO_NETWORK,
            NetworkState.classifyVpn(underlyingCount = 0, underlyingOnline = false)
        )
    }

    @Test
    fun `底层有一张通过验证的网就算在线`() {
        assertEquals(
            NetStatus.ONLINE,
            NetworkState.classifyVpn(underlyingCount = 1, underlyingOnline = true)
        )
    }

    @Test
    fun `底层网络存在但没通过验证`() {
        assertEquals(
            NetStatus.NO_INTERNET,
            NetworkState.classifyVpn(underlyingCount = 1, underlyingOnline = false)
        )
    }

    @Test
    fun `VPN 自己没验证位但底层在线仍是在线`() {
        // 修复点：代理类 VPN 的网络往往永远没有 VALIDATED，
        // 以前这里会判成 NO_INTERNET，检测永远暂停、界面一直「等待网络」
        assertEquals(
            NetStatus.ONLINE,
            NetworkState.classifyVpn(underlyingCount = 2, underlyingOnline = true)
        )
    }
}
