package com.os4.immersive

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/** Cubic ease-out: fast, then settling. */
internal fun ease(x: Float): Float {
    val t = 1f - x.coerceIn(0f, 1f)
    return 1f - t * t * t
}

private fun strokePaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeCap = Paint.Cap.ROUND
}

class StatusCluster(private val dens: Float, private val res: android.content.res.Resources, private val wake: (Long) -> Unit,
                    private val redraw: () -> Unit) {
    private val stroke = strokePaint()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val bold = Typeface.create(Typeface.DEFAULT, 700, false)

    /**
     * The phone's status, as iPhone's StandBy gathers it: the row the status bar shows - mobile
     * signal, Wi-Fi, the battery with its percentage - gathers into one round glyph: Wi-Fi (or
     * mobile data) in the middle, the battery an arc over the top as long as the charge, and
     * the signal bars become dots along its open bottom. Leaving, it goes back into the row. In the top corner away from
     * the front camera. Mobile bars 0-4 or -1 for none, Wi-Fi arcs 0-3 or -1, battery 0-1.
     */
    private var cellLevel = -1
    private var wifiLevel = -1
    private var battery = 1f
    private var charging = false
    private var statusAt = 0L
    private var statusIn = false
    private var statusFrom = 0f
    private val statusArc = RectF()
    private val statusRect = RectF()

    fun setStatus(cell: Int, wifi: Int, level: Float, isCharging: Boolean) {
        cellLevel = cell
        wifiLevel = wifi
        battery = level
        charging = isCharging
        redraw()
    }

    /** StandBy came up: the row gathers into the glyph, after a short wait. */
    fun enter() {
        statusAt = SystemClock.uptimeMillis()
        statusIn = true
    }

    /** StandBy goes: the glyph opens back into the row from wherever it is. */
    fun leave() {
        statusFrom = statusMorph(SystemClock.uptimeMillis())
        statusAt = SystemClock.uptimeMillis()
        statusIn = false
    }

    private fun statusMorph(now: Long): Float = if (statusIn) {
        ease(((now - statusAt - STATUS_DELAY_MS) / STATUS_MS.toFloat()).coerceIn(0f, 1f))
    } else {
        statusFrom * (1f - ease(((now - statusAt) / STATUS_OUT_MS.toFloat()).coerceIn(0f, 1f)))
    }

    // ------------------------------------------------------------------ the way out

    /**
     * Over the lock screen the system's gestures are off ([EdgeLock]), so the glyph is the way
     * out in an emergency: [EXIT_TAPS] taps in a row, each one bouncing it, and beside it how
     * many more it takes, coming in with a fade out of a blur. A pause of [TAP_WINDOW_MS]
     * starts the count again.
     */
    private var taps = 0
    private var tapAt = 0L
    private var hintText = ""
    private val hintNode = RenderNode("exitHint")
    private var ringCxNow = 0f

    /** Whether a touch at ([x], [y]) on the page lands on the glyph. */
    fun hit(x: Float, y: Float): Boolean {
        val r = (STATUS_RING_DP + TAP_SLOP_DP) * dens
        val dx = x - ringCxNow
        val dy = y - STATUS_TOP_DP * dens
        return statusIn && dx * dx + dy * dy <= r * r
    }

    /** A tap on the glyph; true on the one that should end StandBy. */
    fun tap(): Boolean {
        val now = SystemClock.uptimeMillis()
        taps = if (now - tapAt < TAP_WINDOW_MS) taps + 1 else 1
        tapAt = now
        val left = EXIT_TAPS - taps
        hintText = res.getQuantityString(R.plurals.standby_exit_hint, left, left)
        wake(TAP_WINDOW_MS)
        redraw()
        return left <= 0
    }

    /** Pressed, then sprung back past its size and settling: 1 at rest. */
    private fun bounce(now: Long): Float {
        val t = (now - tapAt) / 1000f
        if (tapAt == 0L || t > BOUNCE_S) return 1f
        return 1f - 0.16f * exp(-6f * t) * cos(2f * Math.PI.toFloat() * 2.2f * t)
    }

    /** The hint beside the glyph, on its inner side. */
    private fun drawHint(c: Canvas, now: Long, ringCx: Float, cy: Float, ringR: Float, right: Boolean, alpha: Float) {
        val since = now - tapAt
        if (tapAt == 0L || taps <= 0 || since > TAP_WINDOW_MS || taps >= EXIT_TAPS) return
        // In with each tap, out once the window is nearly over.
        val inT = ease(since / HINT_IN_MS.toFloat())
        val outT = ease((since - (TAP_WINDOW_MS - HINT_OUT_MS)) / HINT_OUT_MS.toFloat())
        val a = inT * (1f - outT) * alpha
        if (a <= 0.01f || !c.isHardwareAccelerated) return
        val d = dens
        text.typeface = bold
        text.textSize = 13f * d
        text.textAlign = Paint.Align.LEFT
        text.color = Color.WHITE
        val tw = text.measureText(hintText)
        val pad = 12f * d
        val blur = (1f - a / alpha.coerceAtLeast(0.01f)) * HINT_BLUR_DP * d
        val bw = (tw + 2 * pad).toInt()
        val bh = (text.textSize * 2 + 2 * pad).toInt()
        hintNode.setPosition(0, 0, bw, bh)
        hintNode.setRenderEffect(if (blur > 0.5f) RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.DECAL) else null)
        val rc = hintNode.beginRecording()
        rc.drawText(hintText, pad, bh / 2f - (text.descent() + text.ascent()) / 2f, text)
        hintNode.endRecording()
        hintNode.alpha = a
        val gap = 10f * d
        val x = if (right) ringCx - ringR - gap - tw - pad else ringCx + ringR + gap - pad
        c.save()
        c.translate(x, cy - bh / 2f)
        c.drawRenderNode(hintNode)
        c.restore()
        wake(500L)
    }

    /** [right]: the corner it sits in, the one away from the front camera. */
    fun draw(c: Canvas, w: Float, alpha: Float, right: Boolean) {
        val now = SystemClock.uptimeMillis()
        val m = statusMorph(now)
        if (alpha <= 0f) return
        if (m in 0.001f..0.999f) wake(500L)
        val d = dens
        val cy = STATUS_TOP_DP * d
        val ringR = STATUS_RING_DP * d
        val ringCx = if (right) w - STATUS_SIDE_DP * d - ringR else STATUS_SIDE_DP * d + ringR
        ringCxNow = ringCx
        drawHint(c, now, ringCx, cy, ringR, right, alpha)
        val s = bounce(now)
        if (s != 1f) wake(500L)
        c.save()
        c.scale(s, s, ringCx, cy)
        drawGlyph(c, w, alpha, right, m, ringCx, cy, ringR)
        c.restore()
    }

    private fun drawGlyph(c: Canvas, w: Float, alpha: Float, right: Boolean, m: Float,
                          ringCx: Float, cy: Float, ringR: Float) {
        val d = dens

        // The row, laid out from its corner: mobile bars, Wi-Fi, battery.
        val cellW = 13f * d
        val wifiW = 18f * d
        val batW = 27f * d
        val batH = 13f * d
        val gap = 5f * d
        val hasCell = cellLevel >= 0
        val hasWifi = wifiLevel >= 0
        val rowW = (if (hasCell) cellW + gap else 0f) + (if (hasWifi) wifiW + gap else 0f) + batW
        var x = if (right) w - STATUS_SIDE_DP * d - rowW else STATUS_SIDE_DP * d
        val cellRowX = x + cellW / 2f
        if (hasCell) x += cellW + gap
        val wifiRowX = x + wifiW / 2f
        if (hasWifi) x += wifiW + gap
        val batRowX = x + batW / 2f

        val g = ease((m / 0.6f).coerceIn(0f, 1f))
        val white = Color.argb((255 * alpha).toInt(), 255, 255, 255)
        val dim = Color.argb((80 * alpha).toInt(), 255, 255, 255)

        // The signal: each bar of the row becomes one of the dots along the ring's open bottom.
        if (hasCell) {
            val bw = 2.4f * d
            val step = 3.5f * d
            val hMax = 10f * d
            val left = cellRowX - (step * 3 + bw) / 2f
            val dot = 0.214f * ringR
            for (i in 0 until 4) {
                val bh = hMax * (i + 1) / 4f
                val rx = left + i * step + bw / 2f
                val ry = cy + hMax / 2f - bh / 2f
                // On the ring's circle, kept well off the arc's ends, as iPhone's.
                val inner = STATUS_GAP - 2f * STATUS_DOT_MARGIN
                val ang = Math.toRadians((STATUS_GAP_FROM + STATUS_DOT_MARGIN + inner - i * inner / 3f).toDouble())
                val dx = ringCx + ringR * 0.994f * cos(ang).toFloat()
                val dy = cy + ringR * 0.994f * sin(ang).toFloat()
                val px = rx + (dx - rx) * g
                val py = ry + (dy - ry) * g
                val pw = bw + (dot - bw) * g
                val ph = bh + (dot - bh) * g
                statusRect.set(px - pw / 2f, py - ph / 2f, px + pw / 2f, py + ph / 2f)
                fill.color = if (i < cellLevel) white else dim
                c.drawRoundRect(statusRect, pw / 2f, pw / 2f, fill)
            }
        }

        // In the middle Wi-Fi, or mobile data when there is no Wi-Fi.
        // In the ring it sits on its centre, a touch low, as iPhone's.
        // Its glyph was fitted against a 19dp ring; it keeps that share of the ring.
        if (hasWifi) drawWifi(c, wifiRowX + (ringCx - wifiRowX) * g, cy, 1f + (STATUS_RING_DP / 19f - 1f) * g, white, dim)
        else if (hasCell) drawData(c, ringCx, cy - 1.5f * d, g * alpha)

        val low = battery < 0.2f
        val tint = when {
            charging -> Color.rgb(52, 199, 89)
            low -> Color.rgb(255, 69, 58)
            else -> Color.WHITE
        }
        val lit = Color.argb((255 * alpha).toInt(), Color.red(tint), Color.green(tint), Color.blue(tint))
        val start = STATUS_GAP_FROM + STATUS_GAP
        val span = 360f - STATUS_GAP
        // The battery: first it shrinks to a dot on its way to the arc's start...
        val shrink = ease((m / 0.4f).coerceIn(0f, 1f))
        if (m < 0.4f) {
            val a0 = Math.toRadians(start.toDouble())
            val ex = ringCx + ringR * cos(a0).toFloat()
            val ey = cy + ringR * sin(a0).toFloat()
            val bw = batW + (3.4f * d - batW) * shrink
            val bh = batH + (3.4f * d - batH) * shrink
            val bx = batRowX + (ex - batRowX) * shrink
            val by = cy + (ey - cy) * shrink
            statusRect.set(bx - bw / 2f, by - bh / 2f, bx + bw / 2f, by + bh / 2f)
            fill.color = lit
            c.drawRoundRect(statusRect, bh * 0.32f, bh * 0.32f, fill)
            if (shrink < 0.3f) {
                // Its tip, and the percentage cut out of it.
                val a = 1f - shrink / 0.3f
                statusRect.set(statusRect.right + 1f * d, by - 2.5f * d, statusRect.right + 2.5f * d, by + 2.5f * d)
                fill.color = Color.argb((120 * alpha * a).toInt(), 255, 255, 255)
                c.drawRoundRect(statusRect, 1f * d, 1f * d, fill)
                text.textAlign = Paint.Align.CENTER
                text.typeface = bold
                text.textSize = 9.5f * d
                text.color = Color.argb((235 * alpha * a).toInt(), 0, 0, 0)
                c.drawText(Math.round(battery * 100).toString(), bx, by - (text.descent() + text.ascent()) / 2f, text)
            }
        } else {
            // ...then runs over the top as the arc, as far as the charge; low, its percentage
            // stands in a break at the top.
            val run = ease(((m - 0.4f) / 0.6f).coerceIn(0f, 1f))
            statusArc.set(ringCx - ringR, cy - ringR, ringCx + ringR, cy + ringR)
            stroke.strokeWidth = 0.15f * ringR
            stroke.color = Color.argb((70 * alpha * run).toInt(), 255, 255, 255)
            val brk = if (low) STATUS_LOW_BREAK else 0f
            if (brk > 0f) {
                c.drawArc(statusArc, start, 270f - brk / 2f - start, false, stroke)
                c.drawArc(statusArc, 270f + brk / 2f, start + span - 270f - brk / 2f, false, stroke)
            } else c.drawArc(statusArc, start, span, false, stroke)
            stroke.color = lit
            c.drawArc(statusArc, start, span * battery.coerceIn(0.02f, 1f) * run, false, stroke)
            if (low) {
                text.textAlign = Paint.Align.CENTER
                text.typeface = bold
                text.textSize = 0.53f * ringR
                text.color = Color.argb((255 * alpha * run).toInt(), 255, 255, 255)
                c.drawText(Math.round(battery * 100).toString(), ringCx,
                    cy - ringR - (text.descent() + text.ascent()) / 2f, text)
            }
        }
    }

    /** Mobile data, for the middle without Wi-Fi: an arrow up and an arrow down side by side. */
    private fun drawData(c: Canvas, cx: Float, cy: Float, a: Float) {
        if (a <= 0f) return
        val d = dens
        stroke.strokeWidth = 1.8f * d
        stroke.color = Color.argb((255 * a).toInt(), 255, 255, 255)
        val hh = 4.5f * d
        val head = 2.4f * d
        for ((x, dir) in listOf(cx - 2.6f * d to -1f, cx + 2.6f * d to 1f)) {
            c.drawLine(x, cy - hh, x, cy + hh, stroke)
            val tipY = cy + dir * hh
            c.drawLine(x, tipY, x - head, tipY - dir * head, stroke)
            c.drawLine(x, tipY, x + head, tipY - dir * head, stroke)
        }
    }

    /**
     * Wi-Fi as iPhone draws it: a filled wedge and two arcs over it, [wifiLevel] (1-3) of them
     * lit from the wedge up. Proportions taken from iPhone's StandBy glyph, measured against
     * its ring; ([cx], [cy]) is the ring's centre, the wedge's point sits below it.
     */
    private fun drawWifi(c: Canvas, cx: Float, cy: Float, s: Float, on: Int, off: Int) {
        // Sizes fitted to iPhone's glyph by overlap (tmp/hi-custom-widgets/fit/fit.py), 94%.
        val u = 22f * dens * s
        val base = cy + 0.347f * u
        val sw = 0.115f * u
        stroke.strokeWidth = sw
        for ((i, r) in floatArrayOf(0.356f * u, 0.573f * u).withIndex()) {
            statusArc.set(cx - r, base - r, cx + r, base + r)
            stroke.color = if (i + 2 <= wifiLevel) on else off
            c.drawArc(statusArc, -128.6f, 77.2f, false, stroke)
        }
        val r0 = 0.195f * u
        statusArc.set(cx - r0, base - r0, cx + r0, base + r0)
        fill.color = if (wifiLevel >= 1) on else off
        c.drawArc(statusArc, -135f, 90f, true, fill)
    }

    private companion object {
        /** The ring's radius and its place, as far from the top as its edge is from the side;
         *  the gathering's delay, length and way back; the ring's open bottom where the signal
         *  dots sit (degrees clockwise from 3 o'clock); the low battery's break. */
        private const val STATUS_RING_DP = 15f
        private const val STATUS_SIDE_DP = 26f
        private const val STATUS_TOP_DP = STATUS_SIDE_DP + STATUS_RING_DP + 1f
        private const val STATUS_DELAY_MS = 350L
        private const val STATUS_MS = 1_100L
        private const val STATUS_OUT_MS = 380L
        private const val STATUS_GAP_FROM = 31.4f
        private const val STATUS_GAP = 118f
        private const val STATUS_DOT_MARGIN = 28.4f
        private const val STATUS_LOW_BREAK = 56f
        /** The way out: taps it takes, how long between them, how far off the ring a tap still
         *  counts, the bounce's length, and the hint's way in, way out and blur. */
        private const val EXIT_TAPS = 3
        private const val TAP_WINDOW_MS = 2_000L
        private const val TAP_SLOP_DP = 14f
        private const val BOUNCE_S = 0.7f
        private const val HINT_IN_MS = 280L
        private const val HINT_OUT_MS = 300L
        private const val HINT_BLUR_DP = 10f
    }
}

class VolumeBar(private val dens: Float, private val wake: (Long) -> Unit) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * The volume, as iPhone's StandBy shows it: a bare bar at the top centre, there only while
     * the volume is being changed. It opens out of a short capsule, its fill glides to the new
     * level, pushed past either end it grows and stays grown until the key is let go, and a while
     * after the last press it draws back in and fades.
     */
    private var volTarget = 0f
    private var volShown = 0f
    private var volLast = 0L
    private var volIn = 0f
    private var volPushing = false
    private var volGrown = 0f
    private var volFrame = 0L
    private val volPath = android.graphics.Path()
    private val volRect = RectF()

    /** The volume changed to [level] (0 to 1); [push] is +1 or -1 when pressed past that end. */
    fun show(level: Float, push: Int) {
        val now = SystemClock.uptimeMillis()
        if (volIn <= 0f) volShown = level
        volTarget = level
        volLast = now
        volPushing = push != 0
        wake(VOL_SHOW_MS + (VOL_FADE_MS * 2).toLong())
    }

    /** The key was let go: a bar grown at an end goes back to its size. */
    fun release() {
        if (!volPushing) return
        volPushing = false
        volLast = SystemClock.uptimeMillis()
        wake(VOL_SHOW_MS + (VOL_FADE_MS * 2).toLong())
    }

    fun draw(c: Canvas, w: Float) {
        val now = SystemClock.uptimeMillis()
        val dt = (now - volFrame).coerceIn(0L, 50L).toFloat()
        volFrame = now
        val showing = volPushing || now - volLast < VOL_SHOW_MS
        volIn = (volIn + (if (showing) dt else -dt) / VOL_FADE_MS).coerceIn(0f, 1f)
        if (volIn <= 0f) return
        volShown += (volTarget - volShown) * (dt / VOL_GLIDE_MS).coerceAtMost(1f)
        // As iPhone's: it comes down from out of sight above the top edge, growing from 80% of
        // its size to all of it with a little spring; leaving, it shrinks and goes back up.
        val a = ease((volIn / 0.6f).coerceAtMost(1f))
        val grow = if (showing) StandbyView.LIFT.getInterpolation(volIn) else ease(volIn)
        val k = 0.8f + 0.2f * grow
        volGrown += ((if (volPushing) 1f else 0f) - volGrown) * (dt / VOL_GROW_MS).coerceAtMost(1f)
        val bh = VOL_H_DP * dens * k * (1f + 0.4f * volGrown)
        val bw = VOL_W_DP * dens * k * (1f + 0.08f * volGrown)
        val cx = w / 2f
        val hidden = -bh
        val cy = hidden + (VOL_TOP_DP * dens - hidden) * a
        volRect.set(cx - bw / 2f, cy - bh / 2f, cx + bw / 2f, cy + bh / 2f)
        c.save()
        volPath.reset()
        volPath.addRoundRect(volRect, bh / 2f, bh / 2f, android.graphics.Path.Direction.CW)
        c.clipPath(volPath)
        fill.color = Color.argb(80, 255, 255, 255)
        c.drawRect(volRect, fill)
        fill.color = Color.WHITE
        c.drawRect(volRect.left, volRect.top, volRect.left + bw * volShown.coerceIn(0f, 1f), volRect.bottom, fill)
        c.restore()
    }

    private companion object {
        /** How long it stays after a press, its flight, glide and growth at an end, and its size. */
        private const val VOL_SHOW_MS = 1_500L
        private const val VOL_FADE_MS = 520f
        private const val VOL_GLIDE_MS = 90f
        private const val VOL_GROW_MS = 90f
        private const val VOL_W_DP = 220f
        private const val VOL_H_DP = 5f
        private const val VOL_TOP_DP = 15f
    }
}
