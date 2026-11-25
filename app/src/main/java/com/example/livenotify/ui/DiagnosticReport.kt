package com.example.livenotify.ui

import com.example.livenotify.data.StreamerEntity
import com.example.livenotify.monitor.MonitorEngine
import com.example.livenotify.platform.PlatformRegistry

/**
 * 平台健康诊断的报告生成（纯函数，便于单测与脱敏断言）。
 *
 * 只用现有数据：主播的 errorCount/lastError/lastCheckedAt 与引擎的 lastRunAt/lastDurationMs。
 * 不虚构「下次重试时间」等没有来源的字段——这类项在界面明确标注「暂不支持」。
 * 报告绝不写 Cookie / 抖音 Cookie / 用户标识之外的敏感串；这里只放平台名、主播数、错误类别。
 */

internal data class PlatformHealth(
    val platformId: String,
    val displayName: String,
    val streamers: Int,
    val failed: Int,
    val lastCheckedAt: Long
)

/** 从关注列表派生各平台健康度，按健康度（失败数降序）排 */
internal fun platformHealth(list: List<StreamerEntity>): List<PlatformHealth> =
    list.filter { it.enabled }
        .groupBy { it.platformId }
        .map { (pid, items) ->
            PlatformHealth(
                platformId = pid,
                displayName = PlatformRegistry.get(pid)?.displayName ?: pid,
                streamers = items.size,
                failed = items.count { it.errorCount > 0 },
                lastCheckedAt = items.maxOfOrNull { it.lastCheckedAt } ?: 0L
            )
        }
        .sortedWith(compareByDescending<PlatformHealth> { it.failed }.thenBy { it.displayName })

/** 生成诊断报告文本（无 Cookie / 令牌字段，天然脱敏） */
internal fun buildDiagnosticReport(
    health: List<PlatformHealth>,
    lastRunAt: Long,
    lastDurationMs: Long,
    skip: MonitorEngine.SkipReason?,
    networkLabel: String,
    serviceRunning: Boolean = false,
    nextTickAt: Long = 0L,
    pendingCount: Int = 0,
    recentProblems: List<String> = emptyList()
): String = buildString {
    append("LiveNotify 诊断报告\n")
    append("生成时间：").append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())).append('\n')
    append("后台服务：").append(if (serviceRunning) "运行中" else "未在运行").append('\n')
    append("最近检测：").append(if (lastRunAt > 0) formatClock(lastRunAt) else "尚未检测").append('\n')
    append("下次检测：").append(if (nextTickAt > 0) formatClock(nextTickAt) else "尚未调度").append('\n')
    append("上轮耗时：").append(if (lastDurationMs > 0) "$lastDurationMs ms" else "—").append('\n')
    append("待补发提醒：").append(pendingCount).append(" 条\n")
    append("网络：").append(networkLabel).append('\n')
    append("检测跳过：").append(when (skip) {
        MonitorEngine.SkipReason.WIFI_ONLY -> "等待 Wi-Fi"
        MonitorEngine.SkipReason.NO_NETWORK -> "等待网络"
        null -> "否"
    }).append("\n\n")
    append("平台状态：\n")
    if (health.isEmpty()) append("（暂无启用的关注）\n")
    health.forEach { h ->
        append("- ").append(h.displayName)
            .append("：").append(h.streamers).append(" 位，失败 ").append(h.failed)
            .append("，最近 ").append(if (h.lastCheckedAt > 0) formatClock(h.lastCheckedAt) else "—")
            .append('\n')
    }
    if (recentProblems.isNotEmpty()) {
        append("\n最近异常：\n")
        recentProblems.forEach { append("- ").append(it).append('\n') }
    }
    append("\n注：本报告不含 Cookie / 令牌；下次检测时间为当前调度估计值，系统回收或 Doze 可能顺延。")
}

private fun formatClock(ms: Long): String =
    java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(ms))
