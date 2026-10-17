package com.os4.immersive

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.text.format.DateFormat
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.animation.PathInterpolator
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The StandBy screen, drawn by hand: page 0 is two widgets side by side (an analog clock and the
 * month), page 1 one big clock - iPhone's first two views. Swiped between like iPhone's pages.
 *
 * Entering, the widgets come in from slightly larger, blurred and transparent, one after the
 * other, the way StandBy settles in once the phone is put on its side; leaving runs it back.
 */
class StandbyView(context: Context) : View(context) {

    private val dens = resources.displayMetrics.density
    private val card = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(28, 28, 30) }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val bold = Typeface.create(Typeface.DEFAULT, 700, false)
    private val heavy = Typeface.create(Typeface.DEFAULT, 800, false)
    private val medium = Typeface.create(Typeface.DEFAULT, 500, false)
    private val rect = RectF()

    private val accent = Color.rgb(255, 149, 0)
    private val red = Color.rgb(255, 69, 58)

    // ------------------------------------------------------------------ paging

    private var page = 0f

    private val music = MusicPage(context, dens) { invalidate() }
    /** Swiped by hand: from then on the page stays where the user put it. */
    private var userPaged = false
    private val onMedia: () -> Unit = {
        post {
            music.sync()
            // Music that turns up after StandBy opened (the session is read a moment late after
            // a restart) still gets the first page, unless the user has already moved.
            if (!userPaged && MediaArt.shown && page != 0f) snapTo(0f)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        MediaArt.addListener(onMedia)
        music.snap()
        // The music page first when there is music, as iPhone opens on Now Playing.
        page = if (MediaArt.shown) 0f else 1f
    }

    override fun onDetachedFromWindow() {
        MediaArt.removeListener(onMedia)
        super.onDetachedFromWindow()
    }
    private var dragX = 0f
    private var dragging = false
    private var downX = 0f
    private var downY = 0f
    private var held = -1
    private var scrubbing = false
    private var velocity: VelocityTracker? = null
    private var snap: ValueAnimator? = null

    // ------------------------------------------------------------------ orientation

    /**
     * The window stays portrait - the lock screen never rotates, so neither does the system -
     * and the landscape page is drawn turned a quarter round instead. [side] is which long edge
     * is up: +1 when the device's right edge is (accelerometer x positive), -1 for the left.
     */
    private var side = 1
    private var angle = 90f
    private var turnAnim: ValueAnimator? = null
    private val toScreen = android.graphics.Matrix()
    private val toPage = android.graphics.Matrix()
    private val lastSent = android.graphics.Matrix().apply { setScale(0f, 0f) }
    /** The page's turn, for the backdrop under it. */
    var onMatrix: ((android.graphics.Matrix) -> Unit)? = null
    /** The entrance's progress, for the backdrop's fade. */
    var onShown: ((Float) -> Unit)? = null

    fun setSide(s: Int, animate: Boolean) {
        if (s == side && turnAnim == null) return
        side = s
        val target = 90f * s
        turnAnim?.cancel()
        if (!animate) {
            angle = target
            turnAnim = null
            return
        }
        turnAnim = ValueAnimator.ofFloat(angle, target).apply {
            duration = 520
            interpolator = PathInterpolator(0.3f, 0f, 0.1f, 1f)
            addUpdateListener { angle = it.animatedValue as Float }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    if (turnAnim === a) turnAnim = null
                }
            })
            start()
        }
    }

    /** The page's own size: the screen's, turned. */
    private val pageW get() = height.toFloat()
    private val pageH get() = width.toFloat()

    private fun updateMatrix() {
        toScreen.reset()
        toScreen.postTranslate(-pageW / 2f, -pageH / 2f)
        toScreen.postRotate(angle)
        toScreen.postTranslate(width / 2f, height / 2f)
        toScreen.invert(toPage)
    }

    override fun onTouchEvent(raw: MotionEvent): Boolean {
        updateMatrix()
        val e = MotionEvent.obtain(raw).apply { transform(toPage) }
        try { return onPageTouch(e) } finally { e.recycle() }
    }

    private fun onPageTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                // A finger on a button of the music page, while that page is the one shown.
                val onMusic = abs(page) < 0.05f
                if (onMusic && music.scrubStart(e.x, e.y)) {
                    scrubbing = true
                    return true
                }
                held = if (onMusic) music.buttonAt(e.x, e.y) else -1
                if (held >= 0) { music.press(held); return true }
                userPaged = true
                snap?.cancel()
                dragX = e.y
                dragging = true
                velocity = VelocityTracker.obtain().apply { addMovement(e) }
            }
            MotionEvent.ACTION_MOVE -> if (scrubbing) {
                music.scrubMove(e.x)
            } else if (held >= 0) {
                if (abs(e.x - downX) > 12 * dens || abs(e.y - downY) > 12 * dens) {
                    music.release(held, false)
                    held = -1
                    userPaged = true
                    dragX = e.y
                    dragging = true
                    velocity = VelocityTracker.obtain().apply { addMovement(e) }
                }
            } else if (dragging) {
                velocity?.addMovement(e)
                // Up and down, like iPhone's StandBy stacks: a swipe up brings the next page.
                // Past either end it gives a little, harder the further it is pulled.
                val dy = e.y - dragX
                dragX = e.y
                val edge = page < 0f || page > PAGES - 1f
                page = (page - dy / (pageH * 0.75f) * (if (edge) 0.35f else 1f))
                    .coerceIn(-0.2f, PAGES - 1 + 0.2f)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (scrubbing) {
                    scrubbing = false
                    music.scrubEnd(e.actionMasked == MotionEvent.ACTION_UP)
                    return true
                }
                if (held >= 0) {
                    music.release(held, e.actionMasked == MotionEvent.ACTION_UP)
                    held = -1
                    return true
                }
                if (!dragging) return true
                dragging = false
                val v = velocity?.run { addMovement(e); computeCurrentVelocity(1000); yVelocity } ?: 0f
                velocity?.recycle()
                velocity = null
                val target = when {
                    v < -800 * dens -> kotlin.math.ceil(page)
                    v > 800 * dens -> kotlin.math.floor(page)
                    else -> Math.round(page).toFloat()
                }.coerceIn(0f, PAGES - 1f)
                snapTo(target)
            }
        }
        return true
    }

    private fun snapTo(target: Float) {
        snap?.cancel()
        snap = ValueAnimator.ofFloat(page, target).apply {
            duration = 460
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener { page = it.animatedValue as Float }
            start()
        }
    }

    // ------------------------------------------------------------------ enter / leave

    /** 0 = nothing on screen, 1 = settled. */
    private var shown = 0f
    private var shownAnim: ValueAnimator? = null

    fun enter() = animateShown(1f, 900L) {}

    fun leave(done: () -> Unit) = animateShown(0f, 420L, done)

    private fun animateShown(to: Float, ms: Long, done: () -> Unit) {
        shownAnim?.cancel()
        shownAnim = ValueAnimator.ofFloat(shown, to).apply {
            duration = (ms * abs(to - shown)).toLong().coerceAtLeast(1)
            interpolator = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)
            addUpdateListener {
                shown = it.animatedValue as Float
                onShown?.invoke(shown)
                val blur = 48f * dens * (1f - shown) * (1f - shown)
                setRenderEffect(if (blur > 0.5f)
                    RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.DECAL) else null)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    if (shownAnim === a) done()
                }
            })
            start()
        }
    }

    /** One element's share of the entrance, [order] steps behind the first. */
    private fun stagger(order: Int): Float {
        val start = order * 0.12f
        return ((shown - start) / (1f - start)).coerceIn(0f, 1f)
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(c: Canvas) {
        val w = pageW
        val h = pageH
        updateMatrix()
        if (toScreen != lastSent) {
            lastSent.set(toScreen)
            onMatrix?.invoke(android.graphics.Matrix(toScreen))
        }
        c.save()
        c.concat(toScreen)
        // iPhone's StandBy stack: the page moving off slides a whole page along the swipe,
        // shrinking as it goes; the one coming in grows out of the same small size into place.
        // No fade - the two stay solid and never overlap.
        for (p in 0 until PAGES) {
            val d = p - page
            val far = abs(d)
            if (far >= 1f) continue
            c.save()
            if (far > 0.001f) {
                val s = 1f - PAGE_SHRINK * far
                c.translate(0f, d * h * PAGE_SLIDE)
                c.scale(s, s, w / 2f, h / 2f)
            }
            when (p) {
                0 -> music.draw(c, w, h, stagger(0))
                1 -> drawWidgets(c, w, h)
                else -> drawBigClock(c, w, h)
            }
            c.restore()
        }
        c.restore()
        // StandBy keeps time to the second; a sweep needs every frame while it is shown.
        if (isAttachedToWindow) postInvalidateOnAnimation()
    }

    private fun withEntrance(c: Canvas, order: Int, cx: Float, cy: Float, body: () -> Unit) {
        val t = stagger(order)
        if (t <= 0f) return
        val s = 1.14f - 0.14f * t
        c.saveLayerAlpha(null, (255 * t).toInt())
        c.scale(s, s, cx, cy)
        body()
        c.restore()
    }

    private fun drawWidgets(c: Canvas, w: Float, h: Float) {
        val gap = 16 * dens
        val side = min(h - 2 * gap, (w - 3 * gap) / 2f)
        val top = (h - side) / 2f
        val left = (w - 2 * side - gap) / 2f
        val r = side * 0.2f

        rect.set(left, top, left + side, top + side)
        withEntrance(c, 0, rect.centerX(), rect.centerY()) {
            c.drawRoundRect(rect, r, r, card)
            drawAnalog(c, rect.centerX(), rect.centerY(), side * 0.42f)
        }
        val l2 = left + side + gap
        rect.set(l2, top, l2 + side, top + side)
        withEntrance(c, 1, rect.centerX(), rect.centerY()) {
            c.drawRoundRect(rect, r, r, card)
            drawCalendar(c, RectF(rect), side)
        }
    }

    private fun drawAnalog(c: Canvas, cx: Float, cy: Float, radius: Float) {
        val now = LocalTime.now()
        val sec = now.second + now.nano / 1e9f
        val min = now.minute + sec / 60f
        val hr = (now.hour % 12) + min / 60f

        for (i in 0 until 60) {
            val a = Math.toRadians(i * 6.0)
            val hour = i % 5 == 0
            val outer = radius
            val inner = radius - (if (hour) 0.09f else 0.045f) * radius
            stroke.color = if (hour) Color.WHITE else Color.argb(110, 255, 255, 255)
            stroke.strokeWidth = (if (hour) 0.022f else 0.01f) * radius
            c.drawLine(cx + (sin(a) * inner).toFloat(), cy - (cos(a) * inner).toFloat(),
                cx + (sin(a) * outer).toFloat(), cy - (cos(a) * outer).toFloat(), stroke)
        }
        text.color = Color.WHITE
        text.typeface = medium
        text.textSize = radius * 0.2f
        for (n in 1..12) {
            val a = Math.toRadians(n * 30.0)
            val rr = radius * 0.74f
            val x = cx + (sin(a) * rr).toFloat()
            val y = cy - (cos(a) * rr).toFloat() - (text.descent() + text.ascent()) / 2f
            c.drawText(n.toString(), x, y, text)
        }
        hand(c, cx, cy, hr * 30f, radius * 0.5f, radius * 0.06f, Color.WHITE)
        hand(c, cx, cy, min * 6f, radius * 0.8f, radius * 0.045f, Color.WHITE)
        hand(c, cx, cy, sec * 6f, radius * 0.88f, radius * 0.016f, accent, tail = radius * 0.15f)
        fill.color = accent
        c.drawCircle(cx, cy, radius * 0.035f, fill)
        fill.color = Color.BLACK
        c.drawCircle(cx, cy, radius * 0.015f, fill)
    }

    private fun hand(c: Canvas, cx: Float, cy: Float, deg: Float, len: Float, width: Float,
                     color: Int, tail: Float = 0f) {
        val a = Math.toRadians(deg.toDouble())
        stroke.color = color
        stroke.strokeWidth = width
        c.drawLine(cx - (sin(a) * tail).toFloat(), cy + (cos(a) * tail).toFloat(),
            cx + (sin(a) * len).toFloat(), cy - (cos(a) * len).toFloat(), stroke)
    }

    private fun drawCalendar(c: Canvas, box: RectF, side: Float) {
        val today = LocalDate.now()
        val pad = side * 0.1f
        val locale = Locale.getDefault()
        text.textAlign = Paint.Align.LEFT
        text.typeface = bold
        text.color = red
        text.textSize = side * 0.075f
        c.drawText(today.month.getDisplayName(TextStyle.FULL, locale).uppercase(locale),
            box.left + pad, box.top + pad + text.textSize, text)

        val gridTop = box.top + pad + side * 0.16f
        val colW = (side - 2 * pad) / 7f
        val rowH = (box.bottom - pad - gridTop) / 7f
        text.textAlign = Paint.Align.CENTER
        text.textSize = side * 0.05f
        text.typeface = bold
        val first = java.time.DayOfWeek.SUNDAY
        for (i in 0 until 7) {
            val d = first.plus(i.toLong())
            text.color = Color.argb(140, 255, 255, 255)
            c.drawText(d.getDisplayName(TextStyle.NARROW, locale),
                box.left + pad + colW * (i + 0.5f), gridTop + rowH * 0.6f, text)
        }
        val start = today.withDayOfMonth(1)
        val lead = (start.dayOfWeek.value % 7)
        text.textSize = side * 0.06f
        for (day in 1..today.lengthOfMonth()) {
            val idx = lead + day - 1
            val col = idx % 7
            val row = idx / 7 + 1
            val x = box.left + pad + colW * (col + 0.5f)
            val y = gridTop + rowH * (row + 0.5f)
            val baseline = y - (text.descent() + text.ascent()) / 2f
            if (day == today.dayOfMonth) {
                fill.color = red
                c.drawCircle(x, y, min(colW, rowH) * 0.46f, fill)
                text.color = Color.WHITE
            } else {
                text.color = if (col == 0 || col == 6) Color.argb(130, 255, 255, 255)
                else Color.WHITE
            }
            c.drawText(day.toString(), x, baseline, text)
        }
    }

    private fun drawBigClock(c: Canvas, w: Float, h: Float) {
        val now = LocalTime.now()
        val h24 = DateFormat.is24HourFormat(context)
        val hour = if (h24) now.hour else (now.hour + 11) % 12 + 1
        val digits = (if (h24) "%02d" else "%d").format(hour) + ":" + "%02d".format(now.minute)
        withEntrance(c, 0, w / 2f, h / 2f) {
            text.textAlign = Paint.Align.CENTER
            text.typeface = heavy
            text.color = Color.rgb(255, 214, 160)
            text.textSize = h * 0.62f
            val baseline = h / 2f - (text.descent() + text.ascent()) / 2f + h * 0.05f
            c.drawText(digits, w / 2f, baseline, text)
            val today = LocalDate.now()
            text.typeface = bold
            text.textSize = h * 0.07f
            text.color = red
            c.drawText(today.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()) +
                " " + today.dayOfMonth, w / 2f, h * 0.16f, text)
        }
    }

    companion object {
        private const val PAGES = 3
        /** How small a page gets on its way out (and in), at a whole page away. */
        private const val PAGE_SHRINK = 0.3f
        /** How far it travels meanwhile, as a share of the page height. */
        private const val PAGE_SLIDE = 1f
    }
}
