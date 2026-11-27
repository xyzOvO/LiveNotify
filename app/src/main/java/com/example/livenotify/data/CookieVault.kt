package com.example.livenotify.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 抖音 Cookie 加密存储（Android KeyStore AES-GCM）。
 *
 * Cookie 等同于账号登录凭据：明文落在 DataStore 里，任何能读应用数据目录的手段
 * （root、备份、adb backup）都能拿到。这里用 AndroidKeyStore 里的 AES-GCM 密钥加密，
 * 密钥由系统 keystore 保管、不进应用数据目录，DataStore 只存密文。
 *
 * 注意：AndroidKeyStore 密钥随应用卸载一起销毁。用户重装 App 后旧密文解不开，
 * decrypt 返回空串，需要重新登录抓取——这是安全设计，不是 bug，界面文案要说清。
 *
 * 纯函数（pack/unpack/isEncrypted）拆在 [CookieVaultFormat]，不碰 Android API，
 * 可在 JVM 单测里验证。
 */
internal object CookieVault {
    const val ALIAS = "douyin_cookie_key"
    private const val TAG_BITS = 128

    fun isEncrypted(s: String): Boolean = CookieVaultFormat.isEncrypted(s)

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply { init(spec) }
            .generateKey()
    }

    /** 加密成可存字符串。失败返回空串——绝不退回明文存储。 */
    fun encrypt(plain: String): String {
        if (plain.isBlank()) return ""
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            CookieVaultFormat.pack(cipher.iv, cipher.doFinal(plain.trim().toByteArray(Charsets.UTF_8)))
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 解密。非本格式（旧明文）原样返回以便迁移；本格式解不开返回空串。
     */
    fun decrypt(stored: String): String {
        if (stored.isBlank()) return ""
        if (!CookieVaultFormat.isEncrypted(stored)) return stored // 旧明文，兼容读取
        val (iv, ct) = CookieVaultFormat.unpack(stored) ?: return ""
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }
}
