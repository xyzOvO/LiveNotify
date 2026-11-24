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
        com.example.livenotify.ui.ThemePrefs.init(this)
        // 建渠道之前先记「是否已存在」：这个次序决定后面是「按设置建」还是「读系统对账」
        val liveChannelExisted = NotificationHelper.liveChannelExists(this)
        NotificationHelper.createChannels(this)

        CoroutineScope(Dispatchers.Default).launch {
            try {
                val s = Graph.settings.current()
                if (liveChannelExisted) {
                    // 渠道已存在：声音/震动归系统管，这里只读回来把开关对齐，绝不重建——
                    // 重建会清掉用户自选的铃声。App 内开关从此显示的就是系统的真实状态。
                    NotificationHelper.readLiveChannelAlert(this@LiveNotifyApp)?.let { actual ->
                        if (actual.sound != s.notifySound) Graph.settings.setNotifySound(actual.sound)
                        if (actual.vibrate != s.notifyVibrate) Graph.settings.setNotifyVibrate(actual.vibrate)
                    }
                } else {
                    // 首次安装或渠道被系统清掉：按用户设置建一次（从备份恢复时也要尊重设置值）
                    NotificationHelper.applyAlertSettings(this@LiveNotifyApp, s.notifySound, s.notifyVibrate)
                }
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
