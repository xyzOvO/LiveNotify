package com.example.livenotify.monitor

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 常驻前台服务。
 *
 * 为什么不用 WorkManager：PeriodicWorkRequest 的最小周期是 15 分钟，且受 Doze 影响实际更久，
 * 无法满足 1~3 分钟的检测需求。因此这里用前台服务 + 协程循环，
 * 代价是常驻通知栏一条「正在检测」，这也是 Android 上的合规做法。
 */
class MonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: kotlinx.coroutines.Job? = null

    private val engine: MonitorEngine by lazy { com.example.livenotify.Graph.engine }
    private val settings by lazy { com.example.livenotify.Graph.settings }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        val n = NotificationHelper.monitorNotification(this, "准备中…", 0, false)
        // Android 14 起 startForeground 必须声明类型；用 ServiceCompat 兼容低版本
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NotificationHelper.MONITOR_ID, n, type)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            // 关注列表变化（增删主播）：只刷新常驻通知文案，不打断正在进行的轮询。
            // 也补一次 startLoop（幂等），避免服务是被这条指令拉起时循环没起来。
            ACTION_REFRESH -> {
                scope.launch { runCatching { refreshForeground() } }
                startLoop()
                return START_STICKY
            }
        }
        startLoop()
        return START_STICKY
    }

    private fun startLoop() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            while (isActive) {
                try {
                    val enabled = try {
                        settings.current().monitorEnabled
                    } catch (t: Throwable) {
                        true
                    }
                    if (!enabled) {
                        stopSelf()
                        return@launch
                    }
                    val report = engine.runOnce()
                    updateForeground(report)
                    val delayMs = engine.nextDelayMs()
                    Log.d(TAG, "checked=${report.checked} notified=${report.notified} err=${report.errors} next=${delayMs}ms")
                    delay(delayMs)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    Log.w(TAG, "loop error", t)
                    delay(30_000) // 异常退避，避免疯狂重试
                }
            }
        }
    }

    private suspend fun updateForeground(report: MonitorEngine.Report) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(report.finishedAt))
        // 数量取「当前库里的」而不是本轮检测的数量：检测结果可能落在增删主播之前，
        // 用实时值才能保证通知里的「共 N 位」永远和列表一致。
        val count = currentCount()
        val text = when {
            report.skipped -> "等待 WiFi（当前移动网络）"
            count == 0 -> "暂无关注，最近检测 $time"
            else -> "最近检测 $time · 共 $count 位" +
                (if (report.errors > 0) " · ${report.errors} 项异常" else "")
        }
        publish(text, count)
    }

    /** 关注列表变化时刷新常驻通知：重新读一次数量与上次检测时间 */
    private suspend fun refreshForeground() {
        val count = currentCount()
        val last = com.example.livenotify.Graph.engine.lastRunAt
        val time = if (last > 0L) SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(last)) else "—"
        publish(if (count == 0) "暂无关注" else "最近检测 $time · 共 $count 位", count)
    }

    private suspend fun currentCount(): Int = try {
        com.example.livenotify.Graph.db.streamerDao().listEnabled().size
    } catch (t: Throwable) {
        0
    }

    private suspend fun publish(text: String, count: Int) {
        // Android 13 起通知是运行时权限，被拒时 notify() 会抛 SecurityException。
        // 判断必须写在本方法内，lint 才会认可；前台服务那条由 startForeground 发出，不受影响。
        val granted =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            } else {
                NotificationManagerCompat.from(this).areNotificationsEnabled()
            }
        if (!granted) return
        val hidden = try {
            settings.current().hideMonitorNotification
        } catch (t: Throwable) {
            false
        }
        runCatching {
            NotificationManagerCompat.from(this)
                .notify(
                    NotificationHelper.MONITOR_ID,
                    NotificationHelper.monitorNotification(this, text, count, hidden)
                )
        }
    }

    override fun onDestroy() {
        isRunning = false
        loopJob?.cancel()
        super.onDestroy()
    }

    /**
     * 从最近任务划掉时，部分 ROM 会把服务连进程一起回收。
     * 这里用 AlarmManager 在 1 秒后拉回来——它在 Android 12+ 属于
     * 「允许后台启动前台服务」的豁免场景，比直接 startService 更稳。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (isRunning) scheduleRestart()
        super.onTaskRemoved(rootIntent)
    }

    private fun scheduleRestart() {
        runCatching {
            val i = Intent(applicationContext, MonitorService::class.java)
            val pi = PendingIntent.getForegroundService(
                this,
                RESTART_REQUEST_CODE,
                i,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val am = getSystemService(AlarmManager::class.java) ?: return
            am.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 1_000L, pi)
            Log.i(TAG, "schedule restart after task removed")
        }.onFailure { Log.w(TAG, "schedule restart failed: ${it.message}") }
    }

    /**
     * Android 15 起，前台服务类型超时（原 dataSync 为 24 小时累计 6 小时）会回调这里。
     * 已改用 specialUse，正常不应触发，留着做防御：不实现会直接崩溃。
     */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "foreground service timeout, type=$fgsType, stopping")
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "MonitorService"
        private const val RESTART_REQUEST_CODE = 1001
        const val ACTION_STOP = "com.example.livenotify.ACTION_STOP_MONITOR"
        const val ACTION_REFRESH = "com.example.livenotify.ACTION_REFRESH_MONITOR"

        /** 服务是否活着。只在它活着时才发刷新指令，避免把已关闭的检测重新拉起 */
        @Volatile
        var isRunning: Boolean = false
            private set

        /**
         * 让常驻通知里的「共 N 位」立刻跟上列表变化。
         * 调用方需已确认监控处于开启状态（必要时会顺带把服务拉起）。
         */
        fun refresh(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, MonitorService::class.java).setAction(ACTION_REFRESH)
                )
            }.onFailure { Log.w(TAG, "refresh failed: ${it.message}") }
        }

        /** 服务活着才刷新；监控已关闭时不要把它拉起来 */
        fun refreshIfRunning(context: Context) {
            if (isRunning) refresh(context)
        }

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, MonitorService::class.java)
                )
            }.onFailure { Log.w(TAG, "start failed: ${it.message}") }
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, MonitorService::class.java).setAction(ACTION_STOP)
                )
            }
            runCatching { context.stopService(Intent(context, MonitorService::class.java)) }
        }
    }
}
