package com.example.livenotify

import com.example.livenotify.data.EasterEggs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 关注问候匹配框架的单测：全部使用虚构的主播名与台词，
 * 只验证「主键 → 展示号 → 昵称兜底」的匹配优先级与归一化逻辑。
 * 真实配置由 CI 注入，不在公开仓库里。
 */
class EasterEggMatchTest {

    private fun egg(id: String, name: String) =
        EasterEggs.Egg("fake_platform", name, "虚构台词-$id")

    private val byKey = mapOf(
        "fake_platform:1001" to egg("1001", "虚构主播甲"),
        "fake_platform:2002" to egg("2002", "虚构主播乙")
    )
    private val byDisplay = mapOf(
        "fake_platform:3003" to egg("3003", "虚构主播丙")
    )
    private val byNickname = mapOf(
        "fake_platform:虚构主播丁" to egg("4004", "虚构主播丁")
    )

    @Test
    fun `主键命中优先于其他匹配`() {
        val hit = EasterEggs.resolve(
            byKey, byDisplay, byNickname,
            "fake_platform", "1001", "3003", "虚构主播丁"
        )
        assertEquals("虚构台词-1001", hit?.text)
    }

    @Test
    fun `主键未中时按展示号命中`() {
        val hit = EasterEggs.resolve(
            byKey, byDisplay, byNickname,
            "fake_platform", "9999", "3003", ""
        )
        assertEquals("虚构台词-3003", hit?.text)
    }

    @Test
    fun `标识都对不上时按昵称兜底`() {
        val hit = EasterEggs.resolve(
            byKey, byDisplay, byNickname,
            "fake_platform", "9999", "8888", "虚构主播丁"
        )
        assertEquals("虚构台词-4004", hit?.text)
    }

    @Test
    fun `昵称大小写与空白归一化后命中`() {
        val hit = EasterEggs.resolve(
            byKey, byDisplay, byNickname,
            "fake_platform", "9999", "8888", "  虚构主播丁  "
        )
        assertEquals("虚构台词-4004", hit?.text)
    }

    @Test
    fun `全部对不上时静默不弹`() {
        val hit = EasterEggs.resolve(
            byKey, byDisplay, byNickname,
            "fake_platform", "9999", "8888", "无关路人"
        )
        assertNull(hit)
    }

    @Test
    fun `昵称为空不触发兜底`() {
        val hit = EasterEggs.resolve(
            byKey, byDisplay, byNickname,
            "fake_platform", "9999", "8888", "   "
        )
        assertNull(hit)
    }
}
