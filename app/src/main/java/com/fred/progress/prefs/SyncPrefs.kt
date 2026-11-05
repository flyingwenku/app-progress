package com.fred.progress.prefs

import android.content.Context

/**
 * 同步设置（坚果云 WebDAV）。沿用 app-word-widget 的 SyncPrefs 做法：
 * 凭据存在 App 私有的 SharedPreferences 里（系统按 uid 隔离，别的 App 读不到），
 * 明文应用密码即可，不引第三方库。
 * 注意：只放"应用密码"，别填登录密码（网页端"账户信息 → 添加应用"里生成）。
 */
class SyncPrefs(context: Context) {

    private val prefs = context.getSharedPreferences("sync", Context.MODE_PRIVATE)

    var url: String
        get() = prefs.getString("url", DEFAULT_URL) ?: DEFAULT_URL
        set(v) = prefs.edit().putString("url", v.trim()).apply()

    var user: String
        get() = prefs.getString("user", "") ?: ""
        set(v) = prefs.edit().putString("user", v.trim()).apply()

    var password: String
        get() = prefs.getString("pass", "") ?: ""
        set(v) = prefs.edit().putString("pass", v).apply()

    var lastSyncAt: Long
        get() = prefs.getLong("last", 0L)
        set(v) = prefs.edit().putLong("last", v).apply()

    /** 上次尝试同步的时刻（节流用，别每次开页面都打一次网络） */
    var lastAttemptAt: Long
        get() = prefs.getLong("attempt", 0L)
        set(v) = prefs.edit().putLong("attempt", v).apply()

    val ready: Boolean get() = url.isNotBlank() && user.isNotBlank() && password.isNotEmpty()

    companion object {
        // 与句子库的 yiju-sync.json 分开，单独一份 progress.json
        const val DEFAULT_URL = "https://dav.jianguoyun.com/dav/Yiju/progress.json"
    }
}
