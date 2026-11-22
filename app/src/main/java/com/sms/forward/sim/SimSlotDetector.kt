package com.sms.forward.sim

import android.content.Context
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log

/**
 * 卡槽检测 + 每卡号码自动读取（v1.4.0）。
 *
 * 数据来源优先级：
 * 1. SubscriptionManager.activeSubscriptionInfoList —— 可取到卡槽/运营商/每卡号码，
 *    但 MIUI 等 ROM 对未授权的应用可能返回空列表（如开启"空白通行证"前实测 size=0）；
 * 2. 列表为空时退回 TelephonyManager.phoneCount + getSimState(slot) 判断是否有卡，
 *    保证"设置页至少能按实际卡数显示输入项"。
 *
 * 号码读取：API 33+ 用 SubscriptionManager.getPhoneNumber(subId)，低版本用
 * TelephonyManager.createForSubscriptionId(subId).line1Number；两者都可能因运营商
 * 未写 MSISDN 而返回空，此时由调用方退回手动填写/ICCID 尾号。
 */
object SimSlotDetector {

    private const val TAG = "SimSlotDetector"

    data class Slot(
        val slot: Int,
        val present: Boolean,
        val carrierName: String?,
        val subId: Int? = null,
        /** 系统读到的本机号码（已去国家码）；读不到为 null */
        val phoneNumber: String? = null
    )

    /** 有卡的卡槽（按 slot 升序）；检测失败返回空列表，由调用方兜底 */
    fun detect(context: Context): List<Slot> {
        val sm = runCatching { context.getSystemService(SubscriptionManager::class.java) }.getOrNull()
        val tm = runCatching { context.getSystemService(TelephonyManager::class.java) }.getOrNull()
        val subs = runCatching { sm?.activeSubscriptionInfoList }.getOrNull().orEmpty()

        val result = if (subs.isNotEmpty()) {
            subs.sortedBy { it.simSlotIndex }.map { info ->
                Slot(
                    slot = info.simSlotIndex,
                    present = true,
                    carrierName = info.carrierName?.toString()?.trim()?.takeIf { it.isNotEmpty() },
                    subId = info.subscriptionId,
                    phoneNumber = numberFor(sm, tm, info.subscriptionId)
                )
            }
        } else {
            // 订阅列表不可读：仅按卡槽状态判断有卡，号码留给手动填写
            val count = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) tm?.phoneCount ?: 1 else 1
            }.getOrDefault(1).coerceIn(1, 4)
            (0 until count).mapNotNull { slot ->
                val state = runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        tm?.getSimState(slot) ?: TelephonyManager.SIM_STATE_UNKNOWN
                    } else {
                        tm?.simState ?: TelephonyManager.SIM_STATE_UNKNOWN
                    }
                }.getOrDefault(TelephonyManager.SIM_STATE_UNKNOWN)
                if (!isPresent(state)) return@mapNotNull null
                val carrier = runCatching { tm?.simOperatorName }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                Slot(
                    slot = slot,
                    present = true,
                    carrierName = carrier,
                    subId = null,
                    phoneNumber = normalizeCnNumber(runCatching { tm?.line1Number }.getOrNull())
                )
            }
        }

        Log.i(
            TAG,
            "卡槽检测: 订阅列表=${subs.size} 结果=" + result.joinToString {
                "卡${it.slot + 1}(${it.carrierName ?: "运营商未知"},号码=${it.phoneNumber?.let { n -> mask(n) } ?: "无"})"
            }
        )
        return result
    }

    /** 读取指定订阅的本机号码（去国家码后返回） */
    @Suppress("DEPRECATION")
    private fun numberFor(sm: SubscriptionManager?, tm: TelephonyManager?, subId: Int): String? {
        if (subId < 0) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val v = runCatching { sm?.getPhoneNumber(subId) }.getOrNull()
            if (!v.isNullOrBlank()) return normalizeCnNumber(v)
        }
        val legacy = runCatching { tm?.createForSubscriptionId(subId)?.line1Number }.getOrNull()
        return normalizeCnNumber(legacy)
    }

    /**
     * 去国家码：+86 / 86 / 0086 前缀统一剥掉，返回 11 位号码；
     * 非中国号码或读不到时原样（去非数字）返回。
     */
    fun normalizeCnNumber(raw: String?): String? {
        val digits = raw?.filter { it.isDigit() }.orEmpty()
        if (digits.isEmpty()) return null
        return when {
            digits.length == 13 && digits.startsWith("86") -> digits.substring(2)
            digits.length == 15 && digits.startsWith("0086") -> digits.substring(4)
            else -> digits
        }
    }

    /** 前3后4脱敏，供日志使用 */
    private fun mask(digits: String): String =
        if (digits.length >= 7) digits.substring(0, 3) + "****" + digits.takeLast(4) else digits

    private fun isPresent(state: Int): Boolean = when (state) {
        TelephonyManager.SIM_STATE_READY,
        TelephonyManager.SIM_STATE_PIN_REQUIRED,
        TelephonyManager.SIM_STATE_PUK_REQUIRED,
        TelephonyManager.SIM_STATE_NETWORK_LOCKED -> true

        else -> false
    }
}
