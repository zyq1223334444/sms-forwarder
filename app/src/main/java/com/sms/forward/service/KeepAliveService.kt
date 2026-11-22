package com.sms.forward.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.sms.forward.R
import com.sms.forward.util.ShizukuGuard

/**
 * 可选前台保活服务（M6 填充完整实现；2026-11-22 增强 Shizuku 守护）。
 *
 * 定位：增强后台存活率，不作为可靠性主方案。
 * - 仅在 App 打开时启动（规避 Android 12+ 后台启动 FGS 限制）；
 * - Android 15 对 dataSync 前台服务有时长限制，接受其影响；
 * - 通知权限被拒时仅通知不显示，不影响核心收发链路。
 * - 服务存活期间每 5 分钟执行一次 Shizuku 守护（FGS 内定时不受 Doze 闹钟延迟影响；
 *   AlarmManager 周期守护保留为"服务被杀后"的兜底）。
 */
class KeepAliveService : Service() {

    private val guardHandler by lazy { Handler(Looper.getMainLooper()) }
    private val guardTask = object : Runnable {
        override fun run() {
            ShizukuGuard.checkAndProtect(this@KeepAliveService)
            guardHandler.postDelayed(this, GUARD_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat()
        // 启动 1 分钟后开始第一次守护，之后每 5 分钟一次（内部异步执行，不阻塞主线程）
        guardHandler.postDelayed(guardTask, 60_000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        guardHandler.removeCallbacks(guardTask)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat() {
        val channelId = "keepalive"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(channelId, getString(R.string.channel_keepalive), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.keepalive_notification_text))
            .setOngoing(true)
            .build()
        startForeground(1, notification)
    }

    companion object {
        private const val GUARD_INTERVAL_MS = 5 * 60 * 1000L

        fun start(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KeepAliveService::class.java))
        }
    }
}
