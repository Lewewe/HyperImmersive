package com.os4.immersive

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.animation.PathInterpolator
import kotlin.math.max

/**
 * StandBy's first page: the music. For now the artwork and how it moves.
 *
 * - Playing, the cover stands at full size; paused, it sinks back to [PAUSED_SCALE] with its
 *   shadow, the way Apple Music's Now Playing does - with a little overshoot each way.
 * - A new track: the old cover shrinks and fades as the new one grows in from [SWAP_FROM].
 *   A refreshed picture of the same track (a sharper copy arriving late) is swapped in place,
 *   with no animation - the key, not the bitmap, says whether the song changed.
 * - No music: a placeholder square in the cover's place.
 */
class MusicPage(private val context: android.content.Context, private val dens: Float,
                private val invalidate: () -> Unit) {

    /**
     * HyperOS's own transport icons, read out of SystemUI: the animated vectors its media card
     * uses (MediaActionsKt.getStandardAction / MiuiMediaActionButtonUtils) - pause shown while
     * playing and play while paused, each morphing in when it is put up, and next/previous
     * playing their nudge when tapped. Plain AnimatedVectorDrawables, so another app can
     * inflate them from SystemUI's resources. Missing ones fall back to drawn shapes.
     */
    private val sysui = runCatching {
        context.createPackageContext("com.android.systemui", 0)
    }.getOrNull()
    private val iconCallback = object : android.graphics.drawable.Drawable.Callback {
        override fun invalidateDrawable(who: android.graphics.drawable.Drawable) = invalidate()
        override fun scheduleDrawable(who: android.graphics.drawable.Drawable, what: Runnable, `when`: Long) {
            main.postAtTime(what, who, `when`)
        }
        override fun unscheduleDrawable(who: android.graphics.drawable.Drawable, what: Runnable) {
            main.removeCallbacks(what, who)
        }
    }

    private fun hyperIcon(name: String): android.graphics.drawable.Drawable? = runCatching {
        val ctx = sysui ?: return null
        val id = ctx.resources.getIdentifier(name, "drawable", "com.android.systemui")
        if (id == 0) null else ctx.getDrawable(id)?.mutate()?.apply {
            setTint(Color.WHITE)
            callback = iconCallback
        }
    }.getOrNull()

    private val prevIcon = hyperIcon("ic_media_prev")
    private val nextIcon = hyperIcon("ic_media_next")
    private var playIcon: android.graphics.drawable.Drawable? = null
    /** Which state [playIcon] was put up for; a change puts up the other and starts it. */
    private var playIconFor: Boolean? = null

    private fun start(d: android.graphics.drawable.Drawable?) {
        (d as? android.graphics.drawable.Animatable)?.let { it.stop(); it.start() }
    }

    private fun playIconFor(running: Boolean): android.graphics.drawable.Drawable? {
        if (playIconFor != running) {
            playIconFor = running
            playIcon = hyperIcon(if (running) "ic_media_pause_animation" else "ic_media_play_animation")
            start(playIcon)
        }
        return playIcon
    }

    private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val placeholder = Paint(Paint.ANTI_ALIAS_FLAG)
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, 700, false)
    }
    private val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(170, 255, 255, 255)
        typeface = Typeface.create(Typeface.DEFAULT, 500, false)
    }
    private val rect = RectF()
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val iconPath = android.graphics.Path()
    /** Temporary transport buttons, in page coordinates as last drawn: prev, play/pause, next. */
    private val buttons = Array(3) { RectF() }
    private var pressed = -1
    private val m = Matrix()

    private class Cover(var bitmap: Bitmap, val key: String, val title: String, val artist: String)

    private var current: Cover? = null
    private var outgoing: Cover? = null
    /** 0..1 through a track change; 1 when nothing is changing. */
    private var swap = 1f
    private var swapAnim: ValueAnimator? = null

    /** 1 playing, [PAUSED_SCALE] paused. */
    private var playScale = 1f
    private var playTarget = 1f
    private var playAnim: ValueAnimator? = null

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val clear = Runnable { if (!MediaArt.shown && current != null) changeTo(null) }

    fun sync() {
        val art = MediaArt.art
        val key = MediaArt.key
        val cur = current
        if (!MediaArt.shown || art == null) {
            // Players pass through a state-less moment on play/pause; only a lasting one clears.
            if (cur != null) {
                main.removeCallbacks(clear)
                main.postDelayed(clear, 1500L)
            }
        } else {
            main.removeCallbacks(clear)
        }
        if (!MediaArt.shown || art == null) {
            Unit
        } else if (cur == null || cur.key != key) {
            changeTo(Cover(art, key, MediaArt.title, MediaArt.artist))
        } else {
            cur.bitmap = art
        }
        val target = if (MediaArt.running) 1f else PAUSED_SCALE
        if (target != playTarget) {
            playTarget = target
            playAnim?.cancel()
            playAnim = ValueAnimator.ofFloat(playScale, target).apply {
                duration = 560
                interpolator = PathInterpolator(0.34f, 1.3f, 0.64f, 1f)
                addUpdateListener { playScale = it.animatedValue as Float; invalidate() }
                start()
            }
        }
    }

    /** First look: no animation, the page simply has what is playing. */
    fun snap() {
        sync()
        swapAnim?.cancel(); swap = 1f; outgoing = null
        playAnim?.cancel(); playScale = playTarget
    }

    private fun changeTo(next: Cover?) {
        outgoing = current
        current = next
        swapAnim?.cancel()
        swap = 0f
        swapAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 620
            interpolator = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)
            addUpdateListener { swap = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    if (swapAnim === a) outgoing = null
                }
            })
            start()
        }
    }

    /**
     * @param enter the page's share of StandBy's entrance (0..1)
     */
    fun draw(c: Canvas, w: Float, h: Float, enter: Float) {
        if (enter <= 0f) return
        val side = h * 0.70f
        val cx = w * 0.30f
        val cy = h / 2f
        val radius = side * 0.08f
        val base = (1.14f - 0.14f * enter) * playScale

        val out = outgoing
        if (out != null) {
            // The old cover gives way: down to SWAP_TO and out over the first two thirds.
            val t = (swap / 0.66f).coerceIn(0f, 1f)
            cover(c, out.bitmap, cx, cy, side, radius, base * (1f - (1f - SWAP_TO) * t),
                enter * (1f - t))
        }
        val cur = current
        val inT = if (out == null) swap else ((swap - 0.15f) / 0.85f).coerceIn(0f, 1f)
        if (cur != null) {
            cover(c, cur.bitmap, cx, cy, side, radius, base * (SWAP_FROM + (1f - SWAP_FROM) * inT),
                enter * inT)
        } else {
            val s = base * (SWAP_FROM + (1f - SWAP_FROM) * inT)
            rect.set(cx - side * s / 2, cy - side * s / 2, cx + side * s / 2, cy + side * s / 2)
            placeholder.color = Color.argb((60 * enter * inT).toInt(), 255, 255, 255)
            c.drawRoundRect(rect, radius * s, radius * s, placeholder)
        }

        // Title and artist beside the cover; they follow the cover's swap, sliding up a little.
        val textLeft = cx + side / 2f + h * 0.08f
        val textW = w - textLeft - h * 0.08f
        drawText(c, out, textLeft, textW, h, enter * (1f - (swap / 0.5f).coerceIn(0f, 1f)), -1f)
        drawText(c, cur, textLeft, textW, h, enter * inT, 1f - inT)
        drawProgress(c, textLeft, textW, h, enter * (if (cur != null) inT else 0f))
        drawControls(c, textLeft, h, enter)
    }

    // ------------------------------------------------------------------ progress

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE }
    private val timePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, 500, false)
        isFakeBoldText = false
    }
    private val bar = RectF()
    /** 0..1 how thick the bar is drawn: 1 while a finger is on it, like HyperOS's seek bar. */
    private var grip = 0f
    private var gripAnim: ValueAnimator? = null
    /** The fraction a finger is holding, or NaN. */
    private var scrub = Float.NaN
    /** After a seek, the fraction to show until the player reports the new position. */
    private var seekHold = Float.NaN
    private var seekHoldUntil = 0L

    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * HyperOS 4's own seek bar look: HyperProgressSeekBar draws its track through a RuntimeShader
     * (raw/music_player_tracker) with a glowing head texture (miuix_hyper_progressbar_light_head).
     * Both are read from SystemUI at run time, as the icons are, and set up the way the widget
     * sets them up; the fill and glow are then exactly the system's.
     */
    private val trackShader: android.graphics.RuntimeShader? by lazy {
        runCatching {
            val res = sysui!!.resources
            val raw = res.getIdentifier("music_player_tracker", "raw", "com.android.systemui")
            val img = res.getIdentifier("miuix_hyper_progressbar_light_head", "drawable",
                "com.android.systemui")
            val src = res.openRawResource(raw).bufferedReader().use { it.readText() }
            val head = android.graphics.BitmapFactory.decodeResource(res, img)
            android.graphics.RuntimeShader(src).apply {
                setInputShader("uTex", BitmapShader(head, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
                setFloatUniform("uHeadSize", head.width.toFloat(), head.height.toFloat())
                // The gain below brightens the bar; the head's glow is held back by the same
                // factor so it stays at the system's own strength.
                setFloatUniform("uHeadGlowAlpha", 1f / BAR_GAIN)
                setFloatUniform("uTime", 0f)
                setIntUniform("uIsRtl", 0)
            }
        }.getOrNull()
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barGain = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix(
        floatArrayOf(BAR_GAIN, 0f, 0f, 0f, 0f, 0f, BAR_GAIN, 0f, 0f, 0f, 0f, 0f, BAR_GAIN, 0f, 0f,
            0f, 0f, 0f, BAR_GAIN, 0f)))

    /**
     * HyperOS's media card seek row (miui_media_session: HyperProgressSeekBar between
     * media_elapsed_time and media_total_time): elapsed time, the bar, total time on one line.
     * The bar is a 6dp capsule on a #1affffff track, its fill fading up towards a bright,
     * softly glowing head. Kept from before: the bar grows while a finger scrubs it.
     */
    private fun drawProgress(c: Canvas, left: Float, width: Float, h: Float, alpha: Float) {
        val d = MediaArt.duration
        if (alpha <= 0f || d <= 0L || width <= 0f) { bar.setEmpty(); return }
        val y = h * 0.56f
        val barLeft = left
        val barRight = left + width
        bar.set(barLeft, y - h * 0.04f, barRight, y + h * 0.04f)
        val now = android.os.SystemClock.uptimeMillis()
        val live = MediaArt.positionNow().toFloat() / d
        if (!seekHold.isNaN() && (now > seekHoldUntil || kotlin.math.abs(live - seekHold) < 0.01f)) {
            seekHold = Float.NaN
        }
        val f = (if (!scrub.isNaN()) scrub else if (!seekHold.isNaN()) seekHold else live)
            .coerceIn(0f, 1f)

        val thick = dens * (6f + 6f * grip)
        val r = thick / 2f
        val rs = trackShader
        if (rs != null) {
            // The widget is 38dp tall around a 6dp track; the head's glow fills that height.
            val cw = barRight - barLeft
            val ch = thick * 38f / 6f
            rs.setFloatUniform("uResolution", cw, ch)
            rs.setFloatUniform("uTrackCanvasSize", cw, ch)
            rs.setFloatUniform("uTrackPosition", 0f, ch / 2f)
            rs.setFloatUniform("uTrackSize", cw, thick)
            rs.setFloatUniform("uTrackProgress", f)
            trackPaint.shader = rs
            // The shader fills at 0.6 and the widget then draws it at 128/255 (dark style):
            // on the blurred backdrop that read dim. Full paint alpha, and the shader's output
            // lifted by [BAR_GAIN] (rgb and alpha alike - it is premultiplied white).
            trackPaint.alpha = (255 * alpha).toInt().coerceIn(0, 255)
            trackPaint.colorFilter = barGain
            c.save()
            c.translate(barLeft, y - ch / 2f)
            c.drawRect(0f, 0f, cw, ch, trackPaint)
            c.restore()
        } else drawOwnTrack(c, barLeft, barRight, y, thick, r, f, alpha)

        // Under the bar, small, at its two ends - the earlier layout, which the user preferred.
        timePaint.textSize = h * 0.034f
        timePaint.color = Color.argb(((170 + 70 * grip) * alpha).toInt(), 255, 255, 255)
        val ty = y + thick / 2f + h * 0.05f
        timePaint.textAlign = Paint.Align.LEFT
        c.drawText(clock((f * d).toLong()), barLeft, ty, timePaint)
        timePaint.textAlign = Paint.Align.RIGHT
        c.drawText(clock(d), barRight, ty, timePaint)
        timePaint.textAlign = Paint.Align.LEFT
    }

    /** The fallback when SystemUI's shader cannot be had. */
    private fun drawOwnTrack(c: Canvas, barLeft: Float, barRight: Float, y: Float, thick: Float,
                             r: Float, f: Float, alpha: Float) {
        track.strokeWidth = thick
        track.shader = null
        track.color = Color.argb((38 * alpha).toInt(), 255, 255, 255)
        c.drawLine(barLeft + r, y, barRight - r, y, track)
        val head = barLeft + r + (barRight - barLeft - thick) * f
        if (f > 0f) {
            // Normal 128, pressed 230 - HyperProgressSeekBar's progressAlpha / progressPressedAlpha.
            val a = (128 + (230 - 128) * grip) / 255f * alpha
            track.shader = android.graphics.LinearGradient(barLeft, y, head + r, y,
                Color.argb((255 * a * 0.45f).toInt(), 255, 255, 255),
                Color.argb((255 * a).toInt(), 255, 255, 255), Shader.TileMode.CLAMP)
            c.drawLine(barLeft + r, y, head, y, track)
            track.shader = null
            glow.color = Color.argb((255 * alpha).toInt(), 255, 255, 255)
            glow.setShadowLayer(dens * 6f, 0f, 0f, Color.argb((200 * alpha).toInt(), 255, 255, 255))
            c.drawCircle(head, y, r, glow)
        }
    }

    private fun clock(ms: Long): String {
        val t = (ms / 1000).coerceAtLeast(0)
        return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60)
        else "%02d:%02d".format(t / 60, t % 60)
    }

    private fun setGrip(on: Boolean) {
        gripAnim?.cancel()
        gripAnim = ValueAnimator.ofFloat(grip, if (on) 1f else 0f).apply {
            duration = 220
            interpolator = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)
            addUpdateListener { grip = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    /** A finger down on the bar (with some slack around it) starts a scrub. */
    fun scrubStart(x: Float, y: Float): Boolean {
        if (bar.isEmpty) return false
        val slackY = bar.height()
        if (y < bar.top - slackY || y > bar.bottom + slackY ||
            x < bar.left - slackY || x > bar.right + slackY) return false
        setGrip(true)
        scrubMove(x)
        return true
    }

    fun scrubMove(x: Float) {
        scrub = ((x - bar.left) / bar.width()).coerceIn(0f, 1f)
        invalidate()
    }

    fun scrubEnd(commit: Boolean) {
        val f = scrub
        scrub = Float.NaN
        setGrip(false)
        if (!commit || f.isNaN()) return
        val d = MediaArt.duration
        if (d <= 0L) return
        seekHold = f
        seekHoldUntil = android.os.SystemClock.uptimeMillis() + 1500
        MediaArt.seekTo((f * d).toLong())
    }

    private fun drawControls(c: Canvas, left: Float, h: Float, enter: Float) {
        val size = h * 0.11f
        val y = h * 0.78f
        val gap = size * 1.3f
        icon.alpha = (255 * enter).toInt()
        for (i in 0..2) {
            val cx = left + size / 2f + i * (size + gap)
            buttons[i].set(cx - size, y - size, cx + size, y + size)
            val k = if (pressed == i) 0.85f else 1f
            c.save()
            c.scale(k, k, cx, y)
            val d = when (i) {
                0 -> prevIcon
                1 -> playIconFor(MediaArt.running)
                else -> nextIcon
            }
            if (d != null) {
                val half = size * (if (i == 1) 0.75f else 0.6f)
                d.setBounds((cx - half).toInt(), (y - half).toInt(), (cx + half).toInt(), (y + half).toInt())
                d.alpha = (255 * enter).toInt()
                d.draw(c)
            } else when (i) {
                0 -> { triangle(c, cx + size * 0.05f, y, size * 0.45f, -1); triangle(c, cx - size * 0.4f, y, size * 0.45f, -1) }
                1 -> if (MediaArt.running) {
                    val bw = size * 0.22f
                    c.drawRoundRect(cx - size * 0.38f, y - size * 0.45f, cx - size * 0.38f + bw,
                        y + size * 0.45f, bw / 3, bw / 3, icon)
                    c.drawRoundRect(cx + size * 0.38f - bw, y - size * 0.45f, cx + size * 0.38f,
                        y + size * 0.45f, bw / 3, bw / 3, icon)
                } else triangle(c, cx - size * 0.25f, y, size * 0.6f, 1)
                else -> { triangle(c, cx - size * 0.05f, y, size * 0.45f, 1); triangle(c, cx + size * 0.4f, y, size * 0.45f, 1) }
            }
            c.restore()
        }
    }

    /** A triangle pointing [dir] (1 right, -1 left) with its flat side at [x]. */
    private fun triangle(c: Canvas, x: Float, y: Float, len: Float, dir: Int) {
        iconPath.reset()
        iconPath.moveTo(x, y - len * 0.6f)
        iconPath.lineTo(x + dir * len, y)
        iconPath.lineTo(x, y + len * 0.6f)
        iconPath.close()
        c.drawPath(iconPath, icon)
    }

    /** Which button is under a page point, or -1. */
    fun buttonAt(x: Float, y: Float) = buttons.indexOfFirst { it.contains(x, y) }

    fun press(i: Int) { pressed = i; invalidate() }

    fun release(i: Int, fire: Boolean) {
        pressed = -1
        invalidate()
        if (!fire) return
        if (i == 0) start(prevIcon) else if (i == 2) start(nextIcon)
        when (i) {
            0 -> MediaArt.previous()
            1 -> MediaArt.playPause()
            2 -> MediaArt.next()
        }
    }

    private fun drawText(c: Canvas, cover: Cover?, left: Float, width: Float, h: Float,
                         alpha: Float, rise: Float) {
        val title = cover?.title ?: if (cover == null && current == null && outgoing == null)
            "Not Playing" else return
        if (alpha <= 0f || width <= 0f) return
        val dy = rise * h * 0.04f
        titlePaint.textSize = h * 0.075f
        artistPaint.textSize = h * 0.055f
        titlePaint.alpha = (255 * alpha).toInt()
        artistPaint.alpha = (170 * alpha).toInt()
        val t = TextUtils.ellipsize(title, titlePaint, width, TextUtils.TruncateAt.END).toString()
        val base = h * 0.38f
        c.drawText(t, left, base + dy, titlePaint)
        val artist = cover?.artist.orEmpty()
        if (artist.isNotEmpty()) {
            val a = TextUtils.ellipsize(artist, artistPaint, width, TextUtils.TruncateAt.END).toString()
            c.drawText(a, left, base + dy + artistPaint.textSize * 1.4f, artistPaint)
        }
    }

    private fun cover(c: Canvas, b: Bitmap, cx: Float, cy: Float, side: Float, radius: Float,
                      scale: Float, alpha: Float) {
        if (alpha <= 0f) return
        val s = side * scale
        rect.set(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2)
        // The shadow sinks with the cover: deeper when it stands forward, playing.
        val depth = ((scale - PAUSED_SCALE) / (1f - PAUSED_SCALE)).coerceIn(0f, 1.2f)
        shadow.color = Color.argb((255 * alpha).toInt(), 0, 0, 0)
        shadow.setShadowLayer(dens * (14f + 22f * depth), 0f, dens * (6f + 10f * depth),
            Color.argb((150 * alpha).toInt(), 0, 0, 0))
        c.drawRoundRect(rect, radius * scale, radius * scale, shadow)

        val shader = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val k = max(s / b.width, s / b.height)
        m.reset()
        m.postScale(k, k)
        m.postTranslate(rect.left + (s - b.width * k) / 2f, rect.top + (s - b.height * k) / 2f)
        shader.setLocalMatrix(m)
        artPaint.shader = shader
        artPaint.alpha = (255 * alpha).toInt()
        c.drawRoundRect(rect, radius * scale, radius * scale, artPaint)
        artPaint.shader = null
    }

    companion object {
        const val PAUSED_SCALE = 0.84f
        const val SWAP_FROM = 0.9f
        const val SWAP_TO = 0.86f
        /** How much brighter than SystemUI's shader output the bar is drawn. */
        const val BAR_GAIN = 1.45f
    }
}
