package com.os4.immersive

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.Shader
import android.view.View
import kotlin.math.max

/**
 * StandBy's background: black, or - with the switch on and music playing - the artwork, blurred
 * and dimmed the way HyperMusicCover-Enhanced's lock screen does it. Turned with the page
 * ([pageMatrix] is the page's own), crossfaded on track changes.
 */
class BackdropView(context: Context) : View(context) {

    private val dens = resources.displayMetrics.density
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dim = Paint().apply { color = Color.argb(DIM_ALPHA, 0, 0, 0) }
    private val m = Matrix()

    /** Page to screen, kept in step by [StandbyView]. */
    var pageMatrix = Matrix()
        set(value) { field = value; invalidate() }

    var artEnabled = true
        set(value) { field = value; update() }

    private var current: Bitmap? = null
    private var outgoing: Bitmap? = null
    /** How far [current] is in; [outgoing] goes out over the same fade from [outFrom]. */
    private var mix = 0f
    private var outFrom = 0f
    private var fade: ValueAnimator? = null

    /** Showing HMC-E's composed backdrop rather than blurring the cover here. */
    private var fromHmc = false

    private val ownLook: RenderEffect by lazy {
        // One blur for the whole layer: cheap, on the GPU, and wide enough that the art reads as
        // colour rather than as a picture. CLAMP so the edges do not darken.
        val blur = RenderEffect.createBlurEffect(BLUR_DP * dens, BLUR_DP * dens, Shader.TileMode.CLAMP)
        val saturate = ColorMatrix().apply { setSaturation(1.35f) }
        RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(saturate), blur)
    }
    /** HMC-E's backdrop is already the lock screen's; only smooth its upscale. */
    private val hmcLook: RenderEffect by lazy {
        RenderEffect.createBlurEffect(6f * dens, 6f * dens, Shader.TileMode.CLAMP)
    }

    init { setRenderEffect(ownLook) }

    private val onArt: () -> Unit = { post { update() } }
    private val onHmc: () -> Unit = { update() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        MediaArt.addListener(onArt)
        HmcBackdrop.addListener(onHmc)
        update()
    }

    override fun onDetachedFromWindow() {
        MediaArt.removeListener(onArt)
        HmcBackdrop.removeListener(onHmc)
        super.onDetachedFromWindow()
    }

    private var currentKey = ""
    private val hideLater = Runnable { apply(null, "") }

    /**
     * Pausing or resuming re-posts the media notification, and with it a freshly decoded copy of
     * the same picture - a new bitmap for the same song, which crossfaded with itself and dipped
     * (the flicker). The track key decides: same song, the picture is swapped in place. A
     * moment with no session state in between (players pass through one on play/pause) does
     * not take the art away either, unless it lasts.
     */
    private fun update() {
        if (burnIn > 0f && current != null) return
        // HMC-E's backdrop when it has sent one, the cover blurred here otherwise.
        val hmc = HmcBackdrop.bitmap
        val useHmc = hmc != null
        if (useHmc != fromHmc) {
            fromHmc = useHmc
            setRenderEffect(if (useHmc) hmcLook else ownLook)
            currentKey = ""
        }
        val next = if (artEnabled && MediaArt.shown) (if (useHmc) hmc else MediaArt.art) else null
        val trackKey = if (useHmc) HmcBackdrop.key else MediaArt.key
        if (next == null) {
            if (current != null) postDelayed(hideLater, if (artEnabled) HIDE_DELAY_MS else 0L)
            return
        }
        removeCallbacks(hideLater)
        if (current != null && trackKey == currentKey) {
            current = next
            invalidate()
            return
        }
        apply(next, trackKey)
    }

    private fun apply(next: Bitmap?, key: String) {
        if (next === current) return
        currentKey = key
        outgoing = current
        outFrom = mix
        current = next
        mix = 0f
        fade?.cancel()
        fade = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 450
            addUpdateListener { mix = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    if (fade === a) { outgoing = null; invalidate() }
                }
            })
            start()
        }
    }

    /**
     * 0..1 of the idle burn-in dimming: the picture taken down by up to [BURN_IN_DIM]. While it
     * is on, the picture stays as it is - a new track's crossfade at the idle rate is a flicker
     * - and catches up once a touch has lit it back up.
     */
    var burnIn = 0f
        set(v) {
            if (field == v) return
            val held = field > 0f
            field = v
            if (held && v == 0f) update()
            invalidate()
        }

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.BLACK)
        val pageW = height.toFloat()
        val pageH = width.toFloat()
        val out = outgoing
        val cur = current
        // A new picture comes in over the old one, which stays whole until it is covered - so
        // the two never add up to less than one. Only a fade to black takes it down.
        val outAlpha = if (out == null) 0f else if (cur != null) outFrom else outFrom * (1f - mix)
        val curAlpha = if (cur != null) mix else 0f
        if (outAlpha <= 0f && curAlpha <= 0f) return
        c.save()
        c.concat(pageMatrix)
        if (out != null) draw(c, out, outAlpha, pageW, pageH)
        if (cur != null) draw(c, cur, curAlpha, pageW, pageH)
        c.restore()
        // HMC-E's backdrop carries the lock screen's own shade.
        if (!fromHmc) {
            dim.alpha = (DIM_ALPHA * maxOf(outAlpha, curAlpha)).toInt()
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
        }
        if (burnIn > 0f) c.drawColor(Color.argb((255 * BURN_IN_DIM * burnIn).toInt(), 0, 0, 0))
    }

    /** Centre-cropped to the page, like a cover over a landscape screen. */
    private fun draw(c: Canvas, b: Bitmap, alpha: Float, w: Float, h: Float) {
        if (alpha <= 0f) return
        val s = max(w / b.width, h / b.height)
        m.reset()
        m.postScale(s, s)
        m.postTranslate((w - b.width * s) / 2f, (h - b.height * s) / 2f)
        paint.alpha = (255 * alpha).toInt()
        c.drawBitmap(b, m, paint)
    }

    companion object {
        private const val BLUR_DP = 60f
        private const val DIM_ALPHA = 90
        private const val BURN_IN_DIM = 0.8f
        private const val HIDE_DELAY_MS = 1500L
    }
}
