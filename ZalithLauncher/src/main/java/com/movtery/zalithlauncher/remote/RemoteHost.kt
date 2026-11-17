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

import com.movtery.zalithlauncher.utils.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import javax.crypto.SecretKey

private const val TAG = "RemoteHost"

/**
 * 被控端状态
 */
sealed interface RemoteHostState {
    /** 未启动 */
    data object Stopped : RemoteHostState

    /** 已上线，等待控制端用配对码接入 */
    data class Waiting(val code: String) : RemoteHostState

    /** 会话进行中 */
    data class Connected(val code: String, val peer: String, val since: Long) : RemoteHostState
}

/**
 * 被控端的链路统计。
 *
 * 存在的理由：控制端报"无响应"时，用户需要知道被控端到底有没有收到包。
 * 收到过（received > 0）= 链路是通的，问题在配对上；
 * 一次都没收到 = 被控端根本没连上中继，或者两端中继地址不同。
 */
data class RemoteHostStats(
    /** 累计收到的请求数 */
    val received: Int = 0,
    /** 最近一次收到请求的时间戳 */
    val lastAt: Long = 0L,
    /** 中继连接当前是否正常；不正常时是失败原因 */
    val relayError: String? = null,
    /** 当前通道的描述，例如 "MQTT · broker.emqx.io" */
    val transport: String = "",
)

/**
 * 被控端引擎。
 *
 * 生命周期与 [RemoteHostService] 绑定：服务在前台常驻，这里维持一条到中继的长连接，
 * 收到命令后交给 [RemoteCommands] 执行，再把结果发回去。
 */
object RemoteHost {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var loopJob: Job? = null

    private val _state = MutableStateFlow<RemoteHostState>(RemoteHostState.Stopped)
    val state: StateFlow<RemoteHostState> = _state.asStateFlow()

    private val _stats = MutableStateFlow(RemoteHostStats())
    val stats: StateFlow<RemoteHostStats> = _stats.asStateFlow()

    /** 本实例标识，用于忽略自己发出去又被中继推回来的报文 */
    val instanceId: String = RemoteCrypto.newInstanceId()

    @Volatile
    private var code: String = ""

    @Volatile
    private var lastMessageId: String? = null

    /** 已处理过的报文 id，防止中继重投导致重复执行 */
    private val seenIds = LinkedHashSet<String>()

    private val handlerMutex = Mutex()

    /**
     * 启动引擎（幂等）
     */
    fun start() {
        if (loopJob?.isActive == true) return
        if (code.isBlank()) code = RemoteCrypto.newPairCode()
        _state.value = RemoteHostState.Waiting(code)
        loopJob = scope.launch { runLoop() }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        lastMessageId = null
        _state.value = RemoteHostState.Stopped
    }

    fun isRunning(): Boolean = loopJob?.isActive == true

    /**
     * 当前配对码；未启动时也返回一个码，便于界面直接展示
     */
    fun currentCode(): String {
        if (code.isBlank()) code = RemoteCrypto.newPairCode()
        return code
    }

    /**
     * 重新生成配对码。
     *
     * 必须重建订阅：旧的长连接还挂在旧码对应的主题上，
     * 只改字段而不重连的话，界面显示的是新码、实际在听的是旧主题，
     * 控制端输入新码永远收不到回应。
     */
    fun rotateCode(): String {
        code = RemoteCrypto.newPairCode()
        lastMessageId = null
        if (isRunning()) {
            loopJob?.cancel()
            loopJob = scope.launch { runLoop() }
        } else {
            _state.value = RemoteHostState.Waiting(code)
        }
        return code
    }

    private fun relayKind(): String =
        RemoteTransports.normalizeKind(RemoteSettings.remoteRelayKind.getValue())

    private fun relayUrl(): String =
        RemoteTransports.normalizeUrl(relayKind(), RemoteSettings.remoteRelayUrl.getValue())

    private suspend fun runLoop() {
        var backoff = 1_000L
        while (currentCoroutineContext().isActive) {
            val currentCode = currentCode()
            val topic = RemoteCrypto.topicOf(currentCode)
            val key = RemoteCrypto.deriveKey(currentCode, topic)
            val kind = relayKind()
            val url = relayUrl()

            if (_state.value !is RemoteHostState.Connected) {
                _state.value = RemoteHostState.Waiting(currentCode)
            }

            val transport = RemoteTransports.create(
                kind = kind,
                url = url,
                topic = topic,
                role = RemoteTransports.ROLE_HOST,
                since = lastMessageId,
            )

            try {
                // 先连接（内部会等到订阅真正生效），再收事件
                transport.connect()
                _stats.value = _stats.value.copy(relayError = null, transport = transport.label)
                backoff = 1_000L

                transport.events().collect { event ->
                    when (event) {
                        is RelayEvent.Message -> {
                            lastMessageId = event.id
                            handle(transport, key, event.text)
                        }
                        RelayEvent.Open -> Unit
                        RelayEvent.KeepAlive -> Unit
                    }
                }
                Logger.debug(TAG, "Relay stream closed, reconnecting")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = e.message ?: e::class.java.simpleName
                _stats.value = _stats.value.copy(relayError = msg)
                Logger.warning(TAG, "Relay error: $msg")
            } finally {
                runCatching { transport.close() }
            }

            if (_state.value is RemoteHostState.Connected) {
                _state.value = RemoteHostState.Waiting(currentCode)
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(30_000L)
        }
    }

    private suspend fun handle(transport: RemoteTransport, key: SecretKey, text: String) {
        val packet = RemoteWire.receive(transport, key, text) ?: return
        if (packet.from == instanceId) return
        if (packet.type != RemotePacket.TYPE_REQ) return
        if (!markSeen(packet.id)) return

        _stats.value = _stats.value.copy(
            received = _stats.value.received + 1,
            lastAt = System.currentTimeMillis(),
        )

        handlerMutex.withLock {
            val result = RemoteCommands.execute(packet.cmd, packet.body, transport, key)

            val response = RemotePacket(
                id = packet.id,
                type = RemotePacket.TYPE_RES,
                cmd = packet.cmd,
                from = instanceId,
                ts = System.currentTimeMillis(),
                ok = result.ok,
                err = result.err,
                body = result.body,
            )
            RemoteWire.send(transport, key, response)

            if (packet.cmd == RemoteCmd.HELLO && result.ok) {
                val peer = (packet.body?.get("device") as? JsonPrimitive)?.contentOrNull
                    ?: "未知设备"
                _state.value = RemoteHostState.Connected(currentCode(), peer, System.currentTimeMillis())
            }

            if (packet.cmd == RemoteCmd.SESSION_CLOSE) {
                _state.value = RemoteHostState.Waiting(currentCode())
            }
        }
    }

    private fun markSeen(id: String): Boolean = synchronized(seenIds) {
        if (!seenIds.add(id)) return false
        if (seenIds.size > 512) {
            val iterator = seenIds.iterator()
            repeat(256) {
                if (iterator.hasNext()) {
                    iterator.next()
                    iterator.remove()
                }
            }
        }
        true
    }
}
