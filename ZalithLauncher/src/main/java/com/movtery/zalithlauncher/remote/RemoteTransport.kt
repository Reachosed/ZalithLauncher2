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

import kotlinx.coroutines.flow.Flow

/**
 * 中继下发的事件
 */
sealed interface RelayEvent {
    /** 一条完整的逻辑消息（分片已在传输层重组完毕） */
    data class Message(val id: String, val text: String) : RelayEvent

    /**
     * 订阅已建立。
     *
     * 这个事件很关键：只有收到它，才说明订阅真正生效了。
     * 在此之前发出的报文，订阅方是收不到的（流默认只推"订阅之后"的消息）。
     */
    data object Open : RelayEvent

    /** 心跳，仅用于保活，无需处理 */
    data object KeepAlive : RelayEvent
}

/**
 * 中继传输层。
 *
 * 上层（[RemoteHost] / [RemoteClient]）只认这一层，不关心底下是
 * MQTT 还是 HTTP。换中继时只需要换一个实现。
 *
 * 约定：文本消息走 [publish]，二进制（文件、整包配置）走
 * [uploadAttachment] + 在报文里带上返回的 token，再由对端
 * [downloadAttachment] 取回。这样两种中继都能用同一套上层逻辑。
 */
interface RemoteTransport {

    /** 界面上展示的通道描述，例如 "MQTT · broker.emqx.io" */
    val label: String

    /**
     * 单条文本报文的安全长度（字符）。
     *
     * 超过这个长度就不能再走 [publish]，必须换成
     * [uploadAttachment] + 带 token 的小报文。
     * MQTT 下这个值是分片后的单包余量，ntfy 下是 4KB 限制的余量。
     */
    val maxTextLength: Int

    /**
     * 建立连接并完成订阅。
     *
     * **返回时订阅必须已经真正生效**——这是一条硬约定。
     * 如果订阅还没生效就发握手报文，对端的应答会落在订阅之前，
     * 控制端永远等不到，表现就是"配对码无响应"。
     */
    suspend fun connect()

    /** 订阅到的消息流。连接断开时流结束，由上层决定何时重连。 */
    fun events(): Flow<RelayEvent>

    /** 发送一条已加密的文本报文 */
    suspend fun publish(text: String)

    /**
     * 发一段二进制内容，返回对端取回它所需的 token。
     *
     * MQTT 下是分片直传（token 为随机串），ntfy 下是附件上传（token 为 URL）。
     */
    suspend fun uploadAttachment(bytes: ByteArray, fileName: String): String

    /** 按 token 取回二进制内容 */
    suspend fun downloadAttachment(token: String): ByteArray

    suspend fun close()
}

/**
 * 中继类型与默认地址
 */
object RemoteTransports {

    /** 自建 Cloudflare Worker（默认，也是推荐通道） */
    const val KIND_CF = "cf"

    /** 公共 MQTT broker，留作不想部署 Worker 时的备选 */
    const val KIND_MQTT = "mqtt"

    const val KIND_NTFY = "ntfy"

    /** 默认：EMQX 公共 broker 的 WebSocket TLS 端口，匿名可用，无需注册 */
    const val DEFAULT_MQTT_URL = "wss://broker.emqx.io:8084/mqtt"

    /** 备选：同样免费匿名，欧洲节点，反过来国内延迟高一些 */
    const val FALLBACK_MQTT_URL = "wss://broker.hivemq.com:8884/mqtt"

    const val DEFAULT_NTFY_URL = "https://ntfy.sh"

    /**
     * Cloudflare Worker 没有公共实例可填——必须是自己部署的那个。
     * 所以默认留空，界面上会引导用户去部署。
     */
    const val DEFAULT_CF_URL = ""

    fun normalizeKind(kind: String?): String = when (kind) {
        KIND_NTFY -> KIND_NTFY
        KIND_MQTT -> KIND_MQTT
        else -> KIND_CF
    }

    /**
     * 地址兜底。
     *
     * 用户可能在输入框里把地址删空，或者切换类型时地址没跟着改。
     * 这里按类型校验前缀，不合法就退回该类型的默认值——否则会拼出
     * `/topic` 这种相对路径，请求必然失败，而失败原因会被笼统显示成"无响应"。
     */
    fun normalizeUrl(kind: String, url: String?): String {
        val trimmed = url?.trim().orEmpty()
        return when (normalizeKind(kind)) {
            KIND_CF -> when {
                // Worker 地址可能带 ?key= 之类的参数，只裁掉尾部斜杠
                trimmed.startsWith("https://") || trimmed.startsWith("http://") -> trimmed.trimEnd('/')
                trimmed.startsWith("wss://") -> "https://" + trimmed.removePrefix("wss://").trimEnd('/')
                trimmed.startsWith("ws://") -> "http://" + trimmed.removePrefix("ws://").trimEnd('/')
                else -> DEFAULT_CF_URL
            }

            KIND_NTFY -> if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                trimmed.trimEnd('/')
            } else {
                DEFAULT_NTFY_URL
            }

            else -> if (trimmed.startsWith("ws://") || trimmed.startsWith("wss://")) {
                trimmed.trimEnd('/')
            } else if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                // 用户把 Worker 地址留在了这里，回退到公共 broker
                DEFAULT_MQTT_URL
            } else {
                DEFAULT_MQTT_URL
            }
        }
    }

    /**
     * Worker 地址 → WebSocket 地址。
     *
     * 用户填的通常是 `https://xxx.workers.dev`，实际要连的是
     * `wss://xxx.workers.dev/ws`。这里统一转换，已经带 /ws 的不重复拼。
     */
    fun toWebSocketUrl(endpoint: String): String {
        val trimmed = endpoint.trim()
        if (trimmed.isEmpty()) return ""

        val (scheme, rest) = when {
            trimmed.startsWith("https://") -> "wss://" to trimmed.removePrefix("https://")
            trimmed.startsWith("http://") -> "ws://" to trimmed.removePrefix("http://")
            trimmed.startsWith("wss://") -> "wss://" to trimmed.removePrefix("wss://")
            trimmed.startsWith("ws://") -> "ws://" to trimmed.removePrefix("ws://")
            else -> "wss://" to trimmed
        }

        val queryIndex = rest.indexOf('?')
        val path = if (queryIndex >= 0) rest.substring(0, queryIndex) else rest
        val query = if (queryIndex >= 0) rest.substring(queryIndex) else ""

        val cleanPath = path.trimEnd('/')
        val wsPath = if (cleanPath.endsWith("/ws")) cleanPath else "$cleanPath/ws"
        return scheme + wsPath + query
    }

    /** 从 Worker 地址里拆出自定义口令（`?key=xxx`） */
    fun keyOf(url: String): String = runCatching {
        val query = java.net.URI(url).rawQuery ?: return ""
        query.split('&')
            .mapNotNull { pair ->
                val index = pair.indexOf('=')
                if (index <= 0) null
                else pair.substring(0, index) to pair.substring(index + 1)
            }
            .firstOrNull { it.first == "key" }
            ?.second
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
            .orEmpty()
    }.getOrDefault("")

    /**
     * 主机名，仅用于报错文案
     */
    fun hostOf(url: String): String {
        if (url.isBlank()) return "未配置地址"
        return runCatching {
            java.net.URI(url).host
        }.getOrNull() ?: url
    }

    /**
     * 当前是否在走兜底通道。
     *
     * Cloudflare 需要用户自己部署一次，地址没填时如果直接连，App 就完全不可用了。
     * 所以这里退回公共 broker 保证"装上就能试"，界面据此提示真实状态。
     */
    fun isFallback(kind: String, url: String?): Boolean =
        normalizeKind(kind) == KIND_CF && normalizeUrl(kind, url).isBlank()

    fun create(
        kind: String,
        url: String,
        topic: String,
        role: String,
        since: String? = null,
    ): RemoteTransport {
        val resolvedKind = normalizeKind(kind)
        val resolvedUrl = normalizeUrl(resolvedKind, url)

        return when (resolvedKind) {
            KIND_CF -> if (resolvedUrl.isBlank()) {
                // 还没部署 Worker：先用公共 broker 顶上，填了地址就会自动切过去
                mqttTransport(DEFAULT_MQTT_URL, topic, role)
            } else {
                CloudflareTransport(
                    endpoint = resolvedUrl,
                    room = topic,
                    key = keyOf(resolvedUrl),
                    clientId = if (role == ROLE_HOST) "host" else "client",
                )
            }

            KIND_NTFY -> NtfyTransport(baseUrl = resolvedUrl, topic = topic, since = since)

            else -> mqttTransport(resolvedUrl, topic, role)
        }
    }

    private fun mqttTransport(wsUrl: String, topic: String, role: String): MqttTransport =
        MqttTransport(
            wsUrl = wsUrl,
            topic = topic,
            // 被控端用稳定的 clientId + 持久会话，断线期间的命令能被 broker 暂存；
            // 控制端每次新的 clientId，避免收到上一次会话遗留的应答。
            clientId = if (role == ROLE_HOST) {
                "zl2h-" + topic.takeLast(16)
            } else {
                "zl2c-" + RemoteCrypto.newInstanceId().take(12)
            },
            persistentSession = role == ROLE_HOST,
        )

    const val ROLE_HOST = "host"
    const val ROLE_CLIENT = "client"
}
