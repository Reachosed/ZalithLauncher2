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
import com.movtery.zalithlauncher.path.createOkHttpClientBuilder
import com.movtery.zalithlauncher.utils.logging.Logger
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.TimeUnit

private const val TAG = "NtfyTransport"

/**
 * 单条消息的安全上限。
 *
 * ntfy.sh 公共实例的单条消息上限是 4096 字节，超过之后它不会报错，
 * 而是**静默地把正文转成附件**——对端收到的是 "You received a file: ..."，
 * 协议就断了。因此这里留出余量，一旦逼近上限就走附件通道。
 */
const val RELAY_MAX_TEXT_LENGTH = 3000

/**
 * 基于 ntfy 公共实例的中继传输层（备选通道）。
 *
 * 现在默认走 [MqttTransport]，这个实现保留下来是为了两种情况：
 * 一是 MQTT 在你所在网络被拦时可以直接切回来；二是自建 ntfy 私有实例
 * 的人可以继续用。
 */
class NtfyTransport(
    baseUrl: String,
    val topic: String,
    private val since: String? = null,
) : RemoteTransport {

    val baseUrl: String = baseUrl.trimEnd('/')

    override val label: String = "ntfy · ${RemoteTransports.hostOf(this.baseUrl)}"

    override val maxTextLength: Int = RELAY_MAX_TEXT_LENGTH

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inbox = Channel<RelayEvent>(Channel.BUFFERED)
    private var streamJob: Job? = null
    private var closed = false

    private fun topicUrl(): String = "$baseUrl/$topic"

    override suspend fun connect() {
        val ready = CompletableDeferred<Unit>()

        streamJob = scope.launch {
            var backoff = 1_000L
            while (!closed) {
                try {
                    subscribe(since).collect { event ->
                        if (event is RelayEvent.Open) {
                            if (!ready.isCompleted) ready.complete(Unit)
                        } else {
                            inbox.send(event)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!ready.isCompleted) {
                        ready.completeExceptionally(e)
                        break
                    }
                    Logger.warning(TAG, "subscribe error: ${e.message}")
                }
                if (closed) break
                kotlinx.coroutines.delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
            inbox.close()
        }

        val ok = try {
            withTimeoutOrNull(15_000L) { ready.await() }
        } catch (e: Exception) {
            close()
            throw IllegalStateException("订阅中继失败：${e.message}", e)
        }
        if (ok == null) {
            close()
            throw IllegalStateException("订阅中继超时：$baseUrl 没有回应")
        }
    }

    override fun events(): Flow<RelayEvent> = inbox.receiveAsFlow()

    override suspend fun publish(text: String) {
        if (text.length > maxTextLength) {
            // 超长内容由 RemoteWire 拆成附件发送，走到这里说明调用方漏了这一步。
            // 直接抛错比静默失败好——ntfy 超限时会把正文转成附件，对端只会
            // 收到一句 "You received a file"，协议就断了。
            throw IllegalStateException("报文超过 ntfy 单条上限（${text.length} 字符）")
        }
        val response = RELAY_CLIENT.post(topicUrl()) {
            contentType(ContentType.Text.Plain)
            setBody(text)
        }
        checkOk(response, "publish")
    }

    override suspend fun uploadAttachment(bytes: ByteArray, fileName: String): String {
        val response = RELAY_CLIENT.put(topicUrl()) {
            contentType(ContentType.Application.OctetStream)
            header("Filename", fileName)
            setBody(bytes)
        }
        checkOk(response, "upload")
        val text = response.bodyAsText()
        val json = runCatching { GLOBAL_JSON.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: throw IllegalStateException("relay upload: unexpected response")
        return json["attachment"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
            ?: throw IllegalStateException("relay upload: no attachment url")
    }

    override suspend fun downloadAttachment(token: String): ByteArray {
        val response = RELAY_CLIENT.get(token)
        checkOk(response, "download")
        return response.body()
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        streamJob?.cancel()
        inbox.close()
        scope.cancel()
    }

    /**
     * 订阅主题。
     *
     * 使用 ntfy 的流式接口（连接保持打开、逐行下发 JSON），而不是短轮询：
     * 短轮询会触发公共实例的请求频率限制，流式只占一条长连接。
     */
    private fun subscribe(since: String?): Flow<RelayEvent> = flow {
        val response = RELAY_CLIENT.get("$baseUrl/$topic/json") {
            parameter("poll", "0")
            if (since != null) parameter("since", since)
            header(HttpHeaders.Accept, "application/x-ndjson")
        }
        if (response.status.value !in 200..299) {
            throw IllegalStateException("relay subscribe failed: HTTP ${response.status.value}")
        }

        val channel = response.bodyAsChannel()
        var opened = false
        while (true) {
            val line = channel.readUTF8Line() ?: break
            if (line.isBlank()) continue
            val event = parseEvent(line) ?: continue
            if (event is RelayEvent.Open) {
                if (opened) continue
                opened = true
            }
            emit(event)
        }
    }

    private suspend fun checkOk(response: HttpResponse, action: String) {
        if (response.status.value !in 200..299) {
            val body = runCatching { response.bodyAsText() }.getOrNull().orEmpty()
            throw IllegalStateException("relay $action failed: HTTP ${response.status.value} $body")
        }
    }

    private fun parseEvent(line: String): RelayEvent? {
        val json = runCatching { GLOBAL_JSON.parseToJsonElement(line).jsonObject }.getOrNull() ?: return null
        val event = json["event"]?.jsonPrimitive?.contentOrNull ?: return null
        return when (event) {
            "message" -> {
                val id = json["id"]?.jsonPrimitive?.contentOrNull ?: return null
                val message = json["message"]?.jsonPrimitive?.contentOrNull ?: return null
                RelayEvent.Message(id, message)
            }
            "keepalive" -> RelayEvent.KeepAlive
            "open" -> RelayEvent.Open
            else -> null
        }
    }

    companion object {
        /**
         * 专用于中继的客户端：关掉所有读取超时。
         * 全局客户端带请求超时，长连接会被它掐断。
         */
        private val RELAY_CLIENT: HttpClient by lazy {
            HttpClient(OkHttp) {
                expectSuccess = false
                engine {
                    preconfigured = createOkHttpClientBuilder { builder ->
                        builder.callTimeout(0, TimeUnit.MILLISECONDS)
                        builder.readTimeout(0, TimeUnit.MILLISECONDS)
                        builder.writeTimeout(60, TimeUnit.SECONDS)
                    }.build()
                }
            }
        }
    }
}

/**
 * 内容过大时改走附件通道的判断
 */
fun needsAttachment(envelopeJson: String): Boolean = envelopeJson.length > RELAY_MAX_TEXT_LENGTH
