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

    // ------------------------------------------------------------------ flip

    /**
     * HyperOS's track-change flip (MiuiMediaAlbumAnimationUtils + miuix FlipAnimation): the cover
     * turns half a turn about its vertical axis on a Folme spring (damping 0.72, response 0.5s),
     * forwards for next and backwards for previous; the new artwork takes over the face as it
     * passes edge-on (90°), and the back half is drawn mirrored back so it reads the right way.
     */
    private var flipAngle = 0f
    private var flipTarget = 0f
    private var flipVelocity = 0f
    private var flipLastNs = 0L
    /** Shown until the flip passes edge-on, then [current] takes the face. */
    private var flipFrom: Cover? = null
    private var flipFromAngle = 0f
    private val camera = android.graphics.Camera()
    private val flipMatrix = Matrix()

    private fun startFlip(from: Cover, dir: Int) {
        // Land on the next multiple of 180 in the direction of travel, like FlipAnimation.
        flipTarget = (kotlin.math.round(flipTarget / 180f) + dir) * 180f
        flipFrom = from
        flipFromAngle = flipAngle
        flipLastNs = 0L
        invalidate()
    }

    /** One step of the spring, on the frame clock. */
    private fun stepFlip() {
        if (flipAngle == flipTarget && flipVelocity == 0f) return
        val now = System.nanoTime()
        val dt = if (flipLastNs == 0L) 1f / 120f else ((now - flipLastNs) / 1e9f).coerceIn(0f, 1f / 30f)
        flipLastNs = now
        val k = (2f * Math.PI.toFloat() / FLIP_RESPONSE).let { it * it }
        val damp = 4f * Math.PI.toFloat() * FLIP_DAMPING / FLIP_RESPONSE
        var t = 0f
        while (t < dt) {
            val h = minOf(1f / 480f, dt - t)
            val a = -k * (flipAngle - flipTarget) - damp * flipVelocity
            flipVelocity += a * h
            flipAngle += flipVelocity * h
            t += h
        }
        if (kotlin.math.abs(flipAngle - flipTarget) < 0.05f && kotlin.math.abs(flipVelocity) < 1f) {
            flipAngle = flipTarget
            flipVelocity = 0f
        }
        if (flipFrom != null && kotlin.math.abs(flipAngle - flipFromAngle) >= 90f) flipFrom = null
        invalidate()
    }

    /** The cover's turn about its own vertical axis through [cx], [cy]. */
    private fun flipMatrix(cx: Float, cy: Float): Matrix? {
        val a = flipAngle % 360f
        if (a == 0f) return null
        camera.save()
        // View's default camera distance: 1280dp, in Camera's 72-per-inch units.
        camera.setLocation(0f, 0f, -1280f * dens / 72f)
        camera.rotateY(-a)
        camera.getMatrix(flipMatrix)
        camera.restore()
        val norm = ((a % 360f) + 360f) % 360f
        // Past edge-on the back is facing: mirror it back (FlipAnimation's scaleX = -1).
        if (norm > 90f && norm < 270f) flipMatrix.preScale(-1f, 1f)
        flipMatrix.preTranslate(-cx, -cy)
        flipMatrix.postTranslate(cx, cy)
        return flipMatrix
    }

    private fun changeTo(next: Cover?) {
        val prev = current
        if (prev != null && next != null) {
            val recent = android.os.SystemClock.uptimeMillis() - MediaArt.lastSkipAt < 3000
            startFlip(prev, if (recent) MediaArt.lastSkip else 1)
        }
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
        // The same gap to the left edge as above and below the cover.
        val cx = (h - side) / 2f + side / 2f
        val cy = h / 2f
        val radius = side * 0.08f
        val base = (1.14f - 0.14f * enter) * playScale

        stepFlip()
        val out = outgoing
        val flipping = flipFrom != null || flipAngle % 360f != 0f
        val fm = flipMatrix(cx, cy)
        if (fm != null) { c.save(); c.concat(fm) }
        if (flipping && current != null) {
            // The flip carries the change: one cover, whichever face is up, no crossfade.
            val face = flipFrom ?: current!!
            cover(c, face.bitmap, cx, cy, side, radius, base, enter)
        } else if (out != null) {
            // The old cover gives way: down to SWAP_TO and out over the first two thirds.
            val t = (swap / 0.66f).coerceIn(0f, 1f)
            cover(c, out.bitmap, cx, cy, side, radius, base * (1f - (1f - SWAP_TO) * t),
                enter * (1f - t))
        }
        val cur = current
        val inT = if (out == null) swap else ((swap - 0.15f) / 0.85f).coerceIn(0f, 1f)
        if (flipping && cur != null) {
            Unit
        } else if (cur != null) {
            cover(c, cur.bitmap, cx, cy, side, radius, base * (SWAP_FROM + (1f - SWAP_FROM) * inT),
                enter * inT)
        } else {
            val s = base * (SWAP_FROM + (1f - SWAP_FROM) * inT)
            rect.set(cx - side * s / 2, cy - side * s / 2, cx + side * s / 2, cy + side * s / 2)
            placeholder.color = Color.argb((60 * enter * inT).toInt(), 255, 255, 255)
            c.drawRoundRect(rect, radius * s, radius * s, placeholder)
        }
        if (fm != null) c.restore()

        // Title and artist beside the cover; they follow the cover's swap, sliding up a little.
        val textLeft = cx + side / 2f + h * 0.08f
        val textW = w - textLeft - h * 0.08f
        // The old title slides up as it fades, from where it stood - not from one step above it.
        val outT = (swap / 0.5f).coerceIn(0f, 1f)
        drawText(c, out, textLeft, textW, h, enter * (1f - outT), -outT)
        drawText(c, cur, textLeft, textW, h, enter * inT, 1f - inT)
        drawProgress(c, textLeft, textW, h, enter * (if (cur != null || out != null) 1f else 0f))
        drawControls(c, textLeft, textW, h, enter)
        drawVisualizer(c, textLeft + textW, h, enter)
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
                // The gain below brightens the bar; the head's glow is held back so the brighter
                // bar does not make it glare - [GLOW] of the system's own strength.
                setFloatUniform("uHeadGlowAlpha", GLOW / BAR_GAIN)
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
    /** The last known length, held across the moment a new track reports none yet. */
    private var lastDuration = 0L
    private var lastDurationAt = 0L

    private fun drawProgress(c: Canvas, left: Float, width: Float, h: Float, alpha: Float) {
        // The bar stays up through a track change: it does not fade with the title, and a new
        // track that has not reported its length yet keeps the old one for a moment.
        val now0 = android.os.SystemClock.uptimeMillis()
        var d = MediaArt.duration
        if (d > 0L) { lastDuration = d; lastDurationAt = now0 }
        else if (now0 - lastDurationAt < 2000L) d = lastDuration
        if (alpha <= 0f || d <= 0L || width <= 0f) { bar.setEmpty(); return }
        val y = h * 0.56f + columnShift(h)
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

    /**
     * Centred under the progress bar, play/pause in the middle and previous/next an equal
     * [SKIP_SPREAD] of the bar's width to either side - the row mirrors the bar above it.
     */
    private fun drawControls(c: Canvas, left: Float, width: Float, h: Float, enter: Float) {
        val size = h * 0.11f
        val y = h * 0.78f + columnShift(h)
        icon.alpha = (255 * enter).toInt()
        for (i in 0..2) {
            val cx = when (i) {
                0 -> left + width * (0.5f - SKIP_SPREAD)
                1 -> left + width / 2f
                else -> left + width * (0.5f + SKIP_SPREAD)
            }
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
        // The title gives up the visualizer's corner on its right.
        val titleW = if (Spectrum.connected) width - h * (VIS_W + 0.03f) else width
        val t = TextUtils.ellipsize(title, titlePaint, titleW, TextUtils.TruncateAt.END).toString()
        val base = h * 0.38f + columnShift(h)
        c.drawText(t, left, base + dy, titlePaint)
        val artist = cover?.artist.orEmpty()
        if (artist.isNotEmpty()) {
            val a = TextUtils.ellipsize(artist, artistPaint, width, TextUtils.TruncateAt.END).toString()
            c.drawText(a, left, base + dy + artistPaint.textSize * 1.4f, artistPaint)
        }
    }

    private val visPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    private var visShader: android.graphics.LinearGradient? = null
    private val visShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private var visShadowRadius = -1f
    private var visShaderKey = 0L

    /**
     * Five audio-reactive bars at the right end of the title line, where HyperOS's card keeps
     * its output-device slot. Drawn the way HMC-E's AudioWaveDrawable draws them in a square
     * slot: bars a twelfth of the slot wide, spread over 7/8 of it, each 9% + 85% * level of
     * its height and never shorter than wide, in the Bar color setting's gradient top to bottom.
     */
    private fun drawVisualizer(c: Canvas, right: Float, h: Float, alpha: Float) {
        if (!Spectrum.connected || alpha <= 0f) return
        Spectrum.step()
        val box = h * VIS_W
        val cy = h * 0.38f + columnShift(h) - h * 0.075f * 0.36f
        val left = right - box
        val width = box / 12f
        val pitch = (box * 0.875f - width) / 4f
        val x0 = left + box / 2f - 2f * pitch - width / 2f
        val key = (Spectrum.top.toLong() shl 32) xor (Spectrum.bottom.toLong() and 0xffffffffL) xor
            java.lang.Float.floatToIntBits(cy).toLong()
        if (visShader == null || key != visShaderKey) {
            visShaderKey = key
            // Fully opaque here: HMC-E's colours keep HyperOS's 0x99 for its own bars, but on
            // StandBy's blurred backdrop the bars are drawn solid.
            visShader = android.graphics.LinearGradient(0f, cy - box / 2f, 0f, cy + box / 2f,
                Spectrum.top or OPAQUE, Spectrum.bottom or OPAQUE, Shader.TileMode.CLAMP)
        }
        visPaint.shader = visShader
        visPaint.alpha = (255 * alpha).toInt()
        // A light drop shadow under each bar, so bars in the artwork's own colours still read on
        // its backdrop - a blurred dark copy drawn first (a shadow layer on a shaded paint is not
        // drawn by the GPU renderer).
        visShadow.alpha = (110 * alpha).toInt()
        if (visShadowRadius != width) {
            visShadowRadius = width
            visShadow.maskFilter = android.graphics.BlurMaskFilter(width * 1.1f,
                android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
        for (pass in 0..1) {
            val paint = if (pass == 0) visShadow else visPaint
            val dy = if (pass == 0) width * 0.5f else 0f
            for (i in 0 until 5) {
                val bh = (box * (0.09f + 0.85f * Spectrum.levels[i])).coerceAtLeast(width)
                val x = x0 + i * pitch
                c.drawRoundRect(x, cy - bh / 2f + dy, x + width, cy + bh / 2f + dy,
                    width / 2f, width / 2f, paint)
            }
        }
        visPaint.shader = null
    }

    private var tintFor: Bitmap? = null
    private var tintColor = Color.BLACK

    /** The artwork's mean colour at a third of its brightness, worked out once per picture. */
    private fun shadowTint(b: Bitmap): Int {
        if (b === tintFor) return tintColor
        tintFor = b
        tintColor = runCatching {
            val one = Bitmap.createScaledBitmap(b, 1, 1, true)
            val px = one.getPixel(0, 0)
            if (one !== b) one.recycle()
            val hsv = FloatArray(3)
            Color.colorToHSV(px, hsv)
            hsv[1] = (hsv[1] * 1.2f).coerceAtMost(1f)
            hsv[2] = hsv[2] * 0.35f
            Color.HSVToColor(hsv)
        }.getOrDefault(Color.BLACK)
        return tintColor
    }

    private fun cover(c: Canvas, b: Bitmap, cx: Float, cy: Float, side: Float, radius: Float,
                      scale: Float, alpha: Float) {
        if (alpha <= 0f) return
        val s = side * scale
        rect.set(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2)
        // The shadow sinks with the cover: deeper when it stands forward, playing.
        val depth = ((scale - PAUSED_SCALE) / (1f - PAUSED_SCALE)).coerceIn(0f, 1.2f)
        // The shape that casts the shadow sits a little inside the cover: drawn at the cover's
        // own edge, its black showed through the artwork's anti-aliased rim as a dark outline.
        // The shadow takes the artwork's own colour, deepened, so it sits in the backdrop
        // (which is made of the same colours) instead of as a grey halo on it.
        val tint = shadowTint(b)
        shadow.color = Color.argb((255 * alpha).toInt(), 0, 0, 0)
        shadow.setShadowLayer(dens * (22f + 18f * depth), 0f, dens * (6f + 8f * depth),
            Color.argb((150 * alpha).toInt(), Color.red(tint), Color.green(tint), Color.blue(tint)))
        val inset = 2f * dens
        c.drawRoundRect(rect.left + inset, rect.top + inset, rect.right - inset,
            rect.bottom - inset, radius * scale, radius * scale, shadow)

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

    /**
     * Moves the text-and-controls column so its visible ink is centred on the cover: from the
     * top of the title's tallest glyphs (0.84 of its size above the baseline at 0.38h - CJK
     * reaches that, Latin caps a little less) to the bottom of the play/pause glyph (0.4 of the
     * 0.11h button size below its centre at 0.78h - the HyperOS icon's drawn part, not its
     * padded bounds), measured on the device. Its middle goes on the cover's, h / 2.
     */
    private fun columnShift(h: Float): Float {
        val top = h * 0.38f - h * 0.075f * 0.84f
        val bottom = h * 0.78f + h * 0.11f * 0.4f
        return h / 2f - (top + bottom) / 2f
    }

    companion object {
        const val PAUSED_SCALE = 0.84f
        const val SWAP_FROM = 0.9f
        const val SWAP_TO = 0.86f
        const val FLIP_DAMPING = 0.72f
        /** The visualizer's width, as a share of the page height. */
        const val VIS_W = 0.1f
        private const val OPAQUE = 0xFF000000.toInt()
        const val FLIP_RESPONSE = 0.5f
        /** How much brighter than SystemUI's shader output the bar is drawn. */
        const val BAR_GAIN = 1.45f
        /** The head's glow against SystemUI's, after [BAR_GAIN]. */
        const val GLOW = 0.5f
        /** Previous/next from the middle, as a share of the bar's width. */
        const val SKIP_SPREAD = 0.36f
    }
}
