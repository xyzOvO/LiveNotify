package com.example.livenotify.ui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.example.livenotify.Graph
import com.example.livenotify.R
import com.example.livenotify.monitor.MonitorService
import com.example.livenotify.monitor.NetworkKind
import com.example.livenotify.monitor.NetworkState
import com.example.livenotify.monitor.WidgetData
import com.example.livenotify.monitor.WidgetPalette
import com.example.livenotify.monitor.WidgetState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 桌面小组件刷新器。
 *
 * 可滚动列表由 [WidgetListService] 的 Factory 提供条目；这里只负责头部状态行和适配器接线。
 * AppWidgetProvider 的回调跑在广播主线程，Room 查询必须切 IO，所以对外只暴露 [requestRefresh]。
 * 数据没变化就不重建 RemoteViews——避免每 10 分钟无谓地跨进程刷新。
 */
object WidgetUpdater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 上一次渲染的数据，用于判重 */
    private var lastData: WidgetData? = null

    /** 上一次的主题明暗（切换浅/深后数据没变也必须重绘套色） */
    private var lastDark: Boolean? = null

    /** 外部（服务每轮检测后、应用启动/退出）统一调这个触发刷新 */
    fun requestRefresh(context: Context, force: Boolean = false) {
        scope.launch {
            runCatching { refresh(context.applicationContext, force) }
                .onFailure { Log.w("WidgetUpdater", "refresh failed: ${it.message}") }
        }
    }

    private suspend fun refresh(context: Context, force: Boolean = false) {
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
        val awm = AppWidgetManager.getInstance(context)
        val ids = awm.getAppWidgetIds(ComponentName(context, LiveWidgetProvider::class.java))
        if (ids.isEmpty()) return
        val systemDark = context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val dark = WidgetPalette.isDark(ThemePrefs.mode.value.name, systemDark)
        if (!force && dark == lastDark && !WidgetState.needsRefresh(lastData, data)) return
        lastData = data
        lastDark = dark

        for (widgetId in ids) {
            awm.updateAppWidget(widgetId, buildViews(context, data, dark))
        }
        // 集合内容变了要通知 Factory 重读；这一步不能省，否则列表停在旧数据
        awm.notifyAppWidgetViewDataChanged(ids, R.id.widget_list)
    }

    private fun buildViews(context: Context, data: WidgetData, dark: Boolean): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.widget_list)
        val c = WidgetPalette.colors(dark)
        val tint = WidgetPalette.statusTint(data.status, dark)

        rv.setInt(R.id.widget_root, "setBackgroundResource",
            if (dark) R.drawable.widget_bg else R.drawable.widget_bg_light)

        rv.setTextViewText(R.id.widget_header_status, WidgetState.statusText(data.status))
        rv.setTextColor(R.id.widget_header_status, color(tint))
        rv.setTextColor(R.id.widget_header_dot, color(tint))
        rv.setInt(R.id.widget_header_count, "setBackgroundResource",
            if (dark) R.drawable.widget_count_pill else R.drawable.widget_count_pill_light)
        rv.setTextColor(R.id.widget_header_count, color(c.pillText))
        rv.setTextViewText(
            R.id.widget_header_count,
            if (data.live.isEmpty()) "共 ${data.total} 位" else "${data.live.size}/${data.total} 在播",
        )
        rv.setViewVisibility(R.id.widget_empty, if (data.live.isEmpty()) View.VISIBLE else View.GONE)
        rv.setViewVisibility(R.id.widget_list, if (data.live.isEmpty()) View.GONE else View.VISIBLE)
        if (data.live.isEmpty()) {
            rv.setTextViewText(R.id.widget_empty, WidgetState.emptyText(data.status, data.total))
            rv.setTextColor(R.id.widget_empty, color(c.title))
        }

        // 集合适配器：intent 必须带 appWidgetId，否则 Factory 不知道服务哪个实例
        val adapterIntent = Intent(context, WidgetListService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0)
            // 系统按 intent 的 URI 做缓存，带不同 data 才不会让多个组件共用一份数据
            setData(android.net.Uri.parse(toUri(Intent.URI_INTENT_SCHEME)))
        }
        rv.setRemoteAdapter(R.id.widget_list, adapterIntent)

        // 子项点击：template（mutable）+ fillIn（Factory 里塞房间链接）。
        // template 必须显式（targetSdk 35 不允许 mutable + 隐式 Intent），指向透明中转页。
        val template = PendingIntent.getActivity(
            context, 4408,
            Intent(context, WidgetOpenActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        rv.setPendingIntentTemplate(R.id.widget_list, template)
        return rv
    }

    private fun color(hex: String): Int = android.graphics.Color.parseColor(hex)
}
