package com.example.livenotify.monitor

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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

    /**
     * 网络恢复信号：每次 onAvailable 触发 complete，让正在 delay 的循环提前醒来重跑一轮。
     * CompletableDeferred 只能 complete 一次，用完立刻 replaced，避免多次 onAvailable 堆在同一信号上。
     */
    @Volatile
    private var wakeSignal: CompletableDeferred<Unit>? = null

    /** 上一次「网络唤醒」的时刻，供 NetworkWake 节流；回调线程与协程线程都会读写 */
    @Volatile
    private var lastWakeAt = 0L

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** 网络恢复时注册的默认网络回调：只负责「打信号」，实际重跑仍由轮询循环统一做 */
    private fun registerNetworkWake() {
        if (networkCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onNetworkAvailable()
        }
        runCatching {
            cm.registerDefaultNetworkCallback(cb)
            networkCallback = cb
            Log.i(TAG, "network wake callback registered")
        }.onFailure { Log.w(TAG, "register network wake failed: ${it.message}") }
    }

    private fun unregisterNetworkWake() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        networkCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        networkCallback = null
    }

    /** 网络恢复：过节流门槛才打信号，避免网络抖动把接口打爆 */
    private fun onNetworkAvailable() {
        val now = System.currentTimeMillis()
        val lastRun = com.example.livenotify.Graph.engine.lastRunAt
        if (!NetworkWake.shouldWake(lastRun, lastWakeAt, now)) return
        lastWakeAt = now
        Log.i(TAG, "network available -> wake poll")
        wakeSignal?.let { it.complete(Unit); wakeSignal = null }
    }

    /**
     * 可打断等待：要么等满 [waitMs]，要么被网络恢复信号提前唤醒。
     *
     * 除了正常的 delay 场景，还兜住一个竞争窗口：若 onAvailable 发生在上一轮 runOnce
     * 结束、本轮信号建立之前（此时没有等待者可通知），用 lastWakeAt > lastRunAt 判断
     * 「上次检测之后网络恢复过」，直接跳过等待立刻重跑，不把这个唤醒丢掉。
     */
    private suspend fun waitOrWake(waitMs: Long) {
        val signal = CompletableDeferred<Unit>()
        wakeSignal = signal
        val lastRun = com.example.livenotify.Graph.engine.lastRunAt
        if (lastWakeAt > lastRun && lastWakeAt > 0L) {
            wakeSignal = null
            return
        }
        try {
            withTimeoutOrNull(waitMs) { signal.await() }
        } finally {
            wakeSignal = null
        }
    }

    /** 上次成功读到的监控开关；设置读盘失败时沿用它，而不是默认「已开启」 */
    private var lastEnabled = true

    /**
     * 当前常驻通知用的渠道（hidden 状态）。渠道一旦和上次不同，必须先 cancel 再
     * startForeground 重建——单纯 notify 换渠道在部分 ROM（实测 MIUI）上不会覆盖旧渠道那条，
     * 于是旧文案（如 "准备中…"）永久残留。
     */
    private var lastForegroundHidden = false

    private val engine: MonitorEngine by lazy { com.example.livenotify.Graph.engine }
    private val settings by lazy { com.example.livenotify.Graph.settings }

    override fun onCreate() {
        super.onCreate()
        running.value = true
        // 首帧先发一条中性文案（不带写死的「0 位 / 准备中…」）。startLoop 第一轮会读真实
        // 的 count / hidden / skip 覆盖它；publish() 还会处理渠道切换时旧通知的清理。
        val n = NotificationHelper.monitorNotification(this, "正在准备首次检测…", 0, false)
        ServiceCompat.startForeground(this, NotificationHelper.MONITOR_ID, n, foregroundType())
        lastForegroundHidden = false
        registerNetworkWake()
    }

    /** Android 14 起 startForeground 必须声明类型；ServiceCompat 兼容低版本 */
    private fun foregroundType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0

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
            // 连续异常次数：成功跑完一轮就清零，异常则递增并指数退避
            var consecutiveFailures = 0
            while (isActive) {
                // 固定周期调度的基准点：本轮开始（不是结束）。
                // 检测耗时被算进周期里，用户设 2 分钟就真的是「这轮开始到下轮开始」2 分钟。
                val tickStart = System.currentTimeMillis()
                try {
                    val enabled = try {
                        settings.current().monitorEnabled.also { lastEnabled = it }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        // 读不到设置时沿用上一次的判断：不能因为一次读盘失败就把用户关掉的监控重新拉起
                        Log.w(TAG, "read settings failed, keep last enabled=$lastEnabled", t)
                        lastEnabled
                    }
                    if (!enabled) {
                        stopSelf()
                        return@launch
                    }
                    val report = engine.runOnce()
                    updateForeground(report)
                    consecutiveFailures = 0 // 跑通一轮（含 skip）就重置退避
                    val period = engine.nextDelayMs()
                    val now = System.currentTimeMillis()
                    // 单轮超过一个周期就跳过错过的格点，绝不连续补跑
                    val nextAt = PollSchedule.nextTickAt(tickStart, period, now)
                    val wait = (nextAt - now).coerceIn(1_000L, PollSchedule.MAX_MS)
                    nextTickAt.value = nextAt // 诊断页读它显示「下次检测」，不再只存在局部变量里
                    Log.d(
                        TAG,
                        "tick check=${report.checked} notified=${report.notified} err=${report.errors}" +
                            " pending=${report.pending}" +
                            " took=${report.durationMs}ms period=${period}ms wait=${wait}ms skip=${report.skip}"
                    )
                    waitOrWake(wait) // 网络恢复可提前打断这段等待，不必等满整个周期
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    consecutiveFailures++
                    nextTickAt.value = 0L // 进入异常退避，之前的调度时间点已作废
                    val wait = PollSchedule.errorBackoffMs(consecutiveFailures)
                    Log.w(TAG, "loop error #$consecutiveFailures, backoff ${wait}ms", t)
                    waitOrWake(wait) // 网络恢复时提前结束异常退避（网络刚恢复正是最该重试的时刻）
                }
            }
        }
    }

    private suspend fun updateForeground(report: MonitorEngine.Report) {
        // 数量取「当前库里的」而不是本轮检测的数量：检测结果可能落在增删主播之前，
        // 用实时值才能保证通知里的「共 N 位」永远和列表一致。
        val count = currentCount()
        val time = clockOf(com.example.livenotify.Graph.engine.lastRunAt)
        val text = NotificationHelper.monitorDetail(
            count = count,
            lastTime = time,
            skip = report.skip,
            errors = report.errors
        )
        publish(text, count, paused = report.skip != null)
    }

    /** 关注列表变化时刷新常驻通知：重新读一次数量与上次检测时间 */
    private suspend fun refreshForeground() {
        val count = currentCount()
        val last = com.example.livenotify.Graph.engine.lastRunAt
        val time = clockOf(last)
        val skip = com.example.livenotify.Graph.engine.lastSkip.value
        val text = NotificationHelper.monitorDetail(count = count, lastTime = time, skip = skip)
        publish(text, count, paused = skip != null)
    }

    private fun clockOf(ms: Long): String? =
        if (ms <= 0L) null else SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ms))

    private suspend fun currentCount(): Int = try {
        com.example.livenotify.Graph.db.streamerDao().listEnabled().size
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        0
    }

    private suspend fun publish(text: String, count: Int, paused: Boolean = false) {
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
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            false
        }
        val nm = NotificationManagerCompat.from(this)
        val notification = NotificationHelper.monitorNotification(this, text, count, hidden, paused)
        if (hidden != lastForegroundHidden) {
            // 渠道切换（用户开/关「隐藏常驻通知」）：先撤掉旧渠道的同 id 通知再重建。
            // 不 cancel 的话，MIUI 上旧渠道那条会残留在通知栏，出现「准备中…」永不消失。
            // 用 startForeground 而不是 notify，保证服务前台身份与通知渠道一致。
            runCatching { nm.cancel(NotificationHelper.MONITOR_ID) }
            runCatching {
                ServiceCompat.startForeground(
                    this, NotificationHelper.MONITOR_ID, notification, foregroundType()
                )
            }
            lastForegroundHidden = hidden
        } else {
            runCatching { nm.notify(NotificationHelper.MONITOR_ID, notification) }
        }
    }

    override fun onDestroy() {
        running.value = false
        unregisterNetworkWake()
        wakeSignal?.let { it.complete(Unit); wakeSignal = null }
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

        /**
         * 下一轮预计检测时刻（毫秒时间戳，0=尚未调度）。
         *
         * 调度发生在 startLoop 的协程里，原本 nextAt 是纯局部变量，诊断页完全看不到。
         * 用 StateFlow 而不是普通字段：诊断页是 Composable，要跟着值变化重组。
         */
        val nextTickAt = MutableStateFlow(0L)

        /** 服务是否活着。只在它活着时才发刷新指令，避免把已关闭的检测重新拉起 */
        val isRunning: Boolean get() = running.value

        /**
         * 服务的真实运行状态。界面用它显示「监控中 / 未在运行」——
         * 只看设置项会把「启动失败」也显示成监控中（后台启动在 Android 12+ 是会被拒的）。
         */
        val running = MutableStateFlow(false)

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

        /**
         * 启动常驻服务。返回 false 表示系统拒绝了这次启动（Android 12+ 后台启动前台服务受限、
         * 或通知权限被关），调用方必须把失败告诉用户，而不是让它静默失败。
         */
        fun start(context: Context): Boolean = runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, MonitorService::class.java)
            )
        }.onFailure { Log.w(TAG, "start failed: ${it.message}") }.isSuccess

        /** 确保服务在跑：已在跑只刷新常驻通知，没跑就拉起。返回调用后是否处于运行中 */
        fun ensureRunning(context: Context): Boolean {
            if (running.value) {
                refresh(context)
                return true
            }
            return start(context)
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
