/*
 * Zalith Launcher 2 - Remote Control Add-on
 * Copyright (C) 2025-2026 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package com.movtery.zalithlauncher.remote

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 端到端加密。
 *
 * 设计要点：
 * 1. 配对码只有 6 位十进制，熵极低，因此**不能**直接当密钥用，
 *    必须经过高迭代次数的 PBKDF2 拉伸，降低离线爆破速度。
 * 2. 中继主题名由配对码经 HMAC 派生，中继侧看不到配对码本身，
 *    但同一配对码必然落到同一主题——所以配对码必须是一次性的，
 *    连接建立后立刻轮换（见 [RemoteHost]）。
 * 3. 每条消息独立随机 nonce，AES-GCM 自带完整性校验，
 *    中继无法伪造、无法重放（发送方还会做 id 去重）。
 */
object RemoteCrypto {
    private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val PBKDF2_ITERATIONS = 200_000
    private const val KEY_BITS = 256
    private const val GCM_TAG_BITS = 128
    private const val NONCE_BYTES = 12
    private const val TOPIC_PREFIX = "zc"
    private const val TOPIC_HMAC_KEY = "zalith-remote-topic-v1"
    private const val KEY_SALT_PREFIX = "zalith-remote-key-v1|"

    private val random = SecureRandom()

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun unb64(text: String): ByteArray = Base64.getDecoder().decode(text)

    /**
     * 规整用户输入的配对码：只保留数字，最多 6 位
     */
    fun normalizeCode(raw: String): String = raw.filter { it.isDigit() }.take(6)

    fun isValidCode(code: String): Boolean = normalizeCode(code).length == 6

    /**
     * 配对码 → 中继主题名。
     * 输出形如 `zc-1a2b3c4d5e6f7a8b`，足够随机，避免被别人用常见词猜到。
     */
    fun topicOf(code: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(TOPIC_HMAC_KEY.toByteArray(), "HmacSHA256"))
        val digest = mac.doFinal(normalizeCode(code).toByteArray())
        val hex = buildString {
            for (i in 0 until 8) append("%02x".format(digest[i]))
        }
        return "$TOPIC_PREFIX-$hex"
    }

    /**
     * 配对码 + 主题 → AES-256 会话密钥
     */
    fun deriveKey(code: String, topic: String): SecretKey {
        val spec = PBEKeySpec(
            normalizeCode(code).toCharArray(),
            (KEY_SALT_PREFIX + topic).toByteArray(),
            PBKDF2_ITERATIONS,
            KEY_BITS
        )
        return try {
            val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
            SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    /**
     * 生成一个新的 6 位配对码（首位非 0，避免前导零被用户漏输）
     */
    fun newPairCode(): String {
        val first = 1 + random.nextInt(9)
        val rest = random.nextInt(100_000)
        return "$first" + rest.toString().padStart(5, '0')
    }

    fun newInstanceId(): String {
        val bytes = ByteArray(6)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * 加密一条文本报文，返回可直接发布到中继的信封
     */
    fun seal(key: SecretKey, plain: ByteArray): RemoteEnvelope {
        val nonce = ByteArray(NONCE_BYTES)
        random.nextBytes(nonce)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
        val encrypted = cipher.doFinal(plain)
        return RemoteEnvelope(n = b64(nonce), d = b64(encrypted))
    }

    /**
     * 解密信封；密钥不对、被篡改、格式错误都会返回 null
     */
    fun open(key: SecretKey, envelope: RemoteEnvelope): ByteArray? {
        return runCatching {
            val nonce = unb64(envelope.n)
            val data = unb64(envelope.d)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
            cipher.doFinal(data)
        }.getOrNull()
    }

    /**
     * 加密文件等原始字节：输出 `[12 字节 nonce][密文]`
     */
    fun sealBytes(key: SecretKey, plain: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_BYTES)
        random.nextBytes(nonce)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
        return nonce + cipher.doFinal(plain)
    }

    fun openBytes(key: SecretKey, packed: ByteArray): ByteArray? {
        if (packed.size <= NONCE_BYTES) return null
        return runCatching {
            val nonce = packed.copyOfRange(0, NONCE_BYTES)
            val data = packed.copyOfRange(NONCE_BYTES, packed.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
            cipher.doFinal(data)
        }.getOrNull()
    }
}
