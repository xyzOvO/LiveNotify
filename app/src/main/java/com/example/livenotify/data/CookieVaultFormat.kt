package com.example.livenotify.data

import java.util.Base64

/**
 * Cookie 密文的打包格式（纯函数，无 Android 依赖，可在 JVM 单测中验证）。
 *
 * 格式：PREFIX + urlBase64(iv) + ":" + urlBase64(ciphertext)
 */
internal object CookieVaultFormat {
    const val PREFIX = "AESGCM1:"

    fun isEncrypted(s: String): Boolean = s.startsWith(PREFIX)

    private fun b64(b: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(b)

    private fun unb64(s: String): ByteArray = Base64.getUrlDecoder().decode(s)

    fun pack(iv: ByteArray, ct: ByteArray): String = PREFIX + b64(iv) + ":" + b64(ct)

    /** 解包；格式坏 / 非本格式返回 null，调用方当作「解不开」处理 */
    fun unpack(s: String): Pair<ByteArray, ByteArray>? {
        if (!isEncrypted(s)) return null
        val body = s.removePrefix(PREFIX)
        val sep = body.indexOf(':')
        if (sep <= 0 || sep == body.length - 1) return null
        return try {
            unb64(body.substring(0, sep)) to unb64(body.substring(sep + 1))
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
