package com.needlebot.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/**
 * A tiny CRO/oscilloscope strip for the ESP32 link: a green trace that scrolls
 * right-to-left. When [live] it shows a repeating heartbeat pulse; when not, a
 * flat line. Drive it by advancing [t] from an animator.
 */
class ScopeView(ctx: Context, private val line: Int, private val dim: Int) : View(ctx) {

    var live = false
        set(v) { field = v; invalidate() }

    /** Scroll phase, advanced 0..1 repeatedly by the animator. */
    var t = 0f
        set(v) { field = v; invalidate() }

    private val trace = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.parseColor("#22FFFFFF"); strokeWidth = 1f
    }
    private val path = Path()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val mid = h / 2f
        // faint baseline (scope grid)
        c.drawLine(0f, mid, w, mid, grid)

        trace.color = if (live) line else dim
        trace.strokeWidth = h * 0.09f
        path.reset()
        val n = 48
        for (i in 0..n) {
            val x = w * i / n
            val y = if (live) mid - pulse((i.toFloat() / n + t) % 1f) * (h * 0.38f) else mid
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        c.drawPath(path, trace)
    }

    /** One heartbeat-ish spike per cycle (0..1 phase -> -1..1 amplitude). */
    private fun pulse(p: Float): Float {
        return when {
            p < 0.42f || p > 0.70f -> 0f                                   // flat rest
            p < 0.48f -> (p - 0.42f) / 0.06f                               // rise
            p < 0.54f -> 1f - (p - 0.48f) / 0.06f * 1.6f                   // sharp peak down
            p < 0.60f -> -0.6f + (p - 0.54f) / 0.06f * 0.6f                // undershoot back
            else -> 0f
        }
    }
}
