package com.sms.forward.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.sms.forward.sim.SimSlotDetector
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设备快照（v1.4.0）：发送邮件时附带「当前电量、当前位置、收到短信那张卡的本机号码（前3后4）」。
 *
 * 设计原则：全部容错，任一信息取不到就显示原因（未授权/无缓存/运营商未写号），
 * 绝不影响邮件发送本身。
 * - 电量：BatteryManager 属性读取，无需注册监听；
 * - 位置：LocationManager 的最近已知位置（GPS/网络/被动取最新），不做持续定位、不耗电；
 *   需 ACCESS_COARSE/FINE_LOCATION（设置页可授权）；
 * - 本机号码：API 33+ 按 subscriptionId 取该卡号码（SubscriptionManager.getPhoneNumber），
 *   低版本取默认卡；需 READ_PHONE_NUMBERS 权限；号码按"前3后4"脱敏展示。
 */
object DeviceSnapshot {

    private const val TAG = "DeviceSnapshot"

    /** 生成邮件中的设备状态段（以标题行开头，调用方 appendLine 后无需额外换行） */
    fun capture(context: Context, subscriptionId: Int?, simSlot: Int?): String {
        val battery = batteryPercent(context)
        val location = locationText(context)
        val number = simNumberMasked(context, subscriptionId, simSlot)
        // 采集摘要日志（位置不打具体坐标，号码为前3后4脱敏），便于排查"邮件没带设备状态"
        val locationOk = !(location.startsWith("未知") || location.startsWith("未授权"))
        Log.i(
            TAG,
            "设备状态采集: 电量=${battery}% 位置=${if (locationOk) "已获取" else location} 号码=$number subId=$subscriptionId slot=$simSlot"
        )
        return buildString {
            appendLine("---- 设备状态 ----")
            appendLine("电量: ${battery}%")
            appendLine("位置: $location")
            appendLine("本机号码: $number")
        }.trimEnd()
    }

    /** 电量百分比（0..100），取不到返回"未知" */
    private fun batteryPercent(context: Context): String = runCatching {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (pct in 0..100) pct.toString() else "未知"
    }.getOrDefault("未知")

    /** 最近一次已知位置（多 provider 取最新）；无权限或无缓存时返回说明文本 */
    private fun locationText(context: Context): String {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return "未授权定位（可在设置页开启）"

        return runCatching {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            var best: Location? = null
            for (provider in listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )) {
                val loc = runCatching { lm.getLastKnownLocation(provider) }.getOrNull() ?: continue
                val current = best
                if (current == null || loc.time > current.time) best = loc
            }
            val loc = best
            if (loc == null) {
                "未知（无定位缓存；先打开地图定位一次即可）"
            } else {
                val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(loc.time))
                String.format(
                    Locale.US,
                    "%.5f, %.5f（±%.0fm，定位于 %s）",
                    loc.latitude, loc.longitude, loc.accuracy, ts
                )
            }
        }.getOrDefault("未知")
    }

    /** 收到短信那张卡的本机号码，按"前3后4"脱敏；取不到返回"未知"+原因 */
    private fun simNumberMasked(context: Context, subscriptionId: Int?, simSlot: Int?): String {
        val slot = simSlot?.takeIf { it >= 0 }
        val prefix = if (slot != null) "卡${slot + 1}：" else ""
        val detected = detectedNumber(context, slot, subscriptionId)
        val configured = configuredNumber(context, slot)
        val iccid = simIccId(context, subscriptionId, simSlot)
        // 诊断：区分"系统自动读取/设置页配置/ICCID"三路是否可用
        Log.i(
            TAG,
            "号码诊断: 系统读取=${detected?.let { maskNumber(it) } ?: "无"} " +
                "配置=${configured?.let { maskNumber(it) } ?: "无"} ICCID=${iccid ?: "null"}"
        )
        // 1) 系统按卡自动读取的号码优先（准确，无需用户填写）
        if (detected != null) return prefix + maskNumber(detected)
        // 2) 设置页为该卡保存的号码（系统读不到时的兜底/手工修正）
        if (configured != null) return prefix + maskNumber(configured)
        // 3) 再用 ICCID 尾号 4 位区分卡1/卡2
        if (iccid != null) {
            return prefix + "号码未写入（ICCID尾号 ${iccid.takeLast(4)}）"
        }
        return prefix + "未知（运营商未写入号码，可在设置页填写本机号码）"
    }

    /** 系统自动读取：优先按卡槽从订阅信息里取该卡号码；卡未知时取任一可读号码 */
    private fun detectedNumber(context: Context, slot: Int?, subscriptionId: Int?): String? {
        val slots = runCatching { SimSlotDetector.detect(context) }.getOrNull().orEmpty()
        val matched = when {
            slot != null -> slots.firstOrNull { it.slot == slot }
            else -> slots.firstOrNull { it.phoneNumber != null }
        }
        val fromSlot = SimSlotDetector.normalizeCnNumber(matched?.phoneNumber)
        if (!fromSlot.isNullOrBlank()) return fromSlot
        // 订阅列表不可读时，用广播里的 subId 直接读该卡号码
        if (subscriptionId != null && subscriptionId >= 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val sm = context.getSystemService(SubscriptionManager::class.java)
            val raw = runCatching { sm?.getPhoneNumber(subscriptionId) }.getOrNull()
            return SimSlotDetector.normalizeCnNumber(raw)
        }
        return null
    }

    /** 读取设置页为指定卡保存的号码；卡未知时依次试用卡1、卡2，再退回旧版单值 */
    private fun configuredNumber(context: Context, slot: Int?): String? {
        val slots = if (slot != null) listOf(slot) else listOf(0, 1)
        for (s in slots) {
            val v = runCatching {
                SecurePrefs.getString(context, SecurePrefs.KEY_OWNER_NUMBER_SLOT + s)
            }.getOrNull()?.filter { it.isDigit() }
            if (!v.isNullOrBlank() && v.length >= 7) return v
        }
        return runCatching {
            SecurePrefs.getString(context, SecurePrefs.KEY_OWNER_NUMBER)
        }.getOrNull()?.filter { it.isDigit() }?.takeIf { it.length >= 7 }
    }

    /** 前 3 位 + 后 4 位脱敏 */
    private fun maskNumber(digits: String): String {
        val d = digits.filter { it.isDigit() }
        return if (d.length >= 7) {
            d.substring(0, 3) + "****" + d.substring(d.length - 4)
        } else {
            d
        }
    }

    /** SIM 卡 ICCID（卡序列号），用于本机号码取不到时标识卡槽；逐级回退，避免无效 subId 直接放弃 */
    @Suppress("DEPRECATION")
    private fun simIccId(context: Context, subscriptionId: Int?, simSlot: Int?): String? {
        val sm = context.getSystemService(SubscriptionManager::class.java)
        // 1) 按 subscriptionId（模拟短信/部分 ROM 可能给出 -1，会抛异常，需容错）
        if (sm != null && subscriptionId != null && subscriptionId >= 0) {
            try {
                val v = sm.getActiveSubscriptionInfo(subscriptionId)?.iccId
                if (!v.isNullOrBlank()) return v
            } catch (e: Exception) {
                Log.w(TAG, "ICCID(bySubId=$subscriptionId) 失败: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        // 2) 按卡槽
        if (sm != null && simSlot != null && simSlot >= 0) {
            try {
                val v = sm.getActiveSubscriptionInfoForSimSlotIndex(simSlot)?.iccId
                if (!v.isNullOrBlank()) return v
            } catch (e: Exception) {
                Log.w(TAG, "ICCID(bySlot=$simSlot) 失败: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        // 3) 可用订阅列表（优先匹配卡槽）
        if (sm != null) {
            try {
                val list = sm.activeSubscriptionInfoList.orEmpty()
                Log.i(TAG, "ICCID 调试: subList size=${list.size} slot参数=$simSlot")
                val matched = if (simSlot != null) list.firstOrNull { it.simSlotIndex == simSlot } else null
                val v = (matched ?: list.firstOrNull())?.iccId
                if (!v.isNullOrBlank()) return v
            } catch (e: Exception) {
                Log.w(TAG, "ICCID(list) 失败: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        // 4) 换 TelephonyManager.getSimSerialNumber（部分 ROM 只放行这条）
        try {
            val tm = if (subscriptionId != null && subscriptionId >= 0) {
                val base = context.getSystemService(TelephonyManager::class.java)
                base?.createForSubscriptionId(subscriptionId)
            } else {
                context.getSystemService(TelephonyManager::class.java)
            }
            val v = tm?.simSerialNumber
            if (!v.isNullOrBlank()) return v
        } catch (e: Exception) {
            Log.w(TAG, "ICCID(serial) 失败: ${e.javaClass.simpleName}: ${e.message}")
        }
        return null
    }
}
