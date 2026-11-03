package com.os4.immersive

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import android.widget.FrameLayout

/**
 * A slot's rounded frame holding its stack of widgets, one child each. Managed ([managed] > 0,
 * the stack's editor, as iPhone's), it also draws each widget's name under it and a "-" at its
 * corner, faded in by [managed].
 */
class StackFrame(context: Context) : FrameLayout(context) {

    var managed = 0f
        set(v) {
            field = v
            invalidate()
        }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, 500, false)
    }
    private val rect = RectF()

    /** Child [i]'s drawn bounds in this frame, its turn and scale applied. */
    fun childRect(i: Int, out: RectF = rect): RectF {
        val v = getChildAt(i)
        val cx = v.left + v.width / 2f + v.translationX
        val cy = v.top + v.height / 2f + v.translationY
        out.set(cx - v.width * v.scaleX / 2f, cy - v.height * v.scaleY / 2f,
            cx + v.width * v.scaleX / 2f, cy + v.height * v.scaleY / 2f)
        return out
    }

    /** The "-" of child [i]: its centre and radius, in this frame. */
    fun badgeOf(i: Int): Triple<Float, Float, Float> {
        val r = childRect(i)
        return Triple(r.left + r.height() * 0.04f, r.top + r.height() * 0.04f, height * 0.04f)
    }

    override fun dispatchDraw(c: Canvas) {
        super.dispatchDraw(c)
        if (managed <= 0f) return
        val a = (255 * managed).toInt()
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            if (v.visibility != View.VISIBLE) continue
            val r = childRect(i)
            text.textSize = height * 0.05f
            text.color = Color.argb(a * 200 / 255, 255, 255, 255)
            val name = HiWidget.ALL.firstOrNull { it.id == (v as? HiWidget)?.kind }?.group ?: ""
            c.drawText(name, r.centerX(), r.bottom + text.textSize * 1.25f, text)
            val (bx, by, br) = badgeOf(i)
            fill.color = Color.argb(a * 235 / 255, 72, 72, 74)
            c.drawCircle(bx, by, br, fill)
            stroke.color = Color.argb(a, 255, 255, 255)
            stroke.strokeWidth = br * 0.18f
            c.drawLine(bx - br * 0.45f, by, bx + br * 0.45f, by, stroke)
        }
    }
}
