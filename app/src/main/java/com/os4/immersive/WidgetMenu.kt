package com.os4.immersive

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A held widget's menu, beside it as on iPhone: to its right when it stands on the page's left,
 * to its left otherwise. Drawn as HyperOS's own (MIUIX) dropdown: a dark, slightly see-through
 * card with 25dp corners and a soft shadow, rows with an icon on the left and medium text, no
 * dividers. Laid out over the whole page (page coordinates, turned with the page by the
 * activity); a tap on the widget itself is [onBoxTap] (its stack's editor), anywhere else closes
 * it. [box] is the widget's slot.
 */
@SuppressLint("ViewConstructor", "SetTextI18n")
class WidgetMenu(context: Context, box: RectF, pageW: Int, pageH: Int,
                 onChange: () -> Unit, onRemove: () -> Unit, onBoxTap: () -> Unit,
                 private val onClose: () -> Unit, onSettings: (() -> Unit)? = null,
                 widget: android.view.View? = null, widgetDark: Boolean = true) : FrameLayout(context) {

    private val dens = resources.displayMetrics.density
    private val card = LinearLayout(context)
    private val gear = WidgetSettingsButton(context)

    private var downX = 0f
    private var downY = 0f

    init {
        setOnTouchListener { _, e ->
            if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                downX = e.x
                downY = e.y
            }
            false
        }
        setOnClickListener { if (box.contains(downX, downY)) onBoxTap() else close() }
        val w = dp(220)
        val rowH = dp(56)
        val pad = dp(8)
        val h = rowH * 2 + pad * 2
        val right = box.centerX() < pageW / 2f
        // Clear of the stack's dots, which stand just beside it.
        val gap = dp(28)
        val x = (if (right) box.right + gap else box.left - gap - w).toInt()
            .coerceIn(dp(12), pageW - w - dp(12))
        val y = (box.centerY() - h / 2f).toInt().coerceIn(dp(12), pageH - h - dp(12))
        card.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, pad, 0, pad)
            background = GradientDrawable().apply {
                setColor(Color.argb(214, 36, 36, 38))
                cornerRadius = 25 * dens
            }
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            elevation = 12 * dens
            isClickable = true
            addView(row(Icon.CHANGE, context.getString(R.string.widget_change), Color.WHITE) { close(); onChange() },
                LinearLayout.LayoutParams(w, rowH))
            addView(row(Icon.REMOVE, context.getString(R.string.widget_remove), Color.rgb(255, 69, 58)) { close(); onRemove() },
                LinearLayout.LayoutParams(w, rowH))
        }
        addView(card, LayoutParams(w, h).apply {
            leftMargin = x
            topMargin = y
        })
        // A widget that can be set up has its way there on its corner.
        if (onSettings != null) gear.apply {
            val lifted = WidgetSettingsButton.lifted(box)
            source = widget
            overDark = widgetDark
            sourceBox = lifted
            addView(this, WidgetSettingsButton.layout(lifted, dens))
            scaleX = 0f
            scaleY = 0f
            animate().scaleX(1f).scaleY(1f).setDuration(OPEN_MS)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.2f)).start()
            setOnClickListener {
                visibility = GONE
                onSettings()
            }
        }
        // Grows out of the widget's side.
        card.pivotX = if (right) 0f else w.toFloat()
        card.pivotY = h / 2f
        card.alpha = 0f
        card.scaleX = 0.6f
        card.scaleY = 0.6f
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(OPEN_MS)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.2f)).start()
    }

    fun close() {
        if (!isEnabled) return
        isEnabled = false
        gear.animate().cancel()
        gear.animate().scaleX(0f).scaleY(0f).setDuration(CLOSE_MS).start()
        card.animate().alpha(0f).scaleX(0.8f).scaleY(0.8f).setDuration(CLOSE_MS)
            .setInterpolator(null).withEndAction(onClose).start()
    }

    private fun row(icon: Icon, label: String, color: Int, click: () -> Unit) = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(20), 0, dp(20), 0)
        addView(ImageView(context).apply { setImageDrawable(IconDrawable(icon, color, dens)) },
            LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(14) })
        addView(TextView(context).apply {
            text = label
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.create(Typeface.DEFAULT, 500, false)
        })
        background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(Color.argb(40, 255, 255, 255)), null, null)
        setOnClickListener { if (this@WidgetMenu.isEnabled) click() }
    }

    private fun dp(v: Int) = (v * dens).toInt()

    private enum class Icon { CHANGE, REMOVE }

    /** The rows' icons, drawn in strokes: two opposed arrows (swap), and a bin. */
    private class IconDrawable(private val icon: Icon, color: Int, dens: Float) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = 1.8f * dens
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val path = Path()

        override fun draw(c: Canvas) {
            val b = bounds
            // In a 24-unit box, as icon sets are drawn.
            val u = b.width() / 24f
            fun x(v: Float) = b.left + v * u
            fun y(v: Float) = b.top + v * u
            path.reset()
            when (icon) {
                Icon.CHANGE -> {
                    path.moveTo(x(4f), y(8f)); path.lineTo(x(19f), y(8f))
                    path.moveTo(x(15f), y(4f)); path.lineTo(x(19f), y(8f)); path.lineTo(x(15f), y(12f))
                    path.moveTo(x(20f), y(16f)); path.lineTo(x(5f), y(16f))
                    path.moveTo(x(9f), y(12f)); path.lineTo(x(5f), y(16f)); path.lineTo(x(9f), y(20f))
                }
                Icon.REMOVE -> {
                    path.moveTo(x(4f), y(6.5f)); path.lineTo(x(20f), y(6.5f))
                    path.moveTo(x(9f), y(6.5f)); path.lineTo(x(9f), y(4f)); path.lineTo(x(15f), y(4f))
                    path.lineTo(x(15f), y(6.5f))
                    path.moveTo(x(6f), y(6.5f)); path.lineTo(x(7f), y(19f))
                    path.quadTo(x(7.2f), y(20.5f), x(8.7f), y(20.5f)); path.lineTo(x(15.3f), y(20.5f))
                    path.quadTo(x(16.8f), y(20.5f), x(17f), y(19f)); path.lineTo(x(18f), y(6.5f))
                    path.moveTo(x(10f), y(10.5f)); path.lineTo(x(10f), y(16.5f))
                    path.moveTo(x(14f), y(10.5f)); path.lineTo(x(14f), y(16.5f))
                }
            }
            c.drawPath(path, paint)
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private companion object {
        const val OPEN_MS = 280L
        const val CLOSE_MS = 160L
    }
}
