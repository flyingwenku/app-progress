package com.fred.progress.model

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 进度条仓库：数据存在 App 私有目录 context.filesDir/progress.json，
 * 零第三方依赖（只用系统自带的 org.json 解析）。
 *
 * 文件格式（与 Windows 端共享，字段顺序 / 名字 / 大小写都不能改）：
 *   {"v":1,"deviceId":"<本机随机uuid，仅标记最后写入者>","bars":[
 *      {"uuid":"...","name":"爬山","theme":"stairs","total":20,"current":7,
 *       "updatedAt":1793278288299,"deleted":false}]}
 *
 * 删除是墓碑：deleted=true 并刷新 updatedAt，**不物理删记录** ——
 * 否则另一端（还没删的那家）下次同步会把它原样送回来，删了等于没删。
 */
class BarStore(context: Context) {

    private val file = File(context.filesDir, "progress.json")
    private val bars: MutableList<Bar> = mutableListOf()

    /** 本机随机 uuid，仅标记"最后是谁写的"；同步比较时不要把它算进去 */
    var deviceId: String = UUID.randomUUID().toString()
        private set

    init {
        val loaded = load()
        bars.clear()
        bars.addAll(loaded.bars)
        deviceId = loaded.deviceId
        // 首次启动就落盘，保证 deviceId 跨启动稳定（它只作"最后写入者"标记，不参与比较）
        if (!file.exists()) save()
    }

    /** UI 只显示 deleted=false 的条目 */
    fun bars(): List<Bar> = bars.filter { !it.deleted }

    /** 含墓碑在内的全部记录（同步层用，绝不能只传 alive 的） */
    fun allIncludingDeleted(): List<Bar> = bars.toList()

    fun add(name: String, total: Int, theme: String): Bar {
        val b = Bar(
            uuid = UUID.randomUUID().toString(),
            name = name.trim().ifEmpty { "未命名" },
            theme = theme,
            total = total,
            current = 0,
            updatedAt = now(),
            deleted = false
        )
        bars.add(b)
        save()
        return b
    }

    /**
     * +1 / -1：进度就是一个数字，以最后更新为准，不累加事件、不做乐观锁。
     * 夹在 0..total 之间只是为了显示合理（进度条不该越界）。
     */
    fun step(uuid: String, delta: Int) {
        val b = bars.firstOrNull { it.uuid == uuid && !it.deleted } ?: return
        b.current = (b.current + delta).coerceIn(0, b.total)
        b.updatedAt = now()
        save()
    }

    fun update(uuid: String, name: String, total: Int, theme: String) {
        val b = bars.firstOrNull { it.uuid == uuid && !it.deleted } ?: return
        b.name = name.trim().ifEmpty { b.name }
        b.total = total
        b.theme = theme
        b.current = b.current.coerceIn(0, total)
        b.updatedAt = now()
        save()
    }

    /** 删除 = 写墓碑（刷新 updatedAt），不物理删记录 */
    fun delete(uuid: String) {
        val b = bars.firstOrNull { it.uuid == uuid && !it.deleted } ?: return
        b.deleted = true
        b.updatedAt = now()
        save()
    }

    /** 同步合并完后整体替换并落盘 */
    fun replaceAll(merged: List<Bar>) {
        bars.clear()
        bars.addAll(merged.map { it.copy() })
        save()
    }

    /** 落成整份文件：本地存的就是这份，PUT 上传的也是这份 */
    fun serialize(): String = toJson(deviceId, bars)

    private fun save() {
        file.writeText(serialize())
    }

    private class Loaded(val bars: List<Bar>, val deviceId: String)

    private fun load(): Loaded {
        if (file.exists()) {
            try {
                val text = file.readText()
                val root = JSONObject(text)
                val dev = root.optString("deviceId").takeIf { it.isNotEmpty() }
                    ?: UUID.randomUUID().toString()
                val parsed = parseBars(text)
                if (parsed != null) return Loaded(parsed, dev)
            } catch (e: Exception) {
                // 文件坏了不能直接崩：当作空库重新开始
            }
        }
        return Loaded(emptyList(), UUID.randomUUID().toString())
    }

    companion object {
        const val FORMAT_VERSION = 1
        fun now(): Long = System.currentTimeMillis()

        /**
         * 解析另一端的整份 JSON，只取 bars 数组；认不出返回 null。
         * 字段按契约名字逐个读，缺字段用默认值兜底（老文件 / 别的端都能兼容）。
         */
        fun parseBars(json: String): List<Bar>? = try {
            val arr = JSONObject(json).optJSONArray("bars") ?: return null
            val out = mutableListOf<Bar>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Bar(
                        uuid = o.optString("uuid", ""),
                        name = o.optString("name", ""),
                        theme = o.optString("theme", Bar.THEME_STAIRS),
                        total = o.optInt("total", 0),
                        current = o.optInt("current", 0),
                        updatedAt = o.optLong("updatedAt", 0L),
                        deleted = o.optBoolean("deleted", false)
                    )
                )
            }
            out
        } catch (e: Exception) {
            null
        }

        /**
         * 手写序列化，保证字段顺序、名字、大小写与契约完全一致。
         * 不用 JSONObject 拼装是因为它的 key 顺序不受控；org.json 只用来解析。
         *
         * bars 固定按 uuid 升序写出（与 Windows 端一致）：内部列表是"创建顺序"，
         * 直接写会让两端文件字节顺序不同 —— 另一端若按有序数组比较，就会每次都判
         * "变了"、来回白写。排序后同一数据集两端产出的字节完全一致。
         */
        fun toJson(deviceId: String, bars: List<Bar>): String = buildString {
            append('{')
            append("\"v\":").append(FORMAT_VERSION).append(',')
            append("\"deviceId\":").append(quote(deviceId)).append(',')
            append("\"bars\":[")
            bars.sortedBy { it.uuid }.forEachIndexed { i, b ->
                if (i > 0) append(',')
                append(barToJson(b))
            }
            append("]}")
        }

        private fun barToJson(b: Bar): String = buildString {
            append('{')
            append("\"uuid\":").append(quote(b.uuid)).append(',')
            append("\"name\":").append(quote(b.name)).append(',')
            append("\"theme\":").append(quote(b.theme)).append(',')
            append("\"total\":").append(b.total).append(',')
            append("\"current\":").append(b.current).append(',')
            append("\"updatedAt\":").append(b.updatedAt).append(',')
            append("\"deleted\":").append(b.deleted)
            append('}')
        }

        /** 借用 org.json 的转义实现，正确处理名字里的引号 / 换行 / 中文 */
        private fun quote(s: String): String = JSONObject.quote(s)
    }
}
