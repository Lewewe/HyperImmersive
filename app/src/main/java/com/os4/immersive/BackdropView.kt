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

    init {
        // One blur for the whole layer: cheap, on the GPU, and wide enough that the art reads as
        // colour rather than as a picture. CLAMP so the edges do not darken.
        val blur = RenderEffect.createBlurEffect(BLUR_DP * dens, BLUR_DP * dens, Shader.TileMode.CLAMP)
        val saturate = ColorMatrix().apply { setSaturation(1.35f) }
        setRenderEffect(RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(saturate), blur))
    }

    private val onArt: () -> Unit = { post { update() } }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        MediaArt.addListener(onArt)
        update()
    }

    override fun onDetachedFromWindow() {
        MediaArt.removeListener(onArt)
        super.onDetachedFromWindow()
    }

    private fun update() {
        val next = if (artEnabled && MediaArt.shown) MediaArt.art else null
        if (next === current) return
        outgoing = current
        outFrom = mix
        current = next
        mix = 0f
        fade?.cancel()
        fade = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 450
            addUpdateListener { mix = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.BLACK)
        val pageW = height.toFloat()
        val pageH = width.toFloat()
        val out = outgoing
        val cur = current
        val outAlpha = if (out != null) outFrom * (1f - mix) else 0f
        val curAlpha = if (cur != null) mix else 0f
        if (outAlpha <= 0f && curAlpha <= 0f) return
        c.save()
        c.concat(pageMatrix)
        if (out != null) draw(c, out, outAlpha, pageW, pageH)
        if (cur != null) draw(c, cur, curAlpha, pageW, pageH)
        c.restore()
        dim.alpha = (DIM_ALPHA * maxOf(outAlpha, curAlpha)).toInt()
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
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
    }
}
