package com.example.livenotify.monitor

/**
 * 「添加小组件到桌面」入口的提示文案。纯函数，不依赖 Android，便于 JVM 单测。
 *
 * 系统一键添加只在桌面支持时可用（AppWidgetManager.isRequestPinAppWidgetSupported），
 * 不支持时必须给出明确的长按路径，不能只让按钮点了没反应。
 */
internal object WidgetPinHint {
    fun text(supported: Boolean, pinnedCount: Int): String = when {
        pinnedCount > 0 -> "桌面已有 $pinnedCount 个，可继续添加"
        !supported -> "当前桌面不支持一键添加：长按桌面空白处 → 小组件 → 找到本应用"
        else -> "点下方按钮，系统会问你把组件放到哪一页"
    }
}
