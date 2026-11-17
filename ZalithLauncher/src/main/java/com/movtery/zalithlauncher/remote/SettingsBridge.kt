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

import androidx.compose.ui.geometry.Offset
import com.movtery.zalithlauncher.setting.ControlSettings
import com.movtery.zalithlauncher.setting.GameSettings
import com.movtery.zalithlauncher.setting.GameUiSettings
import com.movtery.zalithlauncher.setting.LauncherSettings
import com.movtery.zalithlauncher.setting.MiscSettings
import com.movtery.zalithlauncher.setting.RendererSettings
import com.movtery.zalithlauncher.setting.SettingsRegistry
import com.movtery.zalithlauncher.setting.unit.AbstractSettingUnit
import com.movtery.zalithlauncher.setting.unit.BooleanSettingUnit
import com.movtery.zalithlauncher.setting.unit.EnumSettingUnit
import com.movtery.zalithlauncher.setting.unit.FloatSettingUnit
import com.movtery.zalithlauncher.setting.unit.IntSettingUnit
import com.movtery.zalithlauncher.setting.unit.LongSettingUnit
import com.movtery.zalithlauncher.setting.unit.NullableIntSettingUnit
import com.movtery.zalithlauncher.setting.unit.OffsetSettingUnit
import com.movtery.zalithlauncher.setting.unit.ParcelableSettingUnit
import com.movtery.zalithlauncher.setting.unit.StringListSettingUnit
import com.movtery.zalithlauncher.setting.unit.StringSettingUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 设置项 ⇄ JSON 的桥。
 *
 * 这是本改动最关键的一块：原项目的所有设置项都散在 MMKV 里，
 * 每项由一个 SettingUnit 包装，**不存在**一份"完整配置文件"。
 * 这里通过 [SettingsRegistry.units] 枚举出全部设置项，
 * 统一序列化成一份 JSON，才谈得上"改设置""传配置"。
 */
object SettingsBridge {

    private data class Section(
        val id: String,
        val name: String,
        val registry: SettingsRegistry,
    )

    private val sections = listOf(
        Section("launcher", "启动器", LauncherSettings),
        Section("game", "游戏", GameSettings),
        Section("renderer", "渲染器", RendererSettings),
        Section("control", "控制", ControlSettings),
        Section("gameUi", "游戏内界面", GameUiSettings),
        Section("misc", "杂项", MiscSettings),
        Section("remote", "远程控制", RemoteSettings),
    )

    /**
     * 描述全部设置项：类型、取值范围、枚举候选、当前值。
     * 控制端据此渲染出可操作的设置面板。
     */
    fun describe(): JsonObject = buildJsonObject {
        put("sections", buildJsonArray {
            sections.forEach { section ->
                add(buildJsonObject {
                    put("id", section.id)
                    put("name", section.name)
                    put("items", buildJsonArray {
                        section.registry.units.forEach { unit ->
                            add(describeUnit(unit))
                        }
                    })
                })
            }
        })
        put("count", sections.sumOf { it.registry.units.size })
    }

    /**
     * 整包快照：扁平的 key → value。用于导出 / 导入配置。
     *
     * 刻意不包含远控自身的设置项（中继地址、常驻开关等）：
     * 把这些一起导入另一台设备，会直接把两端的通道参数改掉，属于自伤行为。
     */
    fun snapshot(): JsonObject = buildJsonObject {
        put("payload", buildJsonObject {
            sections.forEach { section ->
                if (section.registry === RemoteSettings) return@forEach
                section.registry.units.forEach { unit ->
                    put(unit.key, valueToJson(unit))
                }
            }
        })
    }

    /**
     * 覆盖式导入快照。
     * @return 成功写入的设置项数量
     */
    suspend fun importSnapshot(snapshot: JsonObject): Int {
        var applied = 0
        for ((key, value) in snapshot) {
            if (RemoteSettings.findUnit(key) != null) continue
            if (apply(key, value) == null) applied++
        }
        return applied
    }

    /**
     * 修改一项设置。
     * @return null 表示成功，否则为错误描述
     */
    suspend fun apply(key: String, value: JsonElement): String? {
        val unit = findUnit(key) ?: return "未知设置项: $key"
        val target: Any = when (unit) {
            is BooleanSettingUnit -> value.jsonPrimitive.booleanOrNull ?: return "需要布尔值"
            is IntSettingUnit -> value.jsonPrimitive.intOrNull ?: return "需要整数"
            is NullableIntSettingUnit -> {
                if (value is JsonNull) return save(unit, null)
                value.jsonPrimitive.intOrNull ?: return "需要整数或 null"
            }
            is LongSettingUnit -> value.jsonPrimitive.longOrNull ?: return "需要整数"
            is FloatSettingUnit -> value.jsonPrimitive.floatOrNull ?: return "需要小数"
            is StringSettingUnit -> (value as? JsonPrimitive)?.content ?: return "需要字符串"
            is StringListSettingUnit -> {
                val array = value as? JsonArray ?: return "需要字符串数组"
                array.mapNotNull { (it as? JsonPrimitive)?.content }
            }
            is EnumSettingUnit<*> -> {
                val raw = (value as? JsonPrimitive)?.content ?: return "需要枚举名"
                val clazz = (unit.defaultValue as? Enum<*>)?.javaClass
                    ?: return "枚举类型未知"
                clazz.enumConstants
                    ?.firstOrNull { (it as Enum<*>).name == raw }
                    ?: return "非法的枚举值: $raw"
            }
            is OffsetSettingUnit -> {
                val obj = value as? JsonObject ?: return "需要 {x,y}"
                val x = obj["x"]?.jsonPrimitive?.floatOrNull ?: return "缺少 x"
                val y = obj["y"]?.jsonPrimitive?.floatOrNull ?: return "缺少 y"
                Offset(x, y)
            }
            is ParcelableSettingUnit<*> ->
                return "该项为 Parcelable 复合类型，暂不支持远程修改: $key"
            else -> return "不支持的设置类型: ${unit::class.simpleName}"
        }
        return save(unit, target)
    }

    /**
     * 恢复默认值
     */
    suspend fun reset(key: String): String? {
        val unit = findUnit(key) ?: return "未知设置项: $key"
        return withContext(Dispatchers.Main) {
            runCatching { unit.reset() }.exceptionOrNull()?.message
        }
    }

    fun findUnit(key: String): AbstractSettingUnit<*>? {
        sections.forEach { section ->
            section.registry.findUnit(key)?.let { return it }
        }
        return null
    }

    private suspend fun save(unit: AbstractSettingUnit<*>, value: Any?): String? {
        return withContext(Dispatchers.Main) {
            runCatching {
                @Suppress("UNCHECKED_CAST")
                (unit as AbstractSettingUnit<Any?>).save(value)
            }.exceptionOrNull()?.message
        }
    }

    private fun describeUnit(unit: AbstractSettingUnit<*>): JsonObject = buildJsonObject {
        put("key", unit.key)
        put("label", humanize(unit.key))
        put("type", typeName(unit))
        put("editable", unit !is ParcelableSettingUnit<*>)
        put("value", valueToJson(unit))
        when (unit) {
            is IntSettingUnit -> {
                put("min", unit.valueRange.first)
                put("max", unit.valueRange.last)
            }
            is NullableIntSettingUnit -> {
                put("min", unit.valueRange.first)
                put("max", unit.valueRange.last)
            }
            is LongSettingUnit -> {
                put("min", unit.valueRange.first)
                put("max", unit.valueRange.last)
            }
            is FloatSettingUnit -> {
                put("min", unit.valueRange.start)
                put("max", unit.valueRange.endInclusive)
            }
            is EnumSettingUnit<*> -> {
                val constants = (unit.defaultValue as? Enum<*>)?.javaClass?.enumConstants
                putJsonArray("options") {
                    constants?.forEach { add((it as Enum<*>).name) }
                }
            }
            is OffsetSettingUnit -> {
                putJsonObject("range") {
                    put("min", -100_000)
                    put("max", 100_000)
                }
            }
            else -> Unit
        }
    }

    private fun typeName(unit: AbstractSettingUnit<*>): String = when (unit) {
        is BooleanSettingUnit -> "bool"
        is IntSettingUnit -> "int"
        is NullableIntSettingUnit -> "int?"
        is LongSettingUnit -> "long"
        is FloatSettingUnit -> "float"
        is StringSettingUnit -> "string"
        is StringListSettingUnit -> "string_list"
        is EnumSettingUnit<*> -> "enum"
        is OffsetSettingUnit -> "offset"
        is ParcelableSettingUnit<*> -> "parcelable"
        else -> "unknown"
    }

    /**
     * 读当前值。每次直读 MMKV，保证控制端拿到的是最新状态。
     */
    private fun valueToJson(unit: AbstractSettingUnit<*>): JsonElement {
        val value = runCatching { unit.getValue() }.getOrNull()
        return anyToJson(value)
    }

    private fun anyToJson(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is Float -> JsonPrimitive(value)
        is String -> JsonPrimitive(value)
        is Enum<*> -> JsonPrimitive(value.name)
        is List<*> -> JsonArray(value.map { anyToJson(it) })
        is Offset -> buildJsonObject {
            put("x", value.x)
            put("y", value.y)
        }
        else -> JsonPrimitive(value.toString())
    }

    /**
     * launcherDarkMode → "Launcher Dark Mode"
     */
    private fun humanize(key: String): String {
        val sb = StringBuilder()
        key.forEachIndexed { index, c ->
            if (c.isUpperCase() && index > 0) sb.append(' ')
            sb.append(c)
        }
        val text = sb.toString().replace('_', ' ').trim()
        return text.replaceFirstChar { it.uppercaseChar() }
    }
}
