package com.example.livenotify.util

/**
 * 极简 JSON 读写（对象 / 数组 / 字符串 / 数字 / 布尔 / null）。
 *
 * 项目不引入 JSON 库有两个原因：备份格式与本地缓存的数据结构都很小，
 * 而且 Android 的 org.json 在 JVM 单元测试里是 stub（调用直接抛异常），
 * 纯 Kotlin 实现才能让编解码真正被测到。
 */
internal object MiniJson {

    fun parse(text: String): Any? {
        val p = Parser(text)
        val v = p.value()
        p.skipWs()
        if (p.pos != text.length) error("尾部有多余内容")
        return v
    }

    fun quote(s: String): String = buildString {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    private class Parser(private val s: String) {
        var pos = 0

        fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun value(): Any? {
            skipWs()
            if (pos >= s.length) error("意外结束")
            return when (s[pos]) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                't' -> { literal("true"); true }
                'f' -> { literal("false"); false }
                'n' -> { literal("null"); null }
                else -> number()
            }
        }

        private fun literal(word: String) {
            if (!s.startsWith(word, pos)) error("非法字面量")
            pos += word.length
        }

        private fun obj(): Map<String, Any?> {
            pos++ // {
            val out = LinkedHashMap<String, Any?>()
            skipWs()
            if (pos < s.length && s[pos] == '}') { pos++; return out }
            while (true) {
                skipWs()
                if (pos >= s.length || s[pos] != '"') error("对象键必须是字符串")
                val k = string()
                skipWs()
                if (pos >= s.length || s[pos] != ':') error("缺少冒号")
                pos++
                out[k] = value()
                skipWs()
                if (pos >= s.length) error("对象未闭合")
                when (s[pos]) {
                    ',' -> pos++
                    '}' -> { pos++; return out }
                    else -> error("对象内缺少逗号")
                }
            }
        }

        private fun array(): List<Any?> {
            pos++ // [
            val out = ArrayList<Any?>()
            skipWs()
            if (pos < s.length && s[pos] == ']') { pos++; return out }
            while (true) {
                out.add(value())
                skipWs()
                if (pos >= s.length) error("数组未闭合")
                when (s[pos]) {
                    ',' -> pos++
                    ']' -> { pos++; return out }
                    else -> error("数组内缺少逗号")
                }
            }
        }

        private fun string(): String {
            pos++ // "
            val sb = StringBuilder()
            while (pos < s.length) {
                val c = s[pos++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (pos >= s.length) error("转义未完成")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) error("\\u 转义不完整")
                                val hex = s.substring(pos, pos + 4)
                                sb.append(hex.toInt(16).toChar())
                                pos += 4
                            }
                            else -> error("未知转义 \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
            error("字符串未闭合")
        }

        private fun number(): Number {
            val start = pos
            if (pos < s.length && s[pos] == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] in "+-.eE")) pos++
            val raw = s.substring(start, pos)
            if (raw.isBlank()) error("非法值")
            return raw.toLongOrNull() ?: raw.toDoubleOrNull() ?: error("非法数字 $raw")
        }
    }
}

/**
 * 配套的极简 JSON 输出。
 *
 * 手写字符串拼接时最常错的是逗号：字段一多就会多一个或少一个，解析直接失败。
 * 这里让每一层自己记「是不是第一个元素」，调用方只管写字段。
 */
internal class JsonWriter {
    private val sb = StringBuilder()

    /** 每层是否还没有写入元素（栈顶 = 当前层） */
    private val fresh = ArrayList<Boolean>()

    private fun sep() {
        if (fresh.isEmpty()) return
        val i = fresh.lastIndex
        if (fresh[i]) fresh[i] = false else sb.append(',')
    }

    fun obj(key: String? = null, block: JsonWriter.() -> Unit): JsonWriter {
        sep()
        if (key != null) sb.append(MiniJson.quote(key)).append(':')
        sb.append('{')
        fresh.add(true)
        block()
        fresh.removeAt(fresh.lastIndex)
        sb.append('}')
        return this
    }

    fun arr(key: String? = null, block: JsonWriter.() -> Unit): JsonWriter {
        sep()
        if (key != null) sb.append(MiniJson.quote(key)).append(':')
        sb.append('[')
        fresh.add(true)
        block()
        fresh.removeAt(fresh.lastIndex)
        sb.append(']')
        return this
    }

    /** 数组里的一个对象元素 */
    fun itemObj(block: JsonWriter.() -> Unit): JsonWriter = obj(null, block)

    fun put(key: String, v: String): JsonWriter = apply {
        sep(); sb.append(MiniJson.quote(key)).append(':').append(MiniJson.quote(v))
    }

    fun put(key: String, v: Long): JsonWriter = apply {
        sep(); sb.append(MiniJson.quote(key)).append(':').append(v)
    }

    fun put(key: String, v: Int): JsonWriter = put(key, v.toLong())

    fun put(key: String, v: Double): JsonWriter = apply {
        sep(); sb.append(MiniJson.quote(key)).append(':')
        // 整数也能安全往返，但别把 3 写成 3.0 再多占几个字节
        if (v.isFinite() && v == v.toLong().toDouble()) sb.append(v.toLong()) else sb.append(v)
    }

    fun put(key: String, v: Boolean): JsonWriter = apply {
        sep(); sb.append(MiniJson.quote(key)).append(':').append(v)
    }

    fun item(v: String): JsonWriter = apply { sep(); sb.append(MiniJson.quote(v)) }

    fun item(v: Int): JsonWriter = apply { sep(); sb.append(v) }

    override fun toString(): String = sb.toString()
}
