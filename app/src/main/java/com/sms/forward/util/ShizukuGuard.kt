package com.sms.forward.util

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.sms.forward.R
import com.sms.forward.mail.MailConfig
import com.sms.forward.ui.ShizukuGuardReceiver
import rikka.shizuku.Shizuku

/**
 * Shizuku 守护（每 5 分钟一次，仅当"自动开网络"开关开启时运行）。
 *
 * 保活原理（2026-10-25 真机验证）：shizuku_server 以 shell(uid 2000) 身份运行，
 * shell 有权修改自己进程的 /proc/<pid>/oom_score_adj。把它设为 -1000
 * （OOM_SCORE_ADJ_MIN，系统最低可杀优先级）后 LMKD 不再回收该进程；
 * 实测写入后持续保持（60s+ 未回改）。每 5 分钟重写一次，即使被系统重算
 * 也会在下一周期恢复，从而让 Shizuku 常驻、自动开网络功能持续可用。
 *
 * 兜底：若 Shizuku 已完全停止（binder 不可用，无法执行保活命令——应用无 shell
 * 通道可用来重启它），则发出常驻通知提醒用户重新激活；Shizuku 恢复后自动清除。
 * 手机重启后 Shizuku 失效，需重新执行 adb 激活命令（重启后本守护检测到 binder
 * 不可用会持续提醒，直到重新激活）。
 */
object ShizukuGuard {

    const val ACTION_CHECK = "com.sms.forward.action.SHIZUKU_GUARD_CHECK"
    const val NOTIFICATION_ID = 2001
    private const val CHANNEL_ID = "shizuku_guard"
    private const val PERIOD_MS = 5 * 60 * 1000L

    /** 注册 5 分钟周期守护（内部判断"自动开网络"开关；开关关闭时自动取消） */
    fun schedule(context: Context) {
        val enabled = MailConfig.load(context).autoNetwork
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, ShizukuGuardReceiver::class.java).setAction(ACTION_CHECK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (!enabled) {
            am.cancel(pi)
            return
        }
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + PERIOD_MS, PERIOD_MS, pi)
    }

    /** 立即执行一次守护（供广播接收器调用）：Shizuku 活着→保活；死了→提醒 */
    fun checkAndProtect(context: Context) {
        val settings = MailConfig.load(context)
        if (!settings.autoNetwork) return

        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (alive) {
            // 保活：将 shizuku_server 标为不可被杀（pid 动态获取，全机型通用）
            val protected = execViaShizuku("echo -1000 > /proc/\$(pidof shizuku_server)/oom_score_adj")
            if (protected) {
                // 恢复/正常运行：清除守护通知（如之前发过）
                nm.cancel(NOTIFICATION_ID)
            }
            return
        }

        // Shizuku 已停止：发出常驻通知（固定 ID 覆盖，不刷屏）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Shizuku 守护",
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            runCatching {
                context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
            }.getOrNull() ?: Intent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Shizuku 已停止运行")
            .setContentText("自动开网络功能暂时失效，点按打开 Shizuku；需重新激活（连电脑执行激活命令）后恢复")
            .setContentIntent(contentIntent)
            .setAutoCancel(false)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        nm.notify(NOTIFICATION_ID, notification)
    }

    /** 通过 Shizuku 以 shell 权限执行命令（同 NetworkAutoToggler 的实现，反射调用 private newProcess） */
    private fun execViaShizuku(command: String): Boolean {
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            val process = method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
            val exit = process.waitFor()
            exit == 0
        } catch (e: Exception) {
            false
        }
    }
}
