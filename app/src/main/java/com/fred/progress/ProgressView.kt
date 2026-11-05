package com.fred.progress

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.fred.progress.model.Bar

/**
 * 列表里"简单绘制表达"三种主题，不做复杂动画，只画静态一帧：
 *  - stairs 台阶：逐级升高的方块，已完成的填实
 *  - metro 站点：一条轨道 + 站点圆点，走过的轨道变色、当前站套一圈环
 *  - dots 圆点方阵：按宽度自动换行的网格（不是一排），已完成的填实
 *
 * 配色与 Windows 端 CardWindow 一致，两端看着是同一个进度条。
 */
class ProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var total = 0
    private var current = 0
    private var theme = Bar.THEME_STAIRS

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C05A2B")
    }
    private val empty = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E3E0DA")
    }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B9C2BE")
        strokeWidth = 3f
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C05A2B")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    fun set(bar: Bar) {
        total = bar.total
        current = bar.current
        theme = bar.theme
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width - paddingLeft - paddingRight
        val h = height - paddingTop - paddingBottom
        if (w <= 0 || h <= 0 || total <= 0) return
        val cur = current.coerceIn(0, total)
        canvas.save()
        canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())
        when (theme) {
            Bar.THEME_METRO -> drawMetro(canvas, w.toFloat(), h.toFloat(), cur)
            Bar.THEME_DOTS -> drawDots(canvas, w.toFloat(), h.toFloat(), cur)
            else -> drawStairs(canvas, w.toFloat(), h.toFloat(), cur)
        }
        canvas.restore()
    }

    private fun drawStairs(c: Canvas, w: Float, h: Float, cur: Int) {
        val n = total
        val stepW = w / n
        for (i in 0 until n) {
            val left = i * stepW
            val barH = h * (i + 1f) / n
            c.drawRect(left + 1f, h - barH, left + stepW - 1f, h, if (i < cur) fill else empty)
        }
    }

    private fun drawMetro(c: Canvas, w: Float, h: Float, cur: Int) {
        val n = total
        val cell = w / n
        val cy = h / 2f
        val r = minOf(cell * 0.28f, h * 0.22f).coerceAtLeast(3f)
        // 轨道 + 已走过的那段线
        c.drawLine(cell * 0.5f, cy, w - cell * 0.5f, cy, track)
        if (cur > 0) c.drawLine(cell * 0.5f, cy, cell * (cur - 0.5f), cy, fill)
        for (i in 0 until n) {
            val cx = cell * (i + 0.5f)
            c.drawCircle(cx, cy, r, if (i < cur) fill else empty)
            if (i == cur - 1) c.drawCircle(cx, cy, r * 1.6f, ring)   // 当前站
        }
    }

    /** 圆点方阵：按宽度算列数、按总数算行数，行太多时压行高，与 Windows 端同规则 */
    private fun drawDots(c: Canvas, w: Float, h: Float, cur: Int) {
        val n = total
        val cols = Math.max(1, Math.floor((w / 22f).toDouble()).toInt())
        val rows = Math.max(1, (n + cols - 1) / cols)
        val cellW = w / cols
        val cellH = Math.max(8f, Math.min(22f, h / rows))
        val r = minOf(cellW, cellH) * 0.34f
        for (i in 0 until n) {
            val cx = cellW * (i % cols + 0.5f)
            val cy = cellH * (i / cols + 0.5f)
            c.drawCircle(cx, cy, r, if (i < cur) fill else empty)
        }
    }
}
