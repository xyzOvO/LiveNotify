package com.example.livenotify

import com.example.livenotify.data.CookieVaultFormat
import com.example.livenotify.util.DouyinCookieCapture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 抖音 Cookie 抓取/加密的纯函数边界（不碰 Android keystore / WebView） */
class DouyinCookieCaptureTest {

    @Test
    fun `合并 Cookie 同名后者覆盖前者并保持顺序`() {
        val merged = DouyinCookieCapture.mergeCookies(
            listOf("a=1; b=2", "b=9; c=3", " a = 7 ")
        )
        assertEquals("a=7; b=9; c=3", merged)
    }

    @Test
    fun `合并 Cookie 忽略空段与没有等号的段`() {
        assertEquals("x=1; y=2", DouyinCookieCapture.mergeCookies(listOf("x=1; ; bad; y=2; =novalue")))
    }

    @Test
    fun `登录态判定 认 session 标志 不认设备指纹 ttwid`() {
        assertFalse(DouyinCookieCapture.hasLoginSession("ttwid=abc; odin_tt=def"))
        assertTrue(DouyinCookieCapture.hasLoginSession("ttwid=abc; sessionid_ss=xyz"))
        assertTrue(DouyinCookieCapture.hasLoginSession("sid_tt=1"))
        assertTrue(DouyinCookieCapture.hasLoginSession("sid_guard=1"))
    }

    @Test
    fun `计数只算有效的键值对`() {
        assertEquals(3, DouyinCookieCapture.count("a=1; b=2; bad; =x; c=3"))
    }

    @Test
    fun `vault 打包解包往返一致`() {
        val iv = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)
        val ct = byteArrayOf(9, 8, 7, 6, 5)
        val packed = CookieVaultFormat.pack(iv, ct)
        assertTrue(CookieVaultFormat.isEncrypted(packed))
        val (iv2, ct2) = CookieVaultFormat.unpack(packed)!!
        assertTrue(iv.contentEquals(iv2))
        assertTrue(ct.contentEquals(ct2))
    }

    @Test
    fun `vault 解包拒绝明文与坏格式`() {
        assertFalse(CookieVaultFormat.isEncrypted("sessionid=abc"))
        assertNull(CookieVaultFormat.unpack("sessionid=abc"))
        assertNull(CookieVaultFormat.unpack("AESGCM1:bad"))
        assertNull(CookieVaultFormat.unpack("AESGCM1:!!!not-base64!!!:also!!!"))
    }
}
