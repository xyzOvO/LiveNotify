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
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.livenotify.R
import com.example.livenotify.data.StreamerEntity
import com.example.livenotify.platform.PlatformRegistry
import com.example.livenotify.platform.Snapshot

object NotificationHelper {

    const val CHANNEL_LIVE = "live_start"
    const val CHANNEL_MONITOR = "monitor_status"
    /** 常驻通知被隐藏时使用的渠道：IMPORTANCE_MIN，不再在状态栏弹出常规条目 */
    const val CHANNEL_MONITOR_SILENT = "monitor_status_silent"
    const val MONITOR_ID = 1

    /** 默认震动节奏：短震—停—短震 */
    private val VIBRATE_PATTERN = longArrayOf(0, 260, 160, 260)

    fun createChannels(context: Context, sound: Boolean = true, vibrate: Boolean = true) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
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
     * 修改开播提醒的响铃 / 震动。
     * Android 8.0 起声音与震动由**渠道**决定，NotificationCompat.Builder 上的设置会被忽略，
     * 所以只能删掉渠道再按新参数重建（代价：会重置用户在该渠道上的手动调整）。
     */
    fun applyAlertSettings(context: Context, sound: Boolean, vibrate: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
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
    }

    private fun applyAlert(ch: NotificationChannel, sound: Boolean, vibrate: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
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

    fun canNotify(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** 跳转到系统通知设置（应用级），供权限缺失提示条使用 */
    fun openNotificationSettings(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")
            )
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /** 跳转到某个通知渠道的系统设置页，用户可在此彻底关闭该渠道 */
    fun openChannelSettings(context: Context, channelId: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** 开播通知：标题=主播名，内容=直播间标题，点击跳转直播间。仅实际发布后返回 true。 */
    fun notifyLive(context: Context, streamer: StreamerEntity, snapshot: Snapshot): Boolean {
        if (!canNotify(context)) return false

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(snapshot.roomUrl)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pi = PendingIntent.getActivity(
            context,
            streamer.id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val platformName = PlatformRegistry.get(streamer.platformId)?.displayName ?: streamer.platformId
        val title = snapshot.title.ifBlank { "开播啦" }

        val notification = NotificationCompat.Builder(context, CHANNEL_LIVE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${streamer.nickname} 开播了")
            .setContentText(title)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$platformName · $title\n点击前往直播间")
            )
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .build()

        // 同一主播使用固定 id，避免重复堆叠；权限在检查后仍可能变化，不能把失败记成已通知。
        return try {
            NotificationManagerCompat.from(context).notify(1000 + streamer.id.toInt(), notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    fun testNotification(context: Context) {
        if (!canNotify(context)) return
        val notification = NotificationCompat.Builder(context, CHANNEL_LIVE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("测试通知")
            .setContentText("开播提醒工作正常")
            .setContentIntent(mainPendingIntent(context))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(Int.MAX_VALUE, notification)
    }

    /** 常驻通知。hidden=true 时改投最低可见度渠道，状态栏不再弹出常规条目。 */
    fun monitorNotification(context: Context, text: String, count: Int, hidden: Boolean) =
        NotificationCompat.Builder(
            context,
            if (hidden) CHANNEL_MONITOR_SILENT else CHANNEL_MONITOR
        )
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (hidden) "正在后台检测 $count 位主播" else "正在检测 $count 位主播")
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
