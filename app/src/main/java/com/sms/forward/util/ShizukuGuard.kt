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
 * 保活原理与边界（2026-11-22 真机复测）：
 * ① shizuku_server（uid 2000 shell）的 oom_score_adj 可被 shell 写入 -1000，
 *    LMKD/内核 OOM 不再回收它 —— 有效；
 * ② 但 MIUI 的「上滑清理 / 一键清理」（SwipeUpClean，系统退出记录 reason=13
 *    OTHER KILLS BY SYSTEM）不走 LMKD，**无视 oom_score_adj**，能直接把 Shizuku
 *    app 进程杀掉，shizuku_server 随之退出。实测 shell 无法写其他 uid 应用的
 *    /proc/<pid>/oom_score_adj（Permission denied），代码无法给 Shizuku app
 *    进程加防杀锁 —— 因此**必须给 Shizuku 在最近任务加锁**（系统级豁免，
 *    这是唯一能阻止上滑清理的办法）。
 * ③ 可自动执行的有效手段：`am set-standby-bucket <pkg> active`（实测可执行），
 *    把 Shizuku 与自身提升到 active 待机桶，显著降低被判为可清理的概率；
 *    并把 Shizuku 加进 doze 白名单（幂等）。
 *
 * Shizuku 停止后应用没有 shell 通道、无法自行重启它：发出常驻通知提醒，
 * 通知文案指导「加锁 + 无线调试重新启动（无需电脑）」；恢复后通知自动消失。
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

    /** 立即执行一次守护（异步，避免广播主线程被 exec 阻塞）：Shizuku 活着→保活；死了→提醒 */
    fun checkAndProtect(context: Context) {
        val app = context.applicationContext
        Thread {
            runCatching { doCheckAndProtect(app) }
        }.apply { isDaemon = true }.start()
    }

    private fun doCheckAndProtect(context: Context) {
        val settings = MailConfig.load(context)
        if (!settings.autoNetwork) return

        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (alive) {
            // ① 关键：shizuku_server 标为不可被 LMKD 回收（pid 动态获取，全机型通用）
            val pinned = execViaShizuku(
                "echo -1000 > /proc/\$(pidof shizuku_server)/oom_score_adj"
            )
            // ② 辅助：待机桶提升为 active + doze 白名单（降低被系统判为可清理的概率；幂等、失败无害）
            execViaShizuku(
                "am set-standby-bucket moe.shizuku.privileged.api active 2>/dev/null; " +
                    "am set-standby-bucket com.sms.forward active 2>/dev/null; " +
                    "cmd deviceidle whitelist +moe.shizuku.privileged.api >/dev/null 2>&1; true"
            )
            if (pinned) {
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
            .setContentTitle("Shizuku 已停止，自动开网暂时失效")
            .setContentText(
                "点按打开 Shizuku。恢复方法：①最近任务里给 Shizuku 加锁（防上滑清理）；" +
                    "②开发者选项开「无线调试」→ Shizuku 内按「通过无线调试启动」，无需电脑。"
            )
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "点按打开 Shizuku。恢复方法：\n" +
                        "① 最近任务里给 Shizuku 加锁（防止上滑清理杀掉它，这一步最关键）；\n" +
                        "② 开发者选项打开「无线调试」→ 打开 Shizuku 应用，按「通过无线调试启动」重新激活（无需电脑）。\n" +
                        "恢复后本通知自动消失。"
                )
            )
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
