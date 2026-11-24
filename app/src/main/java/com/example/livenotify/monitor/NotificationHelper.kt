package com.example.livenotify.monitor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.livenotify.R
import com.example.livenotify.data.StreamerEntity
import com.example.livenotify.platform.PlatformRegistry
import com.example.livenotify.platform.Snapshot
import com.example.livenotify.platform.isPlaceholderNickname

object NotificationHelper {

    const val CHANNEL_LIVE = "live_start"
    const val CHANNEL_MONITOR = "monitor_status"
    /** 常驻通知被隐藏时使用的渠道：IMPORTANCE_MIN，不再在状态栏弹出常规条目 */
    const val CHANNEL_MONITOR_SILENT = "monitor_status_silent"
    const val MONITOR_ID = 1

    /** 默认震动节奏：短震—停—短震 */
    private val VIBRATE_PATTERN = longArrayOf(0, 260, 160, 260)

    private const val TAG = "NotificationHelper"

    /** 系统渠道里「开播提醒」实际的响铃/震动，用来和 App 内的开关对账 */
    data class ChannelAlertFlags(val sound: Boolean, val vibrate: Boolean)

    fun createChannels(context: Context, sound: Boolean = true, vibrate: Boolean = true) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_LIVE,
                context.getString(R.string.notification_channel_live),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.notification_channel_live_desc)
                applyAlert(this, sound, vibrate)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MONITOR,
                context.getString(R.string.monitor_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.monitor_channel_desc)
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
        )
        // 隐藏模式专用渠道：最低可见度，不发声、不震动、不弹窗
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MONITOR_SILENT,
                context.getString(R.string.monitor_channel_name),
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = context.getString(R.string.monitor_channel_desc_hidden)
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    /**
     * 按给定参数**重建**开播提醒渠道。
     *
     * Android 8.0 起声音与震动由渠道决定，`NotificationCompat.Builder` 上的设置会被忽略，
     * 所以想改声音只能删掉渠道再重建（代价：会重置用户在该渠道上的手动调整，
     * 包括他自选的铃声——这是系统限制，无法避免）。
     *
     * 因此只在两种情况下调用：用户主动改了 App 内的响铃/震动开关；
     * 或渠道压根不存在、需要按恢复的设置建一次。**冷启动不要调它**，
     * 启动时只做「读系统实际状态回写设置」的对账（见 [readLiveChannelAlert]）。
     */
    fun applyAlertSettings(context: Context, sound: Boolean, vibrate: Boolean) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.deleteNotificationChannel(CHANNEL_LIVE)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_LIVE,
                context.getString(R.string.notification_channel_live),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.notification_channel_live_desc)
                applyAlert(this, sound, vibrate)
            }
        )
        Log.i(TAG, "rebuild live channel: sound=$sound vibrate=$vibrate")
    }

    /** 开播提醒渠道是否已存在（用来区分「首次创建」和「已有渠道需对账」） */
    fun liveChannelExists(context: Context): Boolean =
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
            ?.getNotificationChannel(CHANNEL_LIVE) != null

    /**
     * 读系统渠道里开播提醒的实际响铃/震动，供启动时与 App 内开关对账。
     *
     * 存在的意义：声音归系统管，用户可能在系统设置里改过、或渠道被系统重置。
     * App 若只记「我上次写了什么」，开关就会显示一个与系统不符的值（issue #3 的现场）。
     * 这里只读不写，调用方拿它回写设置即可——绝不主动重建渠道，免得清掉用户自选的铃声。
     */
    fun readLiveChannelAlert(context: Context): ChannelAlertFlags? {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return null
        val ch = nm.getNotificationChannel(CHANNEL_LIVE) ?: return null
        return ChannelAlertFlags(sound = ch.sound != null, vibrate = ch.shouldVibrate())
    }

    private fun applyAlert(ch: NotificationChannel, sound: Boolean, vibrate: Boolean) {
        if (sound) {
            ch.setSound(
                Settings.System.DEFAULT_NOTIFICATION_URI,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
        } else {
            ch.setSound(null, null)
        }
        ch.enableVibration(vibrate)
        ch.vibrationPattern = if (vibrate) VIBRATE_PATTERN else null
    }

    /**
     * 能否真正弹出开播提醒。
     *
     * 除了应用级通知总开关，还要看「开播提醒」这个渠道本身：
     * 用户可以在系统设置里单独关掉某个渠道，此时 notify() 不报错、也不抛异常，
     * 但通知不会出现。只看应用级开关会把这种情况误记成「已通知」而不再重试。
     */
    fun canNotify(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            isChannelEnabled(context, CHANNEL_LIVE)

    private fun isChannelEnabled(context: Context, channelId: String): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return true
        val ch = nm.getNotificationChannel(channelId) ?: return true
        return ch.importance != NotificationManager.IMPORTANCE_NONE
    }

    /**
     * 开播提醒渠道当前的声音状态。
     *
     * [canNotify] 只判断会不会弹出（渠道没被彻底关掉），但「能弹」≠「会响」：
     * 用户可能把渠道调成低优先级，或在 App 里关掉声音（渠道会被重建，sound=null）。
     * 这两种情况 notify() 仍然成功，用户却听不到铃声——issue #3 的另一种成因。
     * 测试通知用它给出可执行的提示，不改通知逻辑。
     */
    enum class LiveChannelAlert { SOUND, VIBRATE_ONLY, SILENT }

    fun liveChannelAlert(context: Context): LiveChannelAlert {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return LiveChannelAlert.SOUND
        val ch = nm.getNotificationChannel(CHANNEL_LIVE) ?: return LiveChannelAlert.SOUND
        // IMPORTANCE_LOW 及以下系统不会出声，只有 DEFAULT/HIGH 才会响
        if (ch.importance < NotificationManager.IMPORTANCE_DEFAULT) return LiveChannelAlert.SILENT
        return when {
            ch.sound != null -> LiveChannelAlert.SOUND
            ch.shouldVibrate() -> LiveChannelAlert.VIBRATE_ONLY
            else -> LiveChannelAlert.SILENT
        }
    }

    /** 跳转到系统通知设置（应用级），供权限缺失提示条使用 */
    fun openNotificationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /** 跳转到某个通知渠道的系统设置页，用户可在此彻底关闭该渠道 */
    fun openChannelSettings(context: Context, channelId: String) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /**
     * 开播通知：标题=主播名，内容=直播间标题，点击跳转直播间。仅实际发布后返回 true。
     *
     * @param lateByMs 补发时距第一次尝试已过去多久；>0 表示这条不是实时的，
     *   文案改成「正在直播」并注明已开播多久，避免用户以为主播刚上播。
     */
    fun notifyLive(
        context: Context,
        streamer: StreamerEntity,
        snapshot: Snapshot,
        lateByMs: Long = 0L,
        sessionSeed: Long = snapshot.liveStartAt,
        previousSessionSeed: Long = 0L
    ): Boolean {
        if (!canNotify(context)) return false

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(snapshot.roomUrl)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val notificationId = NotificationPolicy.liveNotificationId(streamer.id, sessionSeed)
        // 换场后旧通知不会自己消失：同一主播会堆两条「开播」。这里先把上一场撤掉。
        // 只有拿得到上一场种子时才撤（平台不给开播时刻的老场次种子是 0，无从计算）。
        if (previousSessionSeed > 0L && previousSessionSeed != sessionSeed) {
            runCatching {
                NotificationManagerCompat.from(context)
                    .cancel(NotificationPolicy.liveNotificationId(streamer.id, previousSessionSeed))
            }
        }
        val pi = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val platformName = PlatformRegistry.get(streamer.platformId)?.displayName ?: streamer.platformId
        val title = snapshot.title.ifBlank { "开播啦" }
        val late = PendingNotify.lateHint(lateByMs)
        // 本轮真名优先；库里若残留过假名也不要拿它当名字用
        val displayName = pickDisplayName(snapshot.nickname, streamer.nickname)
        // 补发时主播已经播了一会儿，说「开播了」会误导；短于 3 分钟虽没有时长提示，
        // 但仍是补发，标题必须说「正在直播」
        val headline = liveHeadline(displayName, lateByMs)
        val bigText = buildString {
            append(platformName)
            append(" · ")
            append(title)
            if (late != null) append('\n').append(late)
            append('\n').append(openUrlHint(snapshot.roomUrl))
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_LIVE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(headline)
            .setContentText(title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .build()

        // 同一场次的补发复用 id（避免重复堆叠），不同场次换 id（保证重新响铃）。
        // 不能再用 1000 + streamer.id：同主播第二场会被当成更新，只改文字不响。
        return try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    /** 发送测试通知；返回 false 表示通知权限未开启（调用方据此提示用户，别静默失败） */
    fun testNotification(context: Context): Boolean {
        if (!canNotify(context)) return false
        val notification = NotificationCompat.Builder(context, CHANNEL_LIVE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("测试通知")
            .setContentText("开播提醒工作正常")
            .setContentIntent(mainPendingIntent(context))
            .setAutoCancel(true)
            .build()
        return try {
            NotificationManagerCompat.from(context)
                .notify(NotificationPolicy.testNotificationId(System.currentTimeMillis()), notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    /**
     * 通知里用的主播名：本轮接口的真名优先，其次用库里保留的真名。
     * 兜底假名（「B站主播 12345」这类）一律不当真名——两边都是假名时才会退回兜底，
     * 那是「从未拿到过真名且接口还在缺字段」的最后手段。
     */
    internal fun pickDisplayName(snapshotName: String, storedName: String): String =
        listOf(snapshotName, storedName)
            .firstOrNull { it.isNotBlank() && !isPlaceholderNickname(it) }
            ?: snapshotName.ifBlank { storedName }

    /**
     * 补发提示语（lateHint）不足 3 分钟时返回 null，但补发状态本身仍在——
     * 此时标题不能说「开播了」，否则和「正在补发一条旧提醒」自相矛盾。
     */
    internal fun liveHeadline(displayName: String, lateByMs: Long): String =
        if (lateByMs > 0L) "$displayName 正在直播" else "$displayName 开播了"

    /** room_id 缺失时 B 站兜底跳个人空间，正文不能承诺「前往直播间」 */
    internal fun openUrlHint(roomUrl: String): String =
        if (roomUrl.contains("space.bilibili.com")) "点击前往主播主页" else "点击前往直播间"

    /**
     * 常驻通知的标题 / 正文 / 渠道，抽成纯函数，供 MonitorService 与单测共用。
     *
     * 抽出来的原因是一次真实 BUG：onCreate 曾写死「正在检测 0 位主播 / 准备中…」且固定用
     * 普通渠道，后续更新却因「隐藏常驻通知」改投静默渠道，换渠道时普通渠道那条旧通知没被
     * 清掉，于是「0 位 / 准备中…」永久残留在通知栏。现在初始通知也走这里，按真实 count、
     * hidden、skip 生成，渠道选择只有 [monitorChannelId] 一个出口。
     */
    internal fun monitorTitle(count: Int, hidden: Boolean, paused: Boolean): String = when {
        paused -> "已暂停检测 · $count 位主播"
        hidden -> "正在后台检测 $count 位主播"
        else -> "正在检测 $count 位主播"
    }

    internal fun monitorChannelId(hidden: Boolean): String =
        if (hidden) CHANNEL_MONITOR_SILENT else CHANNEL_MONITOR

    internal fun skipLine(skip: MonitorEngine.SkipReason, lastTime: String?): String {
        val head = when (skip) {
            MonitorEngine.SkipReason.WIFI_ONLY -> "等待 Wi-Fi（当前不是 Wi-Fi）"
            MonitorEngine.SkipReason.NO_NETWORK -> "等待网络（无可用连接）"
        }
        return if (lastTime != null) "$head · 最近检测 $lastTime" else head
    }

    /** [count] 必须是当前库的真实数量，绝不写死 0；lastTime 为空表示还没跑过第一轮 */
    internal fun monitorDetail(
        count: Int,
        lastTime: String?,
        skip: MonitorEngine.SkipReason? = null,
        errors: Int = 0
    ): String = when {
        skip != null -> skipLine(skip, lastTime)
        count == 0 -> if (lastTime != null) "暂无关注，最近检测 $lastTime" else "暂无关注"
        lastTime == null -> "正在准备首次检测 · 共 $count 位"
        else -> "最近检测 $lastTime · 共 $count 位" +
            (if (errors > 0) " · $errors 项异常" else "")
    }

    /** 常驻通知。hidden=true 时改投最低可见度渠道，状态栏不再弹出常规条目。 */
    fun monitorNotification(
        context: Context,
        text: String,
        count: Int,
        hidden: Boolean,
        paused: Boolean = false
    ) =
        NotificationCompat.Builder(context, monitorChannelId(hidden))
            .setSmallIcon(R.drawable.ic_notification)
            // 没有网络（或「仅 Wi-Fi」待机）时别说「正在检测」——实际一次请求都没发
            .setContentTitle(monitorTitle(count, hidden, paused))
            .setContentText(text)
            .setContentIntent(mainPendingIntent(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(if (hidden) NotificationCompat.PRIORITY_MIN else NotificationCompat.PRIORITY_LOW)
            .build()

    private fun mainPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, com.example.livenotify.ui.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
