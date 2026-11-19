package com.example.livenotify.net

import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.math.min

/**
 * HTTP GET 的重试判据与退避计算（零 Android 依赖，单测可跑）。
 *
 * 只对**幂等的 GET**做有限重试，且不碰 4xx——那是请求本身的问题（404/401/参数错），
 * 重试一百次也一样，只会浪费流量并可能招致风控。可重试的是：
 *   - 连接/读取超时、连接被重置等 IOException（多半是网络抖动）
 *   - 429（限流）与 5xx（服务端临时故障）
 *
 * 退避用指数增长 + 确定性抖动：抖动按 attempt 取固定序列，避免同一时刻发出的多个请求
 * 在重试时又撞到一起（惊群），同时保持纯函数可测。取消（CancellationException）永远不重试，
 * 由调用方原样抛出。
 */
internal object RetryPolicy {

    /** 加上首次共最多 3 次请求（即最多重试 2 次） */
    const val MAX_ATTEMPTS = 3

    private const val BASE_MS = 500L
    private const val MAX_DELAY_MS = 8_000L

    /** 确定性抖动序列（毫秒），让退避不是整齐的 500/1000/2000 */
    private val JITTER_MS = longArrayOf(0L, 170L, 430L, 260L, 610L)

    fun shouldRetryHttpCode(code: Int): Boolean = code == 429 || code in 500..599

    fun shouldRetryThrowable(t: Throwable): Boolean = when (t) {
        is SocketTimeoutException -> true
        is IOException -> true
        else -> false
    }

    /** @param attempt 已经失败的次数（0 = 第一次失败后），返回下一次重试前应等待的毫秒 */
    fun delayMs(attempt: Int): Long {
        if (attempt <= 0) return BASE_MS
        val exponential = BASE_MS shl min(attempt, 5) // 500→1000→2000→4000→8000 封顶
        val raw = min(exponential, MAX_DELAY_MS)
        val jitter = JITTER_MS[attempt % JITTER_MS.size]
        return min(raw + jitter, MAX_DELAY_MS)
    }
}
