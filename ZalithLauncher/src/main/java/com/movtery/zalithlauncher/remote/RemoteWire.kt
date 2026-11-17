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

import com.movtery.zalithlauncher.path.GLOBAL_JSON
import com.movtery.zalithlauncher.utils.logging.Logger
import kotlinx.serialization.json.JsonObject
import javax.crypto.SecretKey

private const val TAG = "RemoteWire"

/**
 * 收发的公共逻辑：加密封装、超大报文改走附件。
 *
 * 附件通道是必需的：一份完整的设置快照轻松超过 ntfy 的 4KB 上限；
 * 换到 MQTT 之后虽然单包能到 1MB，但文件传输同样需要它，
 * 而且留着这层抽象，下面换成任何中继都不用改上层。
 */
object RemoteWire {

    /**
     * 发送一个报文。
     *
     * 若加密后的信封超过当前通道的单条上限，就把信封本身作为附件上传，
     * 再发一条只带 token 的小报文——附件内容仍是密文，端到端加密不被破坏。
     */
    suspend fun send(transport: RemoteTransport, key: SecretKey, packet: RemotePacket) {
        val envelopeJson = encodeEnvelope(key, packet)
        if (envelopeJson.length <= transport.maxTextLength) {
            transport.publish(envelopeJson)
            return
        }

        val token = transport.uploadAttachment(envelopeJson.toByteArray(), "pkt.json")
        val stub = packet.copy(body = null, attach = token)
        transport.publish(encodeEnvelope(key, stub))
    }

    /**
     * 解析一条中继消息。
     *
     * @return 解析失败（比如密钥不对、是附件提示消息）时返回 null
     */
    suspend fun receive(transport: RemoteTransport, key: SecretKey, text: String): RemotePacket? {
        val packet = decodePacket(key, text) ?: return null
        if (packet.attach == null) return packet

        return runCatching {
            val raw = transport.downloadAttachment(packet.attach).decodeToString()
            decodePacket(key, raw)
        }.onFailure {
            Logger.warning(TAG, "Failed to fetch attached packet", it)
        }.getOrNull()
    }

    private fun encodeEnvelope(key: SecretKey, packet: RemotePacket): String {
        val plain = GLOBAL_JSON.encodeToString(RemotePacket.serializer(), packet)
        val envelope = RemoteCrypto.seal(key, plain.toByteArray())
        return GLOBAL_JSON.encodeToString(RemoteEnvelope.serializer(), envelope)
    }

    private fun decodePacket(key: SecretKey, text: String): RemotePacket? {
        val envelope = runCatching {
            GLOBAL_JSON.decodeFromString(RemoteEnvelope.serializer(), text)
        }.getOrNull() ?: return null

        val plain = RemoteCrypto.open(key, envelope) ?: return null
        return runCatching {
            GLOBAL_JSON.decodeFromString(RemotePacket.serializer(), plain.decodeToString())
        }.getOrNull()
    }

    fun emptyBody(): JsonObject? = null
}
