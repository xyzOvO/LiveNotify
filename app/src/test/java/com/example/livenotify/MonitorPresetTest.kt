package com.example.livenotify

import com.example.livenotify.data.Settings
import com.example.livenotify.ui.MonitorPreset
import com.example.livenotify.ui.presetLabel
import com.example.livenotify.ui.presetOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 检测三档预设：档位识别必须严格，任何一项被单独改过都要落到「自定义」。 */
class MonitorPresetTest {

    private fun settingsOf(p: MonitorPreset) = Settings(
        pollIntervalSec = p.intervalSec,
        mobileFactor = p.mobileFactor,
        confirmTimes = p.confirmTimes,
        cooldownMin = p.cooldownMin
    )

    @Test
    fun `默认设置命中默认档`() {
        assertEquals(MonitorPreset.BALANCED, presetOf(Settings()))
        assertEquals("默认", presetLabel(Settings()))
    }

    @Test
    fun `三档都能被准确识别`() {
        MonitorPreset.entries.forEach { p ->
            assertEquals("档位 ${p.label} 未被自己的参数命中", p, presetOf(settingsOf(p)))
        }
    }

    @Test
    fun `档位之间参数不能重复`() {
        // 参数撞车会导致同一份设置命中两个档，判定结果取决于枚举顺序——必须提前拦住
        val signatures = MonitorPreset.entries.map {
            listOf(it.intervalSec, it.mobileFactor, it.confirmTimes, it.cooldownMin)
        }
        assertEquals("两个档位的参数完全一样", signatures.size, signatures.toSet().size)
    }

    @Test
    fun `只改确认次数就落入自定义`() {
        val s = settingsOf(MonitorPreset.BALANCED).copy(confirmTimes = 2)
        assertNull(presetOf(s))
        assertEquals("自定义", presetLabel(s))
    }

    @Test
    fun `只改提醒间隔就落入自定义`() {
        assertNull(presetOf(settingsOf(MonitorPreset.POWER_SAVING).copy(cooldownMin = 30)))
    }

    @Test
    fun `仅 WiFi 开关不影响档位判定`() {
        // wifiOnly 是流量策略，不属于检测节奏，开了不该掉出档位
        val s = Settings(wifiOnly = true)
        assertEquals(MonitorPreset.BALANCED, presetOf(s))
    }

    @Test
    fun `省电档比默认档更省 激进档更频繁`() {
        assertTrue(MonitorPreset.POWER_SAVING.intervalSec > MonitorPreset.BALANCED.intervalSec)
        assertTrue(MonitorPreset.AGGRESSIVE.intervalSec < MonitorPreset.BALANCED.intervalSec)
        assertTrue(MonitorPreset.POWER_SAVING.mobileFactor >= MonitorPreset.BALANCED.mobileFactor)
        assertTrue(MonitorPreset.AGGRESSIVE.mobileFactor <= MonitorPreset.BALANCED.mobileFactor)
    }
}
