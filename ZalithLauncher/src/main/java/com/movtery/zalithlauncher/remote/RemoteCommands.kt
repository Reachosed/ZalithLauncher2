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

import android.os.Build
import android.os.Environment
import com.movtery.zalithlauncher.context.GlobalContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.crypto.SecretKey

/**
 * 一条命令的执行结果
 */
class RemoteResult(
    val ok: Boolean,
    val body: JsonObject? = null,
    val err: String? = null,
)

/**
 * 被控端的命令执行器。
 *
 * 所有能力都收敛在这里，控制端只能通过这里列出的命令字访问被控端，
 * 不存在"任意代码执行"的口子。
 */
object RemoteCommands {

    suspend fun execute(
        cmd: String,
        body: JsonObject?,
        transport: RemoteTransport,
        key: SecretKey,
    ): RemoteResult {
        return runCatching {
            when (cmd) {
                RemoteCmd.HELLO, RemoteCmd.PING -> RemoteResult(true, deviceInfo())
                RemoteCmd.SETTINGS_LIST -> RemoteResult(true, SettingsBridge.describe())
                RemoteCmd.SETTINGS_APPLY -> applySetting(body)
                RemoteCmd.SETTINGS_RESET -> resetSetting(body)
                RemoteCmd.CONFIG_EXPORT -> RemoteResult(true, SettingsBridge.snapshot())
                RemoteCmd.CONFIG_IMPORT -> importConfig(body)
                RemoteCmd.FS_LIST -> RemoteResult(
                    true,
                    FileBridge.list(
                        path = body.string(RemoteKeys.PATH, FileBridge.externalRoot().absolutePath),
                        limit = body.int(RemoteKeys.COUNT, 500).coerceIn(1, 2000),
                    )
                )
                RemoteCmd.FS_STAT -> RemoteResult(
                    true,
                    FileBridge.stat(body.string(RemoteKeys.PATH, ""))
                )
                RemoteCmd.FS_MKDIR -> RemoteResult(
                    true,
                    FileBridge.mkdir(body.string(RemoteKeys.PATH, ""))
                )
                RemoteCmd.FS_DELETE -> RemoteResult(
                    true,
                    FileBridge.delete(body.string(RemoteKeys.PATH, ""))
                )
                RemoteCmd.FS_RENAME -> RemoteResult(
                    true,
                    FileBridge.rename(
                        from = body.string("from", ""),
                        to = body.string("to", ""),
                    )
                )
                RemoteCmd.FS_PULL -> pullFile(body, transport, key)
                RemoteCmd.FS_PUSH -> pushFile(body, transport, key)
                RemoteCmd.SESSION_CLOSE -> RemoteResult(true)
                else -> RemoteResult(false, err = "未知命令: $cmd")
            }
        }.getOrElse { e ->
            RemoteResult(false, err = "${e::class.simpleName}: ${e.message}")
        }
    }

    private suspend fun applySetting(body: JsonObject?): RemoteResult {
        val key = body.string(RemoteKeys.KEY, "")
        if (key.isBlank()) return RemoteResult(false, err = "缺少参数: key")
        val value = body?.get(RemoteKeys.VALUE) ?: return RemoteResult(false, err = "缺少参数: value")
        val error = SettingsBridge.apply(key, value)
        return if (error == null) {
            RemoteResult(true, buildJsonObject { put("key", key) })
        } else {
            RemoteResult(false, err = error)
        }
    }

    private suspend fun resetSetting(body: JsonObject?): RemoteResult {
        val key = body.string(RemoteKeys.KEY, "")
        if (key.isBlank()) return RemoteResult(false, err = "缺少参数: key")
        val error = SettingsBridge.reset(key)
        return if (error == null) {
            RemoteResult(true, buildJsonObject { put("key", key) })
        } else {
            RemoteResult(false, err = error)
        }
    }

    private suspend fun importConfig(body: JsonObject?): RemoteResult {
        if (body == null) return RemoteResult(false, err = "缺少配置内容")
        //允许两种形态：直接的扁平 key→value，或 {payload:{...}}
        val payload = body["payload"]?.jsonObject ?: body
        val count = SettingsBridge.importSnapshot(payload)
        return RemoteResult(true, buildJsonObject { put("count", count) })
    }

    /**
     * 被控端把文件加密后上传到中继，返回附件地址
     */
    private suspend fun pullFile(
        body: JsonObject?,
        transport: RemoteTransport,
        key: SecretKey,
    ): RemoteResult {
        val path = body.string(RemoteKeys.PATH, "")
        if (path.isBlank()) return RemoteResult(false, err = "缺少参数: path")
        val limit = fileLimitBytes()
        val bytes = FileBridge.read(path, limit)
        val sealed = RemoteCrypto.sealBytes(key, bytes)
        val url = transport.uploadAttachment(sealed, "file.bin")
        return RemoteResult(
            true,
            buildJsonObject {
                put("path", path)
                put("size", bytes.size)
                put("attach", url)
            }
        )
    }

    /**
     * 控制端把文件上传到中继，被控端下载解密后落盘
     */
    private suspend fun pushFile(
        body: JsonObject?,
        transport: RemoteTransport,
        key: SecretKey,
    ): RemoteResult {
        val path = body.string(RemoteKeys.PATH, "")
        val attach = body.string(RemoteKeys.ATTACH, "")
        if (path.isBlank()) return RemoteResult(false, err = "缺少参数: path")
        if (attach.isBlank()) return RemoteResult(false, err = "缺少参数: attach")

        val sealed = transport.downloadAttachment(attach)
        if (sealed.size > fileLimitBytes() + 1024) {
            return RemoteResult(false, err = "文件超过大小上限")
        }
        val bytes = RemoteCrypto.openBytes(key, sealed)
            ?: return RemoteResult(false, err = "文件解密失败（密钥不匹配或数据损坏）")

        FileBridge.write(path, bytes)
        return RemoteResult(
            true,
            buildJsonObject {
                put("path", path)
                put("size", bytes.size)
            }
        )
    }

    private fun fileLimitBytes(): Long =
        RemoteSettings.remoteFileSizeLimitMb.getValue().toLong() * 1024L * 1024L

    private fun deviceInfo(): JsonObject = buildJsonObject {
        val name = RemoteSettings.remoteDeviceName.getValue()
            .ifBlank { "${Build.MANUFACTURER} ${Build.MODEL}" }
        put("device", name)
        put("model", Build.MODEL)
        put("sdk", Build.VERSION.SDK_INT)
        put("version", launcherVersionName())
        put("allFilesAccess", hasAllFilesAccess())
        put("roots", FileBridge.roots())
        put("relay", RemoteSettings.remoteRelayUrl.getValue())
    }

    private fun launcherVersionName(): String = runCatching {
        val pm = GlobalContext.packageManager
        pm.getPackageInfo(GlobalContext.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    fun hasAllFilesAccess(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        true
    }

    private fun JsonObject?.string(key: String, default: String): String =
        (this?.get(key) as? JsonPrimitive)?.contentOrNull ?: default

    private fun JsonObject?.int(key: String, default: Int): Int =
        (this?.get(key) as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: default
}
