package com.os4.immersive

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.ZoneId

/** The cities a clock widget can show; none is the phone's own time. */
object WidgetCities {
    class City(val name: String, val zone: ZoneId) {
        val short get() = name.substringBefore(',')
    }

    val ALL = listOf(
        "Hanoi, Vietnam" to "Asia/Ho_Chi_Minh",
        "Bangkok, Thailand" to "Asia/Bangkok",
        "Singapore, Singapore" to "Asia/Singapore",
        "Beijing, China" to "Asia/Shanghai",
        "Seoul, South Korea" to "Asia/Seoul",
        "Tokyo, Japan" to "Asia/Tokyo",
        "Sydney, Australia" to "Australia/Sydney",
        "Dubai, U.A.E." to "Asia/Dubai",
        "Moscow, Russia" to "Europe/Moscow",
        "Berlin, Germany" to "Europe/Berlin",
        "Paris, France" to "Europe/Paris",
        "London, U.K." to "Europe/London",
        "New York, U.S.A." to "America/New_York",
        "Cupertino, U.S.A." to "America/Los_Angeles",
    ).map { City(it.first, ZoneId.of(it.second)) }

    fun named(name: String?) = ALL.firstOrNull { it.name == name }
}

/**
 * The button in a customizable widget's bottom right corner: a rounded square of thin frost over
 * the widget blurred ([source], laid out on [sourceBox]), with MIUIX's settings icon, turning into
 * its back icon once the widget shows its settings ([back], 0 to 1).
 */
class WidgetSettingsButton(context: Context) : View(context) {
    private val dens = resources.displayMetrics.density
    private val frost = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gear = resources.getDrawable(R.drawable.ic_miuix_settings, null).mutate()
    private val arrow = resources.getDrawable(R.drawable.ic_miuix_back, null).mutate()
    private val radius = RADIUS_DP * dens
    private val node = android.graphics.RenderNode("settings-button")
    private val disc = android.graphics.Path()

    /** Over something dark: light frost and a white icon; over something light, the other way. */
    var overDark = true
        set(v) {
            field = v
            look()
        }
    var source: View? = null
    var sourceBox: RectF? = null

    var back = 0f
        set(v) {
            field = v
            invalidate()
        }

    init {
        look()
        elevation = 2 * dens
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, radius)
        }
    }

    private fun look() {
        frost.color = if (overDark) Color.argb(60, 255, 255, 255) else Color.argb(40, 60, 60, 67)
        val icon = if (overDark) Color.WHITE else Color.rgb(28, 28, 30)
        gear.setTint(icon)
        arrow.setTint(icon)
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val src = source
        val box = sourceBox
        if (src != null && box != null && src.width > 0 && c.isHardwareAccelerated) {
            // The widget under it, drawn again here and blurred: Android has no blur of what is behind a view.
            node.setPosition(0, 0, width, height)
            val rc = node.beginRecording(width, height)
            rc.translate(box.left - left, box.top - top)
            rc.scale(box.width() / src.width, box.height() / src.height)
            src.draw(rc)
            node.endRecording()
            val blur = BLUR_DP * dens
            node.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(blur, blur,
                android.graphics.Shader.TileMode.CLAMP))
            disc.rewind()
            disc.addRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, android.graphics.Path.Direction.CW)
            c.save()
            c.clipPath(disc)
            c.drawRenderNode(node)
            c.restore()
            postInvalidateOnAnimation()
        }
        c.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, frost)
        val half = (ICON_DP * dens / 2f).toInt()
        val l = cx.toInt() - half
        val t = cy.toInt() - half
        c.save()
        c.rotate(90f * back, cx, cy)
        gear.setBounds(l, t, l + 2 * half, t + 2 * half)
        gear.alpha = (255 * (1f - back)).toInt()
        gear.draw(c)
        c.restore()
        arrow.setBounds(l, t, l + 2 * half, t + 2 * half)
        arrow.alpha = (255 * back).toInt()
        arrow.draw(c)
    }

    companion object {
        private const val SIZE_DP = 40
        private const val ICON_DP = 24
        private const val RADIUS_DP = 12f
        private const val INNER_DP = 14
        private const val BLUR_DP = 10f

        /** The size of a held widget, lifted off its slot [box] (StandbyView's lift). */
        fun lifted(box: RectF) = RectF(box).apply { inset(-box.width() * 0.03f, -box.height() * 0.03f) }

        /** Where it stands on [lifted]: inside the widget, following the curve of its corner. */
        fun layout(lifted: RectF, dens: Float) = FrameLayout.LayoutParams((SIZE_DP * dens).toInt(), (SIZE_DP * dens).toInt()).apply {
            val corner = lifted.height() * StandbyView.CORNER
            val half = SIZE_DP * dens / 2f
            // How far its own rounded corner reaches from its centre, towards the widget's.
            val reach = (half - RADIUS_DP * dens) * 1.4142f + RADIUS_DP * dens
            val off = corner - (corner - reach - INNER_DP * dens) * 0.7071f
            leftMargin = (lifted.right - off - half).toInt()
            topMargin = (lifted.bottom - off - half).toInt()
        }
    }
}

/**
 * A customizable widget's settings, as iPhone's: the widget turns into a card, right where it is
 * and its size, naming itself and what can be set. Laid out over the whole page (page coordinates,
 * turned with the page by the activity), as [WidgetMenu] is; a tap off the card closes it, and the
 * corner button goes back: from the city list to the card, from the card to the widget.
 */
@SuppressLint("ViewConstructor", "SetTextI18n")
class WidgetSettings(context: Context, kind: HiWidget.Kind, box: RectF, current: WidgetCities.City?,
                     dark: Boolean, private val onPick: (WidgetCities.City?) -> Unit,
                     onDark: (Boolean) -> Unit,
                     onShown: () -> Unit, private val onClosing: () -> Unit,
                     private val onClose: () -> Unit) : FrameLayout(context) {

    private val dens = resources.displayMetrics.density
    private val orange = Color.rgb(255, 159, 10)
    private val gray = Color.argb(150, 235, 235, 245)
    private val card = FrameLayout(context)
    private val button = WidgetSettingsButton(context)
    private val main = LinearLayout(context)
    private val list = ScrollView(context)
    private val rows = LinearLayout(context)
    private val value = text(15f, orange, 400)
    private var picked = current

    init {
        val lifted = WidgetSettingsButton.lifted(box)
        val corner = lifted.height() * StandbyView.CORNER
        setOnClickListener { close() }
        card.apply {
            background = GradientDrawable().apply {
                setColor(Color.rgb(28, 28, 30))
                cornerRadius = corner
            }
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            isClickable = true
        }

        val icon = HiWidget(context, kind.id, live = false).apply {
            this.dark = dark
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) =
                    o.setRoundRect(0, 0, v.width, v.height, 10 * dens)
            }
            clipToOutline = true
        }
        val look = text(15f, orange, 400).apply { text = lookName(dark) }
        var isDark = dark
        main.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(EDGE_DP), dp(EDGE_DP), dp(EDGE_DP), 0)
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(icon, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) })
                addView(text(18f, Color.WHITE, 600).apply { text = kind.group })
            })
            addView(text(13.5f, gray, 400).apply {
                text = if (HiWidget.hasCity(kind.id)) "Set a city to check the time at that location."
                    else "Choose how this widget looks."
            }, margins(top = 14))
            if (HiWidget.hasCity(kind.id)) addView(row("City", value) { showList(true) },
                rowParams(top = 16))
            addView(row("Appearance", look) {
                isDark = !isDark
                look.text = lookName(isDark)
                icon.dark = isDark
                onDark(isDark)
            }, rowParams(top = if (HiWidget.hasCity(kind.id)) 8 else 16))
        }
        card.addView(main)

        rows.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(EDGE_DP - 14), dp(EDGE_DP - 10), dp(EDGE_DP - 14), dp(60))
        }
        list.apply {
            isVerticalScrollBarEnabled = false
            visibility = GONE
            addView(rows)
        }
        card.addView(list)
        showValue()

        addView(card, LayoutParams(lifted.width().toInt(), lifted.height().toInt()).apply {
            leftMargin = lifted.left.toInt()
            topMargin = lifted.top.toInt()
        })
        addView(button, WidgetSettingsButton.layout(lifted, dens))
        button.setOnClickListener { back() }

        // Exactly over the widget, which goes once this covers it: nothing of it shows round the card.
        card.alpha = 0f
        card.animate().alpha(1f).setDuration(OPEN_MS).withEndAction(onShown).start()
        morph(1f)
    }

    private fun lookName(dark: Boolean) = if (dark) "Dark" else "Light"

    /** A row of the card: what it sets, and to what, in a rounded square. */
    private fun row(label: String, shown: TextView, click: () -> Unit) = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), 0, dp(16), 0)
        background = GradientDrawable().apply {
            setColor(Color.rgb(44, 44, 46))
            cornerRadius = 14 * dens
        }
        addView(text(15f, Color.WHITE, 500).apply { text = label },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(shown)
        setOnClickListener { click() }
    }

    private fun rowParams(top: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
        topMargin = dp(top)
    }

    private fun morph(to: Float) {
        android.animation.ValueAnimator.ofFloat(button.back, to).apply {
            duration = OPEN_MS
            addUpdateListener { button.back = it.animatedValue as Float }
            start()
        }
    }

    private fun showValue() {
        value.text = picked?.name ?: LOCAL
        rows.removeAllViews()
        for (city in listOf<WidgetCities.City?>(null) + WidgetCities.ALL) {
            rows.addView(text(16f, if (city === picked) orange else Color.WHITE, 400).apply {
                text = city?.name ?: LOCAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), 0, dp(14), 0)
                background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(Color.argb(40, 255, 255, 255)), null, null)
                setOnClickListener {
                    picked = city
                    onPick(city)
                    showValue()
                    showList(false)
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))
        }
    }

    private fun showList(on: Boolean) {
        val show = if (on) list else main
        val hide = if (on) main else list
        hide.visibility = GONE
        show.alpha = 0f
        show.visibility = VISIBLE
        show.animate().alpha(1f).setDuration(SWAP_MS).start()
    }

    /** One step back: the city list to the card, the card to the widget. */
    fun back() {
        if (list.visibility == VISIBLE) showList(false) else close()
    }

    private fun close() {
        if (!isEnabled) return
        isEnabled = false
        onClosing()
        morph(0f)
        button.animate().alpha(0f).setDuration(OPEN_MS).start()
        card.animate().alpha(0f).setDuration(OPEN_MS).withEndAction(onClose).start()
    }

    private fun text(sp: Float, color: Int, weight: Int) = TextView(context).apply {
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        typeface = Typeface.create(Typeface.DEFAULT, weight, false)
    }

    private fun margins(top: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(top)
    }

    private fun dp(v: Int) = (v * dens).toInt()

    private companion object {
        const val LOCAL = "This phone"
        /** Everything on the card keeps this far from its edge, clear of its big corners. */
        const val EDGE_DP = 32
        const val OPEN_MS = 260L
        const val SWAP_MS = 180L
    }
}
