package com.example.livenotify

import android.app.Application
import android.util.Log
import com.example.livenotify.monitor.MonitorService
import com.example.livenotify.monitor.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class LiveNotifyApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        NotificationHelper.createChannels(this)

        CoroutineScope(Dispatchers.Default).launch {
            try {
                val s = Graph.settings.current()
                // 参数没变时 applyAlertSettings 直接返回；只有用户改过响铃/震动才会重建渠道，
                // 因此不会像以前那样每次冷启动都清掉已弹通知和用户的渠道设置
                NotificationHelper.applyAlertSettings(this@LiveNotifyApp, s.notifySound, s.notifyVibrate)
                if (s.monitorEnabled) {
                    MonitorService.start(this@LiveNotifyApp)
                }
            } catch (t: Throwable) {
                // 后台启动前台服务在 Android 12+ 可能受限，用户打开 App 时会再次拉起
                Log.w("LiveNotifyApp", "auto start monitor failed: ${t.message}")
            }
        }
    }
}
