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

package com.movtery.zalithlauncher.ui.screens.content.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.movtery.zalithlauncher.path.GLOBAL_JSON
import com.movtery.zalithlauncher.remote.RemoteClient
import com.movtery.zalithlauncher.remote.RemoteClientState
import com.movtery.zalithlauncher.remote.RemoteCmd
import com.movtery.zalithlauncher.remote.RemoteCrypto
import com.movtery.zalithlauncher.remote.RemoteDiagnostics
import com.movtery.zalithlauncher.remote.RemoteHost
import com.movtery.zalithlauncher.remote.RemoteHostService
import com.movtery.zalithlauncher.remote.RemoteHostState
import com.movtery.zalithlauncher.remote.RemoteSettings
import com.movtery.zalithlauncher.remote.RemoteTransports
import com.movtery.zalithlauncher.ui.base.BaseScreen
import com.movtery.zalithlauncher.ui.screens.NestedNavKey
import com.movtery.zalithlauncher.ui.screens.NormalNavKey
import com.movtery.zalithlauncher.ui.screens.TitledNavKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 远程控制页面。
 *
 * 同时承载两种角色：
 * - 本机作为被控端：显示配对码、常驻开关、中继地址
 * - 本机作为控制端：输入配对码接入另一台设备，读写它的设置与文件
 *
 * 之所以放在同一个页面，是因为这套方案里没有"服务器"这一角色——
 * 任意两台装了本修改版的设备都可以互为控制端/被控端。
 */
@Composable
fun RemoteControlScreen(
    key: NestedNavKey.Settings,
    settingsScreenKey: TitledNavKey?,
    mainScreenKey: TitledNavKey?,
) {
    BaseScreen(
        Triple(key, mainScreenKey, false),
        Triple(NormalNavKey.Settings.RemoteControl, settingsScreenKey, false)
    ) {
        var mode by remember { mutableStateOf(0) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(all = 12.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeChip("本机作为被控端", mode == 0) { mode = 0 }
                ModeChip("连接到其他设备", mode == 1) { mode = 1 }
            }

            Spacer(Modifier.height(12.dp))

            if (mode == 0) HostPanel() else ClientPanel()
        }
    }
}

@Composable
private fun ModeChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 中继类型选择。
 *
 * 默认 Cloudflare：自己部署一个 Worker（一次性），之后不依赖任何公共
 * broker 的限流与可用性。MQTT / ntfy 留作备选，三者协议不同，
 * 两端必须选同一个。
 */
@Composable
private fun RelayTypeRow(kind: String, onKindChange: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ModeChip("Cloudflare（推荐）", kind == RemoteTransports.KIND_CF) {
            onKindChange(RemoteTransports.KIND_CF)
        }
        ModeChip("公共 MQTT", kind == RemoteTransports.KIND_MQTT) {
            onKindChange(RemoteTransports.KIND_MQTT)
        }
        ModeChip("ntfy", kind == RemoteTransports.KIND_NTFY) {
            onKindChange(RemoteTransports.KIND_NTFY)
        }
    }
}

private fun relayHint(kind: String): String = when (kind) {
    RemoteTransports.KIND_CF ->
        "填入你自己部署的 Worker 地址（形如 https://xxx.workers.dev）。部署一次即可，仓库里 cloudflare/ 目录有现成代码。留空也能用——会自动走公共中继，但稳定性差一些。两端必须填同一个地址；Worker 只转发密文。"

    RemoteTransports.KIND_NTFY ->
        "默认 ntfy.sh。公共实例单条消息限 4KB，大内容会自动改走附件通道。两端必须填同一个地址。"

    else ->
        "默认 broker.emqx.io（公共、匿名、免费）。两端必须填同一个地址；中继只转发密文。"
}

private fun relayAddressLabel(kind: String): String = when (kind) {
    RemoteTransports.KIND_CF -> "Worker 地址（https://xxx.workers.dev）"
    RemoteTransports.KIND_NTFY -> "ntfy 服务地址"
    else -> "MQTT WebSocket 地址（wss://）"
}

/* ------------------------------- 被控端 ------------------------------- */
@Composable
private fun HostPanel() {
    val context = LocalContext.current
    val hostState by RemoteHost.state.collectAsStateWithLifecycle()
    val hostStats by RemoteHost.stats.collectAsStateWithLifecycle()

    var enabled by remember { mutableStateOf(RemoteSettings.remoteHostEnabled.state) }
    var relayKind by remember {
        mutableStateOf(RemoteTransports.normalizeKind(RemoteSettings.remoteRelayKind.state))
    }
    var relayUrl by remember { mutableStateOf(RemoteSettings.remoteRelayUrl.state) }
    var deviceName by remember { mutableStateOf(RemoteSettings.remoteDeviceName.state) }
    var code by remember { mutableStateOf(RemoteHost.currentCode()) }
    var limitText by remember {
        mutableStateOf(RemoteSettings.remoteFileSizeLimitMb.state.toString())
    }

    // 界面上的码必须永远等于"实际在订阅的那个码"。
    // 否则会出现"用户抄了一个码、服务听的是另一个"的错位，症状就是控制端怎么连都无响应。
    LaunchedEffect(hostState) {
        when (val s = hostState) {
            is RemoteHostState.Waiting -> if (s.code != code) code = s.code
            is RemoteHostState.Connected -> if (s.code != code) code = s.code
            is RemoteHostState.Stopped -> Unit
        }
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    fun applyEnabled(value: Boolean) {
        enabled = value
        RemoteSettings.remoteHostEnabled.save(value)
        if (value) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            RemoteHostService.start(context)
            code = RemoteHost.currentCode()
        } else {
            RemoteHostService.stop(context)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SettingsSection(title = "常驻状态") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("允许被远程控制", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "开启后以前台服务常驻，锁屏也能被连上",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = enabled, onCheckedChange = { applyEnabled(it) })
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text(
                text = when (val s = hostState) {
                    is RemoteHostState.Stopped -> "状态：未启动"
                    is RemoteHostState.Waiting -> "状态：待命，等待控制端接入"
                    is RemoteHostState.Connected -> "状态：已连接 ${s.peer}"
                },
                style = MaterialTheme.typography.bodySmall
            )

            // 链路诊断：控制端报"无响应"时，这里能直接看出被控端到底有没有收到包
            Text(
                text = buildString {
                    append("已收到请求 ${hostStats.received} 次")
                    if (hostStats.lastAt > 0L) {
                        val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                        append("，最近一次 ")
                        append(fmt.format(Date(hostStats.lastAt)))
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            hostStats.relayError?.let { err ->
                Text(
                    text = "中继连接失败：$err",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        SettingsSection(title = "配对码") {
            Text(
                text = code.chunked(3).joinToString(" "),
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "在控制端输入这 6 位数字即可接入。配对码等同于密码，不要外发。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    code = RemoteHost.rotateCode()
                    RemoteSettings.remotePairCode.save(code)
                }) { Text("重新生成") }

                TextButton(onClick = { applyEnabled(true) }) { Text("立即上线") }
            }
        }

        SettingsSection(title = "中继节点") {
            RelayTypeRow(relayKind) { newKind ->
                relayKind = newKind
                RemoteSettings.remoteRelayKind.save(newKind)
                //切换类型时地址要跟着换，否则会拿 ntfy 的地址去连 MQTT
                val fixed = RemoteTransports.normalizeUrl(newKind, relayUrl)
                relayUrl = fixed
                RemoteSettings.remoteRelayUrl.save(fixed)
                if (enabled) {
                    RemoteHostService.stop(context)
                    RemoteHostService.start(context)
                }
            }
            OutlinedTextField(
                value = relayUrl,
                onValueChange = { relayUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(relayAddressLabel(relayKind)) },
                singleLine = true
            )
            Text(
                text = relayHint(relayKind),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (RemoteTransports.isFallback(relayKind, relayUrl)) {
                Text(
                    text = "没填 Worker 地址，当前自动走公共 MQTT 中继（能用，但可能不稳定）。" +
                        "两台设备都留空可以互通；只要有一台填了地址，另一台必须填同一个，否则两端不在同一条通道上。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            TextButton(onClick = {
                RemoteSettings.remoteRelayKind.save(relayKind)
                RemoteSettings.remoteRelayUrl.save(
                    RemoteTransports.normalizeUrl(relayKind, relayUrl)
                )
                if (enabled) {
                    RemoteHostService.stop(context)
                    RemoteHostService.start(context)
                }
            }) { Text("保存并重连") }

            if (hostStats.transport.isNotBlank()) {
                Text(
                    text = "当前通道：${hostStats.transport}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        SettingsSection(title = "本机标识") {
            OutlinedTextField(
                value = deviceName,
                onValueChange = {
                    deviceName = it
                    RemoteSettings.remoteDeviceName.save(it)
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("控制端会看到的名字") },
                singleLine = true
            )
        }

        SettingsSection(title = "文件访问权限") {
            val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
                    Environment.isExternalStorageManager()
            Text(
                text = if (granted) "已获得「所有文件访问」权限，可浏览下载目录等位置。"
                else "未授权，此时只能访问启动器自己的目录；要浏览下载文件夹必须授权。",
                style = MaterialTheme.typography.bodySmall
            )
            if (!granted) {
                Button(onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                            .setData(Uri.parse("package:${context.packageName}"))
                        runCatching { context.startActivity(intent) }
                    }
                }) { Text("去授权") }
            }
        }

        SettingsSection(title = "传输上限") {
            OutlinedTextField(
                value = limitText,
                onValueChange = { input ->
                    limitText = input.filter { it.isDigit() }.take(2)
                    limitText.toIntOrNull()?.let { RemoteSettings.remoteFileSizeLimitMb.save(it) }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("单个文件上限（MB）") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

/* ------------------------------- 控制端 ------------------------------- */

@Composable
private fun ClientPanel() {
    var code by remember { mutableStateOf("") }
    var client by remember { mutableStateOf<RemoteClient?>(null) }
    var message by remember { mutableStateOf("") }
    var relayKind by remember {
        mutableStateOf(RemoteTransports.normalizeKind(RemoteSettings.remoteRelayKind.state))
    }
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        onDispose { client?.close() }
    }

    val activeClient = client
    if (activeClient == null) {
        ConnectForm(
            code = code,
            message = message,
            kind = relayKind,
            onKindChange = { relayKind = it },
            onCodeChange = { code = it },
            onConnect = {
                val normalized = RemoteCrypto.normalizeCode(code)
                if (normalized.length != 6) {
                    message = "配对码必须是 6 位数字"
                    return@ConnectForm
                }
                message = "正在连接…"
                val relayUrl = RemoteSettings.remoteRelayUrl.state
                val deviceName = RemoteSettings.remoteDeviceName.state.ifBlank {
                    "${Build.MANUFACTURER} ${Build.MODEL}"
                }
                val newClient = RemoteClient(normalized, relayUrl, deviceName, relayKind)
                client = newClient
                scope.launch {
                    val result = newClient.connect()
                    message = result.fold(
                        onSuccess = { "已连接" },
                        onFailure = { it.message ?: "连接失败" }
                    )
                }
            }
        )
        return
    }

    val state by activeClient.state.collectAsStateWithLifecycle()

    when (val current = state) {
        is RemoteClientState.Online -> ConnectedPanel(
            client = activeClient,
            device = current.device,
            onDisconnect = {
                activeClient.close()
                client = null
                message = ""
            }
        )
        is RemoteClientState.Offline -> ConnectForm(
            code = code,
            message = current.reason,
            kind = relayKind,
            onKindChange = { relayKind = it },
            onCodeChange = { code = it },
            onConnect = {
                client = null
                message = ""
            }
        )
        else -> ConnectForm(
            code = code,
            message = message,
            kind = relayKind,
            onKindChange = { relayKind = it },
            onCodeChange = { code = it },
            onConnect = { message = "请稍候…" }
        )
    }
}

@Composable
private fun ConnectForm(
    code: String,
    message: String,
    kind: String,
    onKindChange: (String) -> Unit,
    onCodeChange: (String) -> Unit,
    onConnect: () -> Unit,
) {
    var relay by remember { mutableStateOf(RemoteSettings.remoteRelayUrl.state) }
    var diagnosing by remember { mutableStateOf(false) }
    var diagLines by remember { mutableStateOf<List<String>>(emptyList()) }
    val diagScope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SettingsSection(title = "连接被控端") {
            OutlinedTextField(
                value = code,
                onValueChange = { onCodeChange(RemoteCrypto.normalizeCode(it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("6 位配对码") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )
            Text(
                text = "配对码在被控端的「远程控制 - 本机作为被控端」页面可以看到。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (message.isNotBlank()) {
                Text(text = message, style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = onConnect) { Text("连接") }
        }

        SettingsSection(title = "中继节点") {
            RelayTypeRow(kind) { newKind ->
                onKindChange(newKind)
                val fixed = RemoteTransports.normalizeUrl(newKind, relay)
                relay = fixed
                RemoteSettings.remoteRelayKind.save(newKind)
                RemoteSettings.remoteRelayUrl.save(fixed)
            }
            OutlinedTextField(
                value = relay,
                onValueChange = { input ->
                    relay = input
                    RemoteSettings.remoteRelayUrl.save(input.trim())
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(relayAddressLabel(kind)) },
                singleLine = true
            )
            Text(
                text = relayHint(kind),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("连不上时先跑一次自检", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "依次验证：能否连上中继 → 能否发布 → 能否从订阅收回",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(
                    enabled = !diagnosing,
                    onClick = {
                        diagnosing = true
                        diagLines = emptyList()
                        diagScope.launch {
                            val target = RemoteTransports.normalizeUrl(kind, relay)
                            diagLines = runCatching { RemoteDiagnostics.run(kind, target) }
                                .getOrElse { listOf("自检异常：${it.message ?: "未知错误"}") }
                            diagnosing = false
                        }
                    }
                ) { Text(if (diagnosing) "检测中…" else "开始自检") }
            }

            if (diagLines.isNotEmpty()) {
                diagLines.forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectedPanel(
    client: RemoteClient,
    device: String,
    onDisconnect: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "已连接 $device",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onDisconnect) { Text("断开") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeChip("设置", tab == 0) { tab = 0 }
            ModeChip("文件", tab == 1) { tab = 1 }
            ModeChip("配置", tab == 2) { tab = 2 }
        }

        Spacer(Modifier.height(8.dp))

        when (tab) {
            0 -> RemoteSettingsPanel(client)
            1 -> RemoteFilesPanel(client)
            else -> RemoteConfigPanel(client)
        }
    }
}

/* ------------------------------- 设置面板 ------------------------------- */

@Composable
private fun RemoteSettingsPanel(client: RemoteClient) {
    val scope = rememberCoroutineScope()
    var sections by remember { mutableStateOf<JsonArray?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var reloadFlag by remember { mutableIntStateOf(0) }

    LaunchedEffect(reloadFlag) {
        loading = true
        val response = client.request(RemoteCmd.SETTINGS_LIST, null, 30_000L)
        if (response == null) {
            error = "读取设置失败：被控端无响应"
        } else if (!response.ok) {
            error = "读取设置失败：${response.err}"
        } else {
            sections = response.body?.get("sections")?.jsonArray
            error = ""
        }
        loading = false
    }

    if (loading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("正在读取被控端设置…")
        }
        return
    }

    if (error.isNotBlank()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(error)
                TextButton(onClick = { reloadFlag++ }) { Text("重试") }
            }
        }
        return
    }

    val sectionList = sections ?: JsonArray(emptyList<JsonElement>())

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        sectionList.forEach { sectionElement ->
            val section = sectionElement.jsonObject
            val sectionName = section["name"]?.jsonPrimitive?.contentOrNull ?: ""
            val itemList = section["items"]?.jsonArray ?: return@forEach

            item(key = "header-$sectionName") {
                Text(
                    text = sectionName,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
                )
            }

            items(itemList.size) { index ->
                val item = itemList[index].jsonObject
                val itemKey = item["key"]?.jsonPrimitive?.contentOrNull
                if (itemKey != null) {
                    SettingRow(
                        item = item,
                        onApply = { value ->
                            scope.launch {
                                client.request(
                                    RemoteCmd.SETTINGS_APPLY,
                                    buildJsonObject {
                                        put("key", itemKey)
                                        put("value", value)
                                    },
                                    20_000L
                                )
                                reloadFlag++
                            }
                        },
                        onReset = {
                            scope.launch {
                                client.request(
                                    RemoteCmd.SETTINGS_RESET,
                                    buildJsonObject { put("key", itemKey) },
                                    20_000L
                                )
                                reloadFlag++
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingRow(
    item: JsonObject,
    onApply: (JsonElement) -> Unit,
    onReset: () -> Unit,
) {
    val key = item["key"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val label = item["label"]?.jsonPrimitive?.contentOrNull ?: key
    val type = item["type"]?.jsonPrimitive?.contentOrNull ?: "string"
    val editable = item["editable"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
    val value = item["value"] ?: JsonNull
    var menuOpen by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(all = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = label, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = key,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (!editable) {
                    Text(text = value.toString(), style = MaterialTheme.typography.labelSmall)
                } else {
                    when (type) {
                        "bool" -> Switch(
                            checked = (value as? JsonPrimitive)?.contentOrNull == "true",
                            onCheckedChange = { onApply(JsonPrimitive(it)) }
                        )
                        "enum" -> Box {
                            TextButton(onClick = { menuOpen = true }) {
                                Text((value as? JsonPrimitive)?.contentOrNull ?: "选择")
                            }
                            DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false }
                            ) {
                                item["options"]?.jsonArray?.forEach { option ->
                                    val name = option.jsonPrimitive.contentOrNull
                                    if (name != null) {
                                        DropdownMenuItem(
                                            text = { Text(name) },
                                            onClick = {
                                                menuOpen = false
                                                onApply(JsonPrimitive(name))
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        "int", "int?", "long", "float" -> NumberEditor(value, onApply)
                        "offset" -> OffsetEditor(value, onApply)
                        else -> TextEditor(value, onApply)
                    }
                }
            }

            if (editable) {
                TextButton(onClick = onReset) {
                    Text("恢复默认", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

/**
 * 数字编辑：失焦时才提交，避免每敲一个字符就发一次网络请求。
 */
@Composable
private fun NumberEditor(value: JsonElement, onApply: (JsonElement) -> Unit) {
    val initial = (value as? JsonPrimitive)?.contentOrNull.orEmpty()
    var text by remember(value) { mutableStateOf(initial) }

    fun commit() {
        val parsed = text.toDoubleOrNull() ?: return
        onApply(
            if (text.contains('.')) JsonPrimitive(parsed.toFloat())
            else JsonPrimitive(parsed.toLong())
        )
    }

    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input.filter { it.isDigit() || it == '-' || it == '.' }.take(12)
        },
        modifier = Modifier
            .width(120.dp)
            .onFocusChanged { if (!it.isFocused && text != initial) commit() },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
}

@Composable
private fun TextEditor(value: JsonElement, onApply: (JsonElement) -> Unit) {
    val initial = when (value) {
        is JsonArray -> value.joinToString(", ") { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }
        is JsonPrimitive -> value.contentOrNull.orEmpty()
        else -> ""
    }
    var text by remember(value) { mutableStateOf(initial) }

    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        modifier = Modifier
            .width(180.dp)
            .onFocusChanged {
                if (!it.isFocused && text != initial) onApply(JsonPrimitive(text))
            },
        singleLine = true
    )
}

@Composable
private fun OffsetEditor(value: JsonElement, onApply: (JsonElement) -> Unit) {
    val obj = value as? JsonObject
    val initialX = obj?.get("x")?.jsonPrimitive?.contentOrNull.orEmpty()
    val initialY = obj?.get("y")?.jsonPrimitive?.contentOrNull.orEmpty()
    var x by remember(value) { mutableStateOf(initialX) }
    var y by remember(value) { mutableStateOf(initialY) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = x,
            onValueChange = { x = it },
            modifier = Modifier
                .width(70.dp)
                .onFocusChanged {
                    if (!it.isFocused && x != initialX) commitOffset(x, y, onApply)
                },
            singleLine = true
        )
        Spacer(Modifier.width(6.dp))
        OutlinedTextField(
            value = y,
            onValueChange = { y = it },
            modifier = Modifier
                .width(70.dp)
                .onFocusChanged {
                    if (!it.isFocused && y != initialY) commitOffset(x, y, onApply)
                },
            singleLine = true
        )
    }
}

private fun commitOffset(x: String, y: String, onApply: (JsonElement) -> Unit) {
    val fx = x.toFloatOrNull() ?: return
    val fy = y.toFloatOrNull() ?: return
    onApply(buildJsonObject {
        put("x", fx)
        put("y", fy)
    })
}

/* ------------------------------- 文件面板 ------------------------------- */

private data class RemoteEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
)

@Composable
private fun RemoteFilesPanel(client: RemoteClient) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var currentPath by remember { mutableStateOf("") }
    var parentPath by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf(listOf<RemoteEntry>()) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf("") }
    var reloadFlag by remember { mutableIntStateOf(0) }
    var pendingDelete by remember { mutableStateOf<RemoteEntry?>(null) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            busy = "正在上传…"
            scope.launch {
                val bytes = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    }.getOrNull()
                }
                if (bytes == null) {
                    busy = "读取本地文件失败"
                    return@launch
                }
                val name = queryDisplayName(context, uri) ?: "upload.bin"
                val target = if (currentPath.endsWith("/")) "$currentPath$name" else "$currentPath/$name"
                val result = client.pushFile(target, bytes)
                busy = result.fold(
                    onSuccess = { "已上传到 $target" },
                    onFailure = { "上传失败：${it.message}" }
                )
                reloadFlag++
            }
        }
    }

    LaunchedEffect(reloadFlag) {
        val body = buildJsonObject {
            put("path", currentPath)
            put("count", 500)
        }
        val response = client.request(RemoteCmd.FS_LIST, body, 30_000L)
        if (response == null) {
            error = "被控端无响应"
        } else if (!response.ok) {
            error = "列目录失败：${response.err}"
        } else {
            val payload = response.body
            error = payload?.get("error")?.jsonPrimitive?.contentOrNull.orEmpty()
            currentPath = payload?.get("path")?.jsonPrimitive?.contentOrNull ?: currentPath
            parentPath = payload?.get("parent")?.jsonPrimitive?.contentOrNull ?: ""
            entries = payload?.get("entries")?.jsonArray?.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val path = obj["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                RemoteEntry(
                    name = name,
                    path = path,
                    isDir = obj["dir"]?.jsonPrimitive?.contentOrNull == "true",
                    size = obj["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
                )
            } ?: emptyList()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                if (parentPath.isNotBlank() && parentPath != currentPath) {
                    currentPath = parentPath
                    reloadFlag++
                }
            }) { Text("上级") }

            Text(
                text = currentPath,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )

            TextButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("上传") }
            TextButton(onClick = { reloadFlag++ }) { Text("刷新") }
        }

        if (busy.isNotBlank()) {
            Text(busy, style = MaterialTheme.typography.labelSmall)
        }
        if (error.isNotBlank()) {
            Text(error, style = MaterialTheme.typography.labelSmall)
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(entries.size) { index ->
                val entry = entries[index]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (entry.isDir) {
                                currentPath = entry.path
                                reloadFlag++
                            } else {
                                busy = "正在下载 ${entry.name}…"
                                scope.launch {
                                    val result = client.pullFile(entry.path)
                                    busy = result.fold(
                                        onSuccess = { bytes ->
                                            runCatching {
                                                val dir = File(downloadDir(), "zlremote")
                                                    .apply { mkdirs() }
                                                val file = File(dir, entry.name)
                                                file.writeBytes(bytes)
                                                file.absolutePath
                                            }.fold(
                                                onSuccess = { "已保存到 $it" },
                                                onFailure = { "保存失败：${it.message}" }
                                            )
                                        },
                                        onFailure = { "下载失败：${it.message}" }
                                    )
                                }
                            }
                        }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = if (entry.isDir) "📁" else "📄", modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(entry.name, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                        if (!entry.isDir) {
                            Text(
                                text = formatSize(entry.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    TextButton(onClick = { pendingDelete = entry }) {
                        Text("删除", style = MaterialTheme.typography.labelSmall)
                    }
                }
                HorizontalDivider()
            }
        }
    }

    val target = pendingDelete
    if (target != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除确认") },
            text = { Text("将删除被控端上的 ${target.path}；如果是目录，会连同内容一起删除。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch {
                        client.request(
                            RemoteCmd.FS_DELETE,
                            buildJsonObject { put("path", target.path) },
                            30_000L
                        )
                        reloadFlag++
                    }
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
}

/* ------------------------------- 配置面板 ------------------------------- */

@Composable
private fun RemoteConfigPanel(client: RemoteClient) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    var pendingImport by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            }.getOrNull()
            if (text.isNullOrBlank()) {
                status = "读取配置文件失败"
            } else {
                pendingImport = text
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsSection(title = "整包配置") {
            Text(
                text = "原版所有设置都散存在 MMKV 里，没有一份完整配置文件。" +
                        "这里导出时会遍历全部设置项，生成一份 JSON 快照，可用于备份或搬到另一台设备。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    status = "正在导出…"
                    scope.launch {
                        val result = client.exportConfig()
                        status = result.fold(
                            onSuccess = { snapshot ->
                                runCatching {
                                    val dir = File(downloadDir(), "zlremote").apply { mkdirs() }
                                    val file = File(
                                        dir,
                                        "remote-config-${System.currentTimeMillis()}.json"
                                    )
                                    file.writeText(snapshot.toString())
                                    file.absolutePath
                                }.fold(
                                    onSuccess = { "已导出到 $it" },
                                    onFailure = { "写入本地失败：${it.message}" }
                                )
                            },
                            onFailure = { "导出失败：${it.message}" }
                        )
                    }
                }) { Text("导出到本机") }

                Button(onClick = { picker.launch(arrayOf("application/json", "*/*")) }) {
                    Text("选择文件导入")
                }
            }

            if (status.isNotBlank()) {
                Text(status, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    val raw = pendingImport
    if (raw != null) {
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("覆盖导入配置") },
            text = { Text("将把这份快照里的每一项写入被控端，未包含的项保持不变，此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingImport = null
                    status = "正在导入…"
                    scope.launch {
                        val snapshot = runCatching {
                            GLOBAL_JSON.parseToJsonElement(raw).jsonObject
                        }.getOrNull()
                        if (snapshot == null) {
                            status = "文件不是合法的配置快照"
                            return@launch
                        }
                        val result = client.importConfig(snapshot)
                        status = result.fold(
                            onSuccess = { "已导入 $it 项设置" },
                            onFailure = { "导入失败：${it.message}" }
                        )
                    }
                }) { Text("导入") }
            },
            dismissButton = {
                TextButton(onClick = { pendingImport = null }) { Text("取消") }
            }
        )
    }
}

/* --------------------------------- 工具 --------------------------------- */

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(all = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

private fun downloadDir(): File =
    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

private fun queryDisplayName(context: Context, uri: Uri): String? {
    return runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) cursor.getString(index) else null
        }
    }.getOrNull() ?: uri.lastPathSegment
}

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    return String.format(Locale.US, "%.1f MB", kb / 1024.0)
}
