package com.zhiwei.ffmpegx.core.settings

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * WebDAV 密码的加解密。
 *
 * ## 设计要点
 *
 * 1. **密钥存在 Android Keystore 里，永不落盘**。Keystore 的密钥不可导出，
 *    也**不参与 `allowBackup` 的备份** —— 所以即使 DataStore 文件被备份带走，
 *    在另一台设备上也解不开密文。这是本方案相对「直接把密码写进 DataStore」
 *    的核心收益。
 * 2. **AES/GCM**，每次加密随机生成 IV，IV 和密文一起 Base64 存进一个字符串。
 *    GCM 自带完整性校验，密文被篡改会解密失败而不是得到垃圾明文。
 * 3. **降级**：API 23 以下没有 Keystore 的 AES 支持（minSdk 是 24，所以实际
 *    都能用），但 Keystore 在本机上**有可能**出问题（部分定制 ROM 会让
 *    `KeyGenerator` 抛异常）。这种情况不抛给调用方，而是返回 `null`，
 *    让上层提示「本机无法安全保存密码，请每次手动输入」—— 宁可不保存，
 *    也不要为了「能用」而退化成明文存储。
 *
 * ## 存储格式
 *
 * `Base64(iv) + ":" + Base64(ciphertext)`。用 `:` 分隔是为了让格式自解释，
 * 也便于将来换算法时识别旧数据。
 */
internal object WebDavCredentialCipher {

    private const val TAG = "WebDavCipher"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "ffmpegx_webdav"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val SEPARATOR = ":"

    /**
     * 加密。失败时返回 null（表示「本机无法安全保存」），而不是抛异常。
     */
    fun encrypt(plain: String): String? {
        if (plain.isEmpty()) return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, obtainKey())
            val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val iv = cipher.iv
            Base64.encodeToString(iv, Base64.NO_WRAP) +
                SEPARATOR +
                Base64.encodeToString(encrypted, Base64.NO_WRAP)
        }.onFailure {
            Log.w(TAG, "加密失败，密码将无法保存", it)
        }.getOrNull()
    }

    /**
     * 解密。任何失败（密文损坏、密钥被系统清除、换设备）都返回空串 ——
     * 界面上表现为「密码框为空」，用户重填即可。不抛异常是因为调用点在
     * 读设置的 Flow 里，抛出去会让整个设置流崩掉。
     */
    fun decrypt(stored: String): String {
        if (stored.isEmpty()) return ""
        return runCatching {
            val parts = stored.split(SEPARATOR)
            if (parts.size != 2) return@runCatching ""
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val data = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, obtainKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(data), Charsets.UTF_8)
        }.onFailure {
            Log.w(TAG, "解密失败（密钥可能已被系统清除），按未设置处理", it)
        }.getOrDefault("")
    }

    /**
     * 取密钥，不存在则创建。
     *
     * `setUserAuthenticationRequired(false)`：不要求指纹/密码解锁才可用。
     * 若设成 true，后台任务（上传到 WebDAV 的前台服务）在锁屏时会解不开密码，
     * 表现为「锁屏后上传失败」—— 那是个极难排查的问题。
     */
    private fun obtainKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                // 不设 setRandomizedEncryptionRequired(true) 的例外 —— 默认就要求随机 IV，
                // 这正是 GCM 安全性的前提，我们每次都由 Cipher 生成新 IV。
                .build(),
        )
        return generator.generateKey()
    }

    /** 本机是否支持（供设置页提示用） */
    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
}
