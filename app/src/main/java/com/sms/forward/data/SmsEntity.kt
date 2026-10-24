package com.sms.forward.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 短信记录表。
 * uniqueKey 唯一索引用于幂等去重（广播路径与补偿路径共享）。
 */
@Entity(
    tableName = "sms",
    indices = [Index(value = ["uniqueKey"], unique = true)]
)
data class SmsEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,

    /** 幂等去重键：SHA-256(normalize(sender)|receivedAt|subscriptionId|partCount|trim(body)) */
    val uniqueKey: String,

    /** 规范化后的发件人号码（去空格/横线、统一 +86），用于去重与主题 */
    val sender: String,

    /** 原始发件人展示（保留运营商下发原样） */
    val senderRaw: String,

    /** 短信正文（长短信合并后完整正文） */
    val body: String,

    /** 系统短信时间（毫秒） */
    val receivedAt: Long,

    /** 广播携带的 subscriptionId；可为 null（补偿路径读不到时） */
    val subscriptionId: Int? = null,

    /** 卡槽 0/1；未知为 null */
    val simSlot: Int? = null,

    /** 运营商显示名，如"中国移动" */
    val carrierName: String? = null,

    /** 状态机状态 */
    val status: SmsStatus = SmsStatus.PENDING,

    /** 重试次数 */
    val retryCount: Int = 0,

    /** 长短信合并失败降级标记：分片不完整 */
    val multipartIncomplete: Boolean = false,

    /** SMTP Message-ID（uniqueKey 短哈希，用于邮件服务商/人工去重识别） */
    val messageId: String? = null,

    /** 最近一次错误信息（脱敏后） */
    val lastError: String? = null,

    /** 入库时间 */
    val createdAt: Long = System.currentTimeMillis()
)
