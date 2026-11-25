package com.example.livenotify

import com.example.livenotify.platform.LiveState
import com.example.livenotify.platform.StatusInterpret
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 平台状态解读回归测试。
 *
 * 四个平台的 fetch() 都依赖 org.json，而本机 JVM 单测 classpath 没有 org.json 实现
 * （android.jar 里只有 stub），所以把状态判定抽成 [StatusInterpret] 这种零依赖纯函数，
 * 在这里把「异常 JSON / 字段缺失 / 未知状态」的解读分支全部锁死。
 * 样本结构见 app/src/test/resources/platform-samples/。
 */
class StatusInterpretTest {

    // ---- B站 live_status（样本 bili_status.json 的 0/1/2 三种形态）----

    @Test fun `B站 live_status 1 视为直播中`() {
        assertEquals(LiveState.LIVE, StatusInterpret.biliStatus(1))
    }

    @Test fun `B站 live_status 0 与 2 轮播都视为未开播`() {
        assertEquals(LiveState.OFF, StatusInterpret.biliStatus(0))
        assertEquals(LiveState.OFF, StatusInterpret.biliStatus(2))
    }

    @Test fun `B站未知 live_status 一律不可信`() {
        assertEquals(null, StatusInterpret.biliStatus(-1))
        assertEquals(null, StatusInterpret.biliStatus(3))
        assertEquals(null, StatusInterpret.biliStatus(999))
    }

    // ---- 斗鱼 room_status（样本 douyu_room.json）----

    @Test fun `斗鱼 room_status 字符串 1 与 2 映射正确`() {
        assertEquals(LiveState.LIVE, StatusInterpret.douyuStatus("1"))
        assertEquals(LiveState.OFF, StatusInterpret.douyuStatus("2"))
    }

    @Test fun `斗鱼字段缺失或未知值不可信`() {
        assertEquals(null, StatusInterpret.douyuStatus(""))
        assertEquals(null, StatusInterpret.douyuStatus("  "))
        assertEquals(null, StatusInterpret.douyuStatus("0"))
        assertEquals(null, StatusInterpret.douyuStatus("3"))
        assertEquals(null, StatusInterpret.douyuStatus("live"))
    }

    // ---- 虎牙 liveStatus / realLiveStatus 交叉校验（样本 huya_room.json）----

    @Test fun `虎牙 ON 且 real 一致或缺失才算直播中`() {
        assertTrue(StatusInterpret.huyaStatus("ON", "ON").isLive)
        assertTrue(StatusInterpret.huyaStatus("ON", "").isLive)
    }

    @Test fun `虎牙 real 不一致时保守判未开播`() {
        val v = StatusInterpret.huyaStatus("ON", "OFF")
        assertTrue(v.valid)
        assertFalse(v.isLive)
    }

    @Test fun `虎牙 OFF 一律未开播 未知主状态不可信`() {
        assertFalse(StatusInterpret.huyaStatus("OFF", "ON").isLive)
        assertFalse(StatusInterpret.huyaStatus("unknown", "").valid)
        assertFalse(StatusInterpret.huyaStatus("", "").valid)
    }

    @Test fun `虎牙大小写不敏感`() {
        assertTrue(StatusInterpret.huyaStatus(" on ", " on ").isLive)
    }
}
