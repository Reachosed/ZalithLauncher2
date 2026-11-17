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

import com.movtery.zalithlauncher.setting.SettingsRegistry

/**
 * 远控自身的设置项。
 *
 * 注意：这些开关本身也被纳入整包配置快照——
 * 因此从别的设备导入配置时，可以把"这台机器作为被控端常驻"这类状态一起带过来。
 */
object RemoteSettings : SettingsRegistry() {
    /** 是否启用被控端常驻 */
    val remoteHostEnabled = boolSetting("remoteHostEnabled", false)

    /**
     * 中继类型：cf（默认）、mqtt 或 ntfy。
     *
     * 默认走自建 Cloudflare Worker：它只做同房间广播，不依赖任何公共
     * broker 的限流与可用性，免费额度对个人远控来说绰绰有余。
     * mqtt / ntfy 作为"不想部署 Worker"时的备选保留。
     */
    val remoteRelayKind = stringSetting("remoteRelayKind", RemoteTransports.KIND_CF)

    /**
     * 中继地址。
     *
     * Cloudflare 是用户自己部署的 Worker 域名，没有公共默认值，首次使用
     * 必须填一次；类型与地址不匹配时会被 [RemoteTransports.normalizeUrl] 纠正。
     */
    val remoteRelayUrl = stringSetting("remoteRelayUrl", RemoteTransports.DEFAULT_CF_URL)

    /** 本机在控制端显示的名称 */
    val remoteDeviceName = stringSetting("remoteDeviceName", "")

    /** 最近一次生成的配对码（仅用于界面回显，重新生成会覆盖） */
    val remotePairCode = stringSetting("remotePairCode", "")

    /** 单次文件传输的大小上限（MB） */
    val remoteFileSizeLimitMb = intSetting("remoteFileSizeLimitMb", 8, 1..64)
}
