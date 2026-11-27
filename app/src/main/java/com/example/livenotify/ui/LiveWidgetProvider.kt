package com.example.livenotify.ui

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * 桌面小组件入口。
 *
 * 自身不做任何监控——只负责在系统广播（添加/删除/尺寸变化）时请求 [WidgetUpdater] 刷新。
 * 数据来自主进程直读 Room + MonitorService 状态，不建第二套轮询。
 */
class LiveWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        WidgetUpdater.requestRefresh(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?,
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        // 用户横竖拖动改尺寸后重新按新尺寸选布局
        WidgetUpdater.requestRefresh(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        // 服务每轮检测完会发这个广播，让小组件跟上状态变化
        if (intent.action == ACTION_REFRESH) {
            WidgetUpdater.requestRefresh(context)
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.example.livenotify.WIDGET_REFRESH"
    }
}
