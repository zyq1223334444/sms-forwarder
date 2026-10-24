package com.sms.forward.work

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sms.forward.data.AppDatabase
import com.sms.forward.data.SmsStatus
import com.sms.forward.mail.MailConfig
import com.sms.forward.mail.MailErrorClassifier
import com.sms.forward.mail.MailSender
import java.util.concurrent.TimeUnit

/**
 * 串行发送队列（M5 完成）。
 *
 * 关键设计：
 * - 唯一工作名 + KEEP：同一时刻只有一个队列实例在跑，保证串行；
 * - 原子抢占：PENDING / FAILED_RETRYABLE(未超重试) → SENDING，防重复发送；
 * - 单次执行约 9 分钟硬时限：到限返回 Result.retry() 链式续投，
 *   规避 WorkManager 约 10 分钟单次执行上限，积压分多轮完成；
 * - 失败分类：网络/4xx 可重试（指数退避），鉴权/5xx 永久失败停止重试；
 * - 干跑模式：不连接网络，仅置 DRY_RUN；
 * - 发送间隔限速（默认 4s/封），防邮箱限流；
 * - 崩溃恢复：执行前将残留 SENDING 回滚为 PENDING。
 */
class SendQueueWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val dao = AppDatabase.get(ctx).smsDao()
        val settings = MailConfig.load(ctx)

        // 崩溃恢复：回滚上次异常退出残留的 SENDING
        runCatching { dao.rollbackSending() }
            .onSuccess { if (it > 0) Log.i(TAG, "回滚 $it 条残留 SENDING 记录") }

        if (!settings.enabled) {
            Log.i(TAG, "转发未启用，队列空转")
            return Result.success()
        }

        val startTime = System.currentTimeMillis()
        var processed = 0

        while (System.currentTimeMillis() - startTime < HARD_TIME_LIMIT_MS) {
            val id = dao.nextClaimableId(MAX_RETRY) ?: break
            // 原子抢占：仅 PENDING/FAILED_RETRYABLE → SENDING
            val claimed = dao.tryClaim(id)
            if (claimed == 0) continue
            val entity = dao.getById(id) ?: continue

            processOne(dao, entity, settings)
            processed++
            if (settings.sendIntervalMs > 0) {
                kotlinx.coroutines.delay(settings.sendIntervalMs)
            }
        }

        // 限时结束仍有积压 → 链式续投；否则完成
        val remaining = dao.pendingCount()
        return if (remaining > 0) {
            Log.i(TAG, "本轮处理 $processed 条，剩余 $remaining 条，链式续投")
            Result.retry()
        } else {
            Log.i(TAG, "队列清空，本轮处理 $processed 条")
            Result.success()
        }
    }

    private suspend fun processOne(
        dao: com.sms.forward.data.SmsDao,
        entity: com.sms.forward.data.SmsEntity,
        settings: MailConfig.MailSettings
    ) {
        if (settings.dryRun) {
            // 干跑：不连接网络，仅记录
            dao.markDryRun(entity.id)
            Log.i(TAG, "干跑 #${entity.id} sender=${entity.sender}")
            return
        }

        when (val result = MailSender.send(entity, settings)) {
            is MailSender.SendResult.Success -> {
                dao.markSent(entity.id, result.messageId)
                Log.i(TAG, "已发送 #${entity.id} -> ${maskEmail(settings.toEmail)}")
            }
            is MailSender.SendResult.Failure -> {
                if (result.permanent) {
                    dao.markPermanent(entity.id, result.message)
                    Log.w(TAG, "永久失败 #${entity.id}: ${result.message}")
                } else {
                    dao.markRetryable(entity.id, result.message)
                    Log.w(TAG, "可重试 #${entity.id} 第 ${entity.retryCount + 1} 次: ${result.message}")
                }
            }
        }
    }

    private fun maskEmail(email: String?): String {
        val s = email.orEmpty()
        val at = s.indexOf('@')
        return if (at > 1) s.substring(0, 1) + "***" + s.substring(at) else s
    }

    companion object {
        private const val TAG = "SendQueueWorker"
        const val WORK_NAME = "send_queue"
        const val MAX_RETRY = 5

        /** 单次执行硬时限：9 分钟（WorkManager 上限约 10 分钟） */
        private const val HARD_TIME_LIMIT_MS = 9 * 60 * 1000L

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<SendQueueWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
