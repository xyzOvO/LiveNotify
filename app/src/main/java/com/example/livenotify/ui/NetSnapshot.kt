package com.example.livenotify.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.example.livenotify.monitor.NetSnapshot
import com.example.livenotify.monitor.NetworkState

/**
 * 当前网络快照，并且随网络切换实时更新（系统回调驱动，不轮询）。
 *
 * 主页状态行和设置页状态卡都用它：网络一断立刻改口「等待网络」，
 * 一恢复立刻变回——不能等下一轮检测才发现，那中间的几分钟里界面是在撒谎。
 */
@Composable
internal fun rememberNetSnapshot(): NetSnapshot {
    val context = LocalContext.current
    var snap by remember { mutableStateOf(NetworkState.read(context)) }
    DisposableEffect(context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                snap = NetworkState.read(context)
            }

            override fun onLost(network: Network) {
                snap = NetworkState.read(context)
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                snap = NetworkState.read(context)
            }
        }
        val registered = runCatching { cm?.registerDefaultNetworkCallback(callback) }.isSuccess
        onDispose {
            if (registered) runCatching { cm?.unregisterNetworkCallback(callback) }
        }
    }
    return snap
}
