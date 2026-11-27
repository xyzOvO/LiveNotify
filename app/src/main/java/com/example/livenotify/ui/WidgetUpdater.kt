package com.example.livenotify.ui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.util.Log
import android.util.LruCache
import android.view.View
import android.widget.RemoteViews
import com.example.livenotify.Graph
import com.example.livenotify.R
import com.example.livenotify.monitor.MonitorService
import com.example.livenotify.monitor.NetworkKind
import com.example.livenotify.monitor.NetworkState
import com.example.livenotify.monitor.WidgetData
import com.example.livenotify.monitor.WidgetLayout
import com.example.livenotify.monitor.WidgetState
import com.example.livenotify.net.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Request

/**
 * 桌面小组件刷新器。
 *
 * AppWidgetProvider 的回调跑在广播主线程，Room 查询与头像下载必须切到 IO 线程，
 * 所以对外只暴露 [requestRefresh]（发一个协程任务），真正干活在 [refresh]。
 * 数据没变化就不重建 RemoteViews——避免每 10 分钟无谓地跨进程刷新。
 */
object WidgetUpdater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 头像缓存：96×96 ARGB ≈ 36KB/张，64 张约 2.3MB，控制 Parcel 大小 */
    private val avatarCache = object : LruCache<String, Bitmap>(64) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    /** 上一次渲染的数据，用于 needsRefresh 判重 */
    private var lastData: WidgetData? = null

    /** 外部（服务每轮检测后、应用启动/退出）统一调这个触发刷新 */
    fun requestRefresh(context: Context) {
        scope.launch {
            runCatching { refresh(context.applicationContext) }
                .onFailure { Log.w("WidgetUpdater", "refresh failed: ${it.message}") }
        }
    }

    private suspend fun refresh(context: Context) {
        // 冷启动时 onUpdate 可能早于 Application.onCreate，这里自举 init，
        // 否则小组件会永远停在布局的静态初始态（只有「监控正常」四个字）
        if (!Graph.isReady()) Graph.init(context)
        val settings = Graph.settings.current()
        val streamers = Graph.db.streamerDao().listEnabled()
        val net = NetworkState.read(context)
        val data = WidgetState.buildData(
            streamers = streamers,
            running = MonitorService.isRunning,
            netOnline = net.online,
            wifiOnly = settings.wifiOnly,
            onWifi = net.kind == NetworkKind.WIFI,
            now = System.currentTimeMillis(),
        )
        if (!WidgetState.needsRefresh(lastData, data)) return
        lastData = data

        val avatars = data.live.associate { it.id to loadAvatar(it.avatarUrl) }
        val awm = AppWidgetManager.getInstance(context)
        val ids = awm.getAppWidgetIds(ComponentName(context, LiveWidgetProvider::class.java))
        for (id in ids) {
            val opts = awm.getAppWidgetOptions(id)
            val width = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 180)
            val height = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110)
            val layout = WidgetState.pickLayout(width, height)
            awm.updateAppWidget(id, buildViews(context, data, layout, avatars))
        }
    }

    /** 下载头像并采样到 ≤96px；失败返回 null（渲染时退化为平台图标） */
    private fun loadAvatar(url: String?): Bitmap? {
        if (url.isNullOrBlank()) return null
        avatarCache.get(url)?.let { return it }
        return try {
            val body = Http.client.newCall(Request.Builder().url(url).build()).execute().body
                ?: return null
            val bytes = body.bytes()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 96 || bounds.outHeight / sample > 96) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }
            ) ?: return null
            val circle = toCircular(bmp)
            if (circle !== bmp) bmp.recycle()
            avatarCache.put(url, circle)
            circle
        } catch (_: Throwable) {
            null
        }
    }

    /** 把头像裁成圆形并描一圈白边，配合 LIVE 红点做「直播灯牌」效果 */
    private fun toCircular(src: Bitmap): Bitmap {
        val size = minOf(src.width, src.height)
        if (size <= 0) return src
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val radius = size / 2f
        val scale = maxOf(size / src.width.toFloat(), size / src.height.toFloat())
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate((size - src.width * scale) / 2f, (size - src.height * scale) / 2f)
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(matrix)
            }
        }
        canvas.drawCircle(radius, radius, radius, fill)
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
        }
        canvas.drawCircle(radius, radius, radius - 1.25f, border)
        return out
    }

    private fun buildViews(
        context: Context,
        data: WidgetData,
        layout: WidgetLayout,
        avatars: Map<Long, Bitmap?>,
    ): RemoteViews {
        val rv = RemoteViews(
            context.packageName,
            if (layout == WidgetLayout.COMPACT) R.layout.widget_compact else R.layout.widget_expanded,
        )
        rv.setTextViewText(R.id.widget_header_status, WidgetState.statusText(data.status))
        rv.setTextViewText(
            R.id.widget_header_count,
            if (data.live.isEmpty()) "共 ${data.total} 位" else "${data.live.size}/${data.total} 在播",
        )
        rv.setViewVisibility(R.id.widget_empty, if (data.live.isEmpty()) View.VISIBLE else View.GONE)

        for (i in 1..layout.maxItems) {
            val itemId = id(context, "widget_item$i")
            val show = i <= data.live.size
            rv.setViewVisibility(itemId, if (show) View.VISIBLE else View.GONE)
            if (!show) continue
            val item = data.live[i - 1]
            rv.setTextViewText(id(context, "widget_item${i}_name"), item.nickname)
            rv.setTextViewText(id(context, "widget_item${i}_title"), item.title.ifBlank { "正在直播" })
            val icon = platformIconRes(item.platformId)
            if (icon != null) rv.setImageViewResource(id(context, "widget_item${i}_platform"), icon)
            val avatar = avatars[item.id]
            if (avatar != null) {
                rv.setImageViewBitmap(id(context, "widget_item${i}_avatar"), avatar)
            } else if (icon != null) {
                rv.setImageViewResource(id(context, "widget_item${i}_avatar"), icon)
            }
            val openRoom = PendingIntent.getActivity(
                context, item.id.toInt(),
                Intent(Intent.ACTION_VIEW, Uri.parse(item.roomUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            rv.setOnClickPendingIntent(itemId, openRoom)
        }

        val overflow = WidgetState.overflowText(data.live.size, layout.maxItems)
        rv.setViewVisibility(R.id.widget_more, if (overflow == null) View.GONE else View.VISIBLE)
        if (overflow != null) rv.setTextViewText(R.id.widget_more, overflow)

        val openApp = PendingIntent.getActivity(
            context, Int.MAX_VALUE,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        rv.setOnClickPendingIntent(R.id.widget_root, openApp)
        return rv
    }

    private fun id(context: Context, name: String): Int =
        context.resources.getIdentifier(name, "id", context.packageName)
}
