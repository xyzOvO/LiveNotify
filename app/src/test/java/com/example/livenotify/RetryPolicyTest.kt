package com.example.livenotify

import com.example.livenotify.net.RetryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** HTTP 重试判据与退避：4xx 绝不重试、指数增长且封顶、抖动不破坏单调性。 */
class RetryPolicyTest {

    @Test
    fun `4xx 不重试 429 与 5xx 重试`() {
        assertFalse(RetryPolicy.shouldRetryHttpCode(400))
        assertFalse(RetryPolicy.shouldRetryHttpCode(401))
        assertFalse(RetryPolicy.shouldRetryHttpCode(403))
        assertFalse(RetryPolicy.shouldRetryHttpCode(404))
        assertTrue(RetryPolicy.shouldRetryHttpCode(429))
        assertTrue(RetryPolicy.shouldRetryHttpCode(500))
        assertTrue(RetryPolicy.shouldRetryHttpCode(502))
        assertTrue(RetryPolicy.shouldRetryHttpCode(503))
        assertTrue(RetryPolicy.shouldRetryHttpCode(599))
        assertFalse(RetryPolicy.shouldRetryHttpCode(600))
    }

    @Test
    fun `IO 与超时重试`() {
        assertTrue(RetryPolicy.shouldRetryThrowable(IOException("reset")))
        assertTrue(RetryPolicy.shouldRetryThrowable(SocketTimeoutException()))
        assertTrue(RetryPolicy.shouldRetryThrowable(UnknownHostException()))
        assertFalse(RetryPolicy.shouldRetryThrowable(IllegalStateException("解析失败")))
    }

    @Test
    fun `退避指数增长且封顶`() {
        assertEquals(500L, RetryPolicy.delayMs(0))
        val d1 = RetryPolicy.delayMs(1)
        val d2 = RetryPolicy.delayMs(2)
        assertTrue("第二次退避应大于第一次: $d1 < $d2", d1 < d2)
        // 最多 3 次请求，attempt 只会到 1（第一次失败后）与 2（第二次失败后）
        assertTrue("退避必须封顶 8 秒", RetryPolicy.delayMs(10) <= 8_000L)
    }

    @Test
    fun `抖动保持单调且非负`() {
        val delays = (0..6).map { RetryPolicy.delayMs(it) }
        assertTrue(delays.all { it >= 0L })
        // 抖动幅度小，不能出现后一次反而明显小于前一次（指数段严格递增）
        assertTrue(delays[1] > delays[0])
        assertTrue(delays[2] > delays[1])
        assertTrue(delays[3] > delays[2])
    }
}
