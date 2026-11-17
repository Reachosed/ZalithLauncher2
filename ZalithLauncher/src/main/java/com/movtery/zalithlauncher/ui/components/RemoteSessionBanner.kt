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

package com.movtery.zalithlauncher.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.movtery.zalithlauncher.remote.RemoteHost
import com.movtery.zalithlauncher.remote.RemoteHostService
import com.movtery.zalithlauncher.remote.RemoteHostState
import com.movtery.zalithlauncher.remote.RemoteSettings

/**
 * 被控端顶部提示条。
 *
 * 这是被控端界面上唯一的改动：不新增页面、不改布局，
 * 只在顶部多出一条细提示，让"这台机器正被远程控制"这件事始终可见、可一键关掉。
 * 远程操作本身全部发生在控制端，被控端保持原版观感。
 */
@Composable
fun RemoteSessionBanner(modifier: Modifier = Modifier) {
    val state by RemoteHost.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    AnimatedVisibility(
        visible = state !is RemoteHostState.Stopped,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut()
    ) {
        val (text, emphasize) = when (val current = state) {
            is RemoteHostState.Waiting ->
                "远程控制待命 · 配对码 ${current.code.chunked(3).joinToString(" ")}" to false
            is RemoteHostState.Connected ->
                "远程会话进行中 · ${current.peer}" to true
            else -> "" to false
        }

        Row(
            modifier = modifier
                .fillMaxWidth()
                .background(
                    if (emphasize) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                )
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        if (emphasize) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline
                    )
            )

            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (emphasize) FontWeight.Medium else FontWeight.Normal,
                modifier = Modifier.weight(1f)
            )

            TextButton(onClick = {
                RemoteSettings.remoteHostEnabled.save(false)
                RemoteHostService.stop(context)
            }) {
                Text("关闭", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
