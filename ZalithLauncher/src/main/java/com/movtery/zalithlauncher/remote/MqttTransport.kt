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

import com.movtery.zalithlauncher.path.createOkHttpClientBuilder
import com.movtery.zalithlauncher.utils.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CompletableDeferred
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "MqttTransport"

private const val CONNACK_TIMEOUT_MS = 15_000L
private const val SUBACK_TIMEOUT_MS = 10_000L
private const val BLOB_WAIT_MS = 10_000L

/** 单片等待 PUBACK 的上限 */
private const val ACK_TIMEOUT_MS = 6_000L

/** 不回执时的固定片间间隔，实测 250ms 在公共 broker 上不丢 */
private const val CHUNK_PACE_MS = 250L

/**
 * 基于 MQTT over WebSocket 的免费中继。
 *
 * 为什么换掉 ntfy：
 * - ntfy 公共实例单条消息只有 4KB，稍大一点就会被**静默**转成附件，
 *   协议直接断掉；MQTT 单包上限是 1MB，分片后完全够用。
 * - ntfy 的流式订阅在部分运营商网络上会被代理拦掉（表现为能发不能收）。
 * - EMQX 公共 broker 有国内节点，且匿名可用，不需要注册。
 *
 * 为什么不用 Paho：Android 上的 Paho 要额外声明 Service，wss 还要替换
 * socket factory；而这里需要的报文只有几种，用项目已有的 OkHttp
 * WebSocket 直接写反而更稳，也不新增依赖。
 */
class MqttTransport(
    private val wsUrl: String,
    private val topic: String,
    private val clientId: String,
    private val persistentSession: Boolean,
    private val keepAliveSeconds: Int = 45,
) : RemoteTransport {

    override val label: String = "MQTT · ${RemoteTransports.hostOf(wsUrl)}"

    override val maxTextLength: Int = ChunkWire.maxPayload(ChunkWire.MQTT_CHUNK)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inbox = Channel<RelayEvent>(Channel.BUFFERED)

    private val connAck = CompletableDeferred<Int>()
    private val subAck = CompletableDeferred<Boolean>()
    private val sendMutex = Mutex()

    private val assembler = ChunkAssembler()
    private val blobs = ConcurrentHashMap<String, ByteArray>()
    private val blobWaiters = ConcurrentHashMap<String, CompletableDeferred<ByteArray>>()

    private val packetCounter = AtomicInteger(1)
    private val pendingAcks = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()

    /**
     * broker 是否会对我们的 QoS1 发布回 PUBACK。
     *
     * 公共 broker 偶尔不回执。一旦发现不回，就退回固定节流，
     * 否则整条消息会被一片一片的 6 秒超时拖死。
     */
    @Volatile
    private var ackSupported = true
    private val bufferLock = Any()
    private var buffer = ByteArray(0)

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var closed = false

    private var pingJob: Job? = null

    private val client: OkHttpClient by lazy {
        createOkHttpClientBuilder { builder ->
            // 长连接不能有任何整体超时，否则挂在那里等消息时会被当成失败掐断
            builder.callTimeout(0, TimeUnit.MILLISECONDS)
            builder.readTimeout(0, TimeUnit.MILLISECONDS)
            builder.writeTimeout(30, TimeUnit.SECONDS)
            // 让 OkHttp 自己发 WebSocket ping：链路半死时能尽快发现，而不是
            // 等 MQTT 心跳超时（那要 1.5 倍 keepAlive，接近一分钟）
            builder.pingInterval(25, TimeUnit.SECONDS)
        }.build()
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val connect = MqttWire.connect(
                clientId = clientId,
                // cleanSession 与「持久会话」相反：持久会话要求 cleanSession=false
                cleanSession = !persistentSession,
                keepAliveSeconds = keepAliveSeconds,
            )
            webSocket.send(connect.toByteString())
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            onBytes(bytes.toByteArray())
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val detail = response?.let { " HTTP ${it.code}" }.orEmpty()
            fail(IllegalStateException("${t.message ?: t::class.java.simpleName}$detail", t))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            fail(IllegalStateException("中继连接已关闭（$code）"))
        }
    }

    override suspend fun connect() {
        val request = Request.Builder()
            .url(wsUrl)
            .header("Sec-WebSocket-Protocol", "mqtt")
            .build()

        val ws = try {
            client.newWebSocket(request, listener)
        } catch (e: Exception) {
            throw IllegalStateException("无法连接中继 $wsUrl：${e.message}", e)
        }
        socket = ws

        val code = try {
            withTimeoutOrNull(CONNACK_TIMEOUT_MS) { connAck.await() }
        } catch (e: Exception) {
            closeQuietly()
            throw IllegalStateException("中继握手失败：${e.message}", e)
        }

        if (code == null) {
            closeQuietly()
            throw IllegalStateException("中继握手超时：$wsUrl 在 ${CONNACK_TIMEOUT_MS / 1000} 秒内没有回应")
        }
        if (code != 0) {
            closeQuietly()
            throw IllegalStateException("中继拒绝连接：CONNACK code=$code")
        }

        sendRaw(MqttWire.subscribe(topic, nextPacketId(), 1))

        val subscribed = try {
            withTimeoutOrNull(SUBACK_TIMEOUT_MS) { subAck.await() }
        } catch (e: Exception) {
            closeQuietly()
            throw IllegalStateException("订阅主题失败：${e.message}", e)
        }

        if (subscribed == null) {
            closeQuietly()
            throw IllegalStateException("订阅主题超时")
        }
        if (!subscribed) {
            closeQuietly()
            throw IllegalStateException("中继拒绝订阅（topic=$topic）")
        }

        startPing()
        Logger.debug(TAG, "Connected to $wsUrl, topic=$topic, persistent=$persistentSession")
    }

    override fun events(): Flow<RelayEvent> = inbox.receiveAsFlow()

    override suspend fun publish(text: String) {
        sendChunks(
            kind = ChunkWire.KIND_TEXT,
            token = "t" + RemoteCrypto.newInstanceId(),
            data = text.toByteArray(Charsets.UTF_8),
        )
    }

    override suspend fun uploadAttachment(bytes: ByteArray, fileName: String): String {
        val token = "b" + RemoteCrypto.newInstanceId()
        sendChunks(kind = ChunkWire.KIND_BLOB, token = token, data = bytes)
        return token
    }

    override suspend fun downloadAttachment(token: String): ByteArray {
        blobs.remove(token)?.let { return it }

        // 分片是逐片到达的，对端发出「附件已就绪」的报文时，
        // 最后几片可能还在路上。这里短暂等一会儿，不要立刻判失败。
        val waiter = blobWaiters.getOrPut(token) { CompletableDeferred() }
        val data = withTimeoutOrNull(BLOB_WAIT_MS) { waiter.await() }
        blobWaiters.remove(token)
        return data ?: throw IllegalStateException("附件未到达（$token）")
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        pingJob?.cancel()
        runCatching { sendRaw(MqttWire.disconnect()) }
        runCatching { socket?.close(1000, "bye") }
        socket = null
        inbox.close()
        scope.cancel()
    }

    // ---------------------------------------------------------------- 内部实现

    private fun nextPacketId(): Int = packetCounter.updateAndGet { current ->
        if (current >= 65535) 1 else current + 1
    }

    private fun sendRaw(bytes: ByteArray): Boolean {
        val ws = socket ?: return false
        return runCatching { ws.send(bytes.toByteString()) }.getOrDefault(false)
    }

    private fun sendQuietly(bytes: ByteArray) {
        sendRaw(bytes)
    }

    private suspend fun sendChunks(kind: Int, token: String, data: ByteArray) {
        val max = ChunkWire.maxPayload(ChunkWire.MQTT_CHUNK)
        val total = if (data.isEmpty()) 1 else (data.size + max - 1) / max
        if (total > 65535) {
            throw IllegalStateException("消息过大：${data.size} 字节，超过分片上限")
        }

        sendMutex.withLock {
            for (index in 0 until total) {
                val from = index * max
                val to = minOf(from + max, data.size)
                val chunk = ChunkWire.encode(kind, token, index, total, data, from, to)

                val packetId = nextPacketId()
                val ack = CompletableDeferred<Unit>()
                if (ackSupported) pendingAcks[packetId] = ack

                if (!sendRaw(MqttWire.publish(topic, chunk, packetId, 1))) {
                    pendingAcks.remove(packetId)
                    throw IllegalStateException("发送失败：中继连接已断开")
                }

                // 一片一片等确认。公共 broker 有隐式流控，连着灌会丢后面的片，
                // 而且丢了不会报错，表现就是"消息永远收不全"。
                if (ackSupported) {
                    val acked = runCatching {
                        withTimeoutOrNull(ACK_TIMEOUT_MS) { ack.await() }
                    }.getOrNull()
                    pendingAcks.remove(packetId)
                    if (acked == null) {
                        // 不回执的 broker：退回固定节流，宁可慢也不丢
                        ackSupported = false
                        delay(CHUNK_PACE_MS)
                    }
                } else {
                    delay(CHUNK_PACE_MS)
                }
            }
        }
    }

    private fun onBytes(chunk: ByteArray) {
        val frames = synchronized(bufferLock) {
            buffer += chunk
            val out = ArrayList<MqttFrame>()
            val consumed = MqttWire.parse(buffer, out)
            if (consumed > 0) {
                buffer = buffer.copyOfRange(consumed, buffer.size)
            }
            // 防御：异常数据把缓冲区撑爆（正常报文不会超过几十 KB）
            if (buffer.size > 4 * 1024 * 1024) buffer = ByteArray(0)
            out
        }
        for (frame in frames) {
            runCatching { handleFrame(frame) }
                .onFailure { Logger.warning(TAG, "handleFrame failed: ${it.message}") }
        }
    }

    private fun handleFrame(frame: MqttFrame) {
        when (frame) {
            is MqttFrame.ConnAck -> connAck.complete(frame.code)

            is MqttFrame.SubAck -> {
                val accepted = frame.codes.isNotEmpty() && frame.codes[0] != 0x80
                subAck.complete(accepted)
            }

            is MqttFrame.Publish -> {
                // QoS 1 必须回 PUBACK，否则 broker 会一直重投
                frame.packetId?.let { sendQuietly(MqttWire.pubAck(it)) }

                val chunk = ChunkWire.decode(frame.payload) ?: return
                val assembled = assembler.accept(chunk) ?: return

                when (assembled.kind) {
                    ChunkWire.KIND_TEXT -> {
                        inbox.trySend(
                            RelayEvent.Message(
                                id = assembled.token,
                                text = String(assembled.data, Charsets.UTF_8),
                            )
                        )
                    }

                    ChunkWire.KIND_BLOB -> {
                        blobs[assembled.token] = assembled.data
                        blobWaiters.remove(assembled.token)?.complete(assembled.data)
                    }
                }
            }

            is MqttFrame.PubAck -> pendingAcks.remove(frame.packetId)?.complete(Unit)
            MqttFrame.PingResp -> Unit
            is MqttFrame.Other -> Unit
        }
    }

    private fun startPing() {
        pingJob?.cancel()
        pingJob = scope.launch {
            val interval = (keepAliveSeconds * 1000L / 3).coerceAtLeast(5_000L)
            while (isActive && !closed) {
                delay(interval)
                if (!sendRaw(MqttWire.pingReq())) break
            }
        }
    }

    private fun fail(error: Throwable) {
        if (closed) return
        if (!connAck.isCompleted) connAck.completeExceptionally(error)
        if (!subAck.isCompleted) subAck.completeExceptionally(error)
        blobWaiters.values.forEach { it.completeExceptionally(error) }
        blobWaiters.clear()
        // 关闭事件通道：订阅方的 collect 会抛异常结束，由上层触发重连
        inbox.close(error)
    }

    private fun closeQuietly() {
        closed = true
        pingJob?.cancel()
        runCatching { socket?.close(1000, null) }
        socket = null
        inbox.close()
    }
}
