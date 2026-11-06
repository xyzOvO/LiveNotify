package com.example.livenotify.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ApkHashTest {

    private val abc = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    @Test
    fun normalizeDigestAcceptsGitHubFormat() {
        assertEquals(abc, ApkHash.normalizeDigest("sha256:$abc"))
        assertEquals(abc, ApkHash.normalizeDigest("  SHA256:$abc  "))
        assertEquals(abc, ApkHash.normalizeDigest(abc.uppercase()))
    }

    @Test
    fun normalizeDigestRejectsMalformedValues() {
        assertNull(ApkHash.normalizeDigest(null))
        assertNull(ApkHash.normalizeDigest(""))
        assertNull(ApkHash.normalizeDigest("sha256:"))
        assertNull(ApkHash.normalizeDigest("sha256:abc"))
        assertNull(ApkHash.normalizeDigest("md5:$abc"))
        assertNull(ApkHash.normalizeDigest("sha256:" + "z".repeat(64)))
    }

    @Test
    fun sha256StreamsFileContent() {
        val f = File.createTempFile("apkhash", ".bin")
        try {
            f.writeBytes("abc".toByteArray())
            assertEquals(abc, ApkHash.sha256(f))
        } finally {
            f.delete()
        }
    }

    @Test
    fun matchesSkipsWhenDigestUnavailable() {
        val f = File.createTempFile("apkhash", ".bin")
        try {
            f.writeBytes("abc".toByteArray())
            assertTrue(ApkHash.matches(f, null))
            assertTrue(ApkHash.matches(f, "sha256:$abc"))
            assertTrue(ApkHash.matches(f, "sha256:" + abc.uppercase()))
            assertFalse(ApkHash.matches(f, "sha256:" + "0".repeat(64)))
            // 拿不到合法 digest（字段缺失 / 302 兜底 / 格式异常）时跳过校验，保持旧 Release 兼容
            assertTrue(ApkHash.matches(f, "not-a-digest"))
        } finally {
            f.delete()
        }
    }
}
