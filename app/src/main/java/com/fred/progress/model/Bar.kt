package com.fred.progress.model

import java.util.UUID

/**
 * 一条进度条。字段与云端 JSON 契约一一对应，顺序 / 名字 / 大小写都不能改：
 *   {"uuid":"...","name":"爬山","theme":"stairs","total":20,"current":7,
 *    "updatedAt":1793278288299,"deleted":false}
 *
 * 同步字段含义：
 *   uuid      跨设备唯一且永久不变，合并按它对齐
 *   updatedAt Unix 毫秒，同 uuid 两端都改过时"最后更新为准"
 *   deleted   墓碑：删记录只置 true 并刷新 updatedAt，不物理删
 *
 * 进度就是一个数字，以最后更新为准：不做事件累加、不做乐观锁。
 */
data class Bar(
    var uuid: String = UUID.randomUUID().toString(),
    var name: String = "",
    var theme: String = "stairs",
    var total: Int = 0,
    var current: Int = 0,
    var updatedAt: Long = 0L,
    var deleted: Boolean = false
) {
    companion object {
        const val THEME_STAIRS = "stairs"
        const val THEME_METRO = "metro"
        const val THEME_DOTS = "dots"
    }
}
