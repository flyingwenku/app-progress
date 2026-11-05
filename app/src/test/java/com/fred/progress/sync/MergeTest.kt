package com.fred.progress.sync

import com.fred.progress.model.Bar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 合并规则的硬约束（共享契约）：并集 / 最后更新胜 / 幂等 / 墓碑 / uuid 升序定序。
 * 纯 JVM 单测：不碰 Android / org.json，规则被改坏时 CI 会直接拦住。
 */
class MergeTest {

    private fun bar(
        uuid: String,
        name: String = "爬山",
        at: Long,
        current: Int = 0,
        deleted: Boolean = false
    ) = Bar(
        uuid = uuid,
        name = name,
        theme = Bar.THEME_STAIRS,
        total = 20,
        current = current,
        updatedAt = at,
        deleted = deleted
    )

    /** ① 并集：只有一端有的直接保留，两端各建的各一条都要在 */
    @Test
    fun unionKeepsBarsFromBothSides() {
        val mine = listOf(bar("a", at = 100))
        val theirs = listOf(bar("b", at = 100))

        val merged = Merge.merge(mine, theirs)
        assertEquals(setOf("a", "b"), merged.map { it.uuid }.toSet())
        assertEquals(2, merged.size)
        // 与顺序无关
        assertEquals(merged, Merge.merge(theirs, mine))
    }

    /** ② 最后更新胜：同 uuid，updatedAt 大的那条留下（进度以最后更新为准） */
    @Test
    fun newerUpdatedAtWins() {
        val older = bar("a", name = "旧的", at = 500, current = 3)
        val newer = bar("a", name = "新的", at = 900, current = 7)

        assertEquals("新的", Merge.merge(listOf(older), listOf(newer)).single().name)
        assertEquals("新的", Merge.merge(listOf(newer), listOf(older)).single().name)
        assertEquals(7, Merge.merge(listOf(older), listOf(newer)).single().current)
    }

    /** ③ 幂等 / 收敛：谁先谁后结果一样，再合一次不变；同时间戳也要收敛 */
    @Test
    fun isIdempotentAndOrderIndependent() {
        val a = bar("a", name = "X", at = 1000)
        val b = bar("b", name = "Y", at = 1000)
        val ab = Merge.merge(listOf(a, b), listOf(b))
        val ba = Merge.merge(listOf(b), listOf(a, b))
        assertEquals(ab, ba)
        assertEquals(ab, Merge.merge(ab, ab))
        assertTrue(Merge.sameBars(ab, ba))          // 只比 bars，顺序无关

        // 时间戳完全相等、内容不同：两端也必须挑中同一条
        val x = bar("a", name = "甲", at = 1000, current = 1)
        val y = bar("a", name = "乙", at = 1000, current = 2)
        assertEquals(Merge.merge(listOf(x), listOf(y)), Merge.merge(listOf(y), listOf(x)))
    }

    /** ④ 墓碑：删除刷新 updatedAt 后不能被还留着旧记录的一端复活，且记录不物理删 */
    @Test
    fun tombstoneWinsAndIsNeverResurrected() {
        val deleted = bar("a", at = 900, deleted = true)
        val stale = bar("a", at = 500, deleted = false)

        val merged = Merge.merge(listOf(deleted), listOf(stale))
        assertTrue(merged.single().deleted)
        assertTrue(Merge.merge(listOf(stale), listOf(deleted)).single().deleted)
        assertEquals(1, merged.size)                // 仍在并集里，没被物理删
    }

    /** ⑤ 定序：合并结果固定按 uuid 升序，与 Windows 端 wire format 字节一致 */
    @Test
    fun mergedBarsAreSortedByUuidAscending() {
        val mine = listOf(bar("c", at = 100), bar("a", at = 100))
        val theirs = listOf(bar("b", at = 100))

        assertEquals(listOf("a", "b", "c"), Merge.merge(mine, theirs).map { it.uuid })
    }

    /**
     * ⑥ 同一毫秒两端各改一次：必须挑**定序较大**的那条（与 Windows 端一致）。
     * 只测"换顺序结果相同"是不够的 —— 两端各自收敛到不同的那条，同样幂等但不跨端一致。
     */
    @Test
    fun equalTimestampPicksTheGreaterTieKey() {
        val x = bar("a", name = "甲", at = 1000, current = 1)
        val y = bar("a", name = "乙", at = 1000, current = 2)
        val bigger = if (Merge.tieKey(x) >= Merge.tieKey(y)) x else y

        assertEquals(bigger.current, Merge.merge(listOf(x), listOf(y)).single().current)
        assertEquals(bigger.current, Merge.merge(listOf(y), listOf(x)).single().current)
    }
}
