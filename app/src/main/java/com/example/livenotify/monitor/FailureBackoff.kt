package com.example.livenotify.monitor

/**
 * 单主播「连续失败退避」判据（零 Android 依赖，单测可跑）。
 *
 * 一个主播连续查询失败（接口变更 / 房间异常 / 被风控）时，每轮都照样发请求只是徒增流量，
 * 还可能加重风控。这里让失败越多、重试间隔越长，但仍周期性重试以便故障恢复后能自动跟上：
 * 达到阈值后下一次可查的时刻 = lastCheckedAt + min((n - 阈值 + 1) × 基础间隔, 上限)。
 *
 * 注意：被退避跳过 != 判定未开播。跳过时引擎完全不动它的状态、不发通知、不加错误计数，
 * 所以不会漏推也不会误推——只是把「这一轮不查」说清楚。
 */
internal object FailureBackoff {

    /** 连续失败达到这个次数才开始退避；偶发 1~4 次抖动不影响检测及时性 */
    const val THRESHOLD = 5

    /** 退避间隔上限：再久就失去「开播提醒」意义 */
    const val MAX_GAP_MS = 30 * 60_000L

    /**
     * 平台层把失败分成「网络抖动（可重试）」与「接口变更/解析失败（不可重试）」两类。
     * 前者每轮重试有希望自愈，按 1 次累加错误、正常退避即可；
     * 后者重试多少轮也不会变好，只会徒增流量/加重风控，因此按更大的步长累加，
     * 让它更快跨过 [THRESHOLD] 进入退避。这就是 Outcome.Failed.retryable 真正影响退避的接线点。
     */
    const val NON_RETRYABLE_STEP = 3

    /** 本轮失败应累加到 errorCount 的步长：可重试=1，不可重试=3（更快退避）。 */
    fun failureStep(retryable: Boolean): Int = if (retryable) 1 else NON_RETRYABLE_STEP

    /**
     * @param errorCount 该主播当前连续失败次数
     * @param lastCheckedAt 上次尝试查询的时刻（毫秒），<=0 视为从未查过
     * @param now 当前时刻（毫秒）
     * @param baseIntervalMs 当前轮询基础间隔（毫秒）
     * @return true 表示本轮跳过该主播的查询
     */
    fun shouldSkip(
        errorCount: Int,
        lastCheckedAt: Long,
        now: Long,
        baseIntervalMs: Long
    ): Boolean {
        if (errorCount < THRESHOLD) return false
        if (lastCheckedAt <= 0L) return false // 没查过就先查一次
        val steps = errorCount - THRESHOLD + 1
        val gap = (steps * baseIntervalMs.coerceAtLeast(1L)).coerceAtMost(MAX_GAP_MS)
        val elapsed = now - lastCheckedAt
        // 系统时间被回拨（elapsed<0）就当间隔已过，允许重试，避免永远卡在退避里
        return elapsed >= 0L && elapsed < gap
    }
}
