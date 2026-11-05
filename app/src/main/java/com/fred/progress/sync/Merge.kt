package com.fred.progress.sync

import com.fred.progress.model.Bar

/**
 * 纯函数合并规则（不依赖 Android / org.json，方便 JVM 单测把规则锁死）。
 *
 * 契约：
 *  - 按 uuid 取并集；只有一端有的直接保留
 *  - 同 uuid：updatedAt 大的胜；完全相等时按内容定序选一个（保证两端收敛、幂等）
 *  - 删除是墓碑（deleted=true），合并时按普通记录处理，别物理删
 *  - 进度就是一个数字，以最后更新为准：不累加事件、不做乐观锁
 */
object Merge {

    fun merge(mine: List<Bar>, theirs: List<Bar>): List<Bar> {
        val map = LinkedHashMap<String, Bar>()
        for (b in mine) {
            if (b.uuid.isNotEmpty()) map[b.uuid] = b.copy()
        }
        for (b in theirs) {
            if (b.uuid.isEmpty()) continue
            val local = map[b.uuid]
            map[b.uuid] = if (local == null) b.copy() else winner(local, b).copy()
        }
        // 按 uuid 升序定序输出：与 Windows 端 wire format 一致，
        // 两端各跑一次得到完全相同的顺序（字节级），才算收敛、不会来回白写。
        return map.values.sortedBy { it.uuid }
    }

    /**
     * 最后更新为准；时间戳完全相等时按内容定序。
     *
     * ⚠️ **两端必须挑中同一条**：Windows 端用
     *   `$"{Name}|{Theme}|{Total:D10}|{Current:D10}|{(Deleted ? 1 : 0)}"`
     * 做 String.CompareOrdinal 并挑**较大**的那个。这里必须用完全一样的键、
     * 一样挑较大，否则同一毫秒两端各改一次会各选各的、永远收敛不了。
     * 数字补零到 10 位，保证字符串比较与数值比较结果一致。
     */
    private fun winner(a: Bar, b: Bar): Bar {
        if (a.updatedAt != b.updatedAt) return if (a.updatedAt > b.updatedAt) a else b
        return if (tieKey(a) >= tieKey(b)) a else b
    }

    /** 与 Windows 端 Bar.TieKey() 逐字一致（分隔符、补零位数、deleted 的 1/0 都不能变） */
    fun tieKey(b: Bar): String = listOf(
        b.name,
        b.theme,
        b.total.toString().padStart(10, '0'),
        b.current.toString().padStart(10, '0'),
        if (b.deleted) "1" else "0"
    ).joinToString("|")

    /**
     * 只比 bars 内容（按 uuid 对齐，与顺序无关），用来判断要不要 PUT。
     *
     * 为什么只比 bars、不把 deviceId 算进去：
     * deviceId 在 JSON 根层，只是"最后是谁写的"，每台设备都不同。
     * 若拿整个文件（含 deviceId）比较，两端会永远觉得对方那份"变了"，
     * 于是一次次互相覆盖、来回空转。bars 才是真正的数据。
     */
    fun sameBars(a: List<Bar>, b: List<Bar>): Boolean {
        if (a.size != b.size) return false
        val ma = a.associateBy { it.uuid }
        val mb = b.associateBy { it.uuid }
        if (ma.keys != mb.keys) return false
        return ma.keys.all { ma[it] == mb[it] }
    }
}
