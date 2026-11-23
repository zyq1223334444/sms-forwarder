package com.sms.forward.sim

import android.content.Context
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.util.Log

/**
 * 单双卡自动检测与卡槽映射（M4 完成）。
 *
 * 设计要点：
 * - **不缓存、实时查询**：每次调用都重新读取 SubscriptionManager，
 *   因此双卡热插拔、飞行模式切换后无需重启应用即自动生效；
 * - 短信广播路径优先用 subscriptionId 映射；取不到时按 simSlotIndex 匹配；
 * - 取不到任何卡信息时降级为"未知卡槽"，不影响发送。
 */
object SimInfoHelper {

    private const val TAG = "SimInfoHelper"

    data class SimInfo(
        val activeCount: Int = 0,
        val slotIndex: Int? = null,
        val carrierName: String? = null,
        val displayName: String? = null
    )

    private fun subscriptionManager(context: Context): SubscriptionManager? =
        runCatching {
            context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
        }.getOrNull()

    /**
     * 当前活动卡数量（1=单卡，2=双卡，0=无卡/不可读）。
     *
     * 必须容错：部分 ROM（MIUI「空白通行证」/权限被回收/设备标识限制）下访问
     * activeSubscriptionInfoList 会抛 SecurityException。历史版本此处直接调用，
     * 异常会从 SmsReceiver 的入库流程里穿透出去，导致**整条短信不入库**，
     * 表现为"短信到了却只有手动扫描时才补到"。
     */
    fun getActiveSimCount(context: Context): Int =
        runCatching { subscriptionManager(context)?.activeSubscriptionInfoList?.size ?: 0 }
            .getOrElse {
                Log.w(TAG, "activeSubscriptionInfoList 不可读: ${it.javaClass.simpleName}")
                0
            }

    /** 全部活动卡摘要（供设置页/状态页展示单双卡状态） */
    fun getSimSummary(context: Context): List<SimInfo> {
        val sm = subscriptionManager(context) ?: return emptyList()
        return runCatching {
            sm.activeSubscriptionInfoList.orEmpty().map { info ->
                SimInfo(
                    activeCount = sm.activeSubscriptionInfoList?.size ?: 0,
                    slotIndex = info.simSlotIndex,
                    carrierName = info.carrierName?.toString(),
                    displayName = info.displayName?.toString()
                )
            }
        }.getOrElse {
            Log.w(TAG, "getSimSummary 失败: ${it.message}")
            emptyList()
        }
    }

    /** 按 subscriptionId 解析卡槽与运营商（广播路径优先） */
    fun resolveSubscription(context: Context, subscriptionId: Int?): SimInfo {
        if (subscriptionId == null) {
            return SimInfo(activeCount = getActiveSimCount(context))
        }
        return runCatching {
            val sm = subscriptionManager(context) ?: return SimInfo(activeCount = 0)
            val info: SubscriptionInfo? = sm.activeSubscriptionInfoList
                ?.firstOrNull { it.subscriptionId == subscriptionId }
            if (info == null) {
                SimInfo(activeCount = getActiveSimCount(context))
            } else {
                SimInfo(
                    activeCount = getActiveSimCount(context),
                    slotIndex = info.simSlotIndex,
                    carrierName = info.carrierName?.toString(),
                    displayName = info.displayName?.toString()
                )
            }
        }.getOrElse {
            Log.w(TAG, "resolveSubscription 失败: ${it.message}")
            SimInfo(activeCount = getActiveSimCount(context))
        }
    }

    /** 按 simSlotIndex（卡 0/卡 1）解析运营商（无 subscriptionId 时兜底） */
    fun resolveBySlot(context: Context, slot: Int?): SimInfo {
        if (slot == null) return SimInfo(activeCount = getActiveSimCount(context))
        return runCatching {
            val sm = subscriptionManager(context) ?: return SimInfo(activeCount = 0)
            val info: SubscriptionInfo? = sm.activeSubscriptionInfoList
                ?.firstOrNull { it.simSlotIndex == slot }
            if (info == null) {
                SimInfo(activeCount = getActiveSimCount(context), slotIndex = slot)
            } else {
                SimInfo(
                    activeCount = getActiveSimCount(context),
                    slotIndex = info.simSlotIndex,
                    carrierName = info.carrierName?.toString(),
                    displayName = info.displayName?.toString()
                )
            }
        }.getOrElse {
            Log.w(TAG, "resolveBySlot 失败: ${it.message}")
            SimInfo(activeCount = getActiveSimCount(context), slotIndex = slot)
        }
    }
}
