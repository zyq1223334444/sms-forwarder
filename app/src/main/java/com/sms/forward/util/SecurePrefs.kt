package com.sms.forward.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 加密存储封装：邮箱授权码等敏感配置使用 EncryptedSharedPreferences（AES256）落盘。
 */
object SecurePrefs {

    private const val PREFS_NAME = "secure_config"
    private const val MASTER_KEY_ALIAS = "sms_forward_master_key"

    @Volatile
    private var instance: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences =
        instance ?: synchronized(this) {
            instance ?: run {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()

                EncryptedSharedPreferences.create(
                    context,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            }.also { instance = it }
        }

    fun putString(context: Context, key: String, value: String) {
        prefs(context).edit().putString(key, value).apply()
    }

    fun getString(context: Context, key: String, default: String? = null): String? =
        prefs(context).getString(key, default)

    fun putBoolean(context: Context, key: String, value: Boolean) {
        prefs(context).edit().putBoolean(key, value).apply()
    }

    fun getBoolean(context: Context, key: String, default: Boolean): Boolean =
        prefs(context).getBoolean(key, default)

    fun putLong(context: Context, key: String, value: Long) {
        prefs(context).edit().putLong(key, value).apply()
    }

    fun getLong(context: Context, key: String, default: Long): Long =
        prefs(context).getLong(key, default)

    fun putInt(context: Context, key: String, value: Int) {
        prefs(context).edit().putInt(key, value).apply()
    }

    fun getInt(context: Context, key: String, default: Int): Int =
        prefs(context).getInt(key, default)

    fun remove(context: Context, key: String) {
        prefs(context).edit().remove(key).apply()
    }
}
