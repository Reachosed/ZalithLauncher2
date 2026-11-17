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

import com.movtery.zalithlauncher.path.GLOBAL_CLIENT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType

/**
 * 链路自检。
 *
 * 存在的理由：控制端报"无响应"时，用户没法判断是网络到不了中继、
 * 中继限流、还是配对码不对。这里把整条链路拆成几段分别验证，
 * 每一段的成败都能直接指向一类原因。
 */
object RemoteDiagnostics {

    suspend fun run(kind: String, url: String): List<String> {
        val normalized = RemoteTransports.normalizeUrl(kind, url)
        return when (RemoteTransports.normalizeKind(kind)) {
            RemoteTransports.KIND_CF -> cloudflare(normalized)
            RemoteTransports.KIND_NTFY -> ntfy(normalized)
            else -> mqtt(normalized)
        }
    }

    /**
     * Cloudflare Worker 自检。
     *
     * 必须开两条连接：Worker 只把消息转给「同房间的其他成员」，
     * 单条连接发出去是收不回来的，那样测不出真实情况。
     */
    private suspend fun cloudflare(endpoint: String): List<String> = withContext(Dispatchers.IO) {
        val lines = mutableListOf<String>()
        val host = RemoteTransports.hostOf(endpoint)

        if (endpoint.isBlank()) {
            lines += "① 还没有填写 Worker 地址 —— 请先部署 cloudflare/ 目录里的 Worker，把地址填进来"
            return@withContext lines
        }

        val room = "zc-probe-" + RemoteCrypto.newInstanceId()
        val a = CloudflareTransport(endpoint = endpoint, room = room, key = RemoteTransports.keyOf(endpoint), clientId = "diag-a")
        val b = CloudflareTransport(endpoint = endpoint, room = room, key = RemoteTransports.keyOf(endpoint), clientId = "diag-b")

        try {
            a.connect()
            lines += "① 连接成功：能连上 $host 并建立 WebSocket"
        } catch (e: Exception) {
            lines += "① 连接 $host 失败：${e.message ?: e::class.java.simpleName}"
            lines += "   可能原因：Worker 没部署、地址填错、或这台设备的网络访问不了 workers.dev。"
            runCatching { a.close() }
            return@withContext lines
        }

        try {
            b.connect()
            lines += "② 房间可用：第二条连接成功加入同一房间"
        } catch (e: Exception) {
            lines += "② 第二条连接失败：${e.message ?: e::class.java.simpleName}（Durable Object 可能没绑定）"
            runCatching { a.close() }
            return@withContext lines
        }

        val token = "probe-" + RemoteCrypto.newInstanceId()
        val received = withTimeoutOrNull(15_000L) {
            try {
                a.publish(token)
            } catch (e: Exception) {
                return@withTimeoutOrNull null
            }
            b.events().first { it is RelayEvent.Message }
        }

        lines += if (received is RelayEvent.Message && received.text == token) {
            "③ 转发成功：A 发出、B 原样收到 —— 通道双向可用"
        } else {
            "③ 转发失败：两条连接都在，但消息没到对端 —— Worker 的 Durable Object 绑定可能没生效"
        }

        runCatching { a.close() }
        runCatching { b.close() }
        lines
    }

    private suspend fun mqtt(url: String): List<String> = withContext(Dispatchers.IO) {
        val lines = mutableListOf<String>()
        val host = RemoteTransports.hostOf(url)
        val topic = "zc-probe-" + RemoteCrypto.newInstanceId()
        val transport = MqttTransport(
            wsUrl = url,
            topic = topic,
            clientId = "zl2diag-" + RemoteCrypto.newInstanceId().take(8),
            persistentSession = false,
        )

        // ① WebSocket 握手 + MQTT CONNECT
        try {
            transport.connect()
        } catch (e: Exception) {
            val message = e.message ?: e::class.java.simpleName
            lines += "① 连接 $host 失败：$message"
            lines += "   可能原因：这台设备的网络到不了该地址（换个网络试试），或该 broker 的 wss 端口被拦。"
            runCatching { transport.close() }
            return@withContext lines
        }
        lines += "① 握手成功：能连上 $host 并完成 MQTT 握手"
        lines += "② 订阅成功：主题已被受理"

        // ③ 发布一条消息，看能不能从订阅里收回来
        val token = "probe-" + RemoteCrypto.newInstanceId()
        val received = withTimeoutOrNull(12_000L) {
            try {
                transport.publish(token)
            } catch (e: Exception) {
                return@withTimeoutOrNull null
            }
            transport.events().first { it is RelayEvent.Message }
        }

        if (received != null) {
            val text = (received as RelayEvent.Message).text
            if (text == token) {
                lines += "③ 收发成功：消息发出后从订阅原样收回 —— 通道双向可用"
            } else {
                lines += "③ 收到消息但内容不符（可能是同主题的其他设备） —— 通道可用"
            }
        } else {
            lines += "③ 收发失败：能发不能收。可能是 broker 对该主题做了限制，或这台设备的上行被拦。"
        }

        runCatching { transport.close() }
        lines
    }

    private suspend fun ntfy(baseUrl: String): List<String> = withContext(Dispatchers.IO) {
        val base = baseUrl.trimEnd('/')
        val lines = mutableListOf<String>()

        // ① 这台设备能不能访问中继
        val reach = runCatching { GLOBAL_CLIENT.get("$base/v1/health") }
        lines += if (reach.isSuccess) {
            "① 中继可达：HTTP ${reach.getOrThrow().status.value}"
        } else {
            val e = reach.exceptionOrNull()
            "① 中继不可达：${e?.message ?: e?.let { it::class.java.simpleName }} —— 这台设备的网络访问不了 $base"
        }
        if (reach.isFailure) return@withContext lines

        // ② 能不能往中继发消息
        val probeTopic = "zc-probe-" + RemoteCrypto.newInstanceId()
        val token = RemoteCrypto.newInstanceId()
        val pub = runCatching {
            GLOBAL_CLIENT.post("$base/$probeTopic") {
                contentType(ContentType.Text.Plain)
                setBody(token)
            }
        }
        val pubCode = pub.getOrNull()?.status?.value
        if (pub.isFailure || pubCode !in 200..299) {
            val e = pub.exceptionOrNull()
            lines += "② 发布消息：失败（${e?.message ?: pubCode}）"
            return@withContext lines
        }
        lines += "② 发布消息：成功"

        // ③ 发出去的消息能不能订阅回来
        val back = runCatching {
            val resp = GLOBAL_CLIENT.get("$base/$probeTopic/json") {
                parameter("poll", "1")
                parameter("since", "all")
            }
            resp.bodyAsText().contains(token)
        }.getOrElse { false }

        lines += if (back) {
            "③ 订阅回读：成功 —— 通道双向可用"
        } else {
            "③ 订阅回读：失败 —— 能发不能收，通常是中继限流，或运营商/代理拦截了流式响应"
        }
        lines
    }
}
