package com.example.livenotify

import com.example.livenotify.ui.fmtMoney
import org.junit.Assert.assertEquals
import org.junit.Test

/** 收入金额的显示规则：大额用「亿/万」，小额不被抹成 0 元。 */
class MoneyFormatTest {

    @Test
    fun `上亿显示亿元而不是万元`() {
        assertEquals("1.3亿元", fmtMoney(127_300_000.0))
        assertEquals("1.0亿元", fmtMoney(100_000_000.0))
    }

    @Test
    fun `万元与元边界`() {
        assertEquals("1.0万元", fmtMoney(10_000.0))
        assertEquals("9999元", fmtMoney(9_999.0))
        assertEquals("1元", fmtMoney(1.4))
    }

    @Test
    fun `小额不抹零负数归一`() {
        assertEquals("0.6元", fmtMoney(0.6))
        assertEquals("0.1元", fmtMoney(0.1))
        assertEquals("0元", fmtMoney(0.0))
        assertEquals("0元", fmtMoney(-5.0))
    }
}
