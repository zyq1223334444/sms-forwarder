package com.sms.forward.util

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 实时链路探针：记录 SMS_RECEIVED 广播的到达情况。
 *
 * 目的：把"短信到底有没有实时送到应用"变成可观测数据。
 * 应用只依赖系统广播收短信；部分 ROM（MIUI 的「短信验证码安全保护」、
 * 后台限制/自启动关闭）会拦截或延迟广播，此时只能靠补偿扫描补发。
 * 状态页据此显示"实时链路：正常 / 长时间无广播"，用户不必靠猜。
 *
 * 只存毫秒时间戳与当日计数，不含任何短信内容。
 */
object SmsLinkProbe {

    private const val KEY_LAST_TS = "link_last_broadcast_ts"
    private const val KEY_DAY = "link_broadcast_day"
    private const val KEY_COUNT = "link_broadcast_count"

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /** 每次收到短信广播调用一次（在入库流程开始前调用，确保解析失败也留有痕迹） */
    fun recordBroadcast(context: Context) {
        runCatching {
            val now = System.currentTimeMillis()
            val day = today()
            val storedDay = SecurePrefs.getString(context, KEY_DAY, null)
            val count = if (storedDay == day) SecurePrefs.getInt(context, KEY_COUNT, 0) else 0
            SecurePrefs.putLong(context, KEY_LAST_TS, now)
            SecurePrefs.putString(context, KEY_DAY, day)
            SecurePrefs.putInt(context, KEY_COUNT, count + 1)
        }
    }

    data class Snapshot(
        val lastBroadcastAt: Long,
        val todayCount: Int
    )

    fun snapshot(context: Context): Snapshot = runCatching {
        val count = if (SecurePrefs.getString(context, KEY_DAY, null) == today()) {
            SecurePrefs.getInt(context, KEY_COUNT, 0)
        } else 0
        Snapshot(SecurePrefs.getLong(context, KEY_LAST_TS, 0L), count)
    }.getOrDefault(Snapshot(0L, 0))

    /** 人类可读的实时链路描述 */
    fun describe(context: Context): String {
        val s = snapshot(context)
        if (s.lastBroadcastAt <= 0L) {
            return "实时链路：尚未收到过短信广播（可能被系统拦截，仅能靠补偿扫描）"
        }
        val minutes = (System.currentTimeMillis() - s.lastBroadcastAt) / 60_000L
        val ago = when {
            minutes < 1 -> "刚刚"
            minutes < 60 -> "${minutes} 分钟前"
            minutes < 60 * 24 -> "${minutes / 60} 小时前"
            else -> "${minutes / (60 * 24)} 天前"
        }
        return "实时链路：上次广播 $ago，今日 ${s.todayCount} 条"
    }
}
