package com.example.livenotify.update

import java.io.File
import java.security.MessageDigest

/**
 * 安装包的 SHA-256 完整性校验。
 *
 * 与 ApkInstaller 的签名比对互补：签名回答「是不是同一个作者发的」，
 * 文件 hash 回答「这一份有没有下全 / 有没有被改过」。签名比对要能解析 APK，
 * 而 hash 在下载完、交给安装器之前就能拦下截断或被替换的文件。
 */
object ApkHash {

    private val HEX64 = Regex("^[0-9a-fA-F]{64}$")

    /**
     * GitHub release asset 的 digest 形如 `sha256:<64位hex>`。
     * 拿不到或格式不对时返回 null——此时跳过文件校验，保持旧版 Releases 的兼容性。
     */
    fun normalizeDigest(raw: String?): String? {
        val v = raw?.trim().orEmpty()
        if (v.isEmpty()) return null
        val hex = (if (v.startsWith("sha256:", ignoreCase = true)) v.substring(7) else v).trim()
        return hex.lowercase().takeIf { HEX64.matches(it) }
    }

    /** 流式计算文件 SHA-256，返回小写 hex；大文件不会一次性读进内存 */
    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { b -> "%02x".format(b.toInt() and 0xff) }
    }

    /** expected 为 null（拿不到 digest）时视为「不校验」，不阻断更新 */
    fun matches(file: File, expected: String?): Boolean {
        val want = normalizeDigest(expected) ?: return true
        return sha256(file).equals(want, ignoreCase = true)
    }
}
