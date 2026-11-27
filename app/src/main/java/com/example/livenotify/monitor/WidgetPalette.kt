package com.example.livenotify.monitor

/**
 * 桌面小组件的主题配色。纯 Kotlin、只产出 #RRGGBB 字符串，不依赖 Android，
 * 渲染侧 [com.example.livenotify.ui.WidgetUpdater] 用 Color.parseColor 套到 RemoteViews。
 *
 * RemoteViews 不能换布局、也不能读 Compose 的 colorScheme，所以浅/深两套颜色在这里定死；
 * 三态主题（跟随系统 / 浅 / 深）由 [isDark] 归一成一个布尔值。
 */
internal object WidgetPalette {
    data class Colors(
        val background: String,
        val statusNormal: String,
        val statusWarn: String,
        val statusError: String,
        val statusMuted: String,
        val name: String,
        val title: String,
        val arrow: String,
        val pill: String,
        val pillText: String,
        val liveDot: String,
        val avatarBg: String,
        val avatarBorder: String,
    )

    /** 主题枚举名（SYSTEM/LIGHT/DARK）→ 是否深色。跟随系统时用系统当前明暗。 */
    fun isDark(modeName: String, systemDark: Boolean): Boolean = when (modeName) {
        "LIGHT" -> false
        "DARK" -> true
        else -> systemDark // SYSTEM 或任何未知值都跟随系统，不擅自强制某一套
    }

    fun colors(dark: Boolean): Colors = if (dark) Colors(
        background = "#1E1B2E",
        statusNormal = "#4ADE80", // 正常=绿（用户口径），深底用亮绿才看得清
        statusWarn = "#FFB86B",
        statusError = "#FF8E8E",
        statusMuted = "#A9A3B8",
        name = "#F2EFF7",
        title = "#A9A3B8",
        arrow = "#8C86A0",
        pill = "#FF4D6D",
        pillText = "#FFFFFF",
        liveDot = "#FF4D6D",
        avatarBg = "#2E2A45",
        avatarBorder = "#FFFFFF",
    ) else Colors(
        background = "#FFFFFF",
        // 浅底上要用更深的绿/橙/红，字才看得清（深色的柔色在白底会糊成一片）
        statusNormal = "#2E7D32",
        statusWarn = "#A85C00",
        statusError = "#C62828",
        statusMuted = "#6B6479",
        name = "#1F1B2E",
        title = "#6B6479",
        arrow = "#9A94A8",
        pill = "#E5315A",
        pillText = "#FFFFFF",
        liveDot = "#E5315A",
        avatarBg = "#EEF0F7",
        avatarBorder = "#4C63D2",
    )

    /** 六态状态色：正常/获取中用中性色，未开启/等 WiFi/过期用告警色，无网用错误色 */
    fun statusTint(status: WidgetStatus, dark: Boolean): String {
        val c = colors(dark)
        return when (status) {
            WidgetStatus.NORMAL -> c.statusNormal
            WidgetStatus.NOT_RUNNING, WidgetStatus.WAIT_WIFI, WidgetStatus.STALE -> c.statusWarn
            WidgetStatus.WAIT_NETWORK -> c.statusError
            WidgetStatus.FETCHING -> c.statusMuted
        }
    }
}
