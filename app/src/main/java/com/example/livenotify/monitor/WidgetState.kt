package com.example.livenotify.monitor

import com.example.livenotify.data.StreamerEntity

/**
 * 桌面小组件的数据模型与纯函数。
 *
 * 这里刻意不依赖 Android / RemoteViews：状态判定、布局选择、列表筛选都能在 JVM 单测里覆盖，
 * 真正跟跨进程渲染打交道的部分留在 [com.example.livenotify.ui.WidgetUpdater]。
 */

/** 小组件展示的一条在播主播（只保留渲染需要的字段，不把整个 Entity 塞进跨进程） */
data class LiveStreamItem(
    val id: Long,
    val platformId: String,
    val nickname: String,
    val title: String,
    val avatarUrl: String?,
    val roomUrl: String,
)

/** 顶部监控状态。注意「等待 Wi-Fi」由 wifiOnly + 当前非 WiFi 组合而来，不是平台状态 */
enum class WidgetStatus {
    NORMAL,          // 监控正常
    NOT_RUNNING,     // 监控未开启
    WAIT_NETWORK,    // 等待网络
    WAIT_WIFI,       // 等待 Wi-Fi（开了「仅 WiFi 检测」但当前是流量）
    FETCHING,        // 正在获取直播状态（还没成功检测过）
    STALE,           // 数据可能已过期
}

/** 两种布局：紧凑（最多 2 位）与展开（最多 6 位，按实际高度用其中几条） */
enum class WidgetLayout(val maxItems: Int) {
    COMPACT(2),
    EXPANDED(6),
}

/** 一份完整的小组件快照，由主进程从 Room + 服务状态汇总 */
data class WidgetData(
    val live: List<LiveStreamItem>,
    val total: Int,
    val status: WidgetStatus,
    val lastSuccessAt: Long,
    val now: Long,
)

internal object WidgetState {
    /** 超过 3 个检测周期（真机 10min × 3）视为数据过期 */
    const val STALE_THRESHOLD_MS = 30L * 60 * 1000

    /** 状态判定。优先级：未开启 > 无网 > 等 WiFi > 未成功检测 > 过期 > 正常 */
    fun widgetStatus(
        running: Boolean,
        netOnline: Boolean,
        wifiOnly: Boolean,
        onWifi: Boolean,
        lastSuccessAt: Long,
        now: Long,
    ): WidgetStatus = when {
        !running -> WidgetStatus.NOT_RUNNING
        !netOnline -> WidgetStatus.WAIT_NETWORK
        wifiOnly && !onWifi -> WidgetStatus.WAIT_WIFI
        lastSuccessAt <= 0L -> WidgetStatus.FETCHING
        now - lastSuccessAt > STALE_THRESHOLD_MS -> WidgetStatus.STALE
        else -> WidgetStatus.NORMAL
    }

    /** 尺寸 → 布局。高度够就展开，否则紧凑；宽度很窄也压回紧凑 */
    fun pickLayout(widthDp: Int, heightDp: Int): WidgetLayout = when {
        widthDp < 160 || heightDp < 110 -> WidgetLayout.COMPACT
        else -> WidgetLayout.EXPANDED
    }

    /**
     * 展开布局里这一刻能放几条。RemoteViews 只能用写死的槽位，做不到真·动态列表，
     * 但可以按高度动态决定"这次显示几条"——拉高一格多冒一条。
     * 每条约 36dp（头像 28 + 上下间距 6 + 余量），头部+胶囊约 44dp，底部溢出提示预留 16dp。
     */
    fun visibleItemCount(heightDp: Int, liveCount: Int, layout: WidgetLayout): Int {
        if (liveCount <= 0) return 0
        val capacity = when (layout) {
            WidgetLayout.COMPACT -> 2
            WidgetLayout.EXPANDED -> ((heightDp - 44 - 16) / 36).coerceAtLeast(2)
        }
        return capacity.coerceAtMost(layout.maxItems).coerceAtMost(liveCount)
    }

    /** 超出可显示条数时的提示：还有 N 位正在直播 */
    fun overflowText(liveCount: Int, maxItems: Int): String? {
        val rest = liveCount - maxItems
        return if (rest > 0) "还有 $rest 位正在直播" else null
    }

    /**
     * 「最近一次成功检测」近似：取 errorCount==0 且未 missing 的主播里最大的 lastCheckedAt。
     * 失败分支也会写 lastCheckedAt，所以不能直接用全表最大值——那样断网时会误报「刚检测过」。
     * OW 已确认允许一定误差，不为此加 Room 迁移。
     */
    fun lastSuccessfulCheck(streamers: List<StreamerEntity>): Long =
        streamers.filter { it.errorCount == 0 && !it.missing && it.lastCheckedAt > 0L }
            .maxOfOrNull { it.lastCheckedAt } ?: 0L

    /** 从关注列表派生小组件数据：只收 enabled && isLive，按昵称排（与列表口径一致） */
    fun buildData(
        streamers: List<StreamerEntity>,
        running: Boolean,
        netOnline: Boolean,
        wifiOnly: Boolean,
        onWifi: Boolean,
        now: Long,
    ): WidgetData {
        val enabled = streamers.filter { it.enabled }
        val live = enabled.filter { it.isLive && !it.missing }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.nickname })
            .map {
                LiveStreamItem(
                    id = it.id,
                    platformId = it.platformId,
                    nickname = it.nickname,
                    title = it.title,
                    avatarUrl = it.avatarUrl,
                    roomUrl = it.roomUrl,
                )
            }
        val lastSuccess = lastSuccessfulCheck(enabled)
        return WidgetData(
            live = live,
            total = enabled.size,
            status = widgetStatus(running, netOnline, wifiOnly, onWifi, lastSuccess, now),
            lastSuccessAt = lastSuccess,
            now = now,
        )
    }

    /** 数据是否值得刷新：在播 id 集合、标题、头像、状态任一变化才重建 RemoteViews */
    fun needsRefresh(old: WidgetData?, new: WidgetData): Boolean {
        if (old == null) return true
        if (old.status != new.status) return true
        if (old.total != new.total) return true
        if (old.live.size != new.live.size) return true
        return old.live.zip(new.live).any { (a, b) ->
            a.id != b.id || a.title != b.title || a.avatarUrl != b.avatarUrl ||
                a.nickname != b.nickname || a.roomUrl != b.roomUrl
        }
    }

    /** 状态行的中文文案 */
    fun statusText(status: WidgetStatus): String = when (status) {
        WidgetStatus.NORMAL -> "监控正常"
        WidgetStatus.NOT_RUNNING -> "监控未开启"
        WidgetStatus.WAIT_NETWORK -> "等待网络"
        WidgetStatus.WAIT_WIFI -> "等待 Wi-Fi"
        WidgetStatus.FETCHING -> "正在获取直播状态"
        WidgetStatus.STALE -> "数据可能已过期"
    }

    /**
     * 状态行的深色配色。保持旧签名兼容，实际逻辑在 [WidgetPalette]（浅色/深色各一套）。
     */
    fun statusTint(status: WidgetStatus): String = WidgetPalette.statusTint(status, dark = true)

    /**
     * 空状态文案按状态走：过去无论监控没开、断网、还没检测都写「暂无主播正在直播」，
     * 会让用户误以为查过了真没人播。现在明确说出为什么是空的。
     */
    fun emptyText(status: WidgetStatus, total: Int): String = when (status) {
        WidgetStatus.NORMAL -> if (total == 0) "还没有关注主播" else "暂无主播正在直播"
        WidgetStatus.NOT_RUNNING -> "监控未开启，开启后才会检测"
        WidgetStatus.WAIT_NETWORK -> "等待网络连接后开始检测"
        WidgetStatus.WAIT_WIFI -> "当前非 Wi-Fi，等待连接 Wi-Fi"
        WidgetStatus.FETCHING -> "正在获取直播状态…"
        WidgetStatus.STALE -> "数据可能已过期，检查下网络"
    }
}
