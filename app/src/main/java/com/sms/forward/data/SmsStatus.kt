package com.sms.forward.data

/**
 * 短信记录状态机：
 * PENDING → SENDING → SENT / DRY_RUN / FAILED_RETRYABLE / FAILED_NETWORK / FAILED_PERMANENT
 * FAILED_RETRYABLE → SENT / FAILED_PERMANENT（超重试次数）
 * FAILED_NETWORK → SENT（网络恢复后长期补发，不计入 5 次上限）
 * FAILED_PERMANENT / FAILED_NETWORK →（手动重试）→ PENDING
 *
 * 注意：FAILED_NETWORK 必须出现在枚举里。SmsDao 的 SQL 会写入该状态的字符串，
 * Room 读回时按枚举名反序列化，缺枚举项会抛 IllegalArgumentException 导致列表/队列崩溃。
 */
enum class SmsStatus {
    PENDING,
    SENDING,
    DRY_RUN,
    SENT,
    FAILED_RETRYABLE,
    FAILED_NETWORK,
    FAILED_PERMANENT
}
