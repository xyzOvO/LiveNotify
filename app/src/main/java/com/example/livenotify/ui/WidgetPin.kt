package com.example.livenotify.ui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * 「把小组件放到桌面」的系统 API 封装。
 *
 * Android 8（minSdk 26）起有 requestPinAppWidget：用户点一下，桌面自己进添加模式，
 * 不用再让用户去桌面长按找。部分桌面（尤其国产 ROM）不支持，返回 false/Unsupported，
 * 入口照常显示但文案改成手动路径（见 WidgetPinHint）。
 */
internal object WidgetPin {
    private fun provider(context: Context) = ComponentName(context, LiveWidgetProvider::class.java)

    fun supported(context: Context): Boolean =
        AppWidgetManager.getInstance(context).isRequestPinAppWidgetSupported

    fun pinnedCount(context: Context): Int =
        AppWidgetManager.getInstance(context).getAppWidgetIds(provider(context)).size

    /**
     * 发起一键添加；成功返回 true（表示系统已受理，用户仍可能中途取消）。
     * 回调广播让组件立刻渲染，不用等下一个 10 分钟检测周期。
     */
    fun request(context: Context): Boolean {
        if (!supported(context)) return false
        val callback = PendingIntent.getBroadcast(
            context, 4407,
            Intent(context, LiveWidgetProvider::class.java).setAction(LiveWidgetProvider.ACTION_REFRESH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return try {
            AppWidgetManager.getInstance(context)
                .requestPinAppWidget(provider(context), null, callback)
        } catch (_: Throwable) {
            false
        }
    }
}
