package com.fred.progress.sync

import android.content.Context
import com.fred.progress.R
import com.fred.progress.model.Bar
import com.fred.progress.model.BarStore
import com.fred.progress.prefs.SyncPrefs
import java.io.IOException

/**
 * 多端同步（坚果云 WebDAV 单文件）。**必须在后台线程调用**（网络 + 文件读写）。
 *
 * 简化后的流程（用户明确拍板，不要加回去）：
 *   一次 GET（404 = 云端还没有）→ 解析 → merge(本地, 云端)
 *   → 若 bars 部分有变化就一次 PUT 整个文件。
 *
 * 明确禁止：If-Match / ETag / 重试（坚果云 ETag 实测不可信，见 [WebDav.put]）。
 * 明确只比 bars：deviceId 在根层，只是"最后谁写的"，不参与变化判断。
 * 同步本身幂等：一次只跑一个，重复触发也不会打多次网络写。
 */
class Sync(private val context: Context, private val store: BarStore) {

    /** 同步结果：一句话结论，直接拿去 Toast */
    data class Result(val message: String)

    private val lock = Any()

    @Volatile
    private var busy = false

    fun syncNow(): Result {
        // 同时只允许一个同步在跑：连点"立即同步"或自动同步撞上手动那一下，
        // 两个线程同时写会互相踩。
        synchronized(lock) {
            if (busy) return Result(context.getString(R.string.sync_in_progress))
            busy = true
        }
        return try {
            doSync()
        } finally {
            synchronized(lock) { busy = false }
        }
    }

    private fun doSync(): Result {
        val cfg = SyncPrefs(context)
        if (!cfg.ready) return Result(context.getString(R.string.sync_need_account))
        cfg.lastAttemptAt = System.currentTimeMillis()

        val dav = WebDav(cfg.url, cfg.user, cfg.password)
        return try {
            // ① 一次 GET，404 当作"云端还没有"，remote 为空列表
            val fetched = dav.fetch()
            val remote: List<Bar> = if (fetched.exists && fetched.content != null) {
                BarStore.parseBars(fetched.content!!) ?: emptyList()
            } else {
                emptyList()
            }

            // ② 解析 → 按 uuid 合并（本地含墓碑，云端也含墓碑）
            val local = store.allIncludingDeleted()
            val merged = Merge.merge(local, remote)

            // ③ 只比 bars 判断变化：deviceId 在根层，天然不进这个比较。
            //    不把 deviceId 算进去，否则两端各自写一次就永远互相判"变了"。
            val barsChanged = !Merge.sameBars(merged, remote)
            // 本地落后于合并结果时也要落盘：这就是"打开 App 拉一次"的落地。
            if (!Merge.sameBars(merged, local)) store.replaceAll(merged)

            if (!barsChanged) return Result(context.getString(R.string.sync_uptodate))

            // ④ 有变化就一次 PUT 整个文件（含本机 deviceId，但内容以 bars 为准）
            val code = dav.put(store.serialize())
            if (code !in 200..299) throw IOException("PUT $code")
            cfg.lastSyncAt = System.currentTimeMillis()
            Result(context.getString(R.string.sync_ok) + " (PUT $code)")
        } catch (e: Exception) {
            // 失败时把真实原因交给界面 Toast：别只说"失败"。
            // WebDav 以 "GET 401" / "PUT 507" 形式抛出，e.message 里就带着真实 HTTP 码。
            Result(context.getString(R.string.sync_failed, e.message ?: e.toString()))
        }
    }
}
