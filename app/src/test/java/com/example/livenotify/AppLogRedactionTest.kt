package com.example.livenotify

import com.example.livenotify.monitor.AppLogger
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 运行日志脱敏的纯函数测试：日志表会长期留存，写进来的东西必须能安全地被复制/分享。
 */
class AppLogRedactionTest {

    @Test
    fun `URL 查询串被抹掉保留路径`() {
        val raw = "请求失败 https://api.example.com/v1/room?token=abc123&uid=98765 超时"
        val out = AppLogger.redact(raw)
        assertEquals("请求失败 https://api.example.com/v1/room?<redacted> 超时", out)
    }

    @Test
    fun `长数字串被替换短数字保留`() {
        val raw = "主播号 123456789 返回 HTTP 500，重试 2 次"
        val out = AppLogger.redact(raw)
        assertEquals("主播号 # 返回 HTTP 500，重试 2 次", out)
    }

    @Test
    fun `无敏感信息时原样返回`() {
        val raw = "B站批量接口未返回该主播，保留上次状态"
        assertEquals(raw, AppLogger.redact(raw))
    }
}
