package com.sms.forward.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SmsDao {

    /** 幂等入库：uniqueKey 冲突时忽略（IGNORE 策略） */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: SmsEntity): Long

    @Query("SELECT * FROM sms WHERE uniqueKey = :key LIMIT 1")
    suspend fun findByUniqueKey(key: String): SmsEntity?

    /** 取当前最早一条可领取记录 id：PENDING，或未超最大重试次数的 FAILED_RETRYABLE */
    @Query("SELECT id FROM sms WHERE status = 'PENDING' OR (status = 'FAILED_RETRYABLE' AND retryCount < :maxRetry) ORDER BY id ASC LIMIT 1")
    suspend fun nextClaimableId(maxRetry: Int): Long?

    /** 取最早的网络类失败记录（FAILED_NETWORK，长期可重试，网络恢复后自动补发） */
    @Query("SELECT id FROM sms WHERE status = 'FAILED_NETWORK' ORDER BY id ASC LIMIT 1")
    suspend fun nextNetworkClaimableId(): Long?

    /**
     * 原子抢占：仅当当前状态为 PENDING / FAILED_RETRYABLE / FAILED_NETWORK 时才置为 SENDING。
     * 返回受影响行数（0 = 已被其他执行者抢占/状态已变）。
     */
    @Query("UPDATE sms SET status = 'SENDING' WHERE id = :id AND status IN ('PENDING','FAILED_RETRYABLE','FAILED_NETWORK')")
    suspend fun tryClaim(id: Long): Int

    @Query("SELECT * FROM sms WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): SmsEntity?

    /** 崩溃恢复：把残留 SENDING 回滚为 PENDING */
    @Query("UPDATE sms SET status = 'PENDING' WHERE status = 'SENDING'")
    suspend fun rollbackSending(): Int

    @Query("UPDATE sms SET status = 'SENT', messageId = :messageId, lastError = NULL WHERE id = :id")
    suspend fun markSent(id: Long, messageId: String?)

    @Query("UPDATE sms SET status = 'DRY_RUN', lastError = NULL WHERE id = :id")
    suspend fun markDryRun(id: Long)

    @Query("UPDATE sms SET status = 'FAILED_RETRYABLE', retryCount = retryCount + 1, lastError = :error WHERE id = :id")
    suspend fun markRetryable(id: Long, error: String?)

    /** 网络类失败：长期可重试（不占 5 次上限），网络恢复后自动补发 */
    @Query("UPDATE sms SET status = 'FAILED_NETWORK', lastError = :error WHERE id = :id")
    suspend fun markNetworkRetryable(id: Long, error: String?)

    @Query("UPDATE sms SET status = 'FAILED_PERMANENT', lastError = :error WHERE id = :id")
    suspend fun markPermanent(id: Long, error: String?)

    /** 手动重试：把失败记录重新置为 PENDING */
    @Query("UPDATE sms SET status = 'PENDING', retryCount = 0 WHERE id = :id")
    suspend fun requeue(id: Long)

    /** 批量重试：把所有失败记录重新置为 PENDING */
    @Query("UPDATE sms SET status = 'PENDING', retryCount = 0 WHERE status IN ('FAILED_RETRYABLE','FAILED_PERMANENT','FAILED_NETWORK')")
    suspend fun requeueAllFailed(): Int

    @Query("SELECT * FROM sms ORDER BY id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<SmsEntity>>

    @Query("SELECT * FROM sms WHERE status IN ('FAILED_RETRYABLE','FAILED_PERMANENT','FAILED_NETWORK') ORDER BY id DESC LIMIT :limit")
    fun observeFailed(limit: Int): Flow<List<SmsEntity>>

    @Query("SELECT COUNT(*) FROM sms WHERE status = 'PENDING'")
    suspend fun pendingCount(): Int

    /** 一键清理干跑历史 */
    @Query("DELETE FROM sms WHERE status = 'DRY_RUN'")
    suspend fun clearDryRun(): Int
}
