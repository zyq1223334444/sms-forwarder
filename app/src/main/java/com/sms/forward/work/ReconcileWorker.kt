package com.sms.forward.work

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sms.forward.data.AppDatabase
import com.sms.forward.data.DedupKey
import com.sms.forward.data.SmsEntity
import com.sms.forward.data.SmsStatus
import com.sms.forward.mail.MailConfig
import com.sms.forward.sim.SimInfoHelper
import com.sms.forward.util.SecurePrefs
import java.util.concurrent.TimeUnit

/**
 * 周期兜底补偿（M6 完成）。
 *
 * 关键设计：
 * - 基于基线增量扫描系统短信库（Telephony.Sms.Inbox），_id 为主游标、单调递增；
 * - **首次获得 READ_SMS 权限时先建立基线（当前最大 _id），绝不回灌历史短信**；
 * - uniqueKey 去重后补入库（与广播路径共享同一 key，重复到达不产生重复邮件）；
 * - 补偿发现新短信后触发 SendQueueWorker；
 * - 周期 ≥ 15 分钟（WorkManager 周期下限）。
 */
class ReconcileWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext

        // 权限检查：无 READ_SMS 则跳过（广播路径仍正常工作）
        val hasReadSms = ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) ==
                PackageManager.PERMISSION_GRANTED
        if (!hasReadSms) {
            Log.i(TAG, "无 READ_SMS 权限，补偿扫描跳过")
            return Result.success()
        }

        val dao = AppDatabase.get(ctx).smsDao()
        var baseline = SecurePrefs.getLong(ctx, MailConfig.KEY_RECONCILE_BASELINE_ID, -1L)
        var newBaseline = baseline
        var inserted = 0

        // 动态投影：非默认短信应用只能读 sms_restricted 受限视图，
        // 该视图可能没有 subscription_id 列，必须先探测再决定投影列，
        // 否则 SELECT 编译直接报 SQLiteException（已验证真机报错）。
        val projection = detectProjection(ctx)

        runCatching {
            ctx.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                projection,
                "${COL_ID} > ?",
                arrayOf(baseline.toString()),
                "${COL_ID} ASC"
            )?.use { cursor ->
                if (baseline == -1L && cursor.count > 0) {
                    // 首次：默认只建基线不回灌历史；但 30 分钟内新短信（≤10 条）
                    // 视为"刚收到未来得及转发的"，允许补发（防止验证码类短信被漏掉）。
                    val cutoff = System.currentTimeMillis() - 30 * 60 * 1000L
                    var recentCount = 0
                    var skipTo = -1L
                    cursor.moveToPosition(-1)
                    while (cursor.moveToNext()) {
                        val date = cursor.getLong(cursor.getColumnIndexOrThrow(COL_DATE))
                        if (date >= cutoff) recentCount++
                        else skipTo = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID))
                    }
                    if (recentCount in 1..10) {
                        // 仅补发 30 分钟内短信：基线设为最近一条旧短信 _id
                        baseline = skipTo
                        newBaseline = baseline
                        Log.i(TAG, "首次补偿：近30分钟内有 $recentCount 条新短信，基线设 _id=$skipTo 予以补发")
                        cursor.moveToPosition(-1)
                    } else {
                        cursor.moveToLast()
                        newBaseline = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID))
                        Log.i(TAG, "首次建立补偿基线 _id=$newBaseline（不回灌历史）")
                        return@use
                    }
                }
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID))
                    // 首次补发路径中 cursor 仍含全部短信，按新基线跳过旧行
                    if (baseline >= 0 && id <= baseline) continue
                    val address = cursor.getString(cursor.getColumnIndexOrThrow(COL_ADDRESS))
                    val body = cursor.getString(cursor.getColumnIndexOrThrow(COL_BODY))
                    val date = cursor.getLong(cursor.getColumnIndexOrThrow(COL_DATE))
                    val subId = readSubId(cursor)

                    val key = DedupKey.compute(address, date, subId, body, 1)
                    if (dao.findByUniqueKey(key) != null) continue

                    val sim = SimInfoHelper.resolveSubscription(ctx, subId)
                    val entity = SmsEntity(
                        uniqueKey = key,
                        sender = DedupKey.normalizeSender(address),
                        senderRaw = address,
                        body = body,
                        receivedAt = date,
                        subscriptionId = subId,
                        simSlot = sim.slotIndex,
                        carrierName = sim.carrierName,
                        status = SmsStatus.PENDING
                    )
                    if (dao.insert(entity) != -1L) inserted++
                    newBaseline = id
                }
            }
        }.onFailure {
            Log.e(TAG, "补偿扫描异常", it)
        }

        if (newBaseline > baseline) {
            SecurePrefs.putLong(ctx, MailConfig.KEY_RECONCILE_BASELINE_ID, newBaseline)
        }
        if (inserted > 0) {
            Log.i(TAG, "补偿新增 $inserted 条短信，唤醒发送队列")
            SendQueueWorker.enqueue(ctx)
        } else {
            Log.i(TAG, "补偿扫描完成：无新增（基线 _id=$newBaseline）")
        }
        return Result.success()
    }

    private fun readSubId(cursor: android.database.Cursor): Int? {
        val idx = cursor.getColumnIndex(COL_SUB_ID)
        return if (idx >= 0 && !cursor.isNull(idx)) cursor.getInt(idx) else null
    }

    /** 探测受限视图是否含 subscription_id 列；不含则回退基础投影（卡槽降级未知） */
    private fun detectProjection(ctx: Context): Array<String> {
        val hasSub = runCatching {
            ctx.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(COL_ID),
                null, null, null
            )?.use { cursor -> cursor.columnNames.contains(COL_SUB_ID) } ?: false
        }.getOrDefault(false)
        if (!hasSub) Log.i(TAG, "受限视图无 subscription_id 列，卡槽降级为未知")
        return if (hasSub) PROJECTION_WITH_SUB else PROJECTION_BASE
    }

    companion object {
        private const val TAG = "ReconcileWorker"
        const val WORK_NAME = "reconcile"
        private const val WORK_ONCE_NAME = "reconcile_once"

        private const val COL_ID = "_id"
        private const val COL_ADDRESS = "address"
        private const val COL_BODY = "body"
        private const val COL_DATE = "date"
        private const val COL_SUB_ID = "subscription_id"

        /** 基础投影：所有第三方短信应用可见的列 */
        private val PROJECTION_BASE = arrayOf(COL_ID, COL_ADDRESS, COL_BODY, COL_DATE)
        /** 完整投影：含卡槽列（仅当受限视图存在该列时使用） */
        private val PROJECTION_WITH_SUB = arrayOf(COL_ID, COL_ADDRESS, COL_BODY, COL_DATE, COL_SUB_ID)

        /** 周期调度（App 启动时调用） */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ReconcileWorker>(15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        /** 一次性补偿（开机自启、手动触发用）；独立任务名避免与周期任务互斥 */
        fun runOnce(context: Context) {
            val request = OneTimeWorkRequestBuilder<ReconcileWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_ONCE_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
