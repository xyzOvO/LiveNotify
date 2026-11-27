package com.example.livenotify.ui

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.example.livenotify.Graph
import com.example.livenotify.R
import com.example.livenotify.monitor.LiveStreamItem
import com.example.livenotify.monitor.WidgetPalette
import kotlinx.coroutines.runBlocking

/**
 * 可滚动小组件的数据源。RemoteViews 集合只能用 RemoteViewsService + Factory 驱动，
 * 条目点击只能「template + fillIn」——不能直接给子项 setOnClickPendingIntent。
 *
 * Factory 跑在主进程（RemoteViewsService 是普通 app service），所以能直读 Graph.db；
 * onDataSetChanged 允许同步读库，头像也在这里预载，getViewAt 只做套色填充。
 */
class WidgetListService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsService.RemoteViewsFactory =
        WidgetListFactory(applicationContext)
}

internal class WidgetListFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {
    private var items: List<LiveStreamItem> = emptyList()
    private var total: Int = 0
    private var dark: Boolean = false
    private val avatarCache = mutableMapOf<Long, android.graphics.Bitmap?>()

    override fun onCreate() { /* 数据源在 onDataSetChanged 里建，onCreate 别做重活（20s ANR） */ }

    override fun onDataSetChanged() {
        // 冷启动时服务可能早于 Application.onCreate，自举一次
        if (!Graph.isReady()) Graph.init(context)
        ThemePrefs.init(context)
        val systemDark = context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        dark = WidgetPalette.isDark(ThemePrefs.mode.value.name, systemDark)

        val all = runBlocking { Graph.db.streamerDao().listEnabled() }
        val enabled = all.filter { it.enabled }
        total = enabled.size
        items = enabled.filter { it.isLive && !it.missing }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.nickname })
            .map {
                LiveStreamItem(
                    id = it.id, platformId = it.platformId, nickname = it.nickname,
                    title = it.title, avatarUrl = it.avatarUrl, roomUrl = it.roomUrl,
                )
            }
        // 预载头像：getViewAt 会被 launcher 频繁调，不能在那里同步下载
        avatarCache.clear()
        items.forEach { avatarCache[it.id] = WidgetAvatar.get(it.avatarUrl, dark) }
    }

    override fun onDestroy() {
        items = emptyList()
        avatarCache.clear()
    }

    override fun getCount(): Int = items.size

    override fun getViewAt(position: Int): RemoteViews? {
        if (position !in items.indices) return null
        val item = items[position]
        val c = WidgetPalette.colors(dark)
        val views = RemoteViews(context.packageName, R.layout.widget_list_item)
        views.setTextViewText(R.id.list_item_name, item.nickname)
        views.setTextColor(R.id.list_item_name, color(c.name))
        views.setTextViewText(R.id.list_item_title, item.title.ifBlank { "正在直播" })
        views.setTextColor(R.id.list_item_title, color(c.title))
        views.setTextColor(R.id.list_item_arrow, color(c.arrow))
        platformIconRes(item.platformId)?.let {
            views.setImageViewResource(R.id.list_item_platform, it)
        }
        views.setInt(R.id.list_item_avatar, "setBackgroundResource",
            if (dark) R.drawable.widget_avatar_bg else R.drawable.widget_avatar_bg_light)
        when {
            avatarCache[item.id] != null -> views.setImageViewBitmap(R.id.list_item_avatar, avatarCache[item.id])
            platformIconRes(item.platformId) != null ->
                views.setImageViewResource(R.id.list_item_avatar, platformIconRes(item.platformId)!!)
            else -> views.setImageViewBitmap(R.id.list_item_avatar, WidgetAvatar.initial(item.nickname, dark))
        }
        views.setInt(R.id.list_item_live_dot, "setBackgroundResource",
            if (dark) R.drawable.widget_live_dot else R.drawable.widget_live_dot_light)

        // 集合子项只能 fillIn：template 在 WidgetUpdater 里设，这里塞进本条的房间链接
        views.setOnClickFillInIntent(
            R.id.list_item_root,
            Intent().setData(Uri.parse(item.roomUrl)),
        )
        return views
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = items.getOrNull(position)?.id ?: position.toLong()
    override fun hasStableIds(): Boolean = true

    private fun color(hex: String): Int = android.graphics.Color.parseColor(hex)
}
