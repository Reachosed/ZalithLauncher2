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

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * 远控协议版本。任何不兼容的改动都必须递增此值。
 */
const val REMOTE_PROTOCOL_VERSION = 1

/**
 * 中继上实际传输的信封。
 *
 * 中继（ntfy）只能看到 [n]（随机 nonce）与 [d]（密文），
 * 明文结构与业务字段全部位于密文内部，中继无法解析、无法篡改。
 */
@Serializable
data class RemoteEnvelope(
    val v: Int = REMOTE_PROTOCOL_VERSION,
    /** Base64 编码的 12 字节 GCM nonce */
    val n: String,
    /** Base64 编码的密文（含 GCM tag） */
    val d: String,
)

/**
 * 加密后的业务报文。
 *
 * - [type] = "req" 控制端发起请求
 * - [type] = "res" 被控端返回结果
 * - [type] = "evt" 任意一侧主动上报事件
 */
@Serializable
data class RemotePacket(
    val id: String,
    val type: String,
    val cmd: String = "",
    /** 发送方实例 id，用于忽略自己发出的报文 */
    val from: String = "",
    val ts: Long = 0L,
    val ok: Boolean = false,
    val err: String? = null,
    val body: JsonObject? = null,
    /**
     * 报文过大（例如整包设置快照）时，正文会被压缩成附件上传到中继，
     * 此处只保留附件的下载地址，避免撞上中继的单条消息体积上限。
     */
    val attach: String? = null,
) {
    companion object {
        const val TYPE_REQ = "req"
        const val TYPE_RES = "res"
        const val TYPE_EVT = "evt"
    }
}

/**
 * 命令字。命名保持扁平，方便日志排查。
 */
object RemoteCmd {
    /** 握手：控制端 → 被控端，确认配对码正确且被控端在线 */
    const val HELLO = "hello"
    /** 心跳，同时用于刷新控制端的在线状态 */
    const val PING = "ping"
    /** 列出全部设置项及其元数据（类型、取值范围、枚举候选） */
    const val SETTINGS_LIST = "settings.list"
    /** 修改单个设置项 */
    const val SETTINGS_APPLY = "settings.apply"
    /** 将单个设置项恢复默认值 */
    const val SETTINGS_RESET = "settings.reset"
    /** 导出整包配置快照 */
    const val CONFIG_EXPORT = "config.export"
    /** 导入整包配置快照（覆盖式） */
    const val CONFIG_IMPORT = "config.import"
    /** 列目录 */
    const val FS_LIST = "fs.list"
    /** 删除文件或目录 */
    const val FS_DELETE = "fs.delete"
    /** 新建目录 */
    const val FS_MKDIR = "fs.mkdir"
    /** 重命名 / 移动 */
    const val FS_RENAME = "fs.rename"
    /** 被控端 → 控制端 拉取文件 */
    const val FS_PULL = "fs.pull"
    /** 控制端 → 被控端 推送文件 */
    const val FS_PUSH = "fs.push"
    /** 取文件元信息 */
    const val FS_STAT = "fs.stat"
    /** 主动断开 */
    const val SESSION_CLOSE = "session.close"
}

/**
 * 协议里的通用字段名，避免各处手写字符串拼错。
 */
object RemoteKeys {
    const val OK = "ok"
    const val ERROR = "error"
    const val KEY = "key"
    const val VALUE = "value"
    const val PATH = "path"
    const val NAME = "name"
    const val SIZE = "size"
    const val MTIME = "mtime"
    const val IS_DIR = "dir"
    const val ENTRIES = "entries"
    const val SECTIONS = "sections"
    const val TYPE = "type"
    const val MIN = "min"
    const val MAX = "max"
    const val OPTIONS = "options"
    const val COUNT = "count"
    const val ATTACH = "attach"
    const val ROOTS = "roots"
}
