package com.os4.immersive

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.RoundedCorner
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs

/**
 * The widgets that can go in a slot, chosen inside StandBy itself, laid out landscape and turned
 * with the page by the activity (a picker activity of its own came up portrait). As iPhone's:
 * the apps down the left, the chosen app's widgets on the right one at a time - swiped or
 * dotted through - and "Add Widget" under them. A half slot is offered the 2x2 ones alone.
 * [replace]: changing a widget, offered those of its own size, and the button says "Replace".
 */
@SuppressLint("ViewConstructor", "SetTextI18n")
class WidgetPicker(context: Context, wide: Boolean, replace: Boolean, private val onPick: (HiWidget.Kind) -> Unit,
                   private val onClose: () -> Unit) : FrameLayout(context) {

    private val dens = resources.displayMetrics.density
    private val kinds = HiWidget.ALL.filter { if (replace) it.wide == wide else wide || !it.wide }
    private val groups = kinds.map { it.group }.distinct()
    private var group = groups.first()
    private var index = 0
    private val variants get() = kinds.filter { it.group == group }

    private val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val title = text(22f, Color.WHITE, 700).apply { gravity = Gravity.CENTER }
    private val about = text(14f, Color.argb(140, 255, 255, 255), 400).apply { gravity = Gravity.CENTER }
    private val preview = FrameLayout(context)
    private val dots = LinearLayout(context).apply { gravity = Gravity.CENTER }
    private val panel = LinearLayout(context)

    init {
        setBackgroundColor(Color.argb(150, 0, 0, 0))
        setOnClickListener { close() }
        panel.apply {
            orientation = LinearLayout.HORIZONTAL
            // The screen's own corner, less the margin: rounded alongside the display's edge.
            val screen = context.display?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius ?: 0
            background = round(Color.rgb(44, 44, 46), maxOf(28f, (screen - dp(14)) / dens))
            isClickable = true
            setPadding(dp(24), dp(24), dp(30), dp(24))
            addView(ScrollView(context).apply {
                isVerticalScrollBarEnabled = false
                addView(rows)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.36f))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(20), dp(4), 0, 0)
                addView(title)
                addView(about)
                addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                addView(dots, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(20)))
                addView(text(17f, Color.WHITE, 600).apply {
                    setText(if (replace) R.string.widget_replace else R.string.widget_add)
                    gravity = Gravity.CENTER
                    background = round(Color.rgb(235, 87, 72), 26f)
                    setOnClickListener { variants.getOrNull(index)?.let(onPick); close() }
                    setOnTouchListener { b, e ->
                        when (e.actionMasked) {
                            MotionEvent.ACTION_DOWN -> b.animate().scaleX(0.96f).scaleY(0.96f).setDuration(FADE_MS).start()
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                                b.animate().scaleX(1f).scaleY(1f).setDuration(FADE_MS).start()
                        }
                        false
                    }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply {
                    topMargin = dp(8)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.64f))
        }
        addView(panel, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT).apply {
            setMargins(dp(48), dp(14), dp(48), dp(14))
        })
        preview.setOnTouchListener(swipe())
        // Sliding widgets fade out over the panel, cut only by its rounded edge, not the preview's.
        preview.clipChildren = false
        (preview.parent as ViewGroup).apply { clipChildren = false; clipToPadding = false }
        panel.clipChildren = false
        panel.clipToOutline = true
        show()
        alpha = 0f
        panel.scaleX = 0.94f
        panel.scaleY = 0.94f
        animate().alpha(1f).setDuration(FADE_MS).start()
        panel.animate().scaleX(1f).scaleY(1f).setDuration(FADE_MS).start()
    }

    fun close() {
        if (!isEnabled) return
        isEnabled = false
        animate().alpha(0f).setDuration(FADE_MS).withEndAction(onClose).start()
    }

    /**
     * The left list, the chosen app's widget [index] and its dots, as they now stand. [dir] is
     * which way the widgets moved: the shown one slides off that way and the new one in behind it.
     */
    private fun show(dir: Int = 0) {
        // Built once, so a choice only moves the highlight: the icons stay as first drawn.
        if (rows.childCount == 0) for (g in groups) rows.addView(LinearLayout(context).apply {
            tag = g
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            addView(widget(kinds.first { it.group == g }.id, icon = true), LinearLayout.LayoutParams(dp(30), dp(30)))
            addView(text(17f, Color.WHITE, 400).apply {
                setText(g)
                setPadding(dp(14), 0, 0, 0)
            })
            setOnClickListener {
                group = g
                index = 0
                show()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(2) })
        for (i in 0 until rows.childCount) rows.getChildAt(i).apply {
            background = if (tag == group) round(Color.rgb(66, 66, 70), 14f) else null
        }

        val kind = variants[index]
        title.setText(kind.name)
        about.setText(kind.about)
        val w = preview.width.toFloat()
        for (i in preview.childCount - 1 downTo 0) {
            val old = preview.getChildAt(i)
            if (dir == 0) preview.removeView(old)
            else old.animate().translationX(-dir * w * TRAVEL).alpha(0f).scaleX(0.92f).scaleY(0.92f)
                .setDuration(SLIDE_MS).setInterpolator(EASE).withEndAction { preview.removeView(old) }.start()
        }
        val h = dp(124)
        val shown = widget(kind.id, icon = false)
        preview.addView(shown, LayoutParams(if (kind.wide) h * 2 + dp(10) else h, h, Gravity.CENTER))
        if (dir != 0) {
            shown.translationX = dir * w * TRAVEL
            shown.alpha = 0f
            shown.scaleX = 0.92f
            shown.scaleY = 0.92f
            shown.animate().translationX(0f).alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(SLIDE_MS).setInterpolator(EASE).start()
            for (t in listOf(title, about)) {
                t.alpha = 0f
                t.animate().alpha(1f).setDuration(SLIDE_MS).setInterpolator(EASE).start()
            }
        }
        dots.removeAllViews()
        if (variants.size > 1) for (i in variants.indices) dots.addView(View(context).apply {
            background = round(if (i == index) Color.WHITE else Color.argb(90, 255, 255, 255), 4f)
            setOnClickListener { if (i != index) { val d = Integer.signum(i - index); index = i; show(d) } }
        }, LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginStart = dp(5); marginEnd = dp(5) })
    }

    /**
     * Across the preview: the widget follows the finger (held back where there is no next one),
     * and let go far enough it gives way to the app's next or previous widget; else springs back.
     */
    private fun swipe() = object : OnTouchListener {
        private var downX = 0f
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val dx = e.x - downX
            val step = if (dx < 0) 1 else -1
            val to = index + step
            val shown = preview.getChildAt(preview.childCount - 1)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> downX = e.x
                MotionEvent.ACTION_MOVE -> shown?.apply {
                    animate().cancel()
                    translationX = if (to in variants.indices) dx else dx * 0.25f
                    alpha = 1f - 0.5f * abs(translationX) / preview.width.coerceAtLeast(1)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    if (e.actionMasked == MotionEvent.ACTION_UP && abs(dx) > dp(32) && to in variants.indices) {
                        index = to
                        show(step)
                    } else shown?.animate()?.translationX(0f)?.alpha(1f)?.setDuration(SLIDE_MS)
                        ?.setInterpolator(EASE)?.start()
            }
            return true
        }
    }

    /** A live widget, rounded like a slot, or a still one, rounded a little, as an app's icon in the list. */
    private fun widget(kind: Int, icon: Boolean) = HiWidget(context, kind, live = !icon).apply {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) =
                o.setRoundRect(0, 0, v.width, v.height,
                    if (icon) dp(7).toFloat() else v.height * StandbyView.CORNER)
        }
        clipToOutline = true
    }

    private fun text(sp: Float, color: Int, weight: Int) = TextView(context).apply {
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        typeface = Typeface.create(Typeface.DEFAULT, weight, false)
    }

    private fun round(color: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusDp * dens
    }

    private fun dp(v: Int) = (v * dens).toInt()

    private companion object {
        const val FADE_MS = 200L
        const val SLIDE_MS = 380L
        /** How far across the preview a widget travels leaving or arriving. */
        const val TRAVEL = 0.4f
        /** Android's emphasized decelerate: quick off the finger, a long soft landing. */
        val EASE = android.view.animation.PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    }
}
