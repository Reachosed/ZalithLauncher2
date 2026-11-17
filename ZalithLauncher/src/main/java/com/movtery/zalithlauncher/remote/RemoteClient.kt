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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.SecretKey

private const val TAG = "RemoteClient"

/**
 * 控制端状态
 */
sealed interface RemoteClientState {
    data object Idle : RemoteClientState
    data object Connecting : RemoteClientState
    data class Online(val device: String, val version: String) : RemoteClientState
    data class Offline(val reason: String) : RemoteClientState
}

/**
 * 控制端会话。
 *
 * 与原版 UI 的关系：这是一个纯粹的"客户端"，不镜像被控端的界面，
 * 而是通过命令字读写对方的设置与文件。这样带宽只有几 KB，
 * 不需要传画面，也就满足"不启动游戏、不传画面"的前提。
 */
class RemoteClient(
    code: String,
    relayUrl: String,
    private val localDeviceName: String,
    relayKind: String = RemoteTransports.KIND_CF,
) {
    private val instanceId = RemoteCrypto.newInstanceId()
    private val topic = RemoteCrypto.topicOf(code)
    private val key: SecretKey = RemoteCrypto.deriveKey(code, topic)

    private val kind: String = RemoteTransports.normalizeKind(relayKind)

    /**
     * 中继地址兜底。
     *
     * 设置项可能被用户清空（输入框里删一下就会存成空串），
     * 空地址会拼出 `/topic` 这种相对路径，请求必然失败，
     * 而失败原因会被笼统地显示成"无响应"。
     */
    private val effectiveRelayUrl: String = RemoteTransports.normalizeUrl(kind, relayUrl)

    private val transport: RemoteTransport = RemoteTransports.create(
        kind = kind,
        url = effectiveRelayUrl,
        topic = topic,
        role = RemoteTransports.ROLE_CLIENT,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<RemotePacket>>()
    private val counter = AtomicLong(0)

    private val _state = MutableStateFlow<RemoteClientState>(RemoteClientState.Idle)
    val state: StateFlow<RemoteClientState> = _state.asStateFlow()

    private var collectorJob: Job? = null
    private var heartbeatJob: Job? = null
    private var lastMessageId: String? = null
    private var closed = false

    /** 最近一次发送失败的原因，用于把"网络不通"和"对端没应答"区分开 */
    @Volatile
    private var lastSendError: String? = null

    /** 中继地址里真正的主机名，仅用于报错文案 */
    val relayHost: String = RemoteTransports.hostOf(effectiveRelayUrl)

    /** 当前通道描述，界面上展示用 */
    val transportLabel: String = transport.label

    /**
     * 连接：传输层的 [RemoteTransport.connect] 返回时订阅已经真正生效，
     * 所以这里可以放心地紧接着发握手——不会出现应答落在订阅之前的情况。
     */
    suspend fun connect(timeoutMs: Long = 20_000L): Result<JsonObject> {
        _state.value = RemoteClientState.Connecting

        try {
            transport.connect()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = "中继连接失败（$relayHost）：${e.message ?: e::class.java.simpleName}"
            _state.value = RemoteClientState.Offline(reason)
            return Result.failure(e)
        }

        startCollector()

        val hello = buildJsonObject {
            put("device", localDeviceName)
            put("platform", "android")
        }

        val response = withTimeoutOrNull(timeoutMs) {
            request(RemoteCmd.HELLO, hello, timeoutMs)
        }

        return if (response == null) {
            val sendError = lastSendError
            val reason = if (sendError != null) {
                "中继可达，但发送失败：$sendError"
            } else {
                "中继可达，被控端无应答。请确认：① 被控端已开启且未被系统杀掉；" +
                        "② 配对码是它当前显示的那个；③ 两台设备选的中继类型和地址一致。"
            }
            _state.value = RemoteClientState.Offline(reason)
            Result.failure(IllegalStateException(reason))
        } else if (!response.ok) {
            val reason = response.err ?: "被控端拒绝连接"
            _state.value = RemoteClientState.Offline(reason)
            Result.failure(IllegalStateException(reason))
        } else {
            val body = response.body ?: buildJsonObject { }
            val device = (body["device"] as? JsonPrimitive)?.contentOrNull ?: "未知设备"
            val version = (body["version"] as? JsonPrimitive)?.contentOrNull ?: ""
            _state.value = RemoteClientState.Online(device, version)
            startHeartbeat()
            Result.success(body)
        }
    }

    /**
     * 发一条请求并等待结果
     */
    suspend fun request(
        cmd: String,
        body: JsonObject? = null,
        timeoutMs: Long = 20_000L,
    ): RemotePacket? {
        val id = "${instanceId}-${counter.incrementAndGet()}"
        val deferred = CompletableDeferred<RemotePacket>()
        pending[id] = deferred

        return try {
            val packet = RemotePacket(
                id = id,
                type = RemotePacket.TYPE_REQ,
                cmd = cmd,
                from = instanceId,
                ts = System.currentTimeMillis(),
                body = body,
            )
            RemoteWire.send(transport, key, packet)
            lastSendError = null
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastSendError = e.message ?: e::class.java.simpleName
            Logger.warning(TAG, "request $cmd failed: ${e.message}")
            null
        } finally {
            pending.remove(id)
        }
    }

    /**
     * 从被控端拉取文件（上限由被控端设置决定）
     */
    suspend fun pullFile(path: String): Result<ByteArray> {
        val body = buildJsonObject { put("path", path) }
        val response = request(RemoteCmd.FS_PULL, body, 60_000L)
            ?: return Result.failure(IllegalStateException("被控端无响应"))
        if (!response.ok) {
            return Result.failure(IllegalStateException(response.err ?: "读取失败"))
        }
        val token = (response.body?.get("attach") as? JsonPrimitive)?.contentOrNull
            ?: return Result.failure(IllegalStateException("被控端未返回文件"))
        return runCatching {
            val sealed = transport.downloadAttachment(token)
            RemoteCrypto.openBytes(key, sealed)
                ?: throw IllegalStateException("解密失败")
        }
    }

    /**
     * 向被控端推送文件
     */
    suspend fun pushFile(path: String, bytes: ByteArray): Result<Int> {
        return runCatching {
            val sealed = RemoteCrypto.sealBytes(key, bytes)
            val token = transport.uploadAttachment(sealed, "push.bin")
            val body = buildJsonObject {
                put("path", path)
                put("attach", token)
                put("size", bytes.size)
            }
            val response = request(RemoteCmd.FS_PUSH, body, 120_000L)
                ?: throw IllegalStateException("被控端无响应")
            if (!response.ok) throw IllegalStateException(response.err ?: "写入失败")
            bytes.size
        }
    }

    /**
     * 拉取整包配置快照
     */
    suspend fun exportConfig(): Result<JsonObject> {
        val response = request(RemoteCmd.CONFIG_EXPORT, null, 60_000L)
            ?: return Result.failure(IllegalStateException("被控端无响应"))
        if (!response.ok) return Result.failure(IllegalStateException(response.err ?: "导出失败"))
        val payload = response.body?.get("payload") as? JsonObject
            ?: return Result.failure(IllegalStateException("快照格式异常"))
        return Result.success(payload)
    }

    /**
     * 覆盖导入整包配置
     */
    suspend fun importConfig(snapshot: JsonObject): Result<Int> {
        val body = buildJsonObject { put("payload", snapshot) }
        val response = request(RemoteCmd.CONFIG_IMPORT, body, 120_000L)
            ?: return Result.failure(IllegalStateException("被控端无响应"))
        if (!response.ok) return Result.failure(IllegalStateException(response.err ?: "导入失败"))
        val count = (response.body?.get("count") as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
        return Result.success(count)
    }

    fun close() {
        if (closed) return
        closed = true
        collectorJob?.cancel()
        heartbeatJob?.cancel()
        // 尽力通知对端"我走了"，用独立作用域发，避免被紧接着的 cancel() 掐断
        runCatching {
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { request(RemoteCmd.SESSION_CLOSE, null, 3_000L) }
            }
        }
        scope.cancel()
        _state.value = RemoteClientState.Idle
    }

    private fun startCollector() {
        if (collectorJob?.isActive == true) return
        collectorJob = scope.launch {
            try {
                transport.events().collect { event ->
                    if (event is RelayEvent.Message) {
                        lastMessageId = event.id
                        onMessage(event.text)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.warning(TAG, "collector stopped: ${e.message}")
            }
            if (!closed && _state.value is RemoteClientState.Online) {
                _state.value = RemoteClientState.Offline("与中继的连接已断开")
            }
        }
    }

    private suspend fun onMessage(text: String) {
        val packet = RemoteWire.receive(transport, key, text) ?: return
        if (packet.from == instanceId) return
        // 只认本会话的应答：应答的 id 一定由本实例的 instanceId 打头。
        // 这样既排除了中继上残留的旧消息，又完全不依赖两台设备的系统时间。
        if (!packet.id.startsWith("$instanceId-")) return
        if (packet.type != RemotePacket.TYPE_RES) return
        pending[packet.id]?.complete(packet)
    }

    private fun startHeartbeat() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = scope.launch {
            var failures = 0
            while (isActive && !closed) {
                delay(25_000L)
                val response = request(RemoteCmd.PING, null, 10_000L)
                if (response == null) {
                    failures++
                    if (failures >= 2 && _state.value is RemoteClientState.Online) {
                        _state.value = RemoteClientState.Offline("与被控端失联")
                    }
                } else {
                    failures = 0
                }
            }
        }
    }

    companion object {
        fun encode(snapshot: JsonObject): String =
            GLOBAL_JSON.encodeToString(JsonObject.serializer(), snapshot)
    }
}
