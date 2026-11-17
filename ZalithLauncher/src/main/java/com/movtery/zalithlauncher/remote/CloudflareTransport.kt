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
import kotlinx.coroutines.CompletableDeferred
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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private const val TAG = "CloudflareTransport"

private const val OPEN_TIMEOUT_MS = 15_000L
private const val BLOB_WAIT_MS = 10_000L

/**
 * 基于自建 Cloudflare Worker 的中继。
 *
 * 和 MQTT 那条路相比，这条路更"窄"但更可控：
 * - Worker 只做同房间广播，代码一百行，跑在 Cloudflare 边缘；
 * - 不依赖任何公共 broker 的可用性与限流；
 * - 免费额度对个人远控来说是几个数量级的富余。
 *
 * 代价是第一次要自己部署一次 Worker（一次性，三分钟），
 * 地址填进 App 即可，之后两端都只需要这个地址 + 6 位配对码。
 *
 * 线上格式：一条 WebSocket 连接 = 一个房间里的一个成员；
 * 二进制帧就是 [ChunkWire] 分片，房间由 `?room=` 指定。
 * 内容依旧是端到端加密的，Worker 只转发字节。
 */
class CloudflareTransport(
    private val endpoint: String,
    private val room: String,
    private val key: String = "",
    private val clientId: String = "",
) : RemoteTransport {

    override val label: String = "Cloudflare · ${RemoteTransports.hostOf(endpoint)}"

    override val maxTextLength: Int = ChunkWire.maxPayload()

    private val wsUrl: String = buildString {
        append(RemoteTransports.toWebSocketUrl(endpoint))
        append(if (endpoint.contains('?')) "&" else "?")
        append("room=").append(room)
        if (key.isNotEmpty()) {
            append("&key=").append(java.net.URLEncoder.encode(key, "UTF-8"))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inbox = Channel<RelayEvent>(Channel.BUFFERED)

    private val openSignal = CompletableDeferred<Unit>()
    private val sendMutex = Mutex()

    private val assembler = ChunkAssembler()
    private val blobs = ConcurrentHashMap<String, ByteArray>()
    private val blobWaiters = ConcurrentHashMap<String, CompletableDeferred<ByteArray>>()

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var closed = false

    private var pingJob: Job? = null

    private val client: OkHttpClient by lazy {
        createOkHttpClientBuilder { builder ->
            builder.callTimeout(0, TimeUnit.MILLISECONDS)
            builder.readTimeout(0, TimeUnit.MILLISECONDS)
            builder.writeTimeout(30, TimeUnit.SECONDS)
            // Cloudflare 侧对空闲连接有 100 秒左右的回收，25 秒一次的 ping
            // 既能让连接保持活跃，也能在半死链路上尽快暴露问题。
            builder.pingInterval(25, TimeUnit.SECONDS)
        }.build()
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
            Logger.debug(TAG, "Worker 连接已建立 room=$room client=$clientId")
            if (!openSignal.isCompleted) openSignal.complete(Unit)
            startPing()
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            onBytes(bytes.toByteArray())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            // Worker 只转发二进制；这条分支理论上不会走到，留作兜底
            onBytes(text.toByteArray(Charsets.UTF_8))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val detail = response?.let { " HTTP ${it.code}" }.orEmpty()
            fail(IllegalStateException("${t.message ?: t::class.java.simpleName}$detail", t))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            fail(IllegalStateException("Worker 连接已关闭（$code）"))
        }
    }

    override suspend fun connect() {
        if (endpoint.isBlank()) {
            throw IllegalStateException("还没有填写 Cloudflare Worker 地址")
        }

        val ws = try {
            client.newWebSocket(Request.Builder().url(wsUrl).build(), listener)
        } catch (e: Exception) {
            throw IllegalStateException("无法连接 ${RemoteTransports.hostOf(endpoint)}：${e.message}", e)
        }
        socket = ws

        val opened = try {
            withTimeoutOrNull(OPEN_TIMEOUT_MS) { openSignal.await() }
        } catch (e: Exception) {
            closeQuietly()
            throw IllegalStateException("Worker 握手失败：${e.message}", e)
        }

        if (opened == null) {
            closeQuietly()
            throw IllegalStateException(
                "连接 Cloudflare Worker 超时：${RemoteTransports.hostOf(endpoint)} " +
                    "在 ${OPEN_TIMEOUT_MS / 1000} 秒内没有回应，请确认地址可访问、Worker 已部署"
            )
        }

        // Worker 的广播是从「连接建立之后」才生效的，所以 Open 事件
        // 必须在那之后才发出去——上层收到它才会发配对握手。
        inbox.trySend(RelayEvent.Open)
        Logger.debug(TAG, "Connected $wsUrl")
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

        val waiter = blobWaiters.getOrPut(token) { CompletableDeferred() }
        val data = withTimeoutOrNull(BLOB_WAIT_MS) { waiter.await() }
        blobWaiters.remove(token)
        return data ?: throw IllegalStateException("附件未到达（$token）")
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        pingJob?.cancel()
        runCatching { socket?.close(1000, "bye") }
        socket = null
        inbox.close()
        scope.cancel()
    }

    // ---------------------------------------------------------------- 内部实现

    private fun sendRaw(bytes: ByteArray): Boolean {
        val ws = socket ?: return false
        return runCatching { ws.send(bytes.toByteString()) }.getOrDefault(false)
    }

    private suspend fun sendChunks(kind: Int, token: String, data: ByteArray) {
        val max = ChunkWire.maxPayload()
        val total = if (data.isEmpty()) 1 else (data.size + max - 1) / max
        if (total > 65535) {
            throw IllegalStateException("消息过大：${data.size} 字节，超过分片上限")
        }

        sendMutex.withLock {
            for (index in 0 until total) {
                val from = index * max
                val to = minOf(from + max, data.size)
                val chunk = ChunkWire.encode(kind, token, index, total, data, from, to)
                if (!sendRaw(chunk)) {
                    throw IllegalStateException("发送失败：Worker 连接已断开")
                }
                // 一次塞几百片会顶满发送缓冲，每隔几片让出一次
                if (index % 8 == 7) delay(8)
            }
        }
    }

    private fun onBytes(bytes: ByteArray) {
        val chunk = ChunkWire.decode(bytes) ?: return
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

    private fun startPing() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isActive && !closed) {
                delay(25_000L)
                if (!sendRaw(PING_FRAME)) break
            }
        }
    }

    private fun fail(error: Throwable) {
        if (closed) return
        if (!openSignal.isCompleted) openSignal.completeExceptionally(error)
        blobWaiters.values.forEach { it.completeExceptionally(error) }
        blobWaiters.clear()
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

/**
 * 应用层心跳。Worker 会把它当成普通帧广播给对端，对端解码失败直接忽略，
 * 只用于把这条 TCP 链路的数据双向拉动一次。
 */
private val PING_FRAME = byteArrayOf(0x50, 0x49, 0x4E, 0x47) // "PING"
