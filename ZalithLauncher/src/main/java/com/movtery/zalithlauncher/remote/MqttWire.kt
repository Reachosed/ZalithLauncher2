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

import java.io.ByteArrayOutputStream

/**
 * MQTT 3.1.1 报文的编解码。
 *
 * 只实现远控需要的这几种：CONNECT / CONNACK / SUBSCRIBE / SUBACK /
 * PUBLISH(QoS 0,1) / PUBACK / PINGREQ / PINGRESP / DISCONNECT。
 *
 * 为什么不引第三方 MQTT 库：Android 上的 Paho 要额外声明 Service、
 * 且 wss 支持还要替换 socket factory；而这里需要的报文格式其实只有
 * 百来行，自己写反而更可控，也不给项目增加依赖。
 */
internal object MqttWire {

    const val TYPE_CONNECT = 1
    const val TYPE_CONNACK = 2
    const val TYPE_PUBLISH = 3
    const val TYPE_PUBACK = 4
    const val TYPE_SUBSCRIBE = 8
    const val TYPE_SUBACK = 9
    const val TYPE_PINGREQ = 12
    const val TYPE_PINGRESP = 13
    const val TYPE_DISCONNECT = 14

    /**
     * 建立一个 MQTT 连接报文。
     *
     * @param cleanSession false 时 broker 会为该 clientId 保留会话与离线消息，
     *        这是断线重连能补收消息的前提。
     */
    fun connect(
        clientId: String,
        cleanSession: Boolean,
        keepAliveSeconds: Int,
    ): ByteArray {
        val varHeader = ByteArrayOutputStream()
        writeUtf8(varHeader, "MQTT")
        varHeader.write(4)                                  // 协议级别 3.1.1
        varHeader.write(if (cleanSession) 0x02 else 0x00)   // 连接标志：只置 cleanSession
        varHeader.write((keepAliveSeconds ushr 8) and 0xFF)
        varHeader.write(keepAliveSeconds and 0xFF)
        writeUtf8(varHeader, clientId)

        return frame(TYPE_CONNECT shl 4, varHeader.toByteArray())
    }

    fun subscribe(topic: String, packetId: Int, qos: Int = 1): ByteArray {
        val body = ByteArrayOutputStream()
        body.write((packetId ushr 8) and 0xFF)
        body.write(packetId and 0xFF)
        writeUtf8(body, topic)
        body.write(qos and 0x03)

        return frame((TYPE_SUBSCRIBE shl 4) or 0x02, body.toByteArray())
    }

    fun publish(topic: String, payload: ByteArray, packetId: Int, qos: Int): ByteArray {
        val body = ByteArrayOutputStream()
        writeUtf8(body, topic)
        if (qos > 0) {
            body.write((packetId ushr 8) and 0xFF)
            body.write(packetId and 0xFF)
        }
        body.write(payload)

        val header = (TYPE_PUBLISH shl 4) or ((qos and 0x03) shl 1)
        return frame(header, body.toByteArray())
    }

    fun pubAck(packetId: Int): ByteArray = byteArrayOf(
        0x40.toByte(), 0x02, (packetId ushr 8).toByte(), packetId.toByte()
    )

    fun pingReq(): ByteArray = byteArrayOf(0xC0.toByte(), 0x00)

    fun disconnect(): ByteArray = byteArrayOf(0xE0.toByte(), 0x00)

    /**
     * 从一段字节里解析出所有完整报文，返回**已消费的字节数**。
     *
     * WebSocket 的一帧里可能塞了多个 MQTT 报文，也可能一个报文被拆到两帧，
     * 所以必须由调用方维护缓冲区、按剩余长度切片。
     */
    fun parse(buffer: ByteArray, out: MutableList<MqttFrame>): Int {
        var offset = 0
        while (offset < buffer.size) {
            if (buffer.size - offset < 2) break

            // 剩余长度：最多 4 字节的变长整数，每字节低 7 位有效
            var remaining = 0
            var multiplier = 1
            var index = offset + 1
            var lengthBytes = 0
            var complete = false
            while (lengthBytes < 4 && index < buffer.size) {
                val b = buffer[index].toInt() and 0xFF
                remaining += (b and 0x7F) * multiplier
                multiplier *= 128
                index++
                lengthBytes++
                if (b and 0x80 == 0) {
                    complete = true
                    break
                }
            }
            if (!complete) break

            val headerSize = 1 + lengthBytes
            if (buffer.size - offset - headerSize < remaining) break

            val bodyStart = offset + headerSize
            decode(
                firstByte = buffer[offset].toInt() and 0xFF,
                body = buffer,
                bodyStart = bodyStart,
                bodyLength = remaining,
                out = out
            )
            offset = bodyStart + remaining

            // 防御性限制：单批最多解析 64 个报文，避免异常数据导致长时间占用
            if (out.size > 64) break
        }
        return offset
    }

    private fun decode(
        firstByte: Int,
        body: ByteArray,
        bodyStart: Int,
        bodyLength: Int,
        out: MutableList<MqttFrame>,
    ) {
        val type = (firstByte ushr 4) and 0x0F
        val end = bodyStart + bodyLength

        when (type) {
            TYPE_CONNACK -> {
                if (bodyLength < 2) return
                val sessionPresent = (body[bodyStart].toInt() and 0x01) != 0
                val code = body[bodyStart + 1].toInt() and 0xFF
                out += MqttFrame.ConnAck(sessionPresent, code)
            }

            TYPE_SUBACK -> {
                if (bodyLength < 2) return
                val packetId = readU16(body, bodyStart)
                val codes = ArrayList<Int>(bodyLength - 2)
                var i = bodyStart + 2
                while (i < end) {
                    codes += body[i].toInt() and 0xFF
                    i++
                }
                out += MqttFrame.SubAck(packetId, codes)
            }

            TYPE_PUBLISH -> {
                val qos = (firstByte ushr 1) and 0x03
                if (bodyLength < 2) return
                val topicLength = readU16(body, bodyStart)
                val topicStart = bodyStart + 2
                if (topicStart + topicLength > end) return
                val topic = String(body, topicStart, topicLength, Charsets.UTF_8)

                var cursor = topicStart + topicLength
                var packetId: Int? = null
                if (qos > 0) {
                    if (cursor + 2 > end) return
                    packetId = readU16(body, cursor)
                    cursor += 2
                }
                val payload = body.copyOfRange(cursor, end)
                out += MqttFrame.Publish(topic, payload, qos, packetId, (firstByte and 0x08) != 0)
            }

            TYPE_PUBACK -> {
                if (bodyLength < 2) return
                out += MqttFrame.PubAck(readU16(body, bodyStart))
            }

            TYPE_PINGRESP -> out += MqttFrame.PingResp

            else -> out += MqttFrame.Other(type)
        }
    }

    private fun frame(firstByte: Int, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(body.size + 5)
        out.write(firstByte)
        var value = body.size
        do {
            var digit = value % 128
            value /= 128
            if (value > 0) digit = digit or 0x80
            out.write(digit)
        } while (value > 0)
        out.write(body)
        return out.toByteArray()
    }

    private fun writeUtf8(out: ByteArrayOutputStream, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        out.write((bytes.size ushr 8) and 0xFF)
        out.write(bytes.size and 0xFF)
        out.write(bytes)
    }

    private fun readU16(buffer: ByteArray, offset: Int): Int =
        ((buffer[offset].toInt() and 0xFF) shl 8) or (buffer[offset + 1].toInt() and 0xFF)
}

/**
 * 解析出来的一帧 MQTT 报文
 */
internal sealed interface MqttFrame {
    data class ConnAck(val sessionPresent: Boolean, val code: Int) : MqttFrame
    data class SubAck(val packetId: Int, val codes: List<Int>) : MqttFrame
    data class Publish(
        val topic: String,
        val payload: ByteArray,
        val qos: Int,
        val packetId: Int?,
        val duplicated: Boolean,
    ) : MqttFrame

    data class PubAck(val packetId: Int) : MqttFrame
    data object PingResp : MqttFrame
    data class Other(val type: Int) : MqttFrame
}

/**
 * 分片协议：一条逻辑消息被切成多片在 MQTT 里传输。
 *
 * 单包体积必须压住——实测公共 broker 上 512KB 的一个包来回要三十秒，
 * 而且中途任何一次抖动都会让整包重传。切成 24KB 一片之后，
 * 单片的往返只要几百毫秒，失败重试的代价也小得多。
 *
 * 布局：magic(2) | version(1) | kind(1) | tokenLen(1) | token | index(2) | total(2) | data
 */
internal object ChunkWire {
    private const val MAGIC_0 = 0x5A.toByte()
    private const val MAGIC_1 = 0x4D.toByte()
    private const val VERSION = 1

    const val KIND_TEXT = 0
    const val KIND_BLOB = 1

    /** 单片最大负载。24KB 在自建 Worker 上是延迟与重传成本比较平衡的点。 */
    const val MAX_CHUNK = 24 * 1024

    /**
     * 公共 MQTT broker 上的单片大小。
     *
     * 实测 broker.emqx.io：连续快速发 24KB 的 QoS1 报文会丢，8KB 且带确认
     * 才稳定。公共 broker 有隐式的流控，不能按"能发多快就发多快"来。
     */
    const val MQTT_CHUNK = 8 * 1024

    /** 分片数据的头部开销上限（token 最多 32 字节） */
    private const val MAX_HEADER = 6 + 32 + 4

    fun maxPayload(chunkSize: Int = MAX_CHUNK): Int = chunkSize - MAX_HEADER

    fun encode(kind: Int, token: String, index: Int, total: Int, data: ByteArray, from: Int, to: Int): ByteArray {
        val tokenBytes = token.toByteArray(Charsets.UTF_8)
        val size = (to - from).coerceAtMost(data.size - from).coerceAtLeast(0)
        // 头部实际是 5 字节（magic/version/kind/tokenLen）+ token + 4 字节（index/total）
        val out = ByteArray(5 + tokenBytes.size + 4 + size)
        out[0] = MAGIC_0
        out[1] = MAGIC_1
        out[2] = VERSION.toByte()
        out[3] = kind.toByte()
        out[4] = tokenBytes.size.toByte()
        System.arraycopy(tokenBytes, 0, out, 5, tokenBytes.size)

        var p = 5 + tokenBytes.size
        out[p++] = ((index ushr 8) and 0xFF).toByte()
        out[p++] = (index and 0xFF).toByte()
        out[p++] = ((total ushr 8) and 0xFF).toByte()
        out[p] = (total and 0xFF).toByte()
        p++

        System.arraycopy(data, from, out, p, size)
        return out
    }

    /**
     * 解析一片。不是本协议的数据（例如对端还是旧版本）返回 null，
     * 调用方应当忽略而不是报错。
     */
    fun decode(payload: ByteArray): Chunk? {
        if (payload.size < 10) return null
        if (payload[0] != MAGIC_0 || payload[1] != MAGIC_1) return null
        if (payload[2].toInt() != VERSION) return null

        val kind = payload[3].toInt()
        val tokenLength = payload[4].toInt() and 0xFF
        if (tokenLength <= 0 || tokenLength > 64) return null
        if (payload.size < 5 + tokenLength + 4) return null

        val token = String(payload, 5, tokenLength, Charsets.UTF_8)
        var p = 5 + tokenLength
        val index = ((payload[p].toInt() and 0xFF) shl 8) or (payload[p + 1].toInt() and 0xFF)
        p += 2
        val total = ((payload[p].toInt() and 0xFF) shl 8) or (payload[p + 1].toInt() and 0xFF)
        p += 2

        if (total <= 0 || index < 0 || index >= total) return null

        return Chunk(kind, token, index, total, payload.copyOfRange(p, payload.size))
    }

    data class Chunk(
        val kind: Int,
        val token: String,
        val index: Int,
        val total: Int,
        val data: ByteArray,
    )
}

/**
 * 分片重组器。
 *
 * 不假设分片一定按序到达：WebSocket 上虽然通常有序，但 QoS 重投会让
 * 同一片出现两次，所以按 index 落位并做去重，最后按 total 校验完整性。
 */
internal class ChunkAssembler {

    private class Pending(val total: Int) {
        val parts = arrayOfNulls<ByteArray>(total)
        var filled = 0
        var updatedAt = System.currentTimeMillis()
    }

    private val pending = LinkedHashMap<String, Pending>()

    /**
     * @return 收齐时返回完整数据，否则 null
     */
    fun accept(chunk: ChunkWire.Chunk): Assembled? {
        evictStale()

        val slot = pending.getOrPut(chunk.token) { Pending(chunk.total) }
        if (slot.total != chunk.total) {
            // 同一 token 的分片总数对不上，说明是对端重启后的新消息，丢弃旧的
            pending.remove(chunk.token)
            return null
        }
        if (slot.parts[chunk.index] == null) {
            slot.parts[chunk.index] = chunk.data
            slot.filled++
            slot.updatedAt = System.currentTimeMillis()
        }
        if (slot.filled < slot.total) return null

        pending.remove(chunk.token)
        val joined = java.io.ByteArrayOutputStream()
        for (part in slot.parts) {
            if (part == null) return null
            joined.write(part)
        }
        return Assembled(chunk.kind, chunk.token, joined.toByteArray())
    }

    fun reset() = pending.clear()

    private fun evictStale() {
        if (pending.size < 32) return
        val now = System.currentTimeMillis()
        val iterator = pending.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.updatedAt > 60_000L) iterator.remove()
        }
    }

    class Assembled(val kind: Int, val token: String, val data: ByteArray)
}
