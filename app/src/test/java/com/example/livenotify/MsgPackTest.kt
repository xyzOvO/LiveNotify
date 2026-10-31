package com.example.livenotify

import com.example.livenotify.bili.BiliStatsApi
import com.example.livenotify.bili.MsgPack
import com.example.livenotify.bili.asMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MessagePack 解码回归测试。
 *
 * 数据接口的「用户互动分析」结果是 `application/x-msgpack`，解码器是手写的，
 * 这类代码一旦字段偏移算错就会静默解析出垃圾值，必须锁死行为。
 * 样本是合成数据（不是真实用户数据），避免把任何人查过的账号写进仓库。
 */
class MsgPackTest {

    /** 把十六进制串转成字节数组，比 byteArrayOf(-115, -90, ...) 好读也好维护 */
    private fun hex(s: String): ByteArray {
        val clean = s.filterNot { it.isWhitespace() }
        require(clean.length % 2 == 0) { "hex 长度必须是偶数" }
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    @Test
    fun `解码各类基础类型`() {
        // {"a":1,"b":-1,"c":"中文","d":true,"e":false,"f":1.5,"g":null,"h":[1,2],"i":{"k":"v"}}
        val data = hex(
            "89a16101a162ffa163a6e4b8ade69687a164c3a165c2a166cb3ff8000000000000a167c0" +
                "a168920102a16981a16ba176"
        )
        val map = MsgPack.decode(data).asMap()!!
        assertEquals(1L, map["a"])
        assertEquals(-1L, map["b"])
        assertEquals("中文", map["c"])
        assertEquals(true, map["d"])
        assertEquals(false, map["e"])
        assertEquals(1.5, map["f"] as Double, 1e-9)
        assertEquals(null, map["g"])
        assertEquals(listOf(1L, 2L), map["h"])
        assertEquals("v", (map["i"] as Map<*, *>)["k"])
    }

    @Test
    fun `数值边界不被截断`() {
        // [127, 128, 255, 256, 65535, 65536, -32, -33, -129, 4294967296]
        val data = hex(
            "9a7fcc80ccffcd0100cdffffce00010000d0e0d0dfd1ff7fcf0000000100000000"
        )
        val list = MsgPack.decode(data) as List<*>
        assertEquals(127L, list[0])
        assertEquals(128L, list[1])
        assertEquals(255L, list[2])
        assertEquals(256L, list[3])
        assertEquals(65535L, list[4])
        assertEquals(65536L, list[5])
        assertEquals(-32L, list[6])
        assertEquals(-33L, list[7])
        assertEquals(-129L, list[8])
        assertEquals(4294967296L, list[9])
    }

    @Test
    fun `数据被截断时抛出异常而不是返回垃圾`() {
        val full = hex("82a16101a162920102")
        val broken = full.copyOfRange(0, full.size - 2)
        var thrown = false
        try {
            MsgPack.decode(broken)
        } catch (t: Throwable) {
            thrown = true
        }
        assertTrue("截断的数据必须报错", thrown)
    }

    @Test
    fun `ext 类型整体跳过 type 字节，后续字段不错位`() {
        // [fixext1(type=1,data=2), 3]：ext 是 type(1)+payload，只跳过 payload 会把 type 当成下一个元素
        val list = MsgPack.decode(hex("92d4010203")) as List<*>
        assertEquals(2, list.size)
        assertEquals(null, list[0])
        assertEquals(3L, list[1])
    }

    @Test
    fun `长度为 0 的 ext8 也要吃掉类型字节`() {
        // {"a": ext8(len=0,type=5), "b": 7}
        val map = MsgPack.decode(hex("82a161c70005a16207")).asMap()!!
        assertEquals(2, map.size)
        assertEquals(null, map["a"])
        assertEquals(7L, map["b"])
    }

    @Test
    fun `解析互动分析结果的各字段`() {
        val root = MsgPack.decode(hex(ANALYSIS_HEX)).asMap()!!
        val r = BiliStatsApi.parseAnalysis(root)

        assertEquals(listOf("旧昵称", "新昵称"), r.names)

        assertEquals(168, r.summary.messages)
        assertEquals(871, r.summary.interactions)
        assertEquals(1720.9, r.summary.payment, 1e-6)
        assertEquals(113, r.summary.activeDays)
        assertEquals(144, r.summary.appearanceLives)
        assertEquals(72, r.summary.interactiveLives)
        assertEquals(5, r.summary.paidLives)

        assertEquals("好听", r.insights?.maxRepeatedText)
        assertEquals(6, r.insights?.maxRepeatedCount)

        assertEquals(1, r.days.size)
        assertEquals("2026-08-01", r.days[0].date)
        assertEquals(20, r.days[0].interactions)

        // count 为 0 的类型应被过滤掉，只留两条
        assertEquals(2, r.eventTypes.size)
        assertEquals("Message", r.eventTypes[0].name)
        assertEquals(871, r.eventTypes[0].count)

        assertEquals(1, r.affinities.size)
        assertEquals("某主播", r.affinities[0].name)
        assertEquals(12, r.affinities[0].messages)

        assertEquals(1, r.topPayments.size)
        assertEquals(100.0, r.topPayments[0].amount, 1e-6)

        assertEquals(listOf(listOf(1, 2), listOf(3, 4)), r.rhythm)

        // 词云按出现次数降序
        assertEquals("好听", r.wordCloud.first().first)
        assertEquals(7, r.wordCloud.first().second)

        assertEquals("虚拟主播", r.areas[0].name)
        assertEquals(300, r.areas[0].count)
    }

    companion object {
        /** 合成样本：结构与真实响应一致，内容为虚构 */
        private const val ANALYSIS_HEX =
            "8da6757365724964ce0001e240a9737461727454696d65cf0000018bcfe56800a7656e6454696d65cf0000018bd5db4900ab" +
                "6e616d65486973746f727992a9e697a7e698b5e7a7b0a9e696b0e698b5e7a7b0a9776f7264436c6f756482a6e5a5bde590ac" +
                "07a6e59388e5938804a773756d6d61727988ad746f74616c4d65737361676573cca8b1746f74616c496e746572616374696f" +
                "6e73cd0367ac746f74616c5061796d656e74cb409ae3999999999aaa626c6f636b436f756e7400aa61637469766544617973" +
                "71af617070656172616e63654c69766573cc90b0696e7465726163746976654c6976657348a9706169644c6976657305a869" +
                "6e73696768747388ad7265706561746572496e646578cb3ff428f5c28f5c29af6d6178526570656174656454657874a6e5a5" +
                "bde590acb06d61785265706561746564436f756e7406aa656d6f6a69526174696fcb3f885f06f6944674ae726f6f6d456d6f" +
                "6a69526174696fcb3f885f06f6944674ad6e696768744f776c526174696fcb3fcdb573eab367a1b06176674d657373616765" +
                "4c656e677468cb4007333333333333b2756e697175654d657373616765526174696fcb3fe9559b3d07c84bac72687974686d" +
                "4d617472697892920102920304b16368616e6e656c416666696e6974696573918baa6368616e6e656c5569646fab6368616e" +
                "6e656c4e616d65a9e69f90e4b8bbe692ada6726f6f6d4964ccdea76661636555726cb968747470733a2f2f6578616d706c65" +
                "2e636f6d2f612e6a7067ac6d657373616765436f756e740ca77061796d656e74cb40769e6666666666aa6163746976654461" +
                "79730aa96c697665436f756e740aac6c6f79616c747953636f7265cb405019999999999aad66697273744163746976654174" +
                "cf0000018bcfe56800ac6c6173744163746976654174cf0000018bd2e05880ab746f705061796d656e74739186a36b6579a1" +
                "6ba47479706501a46e616d65a9e7a4bce789a9e5908da6616d6f756e74cb4059000000000000ab6368616e6e656c4e616d65" +
                "a9e69f90e4b8bbe692ada974696d657374616d70cf0000018bcfe56800aa6576656e74547970657383a74d65737361676582" +
                "a5636f756e74cd0367a77061796d656e74cb0000000000000000a44769667482a5636f756e7405a77061796d656e74cb409a" +
                "e3999999999aa5456e74657282a5636f756e7400a77061796d656e74cb0000000000000000a561726561739182a46e616d65" +
                "ace8999ae68b9fe4b8bbe692ada5636f756e74cd012cac6163746976697479446179739185a464617465aa323032362d3038" +
                "2d3031a86d657373616765730aac696e746572616374696f6e7314a77061796d656e74cb4016000000000000a56c69766573" +
                "02"
    }
}
