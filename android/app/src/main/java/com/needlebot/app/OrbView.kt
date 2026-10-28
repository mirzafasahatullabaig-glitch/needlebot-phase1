package com.needlebot.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View

/**
 * A little robot head drawn in a shaded-3D style: a rounded metallic head with a
 * top-light gradient, two glowing eyes that blink, and an antenna with a pulsing
 * tip. [phase] (0..1, animated) drives a subtle bob, the antenna pulse and the
 * blink — so it reads as an alive bot, not a static icon. Not a polygon mesh; a
 * shaded 2D illusion, the cheap honest way to look 3D here.
 */
class OrbView(ctx: Context, private val core: Int, private val edge: Int) : View(ctx) {

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val eye = Paint(Paint.ANTI_ALIAS_FLAG)
    private val metal = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 0..1 loop; animate to bob/blink/pulse. */
    var phase = 0f
        set(v) { field = v; invalidate() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val ang = phase * 2f * Math.PI.toFloat()
        val bob = kotlin.math.sin(ang).toFloat() * h * 0.03f   // gentle up/down
        val cx = w / 2f
        val headW = w * 0.72f; val headH = h * 0.6f
        val headTop = h * 0.28f + bob
        val head = RectF(cx - headW / 2f, headTop, cx + headW / 2f, headTop + headH)
        val rad = headW * 0.3f

        // antenna: stalk + pulsing glowing tip
        val tipY = headTop - h * 0.16f
        p.shader = null; p.color = Color.parseColor("#8A909C"); p.strokeWidth = w * 0.03f
        c.drawLine(cx, headTop + h * 0.02f, cx, tipY, p)
        val pulse = 0.7f + 0.3f * kotlin.math.sin(ang * 2f).toFloat()
        p.shader = RadialGradient(cx, tipY, w * 0.11f * pulse,
            intArrayOf(Color.WHITE, core, Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, tipY, w * 0.11f * pulse, p)

        // head: metallic rounded body, lit from top-left
        metal.shader = LinearGradient(head.left, head.top, head.left, head.bottom,
            intArrayOf(lighten(core), edge, Color.parseColor("#0C1826")),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawRoundRect(head, rad, rad, metal)
        // top specular sheen
        p.shader = RadialGradient(cx - headW * 0.2f, headTop + headH * 0.18f, headW * 0.5f,
            intArrayOf(Color.argb(120, 255, 255, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        c.drawRoundRect(head, rad, rad, p)

        // eyes: two glowing dots that blink (squash near the blink point of the loop)
        val blink = if (phase > 0.9f) (1f - (phase - 0.9f) / 0.1f * 2f).coerceIn(0.1f, 1f) else 1f
        val eyeY = headTop + headH * 0.46f
        val eyeDx = headW * 0.22f
        val eyeR = headW * 0.11f
        for (ex in floatArrayOf(cx - eyeDx, cx + eyeDx)) {
            eye.shader = RadialGradient(ex, eyeY, eyeR,
                intArrayOf(Color.WHITE, core), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
            c.save(); c.scale(1f, blink, ex, eyeY)
            c.drawCircle(ex, eyeY, eyeR, eye)
            c.restore()
        }
    }

    private fun lighten(col: Int): Int {
        val f = 0.4f
        return Color.rgb(
            (Color.red(col) + (255 - Color.red(col)) * f).toInt(),
            (Color.green(col) + (255 - Color.green(col)) * f).toInt(),
            (Color.blue(col) + (255 - Color.blue(col)) * f).toInt())
    }
}
