package com.example.livenotify

import com.example.livenotify.update.pickSigners
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 锁住「签名读取」的择一规则：新 API 有值优先，空了才回退旧字段，两边都空才算读不到。
 * 只测纯函数——PackageInfo 在 JVM 单测里是 Android stub，构造即抛异常。
 */
class SignerFallbackTest {

    @Test
    fun `新字段有值时优先用新字段`() {
        val modern = arrayOf("new")
        val legacy = arrayOf("old")
        assertSame(modern, pickSigners(modern, legacy))
    }

    @Test
    fun `新字段为 null 时回退旧字段`() {
        val legacy = arrayOf("old")
        assertSame(legacy, pickSigners(null, legacy))
    }

    @Test
    fun `新字段为空数组时回退旧字段`() {
        val legacy = arrayOf("old")
        assertSame(legacy, pickSigners(emptyArray<String>(), legacy))
    }

    @Test
    fun `旧字段缺失不影响新字段`() {
        val modern = arrayOf("new")
        assertSame(modern, pickSigners(modern, null))
    }

    @Test
    fun `两个字段都读不到才返回 null`() {
        assertNull(pickSigners<String>(null, null))
        assertNull(pickSigners(emptyArray<String>(), null))
        assertNull(pickSigners(emptyArray<String>(), emptyArray<String>()))
    }
}
