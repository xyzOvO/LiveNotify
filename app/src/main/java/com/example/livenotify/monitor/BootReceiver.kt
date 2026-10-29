package com.example.livenotify.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val enabled = com.example.livenotify.Graph.settings.current().monitorEnabled
                if (enabled) MonitorService.start(context)
            } catch (t: Throwable) {
                // Android 12+ 后台启动前台服务可能被拒，忽略即可，用户打开 App 时会重新拉起
                Log.w("BootReceiver", "auto start failed: ${t.message}")
            } finally {
                pending.finish()
            }
        }
    }
}
