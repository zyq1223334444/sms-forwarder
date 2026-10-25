package com.sms.forward.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.delay
import rikka.shizuku.Shizuku
import java.lang.reflect.InvocationTargetException

/**
 * 网络自动开关（可选功能，默认关闭）。
 *
 * 语义：发送邮件前若移动数据/WiFi 未开启则临时打开，发送完成后恢复原状。
 * 能力边界（如实说明，2026-10-24 真机实测）：
 * - WiFi 与移动数据：Android 5.0+ 起系统禁止普通应用直接开关（`setWifiEnabled` 在 Android 11+
 *   对第三方应用一律返回 false；移动数据无公开 API）。要真正自动开关，必须走以下任一通道：
 *   ① Shizuku（推荐，无 Root）：安装 Shizuku 应用 + adb 激活后，本应用以 shell 权限执行
 *      `svc wifi/data enable/disable`；
 *   ② Root：直接执行 `su -c "svc ..."`；
 * - 无 Shizuku/无 Root 的机型上，该功能无法自动开启任何网络，仅记录日志并提示。
 *
 * 恢复原则：只恢复"本功能自己打开的"开关——发送前本就是开启的项不动，
 * 原本关闭、由本功能临时打开的项在发送完成后关闭，避免影响用户已有网络习惯。
 */
object NetworkAutoToggler {

    private const val TAG = "NetworkAutoToggler"

    /** 记录发送前网络状态与本次改动，用于发送后恢复 */
    data class ToggleState(
        val wifiWasOn: Boolean,
        val wifiToggled: Boolean = false,
        val mobileToggled: Boolean = false,
        /** 发送前移动数据是否已在线（防止恢复时误关用户本来就开着的数据） */
        val mobileDataWasOn: Boolean = false
    )

    /**
     * 确保网络开启（临时）：记录原状态，关闭的项逐一打开。
     * @return 恢复所需的状态快照；调用方必须在发送完成后调用 [restoreNetwork]。
     */
    fun ensureNetworkOn(context: Context): ToggleState {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val wifiWasOn = isWifiEnabled(context)
        var wifiToggled = false

        if (wifi == null) {
            Log.w(TAG, "WifiManager 不可用，跳过 WiFi 自动开关")
        } else if (!wifiWasOn) {
            // 通道 1：Shizuku（svc wifi enable，shell 权限，Android 11+ 唯一可用路径）
            val ok = if (shizukuAvailable()) {
                execViaShizuku("svc wifi enable")
            } else {
                runCatching { wifi.setWifiEnabled(true) }.getOrElse {
                    Log.w(TAG, "调用 setWifiEnabled(true) 异常: ${it.message}")
                    false
                }
            }
            // 延迟验证实际开关状态（系统切换 WiFi 有短暂延迟）
            val opened = runCatching {
                Thread.sleep(1500)
                isWifiEnabled(context)
            }.getOrDefault(false)
            Log.i(TAG, "尝试开启 WiFi: 通道=${if (shizukuAvailable()) "Shizuku" else "setWifiEnabled"} 调用结果=$ok, 验证状态=$opened")
            wifiToggled = ok && opened
            if (wifiToggled) Log.i(TAG, "已临时开启 WiFi（发送后恢复）")
            else Log.w(TAG, "WiFi 未能开启（调用=$ok 验证=$opened）: Android 11+ 系统限制，需 Shizuku 或 Root")
        } else {
            Log.i(TAG, "WiFi 原本已开启，无需操作")
        }

        // 移动数据：仅在"当前没有可用网络"（WiFi 连不上/未开）时才尝试开启——
        // 有可用网络（如 WiFi 已连）不动数据，省流量；发送前数据已在线也不操作，
        // 发送后只关"本次打开"的项，绝不误关用户数据。
        val dataWasOn = isMobileNetworkActive(context)
        val mobileToggled = if (!isNetworkAvailable(context) && !dataWasOn) {
            tryEnableMobileData(context, enable = true)
        } else {
            false
        }
        if (mobileToggled) Log.i(TAG, "已临时开启移动数据（发送后恢复）")
        else if (dataWasOn) Log.i(TAG, "移动数据原本已在线，无需操作")
        else if (isNetworkAvailable(context)) Log.i(TAG, "已有可用网络，无需开启移动数据")

        return ToggleState(
            wifiWasOn = wifiWasOn,
            wifiToggled = wifiToggled,
            mobileToggled = mobileToggled,
            mobileDataWasOn = dataWasOn
        )
    }

    /** 发送完成后恢复网络原状（只关掉本功能打开的项） */
    fun restoreNetwork(context: Context, state: ToggleState) {
        if (state.wifiToggled) {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val closed = if (shizukuAvailable()) {
                execViaShizuku("svc wifi disable")
            } else if (wifi != null) {
                runCatching { wifi.setWifiEnabled(false) }.getOrDefault(false)
            } else false
            if (closed) Log.i(TAG, "发送完成，已恢复关闭 WiFi")
            else Log.w(TAG, "恢复 WiFi 失败: ${if (wifi == null) "WifiManager 不可用" else "系统限制"}")
        }
        if (state.mobileToggled && !state.mobileDataWasOn) {
            tryEnableMobileData(context, enable = false)
            Log.i(TAG, "发送完成，已恢复关闭移动数据")
        }
    }

    /**
     * 等待网络可达（已连接且校验通过），最多等 [timeoutMs] 毫秒。
     * 超时返回 false（不阻塞发送，失败会走可重试逻辑）。
     */
    suspend fun waitForNetwork(context: Context, timeoutMs: Long = 12_000L): Boolean {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false

        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val network = cm.activeNetwork
            val caps = network?.let { cm.getNetworkCapabilities(it) }
            if (caps != null &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            ) {
                Log.i(TAG, "网络已就绪（等待 ${System.currentTimeMillis() - start}ms）")
                return true
            }
            delay(500)
        }
        Log.w(TAG, "等待网络超时（${timeoutMs}ms），继续发送尝试")
        return false
    }

    // ---------- WiFi 状态检测 ----------

    /**
     * 当前是否有可用网络（INTERNET + VALIDATED）。作为"是否需要开移动数据"的兜底判断，
     * 避免 Settings.Global.mobile_data 读数不准导致误操作（2026-10-24 真机实测该读数不可靠）。
     */
    private fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * 双信号检测 WiFi 开关状态：`isWifiEnabled()`（API）与 `Settings.Global.WIFI_ON`（系统设置）。
     * 任一信号显示"关"即视为关——规避部分 MIUI 版本上 isWifiEnabled() 误报 true、
     * 导致"本应自动开启却被跳过"的问题（2026-10-24 真机实测发现）。
     */
    private fun isWifiEnabled(context: Context): Boolean {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val apiState = wm?.isWifiEnabled
        val settingState = runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.WIFI_ON, -1)
        }.getOrDefault(-1)
        val on = !(apiState == false || settingState == 0)
        Log.i(TAG, "WiFi 状态检测: api=$apiState setting=$settingState -> $on")
        return on
    }

    // ---------- 移动数据（尽力而为） ----------

    /**
     * 移动数据是否真实在线：存在已连接且具备 INTERNET 能力的蜂窝网络。
     * 用真实网络状态替代 Settings.Global.mobile_data 读数（2026-10-24 真机实测该读数不可靠，
     * 值为 0 时数据可能实际在线），避免误开/误关用户数据。
     */
    private fun isMobileNetworkActive(context: Context): Boolean {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        return cm.allNetworks.any { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@any false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }

    private fun tryEnableMobileData(context: Context, enable: Boolean): Boolean {
        // 通道 1：Shizuku（svc data enable/disable，shell 权限）
        if (shizukuAvailable()) {
            val cmd = if (enable) "svc data enable" else "svc data disable"
            if (execViaShizuku(cmd)) {
                Log.i(TAG, "Shizuku 执行成功: $cmd")
                return true
            }
        }

        // 通道 2：反射隐藏 API ConnectivityManager.setMobileDataEnabled()
        val reflectOk = tryReflectMobileData(context, enable)
        if (reflectOk) return true

        // 通道 3：Root(su) 命令
        val suOk = trySuMobileData(enable)
        if (suOk) return true

        Log.w(TAG, "移动数据${if (enable) "开启" else "关闭"}失败：需 Shizuku 或 Root（Android 系统限制）")
        return false
    }

    // ---------- Shizuku（无 Root 自动开关网络的核心通道） ----------

    /** Shizuku 服务是否已激活并授权本应用（未安装/未激活/未授权都返回 false） */
    fun shizukuAvailable(): Boolean {
        return runCatching { Shizuku.pingBinder() }.getOrDefault(false)
    }

    /** 通过 Shizuku 以 shell 权限执行命令（如 svc wifi enable），成功返回 true。
     *  Shizuku 13.x 的 newProcess 为 private（标注 @RefineAs），用反射调用。 */
    private fun execViaShizuku(command: String): Boolean {
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            val process = method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
            val exit = process.waitFor()
            Log.i(TAG, "Shizuku 执行 '$command' 退出码=$exit")
            exit == 0
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku 执行失败: ${e.message}")
            false
        }
    }

    private fun tryReflectMobileData(context: Context, enable: Boolean): Boolean {
        return try {
            val cm = context.applicationContext
                .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val method = ConnectivityManager::class.java.getMethod("setMobileDataEnabled", Boolean::class.javaPrimitiveType)
            method.invoke(cm, enable)
            Log.i(TAG, "反射调用 setMobileDataEnabled($enable) 成功")
            true
        } catch (e: NoSuchMethodException) {
            false
        } catch (e: InvocationTargetException) {
            false
        } catch (e: SecurityException) {
            false
        } catch (e: Exception) {
            false
        }
    }

    private fun trySuMobileData(enable: Boolean): Boolean {
        return try {
            val cmd = if (enable) "svc data enable" else "svc data disable"
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val exit = process.waitFor()
            if (exit == 0) {
                Log.i(TAG, "su 命令执行成功: $cmd")
                true
            } else {
                Log.i(TAG, "su 命令退出码 $exit（可能无 Root 或命令不被支持）")
                false
            }
        } catch (e: Exception) {
            Log.i(TAG, "su 不可用: ${e.message}")
            false
        }
    }
}
