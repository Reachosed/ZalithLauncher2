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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.utils.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val TAG = "RemoteHostService"

private const val CHANNEL_ID = "remote.host"
private const val NOTIFICATION_ID = 0x5A21

/**
 * 被控端前台服务。
 *
 * 之所以必须是前台服务：安卓在锁屏/后台一段时间后会冻结甚至杀掉普通进程，
 * 而"被控端随时能被连上"这个体验的前提就是进程活着。
 * 代价是通知栏常驻一条通知——这既是系统要求，也正好让用户随时知道远控开着。
 */
class RemoteHostService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundSafely()
        RemoteHost.start()
        observeState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        //从最近任务划掉后系统会在稍后重建，保证仍然在线
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        RemoteHost.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun observeState() {
        scope.launch {
            RemoteHost.state.collectLatest { state ->
                runCatching { notifyState(state) }
            }
        }
    }

    private fun notifyState(state: RemoteHostState) {
        val (title, text) = when (state) {
            is RemoteHostState.Stopped ->
                "远程控制已停止" to "服务即将退出"
            is RemoteHostState.Waiting ->
                "远程控制待命中" to "配对码 ${state.code} · 等待控制端接入"
            is RemoteHostState.Connected ->
                "远程控制已连接" to "控制端：${state.peer} · 配对码 ${state.code}"
        }

        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(title, text))
    }

    private fun startForegroundSafely() {
        val notification = buildNotification("远程控制待命中", "正在连接中继节点…")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            //系统在受限状态下会拒绝前台身份，此时保活没有意义，直接收摊
            Logger.error(TAG, "Failed to enter foreground", e)
            stopSelf()
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val pending = intent?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setShowWhen(false)
            .apply { pending?.let { setContentIntent(it) } }
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "远程控制",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "被控端在线状态与配对码"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RemoteHostService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RemoteHostService::class.java))
        }
    }
}
