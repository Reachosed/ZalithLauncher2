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

import android.os.Environment
import com.movtery.zalithlauncher.context.GlobalContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.RandomAccessFile

/**
 * 文件侧的桥。
 *
 * 权限前提：被控端需要"所有文件访问"（MANAGE_EXTERNAL_STORAGE），
 * 否则只能看到启动器自己的目录。Android 11 起该权限必须由用户在系统设置里手动授予。
 */
object FileBridge {

    /**
     * 默认展示的入口目录。
     * 内置存储根目录在绝大多数设备上是 /storage/emulated/0，
     * Environment.getExternalStorageDirectory() 在部分机型上会指向别的挂载点，故两者都给出。
     */
    fun roots(): JsonArray = buildJsonArray {
        add(rootEntry("内置存储", externalRoot().absolutePath))
        add(rootEntry("下载", File(externalRoot(), Environment.DIRECTORY_DOWNLOADS).absolutePath))
        add(rootEntry("启动器目录", appFilesRoot().absolutePath))
        add(rootEntry("根目录", "/"))
    }

    private fun rootEntry(name: String, path: String) = buildJsonObject {
        put("name", name)
        put("path", path)
        put("dir", true)
    }

    fun externalRoot(): File {
        val primary = Environment.getExternalStorageDirectory()
        val fallback = File("/storage/emulated/0")
        return if (primary != null && primary.exists()) primary else fallback
    }

    fun appFilesRoot(): File = GlobalContext.getExternalFilesDir(null) ?: GlobalContext.filesDir

    /**
     * 列目录
     */
    fun list(path: String, limit: Int = 500): JsonObject {
        val dir = File(safePath(path))
        if (!dir.exists()) return errorResult("路径不存在: ${dir.absolutePath}")
        if (!dir.isDirectory) return errorResult("不是目录: ${dir.absolutePath}")

        val children = dir.listFiles()
        if (children == null) {
            return errorResult("无权限读取该目录: ${dir.absolutePath}")
        }

        val sorted = children.sortedWith(
            compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() }
        )

        return buildJsonObject {
            put("path", dir.absolutePath)
            put("parent", dir.parentFile?.absolutePath ?: dir.absolutePath)
            put("dir", true)
            put("truncated", sorted.size > limit)
            put("entries", buildJsonArray {
                sorted.take(limit).forEach { file ->
                    add(buildJsonObject {
                        put("name", file.name)
                        put("path", file.absolutePath)
                        put("dir", file.isDirectory)
                        put("size", if (file.isDirectory) 0L else file.length())
                        put("mtime", file.lastModified())
                        put("readable", file.canRead())
                        put("writable", file.canWrite())
                    })
                }
            })
        }
    }

    fun stat(path: String): JsonObject {
        val file = File(safePath(path))
        return buildJsonObject {
            put("path", file.absolutePath)
            put("name", file.name)
            put("exists", file.exists())
            put("dir", file.isDirectory)
            put("size", if (file.isFile) file.length() else 0L)
            put("mtime", file.lastModified())
            put("readable", file.canRead())
            put("writable", file.canWrite())
        }
    }

    /**
     * 读取整个文件（有上限，超出由上层拆分）
     */
    fun read(path: String, maxBytes: Long): ByteArray {
        val file = File(safePath(path))
        require(file.isFile) { "不是文件: ${file.absolutePath}" }
        require(file.length() <= maxBytes) {
            "文件过大: ${file.length()} 字节，上限 $maxBytes 字节"
        }
        return file.readBytes()
    }

    /**
     * 写入整个文件（覆盖）
     */
    fun write(path: String, bytes: ByteArray) {
        val file = File(safePath(path))
        file.parentFile?.mkdirs()
        val raf = RandomAccessFile(file, "rw")
        raf.use {
            it.setLength(0)
            it.write(bytes)
        }
    }

    fun mkdir(path: String): JsonObject {
        val file = File(safePath(path))
        val ok = file.exists() || file.mkdirs()
        return buildJsonObject {
            put("path", file.absolutePath)
            put("ok", ok)
        }
    }

    /**
     * 递归删除。为防误删，拒绝删除若干关键目录本身。
     */
    fun delete(path: String): JsonObject {
        val file = File(safePath(path))
        val protected = setOf(
            "/",
            "/sdcard",
            "/storage",
            "/storage/emulated",
            externalRoot().absolutePath,
            File(externalRoot(), Environment.DIRECTORY_DOWNLOADS).absolutePath,
        )
        if (file.absolutePath in protected) {
            return errorResult("拒绝删除受保护目录: ${file.absolutePath}")
        }
        if (!file.exists()) return errorResult("不存在: ${file.absolutePath}")

        val ok = file.deleteRecursively()
        return buildJsonObject {
            put("path", file.absolutePath)
            put("ok", ok)
        }
    }

    fun rename(from: String, to: String): JsonObject {
        val source = File(safePath(from))
        val target = File(safePath(to))
        if (!source.exists()) return errorResult("不存在: ${source.absolutePath}")
        target.parentFile?.mkdirs()
        val ok = source.renameTo(target)
        return buildJsonObject {
            put("from", source.absolutePath)
            put("to", target.absolutePath)
            put("ok", ok)
        }
    }

    /**
     * 规范化路径：展开 ~、裁剪多余的 ..，避免越权写到意料之外的位置。
     * 注意这里不做"白名单"限制——用户明确要求能操作整个手机存储。
     */
    fun safePath(raw: String): String {
        val trimmed = raw.trim()
        val expanded = when {
            trimmed.isEmpty() -> externalRoot().absolutePath
            trimmed == "~" -> externalRoot().absolutePath
            trimmed.startsWith("~/") -> File(externalRoot(), trimmed.removePrefix("~/")).absolutePath
            else -> trimmed
        }
        return runCatching { File(expanded).canonicalPath }.getOrDefault(expanded)
    }

    private fun errorResult(message: String): JsonObject = buildJsonObject {
        put("error", message)
    }
}
