package com.sms.forward

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log
import com.sms.forward.data.AppDatabase
import com.sms.forward.data.DedupKey
import com.sms.forward.data.SmsEntity
import com.sms.forward.data.SmsStatus
import com.sms.forward.sim.SimInfoHelper
import com.sms.forward.work.SendQueueWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 短信到达广播接收器（M3 完成）。
 *
 * 约束：goAsync() 窗口内只做 解析→拼接→幂等入库→投递 WorkManager 唤醒，
 * 绝不在此处连接 SMTP 或做长任务。
 *
 * 长短信处理：
 * - Telephony.Sms.Intents.getMessagesFromIntent 将同一条长短信的所有分片
 *   按顺序放入同一 intent 返回（SmsMessage[]）；
 * - 直接按数组顺序拼接 getMessageBody() 得到完整正文；
 * - 注意：SmsMessage 的 getMessageCount()/getIndexInMessage()/getSubscriptionId()
 *   在 SDK 中为隐藏 API（@SystemApi），编译期不可见，故不使用；
 *   卡槽信息改为从广播 Intent 的 "subscription"/"slot" extra 读取。
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        Log.i(TAG, "收到 SMS_RECEIVED 广播")

        val messages = runCatching {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)
        }.getOrElse {
            Log.w(TAG, "PDU 解析失败: ${it.message}")
            return
        }
        if (messages.isEmpty()) return
        Log.i(TAG, "广播携带 ${messages.size} 个分片")

        val subIdExtra = intent.getIntExtra(EXTRA_SUBSCRIPTION_ID, -1).takeIf { it != -1 }
        val slotExtra = intent.getIntExtra(EXTRA_SLOT, -1).takeIf { it != -1 }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                handleMessages(
                    context = context.applicationContext,
                    messages = messages.toList(),
                    subId = subIdExtra,
                    slot = slotExtra
                )
            } catch (t: Throwable) {
                Log.e(TAG, "处理短信异常", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handleMessages(
        context: Context,
        messages: List<SmsMessage>,
        subId: Int?,
        slot: Int?
    ) {
        val first = messages.first()
        val sender = first.displayOriginatingAddress ?: first.originatingAddress.orEmpty()
        val body = messages.joinToString("") { it.messageBody.orEmpty() }
        val receivedAt = first.timestampMillis

        insertIfAbsent(
            context = context,
            senderRaw = sender,
            body = body,
            receivedAt = receivedAt,
            subId = subId,
            slot = slot,
            partCount = messages.size,
            incomplete = false
        )
    }

    // ---------- 幂等入库 ----------

    private suspend fun insertIfAbsent(
        context: Context,
        senderRaw: String,
        body: String,
        receivedAt: Long,
        subId: Int?,
        slot: Int?,
        partCount: Int,
        incomplete: Boolean
    ) {
        if (body.isBlank()) {
            Log.w(TAG, "短信正文为空，跳过: sender=$senderRaw ts=$receivedAt")
            return
        }
        val key = DedupKey.compute(senderRaw, receivedAt, subId, body, partCount)
        val dao = AppDatabase.get(context).smsDao()

        // 幂等：已存在则跳过（广播路径与补偿路径共享同一 key）
        if (dao.findByUniqueKey(key) != null) return

        val sim = SimInfoHelper.resolveSubscription(context, subId)
        val effectiveSlot = sim.slotIndex ?: slot
        val entity = SmsEntity(
            uniqueKey = key,
            sender = DedupKey.normalizeSender(senderRaw),
            senderRaw = senderRaw,
            body = body,
            receivedAt = receivedAt,
            subscriptionId = subId,
            simSlot = effectiveSlot,
            carrierName = sim.carrierName,
            status = SmsStatus.PENDING,
            multipartIncomplete = incomplete
        )
        val rowId = dao.insert(entity)
        if (rowId != -1L) {
            Log.i(TAG, "短信入库 #$rowId sender=$senderRaw len=${body.length} parts=$partCount slot=$effectiveSlot")
            // 唤醒串行发送队列（M5 前队列为空操作，仅占位）
            SendQueueWorker.enqueue(context)
        }
    }

    companion object {
        private const val TAG = "SmsReceiver"

        /** 广播 Intent 中可能携带的卡信息 extra（telephony 框架写入，非 SDK 常量） */
        private const val EXTRA_SUBSCRIPTION_ID = "subscription"
        private const val EXTRA_SLOT = "slot"
    }
}
