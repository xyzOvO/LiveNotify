package com.example.livenotify.bili

import java.nio.charset.StandardCharsets

/**
 * 极简 MessagePack 解码器。
 *
 * 只为解析数据接口的 `application/x-msgpack` 响应而写，避免为了一个接口引入额外依赖。
 * 覆盖规范里的全部类型族：nil / bool / int / float / str / bin / array / map / ext。
 *
 * 映射规则：整数一律 Long，浮点一律 Double，map 的 key 一律转成 String，
 * 二进制块转 ByteArray，ext 类型不参与业务，读掉后返回 null。
 */
object MsgPack {

    /** 解析一段完整的 MessagePack 数据，返回 Map / List / 基础类型 */
    fun decode(bytes: ByteArray): Any? = Reader(bytes).readValue()

    private class Reader(private val b: ByteArray) {
        private var pos = 0

        private fun need(n: Int) {
            if (pos + n > b.size) throw IllegalArgumentException("数据不完整：需要 $n 字节，剩余 ${b.size - pos}")
        }

        private fun u8(): Int {
            need(1)
            return b[pos++].toInt() and 0xFF
        }

        private fun i8(): Int {
            need(1)
            return b[pos++].toInt()
        }

        private fun u16(): Int {
            need(2)
            val v = ((b[pos].toInt() and 0xFF) shl 8) or (b[pos + 1].toInt() and 0xFF)
            pos += 2
            return v
        }

        private fun i16(): Int {
            need(2)
            val v = ((b[pos].toInt() shl 8) or (b[pos + 1].toInt() and 0xFF))
            pos += 2
            return v
        }

        private fun u32(): Long {
            need(4)
            var v = 0L
            for (i in 0 until 4) v = (v shl 8) or (b[pos + i].toLong() and 0xFF)
            pos += 4
            return v
        }

        private fun i32(): Int {
            need(4)
            var v = 0
            for (i in 0 until 4) v = (v shl 8) or (b[pos + i].toInt() and 0xFF)
            pos += 4
            return v
        }

        private fun u64(): Long {
            need(8)
            var v = 0L
            for (i in 0 until 8) v = (v shl 8) or (b[pos + i].toLong() and 0xFF)
            pos += 8
            return v
        }

        private fun i64(): Long = u64()

        private fun str(len: Int): String {
            need(len)
            val s = String(b, pos, len, StandardCharsets.UTF_8)
            pos += len
            return s
        }

        private fun bin(len: Int): ByteArray {
            need(len)
            val out = b.copyOfRange(pos, pos + len)
            pos += len
            return out
        }

        /** 读掉 ext 的负载（不解析业务语义） */
        private fun skipExt(len: Int): Any? {
            if (len > 0) bin(len)
            return null
        }

        fun readValue(): Any? {
            val code = u8()
            return when {
                code <= 0x7f -> code.toLong()                                   // positive fixint
                (code and 0xF0) == 0x80 -> readMap(code and 0x0F)                 // fixmap
                (code and 0xF0) == 0x90 -> readArray(code and 0x0F)               // fixarray
                (code and 0xE0) == 0xA0 -> str(code and 0x1F)                     // fixstr
                code == 0xC0 -> null                                            // nil
                code == 0xC2 -> false
                code == 0xC3 -> true
                code == 0xC4 -> bin(u8())
                code == 0xC5 -> bin(u16())
                code == 0xC6 -> bin(u32().toInt())
                code == 0xC7 -> { val n = u8(); skipExt(n) }                    // ext8
                code == 0xC8 -> { val n = u16(); skipExt(n) }                   // ext16
                code == 0xC9 -> { val n = u32().toInt(); skipExt(n) }           // ext32
                code == 0xCA -> Float.fromBits(i32()).toDouble()                // float32
                code == 0xCB -> Double.fromBits(u64())                          // float64
                code == 0xCC -> u8().toLong()                                   // uint8
                code == 0xCD -> u16().toLong()                                  // uint16
                code == 0xCE -> u32()                                           // uint32
                code == 0xCF -> u64()                                           // uint64
                code == 0xD0 -> i8().toLong()                                   // int8
                code == 0xD1 -> i16().toLong()                                  // int16
                code == 0xD2 -> i32().toLong()                                  // int32
                code == 0xD3 -> i64()                                           // int64
                code == 0xD4 -> { skipExt(1); null }                            // fixext1
                code == 0xD5 -> { skipExt(2); null }                            // fixext2
                code == 0xD6 -> { skipExt(4); null }                            // fixext4
                code == 0xD7 -> { skipExt(8); null }                            // fixext8
                code == 0xD8 -> { skipExt(16); null }                           // fixext16
                code == 0xD9 -> str(u8())                                       // str8
                code == 0xDA -> str(u16())                                      // str16
                code == 0xDB -> str(u32().toInt())                              // str32
                code == 0xDC -> readArray(u16())                                // array16
                code == 0xDD -> readArray(u32().toInt())                        // array32
                code == 0xDE -> readMap(u16())                                  // map16
                code == 0xDF -> readMap(u32().toInt())                          // map32
                code >= 0xE0 -> (code - 0x100).toLong()                         // negative fixint
                else -> throw IllegalArgumentException("未知类型标记 0x${code.toString(16)}")
            }
        }

        private fun readArray(n: Int): List<Any?> {
            if (n < 0 || n > 1_000_000) throw IllegalArgumentException("数组长度异常：$n")
            val out = ArrayList<Any?>(minOf(n, 4096))
            repeat(n) { out.add(readValue()) }
            return out
        }

        private fun readMap(n: Int): Map<String, Any?> {
            if (n < 0 || n > 1_000_000) throw IllegalArgumentException("映射长度异常：$n")
            val out = LinkedHashMap<String, Any?>(minOf(n, 4096))
            repeat(n) {
                val k = readValue()
                val key = when (k) {
                    is String -> k
                    is Long -> k.toString()
                    is Double -> k.toString()
                    is Boolean -> k.toString()
                    null -> "null"
                    else -> k.toString()
                }
                out[key] = readValue()
            }
            return out
        }
    }
}

// ── 取值助手：MessagePack 解出来是弱类型结构，逐个字段安全转换 ──────────────

internal fun Any?.asMap(): Map<String, Any?>? = this as? Map<String, Any?>

internal fun Map<String, Any?>.long(key: String): Long? = when (val v = this[key]) {
    is Long -> v
    is Int -> v.toLong()
    is Double -> v.toLong()
    is String -> v.toLongOrNull()
    else -> null
}

internal fun Map<String, Any?>.int(key: String): Int? = long(key)?.toInt()

internal fun Map<String, Any?>.double(key: String): Double? = when (val v = this[key]) {
    is Double -> v
    is Long -> v.toDouble()
    is String -> v.toDoubleOrNull()
    else -> null
}

internal fun Map<String, Any?>.str(key: String): String = when (val v = this[key]) {
    is String -> if (v == "null") "" else v
    null -> ""
    else -> v.toString()
}

internal fun Map<String, Any?>.list(key: String): List<Any?> = (this[key] as? List<Any?>) ?: emptyList()

internal fun Map<String, Any?>.bool(key: String): Boolean = this[key] as? Boolean ?: false
