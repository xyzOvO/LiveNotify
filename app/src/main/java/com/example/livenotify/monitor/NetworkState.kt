package com.example.livenotify.monitor

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * 网络可用性。
 *
 * 只看「有没有连上」是不够的：连着一个没有出口的 Wi-Fi（酒店门户、地铁隧道里的残留关联）
 * 时请求一样会发出去，然后一个个等到超时。抖音是逐个主播请求，这里浪费的时间与电量
 * 会随着关注数量线性增长，还会把一堆主播的错误次数顶上去。
 *
 * 所以用系统已经做过的连通性验证（NET_CAPABILITY_VALIDATED）作为判据：
 * 验证没过就当作没有网络，直接跳过本轮，一次请求都不发。
 */
enum class NetStatus {
    /** 有网且已通过系统连通性验证 */
    ONLINE,

    /** 完全没有活动网络 */
    NO_NETWORK,

    /** 连上了，但没有出口（强制门户 / 未验证 / 无法上网） */
    NO_INTERNET
}

/** 一次读取的结果：类型用于界面说明，状态用于决定要不要发请求 */
data class NetSnapshot(val kind: NetworkKind, val status: NetStatus) {
    val online: Boolean get() = status == NetStatus.ONLINE
}

object NetworkState {

    /**
     * 纯函数版本，便于单测。任何一项不满足都算不能上网。
     */
    fun classify(
        hasActiveNetwork: Boolean,
        internetCapability: Boolean,
        validated: Boolean
    ): NetStatus = when {
        !hasActiveNetwork -> NetStatus.NO_NETWORK
        !internetCapability -> NetStatus.NO_INTERNET
        !validated -> NetStatus.NO_INTERNET
        else -> NetStatus.ONLINE
    }

    /**
     * 走 VPN 时的判定：**只看底层**（真正承载流量的那张网）。
     *
     * 两个方向都实测踩过：
     * 1. Wi-Fi 和移动数据都关掉后，VPN 那张网络仍然挂着 `VALIDATED`，而实际请求
     *    全部 DNS 失败——所以不能信 VPN 自己的标志位，必须确认底层在线；
     * 2. 反过来，不少代理类 VPN 的网络**永远没有** `VALIDATED` 位（系统不对 VPN
     *    做连通性验证），底层 Wi-Fi 明明已经验证通过，只看 VPN 自己却判成「没网」，
     *    于是检测永远暂停、界面一直写「等待网络」。
     *
     * 结论：底层有一张通过验证的网就是在线，底层没有就是没网。VPN 自己的
     * `VALIDATED` 与否不参与判断。
     */
    fun classifyVpn(
        underlyingCount: Int,
        underlyingOnline: Boolean
    ): NetStatus = when {
        underlyingCount <= 0 -> NetStatus.NO_NETWORK
        !underlyingOnline -> NetStatus.NO_INTERNET
        else -> NetStatus.ONLINE
    }

    /**
     * 读取当前网络。
     *
     * 读不到系统服务时按「有网」放行：宁可多查一次，也不能因为某个 ROM 的行为差异
     * 把开播提醒永久停掉——那是这个应用唯一的核心功能。
     */
    fun read(context: Context): NetSnapshot {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return NetSnapshot(NetworkKind.OTHER, NetStatus.ONLINE)
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            ?: return NetSnapshot(NetworkKind.OTHER, NetStatus.NO_NETWORK)

        val underlying = otherNetworks(cm)
        val internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val status = if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            classifyVpn(
                underlyingCount = underlying.size,
                underlyingOnline = underlying.any {
                    it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                }
            )
        } else {
            classify(hasActiveNetwork = true, internetCapability = internet, validated = validated)
        }
        return NetSnapshot(directKind(caps) ?: kindOf(underlying), status)
    }

    /** 除 VPN 以外的网络（真实承载流量的那张）。读不到就当没有 */
    private fun otherNetworks(cm: ConnectivityManager): List<NetworkCapabilities> = runCatching {
        cm.allNetworks
            .mapNotNull { cm.getNetworkCapabilities(it) }
            .filter { !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
    }.getOrDefault(emptyList())

    /** 同时连着 Wi-Fi 与移动网络时优先算 Wi-Fi：「仅 Wi-Fi 下检测」不该因此暂停 */
    private fun kindOf(networks: List<NetworkCapabilities>): NetworkKind {
        val kinds = networks.mapNotNull { directKind(it) }
        return if (kinds.contains(NetworkKind.WIFI)) NetworkKind.WIFI
        else kinds.firstOrNull() ?: NetworkKind.OTHER
    }

    private fun directKind(caps: NetworkCapabilities): NetworkKind? = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkKind.WIFI
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkKind.CELLULAR
        else -> null
    }
}
