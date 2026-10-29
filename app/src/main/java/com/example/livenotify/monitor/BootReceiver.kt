package com.example.livenotify.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 开机 / 应用更新 / 解锁时把检测服务拉起来。
 *
 * 三道广播的实际作用不同：
 * - `BOOT_COMPLETED`：正常开机。国产 ROM 默认拦截第三方自启动，这条常常根本收不到
 * - `MY_PACKAGE_REPLACED`：覆盖安装后，系统一定会发，用于升级后自动恢复
 * - `USER_PRESENT`：解锁。部分 ROM 吞掉开机广播但仍会发这条，作为第二道保险
 *
 * 注意：Android 12+ 对「后台启动前台服务」有限制，只有 BOOT_COMPLETED / MY_PACKAGE_REPLACED
 * 属于豁免场景；USER_PRESENT 不在豁免清单里，服务没在跑时大概率会被拒。
 * 所以这条只在低版本或服务已被系统挂起时有效，失败只记日志，不打扰用户。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val isBoot = action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (!isBoot && action != Intent.ACTION_USER_PRESENT) return

        // 解锁广播一天可能来几十次，服务已经在跑就什么都不用做
        if (MonitorService.isRunning) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val enabled = com.example.livenotify.Graph.settings.current().monitorEnabled
                if (enabled) {
                    MonitorService.start(context)
                    Log.i(TAG, "auto start on $action")
                }
            } catch (t: Throwable) {
                // Android 12+ 后台启动前台服务可能被拒，忽略即可，用户打开 App 时会重新拉起
                Log.w(TAG, "auto start on $action failed: ${t.message}")
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
