package com.os4.immersive

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.animation.PathInterpolator
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * The StandBy screen, drawn by hand: page 0 is the music, every page after it one 4x2 widget slot
 * - two 2x2 ones side by side once a 2x2 widget is put in it - and an add page last. Swiped
 * between like iPhone's pages.
 *
 * A slot is a "+" tile until a widget is picked for it ([onSlotTap]); the widget itself is a
 * [HiWidget] view the activity lays over this one ([hosted]), and this view only moves
 * it - turned, slid and shrunk with its page every frame, as if it were drawn here.
 *
 * Holding a widget opens its menu ([beginWidgetHold]); moving on while still holding it opens
 * widget edit, where the page's widgets show a "-" to remove one, and the held one follows the finger: dropped on a widget of
 * its size it joins it as a stack, on an empty slot of its size it moves there, held at the top
 * or bottom edge it turns the page first. Holding the background zooms out to the widget pages
 * as a grid ([overview]): dragged to reorder, "-" to delete, tapped to go to.
 *
 * Entering, the slots come in from slightly larger, blurred and transparent, one after the
 * other, the way StandBy settles in once the phone is put on its side; leaving runs it back.
 */
class StandbyView(context: Context) : View(context) {

    private val dens = resources.displayMetrics.density
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val bold = Typeface.create(Typeface.DEFAULT, 700, false)
    private val status = StatusCluster(dens, resources, { wake(it) }, { invalidate() })
    private val volume = VolumeBar(dens) { wake(it, user = true) }

    fun setStatus(cell: Int, wifi: Int, level: Float, isCharging: Boolean) =
        status.setStatus(cell, wifi, level, isCharging)

    /** The volume changed to [level] (0 to 1); [push] is +1 or -1 when pressed past that end. */
    fun showVolume(level: Float, push: Int) = volume.show(level, push)

    /** The volume key was let go. */
    fun releaseVolume() = volume.release()

    // ------------------------------------------------------------------ widget slots

    /** An empty slot was tapped: the activity picks a widget for it. */
    var onSlotTap: ((String) -> Unit)? = null
    /** Three taps on the status glyph: leave StandBy, whatever the lock screen says. */
    var onExit: (() -> Unit)? = null
    /** The widget showing in a slot's stack is to go. */
    var onRemoveWidget: ((String) -> Unit)? = null
    /** A slot's widgets dropped on another slot: stacked onto its own, or moved into it empty. */
    var onWidgetDrop: ((from: String, to: String) -> Unit)? = null
    /** The widget pages' new order (added, reordered). */
    var onPagesChanged: ((List<Int>) -> Unit)? = null
    /** A widget page and everything in it is to go. */
    var onDeletePage: ((Int) -> Unit)? = null
    /** The look of every widget, as page editing's button shows it, and that button pressed. */
    var widgetsDark = true
    var onWidgetsDark: ((Boolean) -> Unit)? = null
    /** How solid the widgets' backgrounds are, 0 to 1, as page editing's slider moves it, and whether it has let go. */
    var widgetGlass = 0f
    var onWidgetGlass: ((level: Float, done: Boolean) -> Unit)? = null
    /** A slot's stack: which widget shows, of how many. */
    var stackInfo: (String) -> Pair<Int, Int>? = { null }
    /** Where a stack stands while it scrolls (a fractional widget index), or null at rest. */
    var stackPosOf: (String) -> Float? = { null }
    /** The view the overlay ([drawOverlay]) is drawn by, redrawn with this one. */
    var overlayHost: View? = null

    /** The widgets in their slots, keyed by [slotKey]; laid over this view by the activity. */
    var hosted: Map<String, View> = emptyMap()
        set(value) {
            field = value
            invalidate()
        }

    /** The widget pages, by id, in order; page 1 is the first of them. */
    var pageIds: List<Int> = Prefs.pageIds(context)
        set(value) {
            field = value
            if (page > last) page = last
            invalidate()
        }

    /** The empty slot a finger went down on, while it is still a tap. */
    private var pressedSlot: String? = null

    // ------------------------------------------------------------------ paging

    private var page = 0f

    private val pages get() = 1 + pageIds.size + 1
    private val addPage get() = pages - 1
    private val last get() = (pages - 1).toFloat()

    private fun idOf(p: Int) = pageIds.getOrNull(p - 1)
    private fun isWidgetPage(p: Int) = p in 1..pageIds.size

    private val music = MusicPage(context, dens) { invalidate() }
    /** Swiped by hand: from then on the page stays where the user put it. */
    private var userPaged = false
    private val onMedia: () -> Unit = {
        post {
            // A track change or pause animates the cover; let it run at the full rate.
            wake(3_000L)
            music.sync()
            // Music that turns up after StandBy opened (the session is read a moment late after
            // a restart) still gets the first page, unless the user has already moved.
            if (!userPaged && MediaArt.shown && page != 0f && !editing) snapTo(0f)
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
        removeCallbacks(backgroundHold)
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
    private val toScreen = Matrix()
    private val toPage = Matrix()
    private val lastSent = Matrix().apply { setScale(0f, 0f) }
    /**
     * Against burn-in, how far the content is off its place. Moved inside the view, which stays
     * where it is: the overview's black has to reach every edge of the screen.
     */
    private var driftX = 0f
    private var driftY = 0f

    fun drift(x: Float, y: Float) {
        driftX = x
        driftY = y
        invalidate()
    }

    /** The page's turn, for the backdrop under it. */
    var onMatrix: ((Matrix) -> Unit)? = null
    /** The entrance's progress, for the backdrop's fade. */
    var onShown: ((Float) -> Unit)? = null
    /** 0..1 how far the idle burn-in dimming has come, for the backdrop under it. */
    var onBurnIn: ((Float) -> Unit)? = null
    /** Linear 0..1 of the dimming, eased when handed on, and when it was last stepped. */
    private var burnRaw = 0f
    private var burnAt = 0L

    fun setSide(s: Int, animate: Boolean) {
        // The sensor repeats the side many times a second; restarting the turn each time drags it out.
        if (s == side) return
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

    /** A movement on the screen as a movement on the page, for the layer over this view. */
    fun toPageVector(dx: Float, dy: Float): PointF {
        updateMatrix()
        val v = floatArrayOf(dx, dy)
        toPage.mapVectors(v)
        return PointF(v[0], v[1])
    }

    // ------------------------------------------------------------------ idle refresh rate

    /**
     * [idleAfterMs] without a touch, StandBy asks for the slowest refresh the panel offers (this
     * phone's ARR goes down to 1Hz) and redraws on that beat; a touch, or something that
     * moves on its own - a track change, StandBy leaving - asks for the normal rate again.
     */
    var idleAfterMs = 60_000L
        set(v) {
            if (field == v) return
            field = v
            activeUntil = SystemClock.uptimeMillis() + v
        }
    private var activeUntil = SystemClock.uptimeMillis() + idleAfterMs
    /** This long without a touch, [onTimeOff]; 0 is never. */
    var offAfterMs = 0L
    var onTimeOff: (() -> Unit)? = null
    private var lastUserAt = SystemClock.uptimeMillis()
    private var idleApplied = false
    /** Views that follow this one's rate - the backdrop under it. */
    var followers: List<View> = emptyList()

    private val lowRate: Float by lazy {
        runCatching { display?.supportedRefreshRates?.filter { it >= 1f }?.minOrNull() }
            .getOrNull() ?: 10f
    }
    /**
     * The idle beat, one at a time: a delayed invalidate per draw stacked up a new chain with every
     * extra frame (a track change's animation), until the chains alone ran the full rate. While
     * idle every redraw asked for waits for it, so nothing draws between beats.
     */
    private var tickPending = false
    private val idleTick = Runnable {
        tickPending = false
        overlayHost?.invalidate()
        beatWidgets()
        super@StandbyView.invalidate()
    }

    /** The slots' widgets, drawn in this view's frame instead of one of their own. */
    private fun beatWidgets() {
        for (v in hosted.values) {
            val frame = v as? android.view.ViewGroup ?: continue
            for (i in 0 until frame.childCount) (frame.getChildAt(i) as? HiWidget)?.beat()
        }
    }

    /** On the second, so a clock's hand steps evenly. */
    private fun tickLater() {
        if (tickPending) return
        tickPending = true
        postDelayed(idleTick, lowFrameMs - System.currentTimeMillis() % lowFrameMs)
    }

    override fun invalidate() {
        if (idleApplied) tickLater() else super.invalidate()
    }
    private val lowFrameMs: Long get() = (1000f / lowRate).toLong().coerceAtLeast(16L)

    /**
     * Normal rate for at least [ms] from now (never shortens a longer one). Once idle, only the
     * user ([user]: a touch, a key, StandBy leaving) brings it back; something moving on its own,
     * a track change, plays out at the idle rate under the burn-in dimming.
     */
    fun wake(ms: Long = idleAfterMs, user: Boolean = ms >= idleAfterMs) {
        val now = SystemClock.uptimeMillis()
        if (user) lastUserAt = now
        if (!user && now >= activeUntil) { invalidate(); return }
        activeUntil = maxOf(activeUntil, now + ms)
        if (idleApplied) applyRate(false)
        invalidate()
    }

    private fun idleNow(): Boolean {
        // Editing jiggles and follows a finger: never at the idle rate.
        // The backdrop darkens first, at the normal rate, then the rate drops.
        val idle = !editing && SystemClock.uptimeMillis() >= activeUntil + BURN_IN_FADE_MS
        if (idle != idleApplied) applyRate(idle)
        return idle
    }

    private fun applyRate(idle: Boolean) {
        idleApplied = idle
        music.idle = idle
        HiWidget.idle = idle
        // Back from idle, a clock's sweep starts at once, not on its next tick.
        if (!idle) beatWidgets()
        val rate = if (idle) lowRate else View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT
        requestedFrameRate = rate
        followers.forEach { it.requestedFrameRate = rate }
        overlayHost?.requestedFrameRate = rate
    }

    // ------------------------------------------------------------------ touch

    override fun onTouchEvent(raw: MotionEvent): Boolean {
        if (editing) {
            editTouch(raw)
            return true
        }
        wake()
        updateMatrix()
        val e = MotionEvent.obtain(raw).apply { transform(toPage) }
        try { return onPageTouch(e) } finally { e.recycle() }
    }

    /** Held still on the background long enough: the pages' overview. */
    private val backgroundHold = Runnable {
        if (!dragging || editing) return@Runnable
        dragging = false
        velocity?.recycle()
        velocity = null
        pressedSlot = null
        addPressed = false
        snapTo(Math.round(page).toFloat().coerceIn(0f, last))
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        enterOverview()
    }

    private fun onPageTouch(e: MotionEvent): Boolean {
        if (statusPressed || e.actionMasked == MotionEvent.ACTION_DOWN && status.hit(e.x, e.y)) {
            statusTouch(e)
            return true
        }
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
                if (abs(page - addPage) < 0.05f && addHit(e.x, e.y)) addPressed = true
                pressedSlot = emptySlotAt(e.x, e.y)
                if (snap?.isRunning != true) leaving = Math.round(page)
                snap?.cancel()
                dragX = e.y
                dragging = true
                velocity = VelocityTracker.obtain().apply { addMovement(e) }
                if (!onMusic) postDelayed(backgroundHold, HOLD_MS)
            }
            MotionEvent.ACTION_MOVE -> if (scrubbing) {
                music.scrubMove(e.x)
            } else if (held >= 0) {
                if (abs(e.x - downX) > 12 * dens || abs(e.y - downY) > 12 * dens) {
                    music.release(held, false)
                    held = -1
                    userPaged = true
                    leaving = Math.round(page)
                    dragX = e.y
                    dragging = true
                    velocity = VelocityTracker.obtain().apply { addMovement(e) }
                }
            } else if (dragging) {
                velocity?.addMovement(e)
                if (abs(e.y - downY) > 12 * dens || abs(e.x - downX) > 12 * dens) {
                    removeCallbacks(backgroundHold)
                }
                if (abs(e.y - downY) > 12 * dens) {
                    addPressed = false
                    pressedSlot = null
                }
                // Up and down, like iPhone's StandBy stacks: a swipe up brings the next page.
                // Past either end it gives a little, harder the further it is pulled.
                val dy = e.y - dragX
                dragX = e.y
                val edge = page < 0f || page > last
                page = (page - dy / (pageH * 0.75f) * (if (edge) 0.35f else 1f))
                    .coerceIn(-0.2f, last + 0.2f)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(backgroundHold)
                addPressed = false
                val slot = pressedSlot
                pressedSlot = null
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
                dotsUntil = SystemClock.uptimeMillis() + DOTS_LINGER_MS
                val tap = e.actionMasked == MotionEvent.ACTION_UP &&
                    abs(e.x - downX) < 12 * dens && abs(e.y - downY) < 12 * dens
                if (tap && abs(page - addPage) < 0.05f && addHit(e.x, e.y)) {
                    velocity?.recycle()
                    velocity = null
                    addNewPage()
                    return true
                }
                if (tap && slot != null && slot == emptySlotAt(e.x, e.y)) {
                    velocity?.recycle()
                    velocity = null
                    performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    onSlotTap?.invoke(slot)
                    return true
                }
                val v = velocity?.run { addMovement(e); computeCurrentVelocity(1000); yVelocity } ?: 0f
                velocity?.recycle()
                velocity = null
                val target = when {
                    v < -800 * dens -> kotlin.math.ceil(page)
                    v > 800 * dens -> kotlin.math.floor(page)
                    else -> Math.round(page).toFloat()
                }.coerceIn(0f, last)
                snapTo(target)
            }
        }
        return true
    }

    /** The status glyph pressed: taps on it count towards the way out ([StatusCluster.tap]). */
    private var statusPressed = false

    private fun statusTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                statusPressed = true
                downX = e.x
                downY = e.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                statusPressed = false
                if (e.actionMasked != MotionEvent.ACTION_UP || !status.hit(e.x, e.y)) return
                if (status.tap()) {
                    performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    onExit?.invoke()
                } else performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
        }
    }

    private fun snapTo(target: Float) {
        if (leaving < 0) leaving = Math.round(page)
        snap?.cancel()
        snap = ValueAnimator.ofFloat(page, target).apply {
            duration = 460
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener { page = it.animatedValue as Float }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    if (snap === a) leaving = -1
                }
            })
            start()
        }
    }

    // ------------------------------------------------------------------ enter / leave

    /** 0 = nothing on screen, 1 = settled. */
    private var shown = 0f
    private var shownAnim: ValueAnimator? = null

    fun enter() {
        status.enter()
        animateShown(1f, 900L) {}
    }

    fun leave(done: () -> Unit) {
        wake(2_000L, user = true)
        status.leave()
        animateShown(0f, 420L, done)
    }

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

    // ------------------------------------------------------------------ where a page is

    private val normalM = Matrix()
    private val thumbM = Matrix()
    private val lerpA = FloatArray(9)
    private val lerpB = FloatArray(9)

    /**
     * Page [p]'s transform in page coordinates, into [out], and how opaque it is (0: not drawn).
     * Swiping, a page slides a whole page along and shrinks; in the overview a widget page is
     * its card in the column, and between the two the page on screen shrinks into its card while
     * the others fade in theirs. The music and add pages fade out of the overview.
     */
    private fun pageMatrix(p: Int, out: Matrix): Float {
        val w = pageW
        val h = pageH
        val d = p - page
        val far = abs(d)
        val normal = far < 1f
        if (normal) {
            normalM.reset()
            if (far > 0.001f) {
                val s = 1f - PAGE_SHRINK * far
                normalM.preTranslate(0f, d * h * PAGE_SLIDE)
                normalM.preScale(s, s, w / 2f, h / 2f)
            }
            val pop = popOf(p)
            if (pop < 1f) normalM.preScale(0.9f + 0.1f * pop, 0.9f + 0.1f * pop, w / 2f, h / 2f)
        }
        val id = idOf(p)
        if (ov <= 0f || id == null) {
            if (!normal) return 0f
            out.set(normalM)
            return if (id == null) 1f - ov else 1f
        }
        thumbMatrix(id, thumbM)
        if (normal) {
            normalM.getValues(lerpA)
            thumbM.getValues(lerpB)
            for (i in 0..8) lerpA[i] += (lerpB[i] - lerpA[i]) * ov
            out.setValues(lerpA)
            return 1f
        }
        out.set(thumbM)
        return ov
    }

    // ------------------------------------------------------------------ drawing

    private val pageM = Matrix()

    override fun onDraw(c: Canvas) {
        val w = pageW
        val h = pageH
        updateMatrix()
        if (toScreen != lastSent) {
            lastSent.set(toScreen)
            onMatrix?.invoke(Matrix(toScreen))
        }
        if (ov > 0f) stepThumbs()
        c.save()
        c.translate(driftX, driftY)
        c.concat(toScreen)
        if (ov > 0f) drawOutsideCards(c, w, h)
        // iPhone's StandBy stack: the page moving off slides a whole page along the swipe,
        // shrinking as it goes; the one coming in grows out of the same small size into place.
        // No fade - the two stay solid and never overlap.
        // In transit a page is also blurred, more the further it is from rest - recorded into
        // its own RenderNode so the blur is the page's alone.
        val dragged = ovDragId?.let { pageIds.indexOf(it) + 1 } ?: -1
        for (p in (0 until pages).sortedBy { if (it == dragged) 1 else 0 }) {
            val a = pageMatrix(p, pageM)
            if (a <= 0f) continue
            c.save()
            c.concat(pageM)
            if (a < 1f) c.saveLayerAlpha(null, (255 * a).toInt())
            if (ov > 0f && isWidgetPage(p)) drawThumbCard(c, w, h)
            val far = abs(p - page)
            // Only the page on its way out blurs; the one coming in arrives sharp.
            val blur = if (p == leaving && ov <= 0f) PAGE_BLUR_DP * dens * far else 0f
            if (blur > 0.5f && c is android.graphics.RecordingCanvas) {
                val node = pageNode(p)
                node.setPosition(0, 0, w.toInt(), h.toInt())
                val rc = node.beginRecording(w.toInt(), h.toInt())
                try { drawPage(rc, p, w, h) } finally { node.endRecording() }
                node.setRenderEffect(RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.DECAL))
                c.drawRenderNode(node)
            } else drawPage(c, p, w, h)
            if (a < 1f) c.restore()
            c.restore()
        }
        drawDots(c, w, h)
        c.restore()
        menuK = menuFade(SystemClock.uptimeMillis())
        if (menuK != appliedMenuK) {
            appliedMenuK = menuK
            setRenderEffect(behindEffect(MENU_BLUR_DP * dens * menuK, menuK))
        }
        placeHosted(w, h)
        // Asked for during a draw it is a frame of its own; idle, idleTick asks with this view's.
        if (!idleApplied) overlayHost?.invalidate()
        // Burn-in: as StandBy goes idle the backdrop darkens; a touch lights it back up, quicker.
        val nowB = SystemClock.uptimeMillis()
        val want = if (editing) 0f
            else ((nowB - activeUntil) / BURN_IN_FADE_MS.toFloat()).coerceIn(0f, 1f)
        // One frame's worth at most: the last draw may be a whole idle beat ago.
        val step = minOf(nowB - burnAt, 50L) / BURN_OUT_FADE_MS.toFloat()
        burnAt = nowB
        val t = if (want >= burnRaw) want else maxOf(want, burnRaw - step)
        if (t != burnRaw) {
            burnRaw = t
            onBurnIn?.invoke(t * t * (3f - 2f * t))
        }
        if (offAfterMs > 0L && !editing && nowB - lastUserAt >= offAfterMs) {
            offAfterMs = 0L
            post { onTimeOff?.invoke() }
        }
        // StandBy keeps time to the second; a sweep needs every frame while it is shown.
        if (!isAttachedToWindow) return
        if (idleNow()) tickLater() else postInvalidateOnAnimation()
    }

    /** The page a swipe started from - the one that is leaving, or -1 at rest. */
    private var leaving = -1

    private val pageNodes = ArrayList<android.graphics.RenderNode>()

    private fun pageNode(p: Int): android.graphics.RenderNode {
        while (pageNodes.size <= p) pageNodes += android.graphics.RenderNode("standby-page-${pageNodes.size}")
        return pageNodes[p]
    }

    private fun drawPage(c: Canvas, p: Int, w: Float, h: Float) {
        when (p) {
            0 -> music.draw(c, w, h, stagger(0))
            addPage -> drawAddPage(c, w, h)
            else -> drawSlots(c, w, h, p)
        }
    }

    // ------------------------------------------------------------------ page dots

    /**
     * iPhone's StandBy shows where it is in a stack only while it is being swiped: a column of
     * dots at the right edge, the current one white. Here they fade in as a swipe starts and
     * out [DOTS_LINGER_MS] after it settles; the music page's dot is a play icon, the add page's a
     * small plus.
     */
    private var dotsAlpha = 0f
    private var dotsUntil = 0L
    private var dotsDrawnAt = 0L
    private val playIcon = android.graphics.Path()

    private fun drawDots(c: Canvas, w: Float, h: Float) {
        val now = SystemClock.uptimeMillis()
        val want = (dragging || snap?.isRunning == true || now < dotsUntil) && ov <= 0f
        val dt = if (dotsDrawnAt == 0L) 0f else ((now - dotsDrawnAt) / 1000f).coerceAtMost(0.1f)
        dotsDrawnAt = now
        val step = dt / if (want) 0.18f else 0.35f
        dotsAlpha = if (want) min(1f, dotsAlpha + step) else maxOf(0f, dotsAlpha - step)
        if (dotsAlpha <= 0f) return
        val a = dotsAlpha * stagger(0)
        val n = pages
        val r = 3.2f * dens
        val pitch = 13f * dens
        val x = w - 16f * dens
        val top = h / 2f - pitch * (n - 1) / 2f
        for (i in 0 until n) {
            val y = top + pitch * i
            // The current page brightens as the swipe nears it.
            val near = (1f - abs(i - page)).coerceIn(0f, 1f)
            val alpha = (255 * a * (0.32f + 0.68f * near)).toInt()
            if (i == 0) {
                // The music page's dot says what it is.
                val k = r * (1.1f + 0.15f * near)
                playIcon.rewind()
                playIcon.moveTo(x - k * 0.75f, y - k)
                playIcon.lineTo(x + k, y)
                playIcon.lineTo(x - k * 0.75f, y + k)
                playIcon.close()
                fill.color = Color.argb(alpha, 255, 255, 255)
                c.drawPath(playIcon, fill)
            } else if (i == addPage) {
                stroke.color = Color.argb(alpha, 255, 255, 255)
                stroke.strokeWidth = 1.6f * dens
                val k = r * (1.05f + 0.15f * near)
                c.drawLine(x - k, y, x + k, y, stroke)
                c.drawLine(x, y - k, x, y + k, stroke)
            } else {
                fill.color = Color.argb(alpha, 255, 255, 255)
                c.drawCircle(x, y, r * (1f + 0.15f * near), fill)
            }
        }
    }

    // ------------------------------------------------------------------ add page

    /** The add page's tile, in page coordinates. */
    private val addBox = RectF()
    private var addPressed = false
    private var press = 0f
    /** 0..1 pop of the page just added, and which page that is. */
    private var born = 1f
    private var bornId = -1
    private var bornAnim: ValueAnimator? = null

    private fun addBoxFor(w: Float, h: Float): RectF {
        val side = h * 0.62f
        addBox.set(w / 2f - side * 0.8f, h / 2f - side / 2f, w / 2f + side * 0.8f, h / 2f + side / 2f)
        return addBox
    }

    private fun addHit(x: Float, y: Float) = addBoxFor(pageW, pageH).contains(x, y)

    private fun addNewPage() {
        if (pageIds.size >= MAX_PAGES) return
        val id = (pageIds.maxOrNull() ?: 0) + 1
        pageIds = pageIds + id
        onPagesChanged?.invoke(pageIds)
        // The new page takes the add page's place; the add page moves one further down.
        snap?.cancel()
        leaving = -1
        page = pageIds.size.toFloat()
        dotsUntil = SystemClock.uptimeMillis() + DOTS_LINGER_MS
        bornId = id
        bornAnim?.cancel()
        bornAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 520
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener { born = it.animatedValue as Float }
            start()
        }
        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    }

    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f * dens, 8f * dens), 0f)
    }

    private fun drawAddPage(c: Canvas, w: Float, h: Float) {
        val box = addBoxFor(w, h)
        press += ((if (addPressed) 1f else 0f) - press) * 0.35f
        val full = pageIds.size >= MAX_PAGES
        withEntrance(c, 0, w / 2f, h / 2f) {
            c.save()
            val s = 1f - 0.04f * press
            c.scale(s, s, box.centerX(), box.centerY())
            val r = box.height() * 0.2f
            fill.color = Color.argb((22 + 18 * press).toInt(), 255, 255, 255)
            c.drawRoundRect(box, r, r, fill)
            dash.color = Color.argb(90, 255, 255, 255)
            dash.strokeWidth = 2f * dens
            c.drawRoundRect(box, r, r, dash)
            val cx = box.centerX()
            val cy = box.centerY() - box.height() * 0.08f
            val k = box.height() * 0.11f
            fill.color = Color.argb(if (full) 40 else 60, 255, 255, 255)
            c.drawCircle(cx, cy, k * 1.6f, fill)
            stroke.color = Color.argb(if (full) 110 else 255, 255, 255, 255)
            stroke.strokeWidth = k * 0.22f
            c.drawLine(cx - k * 0.7f, cy, cx + k * 0.7f, cy, stroke)
            c.drawLine(cx, cy - k * 0.7f, cx, cy + k * 0.7f, stroke)
            text.textAlign = Paint.Align.CENTER
            text.typeface = bold
            text.textSize = box.height() * 0.085f
            text.color = Color.argb(200, 255, 255, 255)
            c.drawText(context.getString(if (full) R.string.page_limit else R.string.page_new), cx,
                box.centerY() + box.height() * 0.3f, text)
            c.restore()
        }
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

    // ------------------------------------------------------------------ slots

    private val slotBox = RectF()

    /**
     * A widget page's slot in page coordinates: [WIDE] is the whole 4x2 tile, 0 and 1 its left
     * and right 2x2 halves - two squares with a gap, which the wide tile spans exactly.
     */
    private fun slotRect(w: Float, h: Float, slot: Int): RectF {
        // Above and below, room for the status corner over the widgets' top right.
        val gap = GAP_DP * dens
        val side = min(h - 2 * EDGE_DP * dens, (w - 3 * gap) / 2f)
        val top = (h - side) / 2f
        val left = (w - 2 * side - gap) / 2f
        if (slot == WIDE) slotBox.set(left, top, left + 2 * side + gap, top + side)
        else slotBox.set(left + slot * (side + gap), top, left + slot * (side + gap) + side, top + side)
        return slotBox
    }

    /**
     * What page [p] shows: one wide slot until a 2x2 widget is put in it, then the two halves.
     * A wide widget fills the page. The widget being dragged still counts where it came from.
     */
    private fun slotsOf(p: Int): IntArray {
        val id = idOf(p) ?: return IntArray(0)
        return when {
            slotKey(id, WIDE) in hosted -> intArrayOf(WIDE)
            slotKey(id, 0) in hosted || slotKey(id, 1) in hosted -> intArrayOf(0, 1)
            else -> intArrayOf(WIDE)
        }
    }

    /** The page just added pops in; every other page is at full size. */
    private fun popOf(p: Int) = if (idOf(p) == bornId) born else 1f

    /** The empty slot under a point of the page at rest, or null. */
    private fun emptySlotAt(x: Float, y: Float): String? {
        val p = Math.round(page)
        if (abs(page - p) >= 0.05f) return null
        val id = idOf(p) ?: return null
        for (slot in slotsOf(p)) {
            val key = slotKey(id, slot)
            if (key !in hosted && slotRect(pageW, pageH, slot).contains(x, y)) return key
        }
        return null
    }

    private fun drawSlots(c: Canvas, w: Float, h: Float, p: Int) {
        val id = idOf(p) ?: return
        for (slot in slotsOf(p)) {
            val key = slotKey(id, slot)
            // A filled slot is the widget's own view, placed over this one (placeHosted).
            if (key in hosted) continue
            val box = RectF(slotRect(w, h, slot))
            if (key == pressedSlot) plusKey = key
            withEntrance(c, orderOf(slot), box.centerX(), box.centerY()) {
                drawPlus(c, box, if (key == plusKey) stepPlus(key == pressedSlot) else 0f)
            }
        }
    }

    /** The "+" a finger last went down on, and how far in it is: eased both ways. */
    private var plusKey: String? = null
    private var plusPress = 0f

    private fun stepPlus(down: Boolean): Float {
        plusPress += ((if (down) 1f else 0f) - plusPress) * 0.3f
        return plusPress
    }

    private fun orderOf(slot: Int) = if (slot == WIDE) 0 else slot

    /** An empty slot: the add page's dashed tile, with a plus and no words. */
    private fun drawPlus(c: Canvas, box: RectF, pressed: Float) {
        c.save()
        val s = 1f - 0.06f * pressed
        c.scale(s, s, box.centerX(), box.centerY())
        val r = box.height() * CORNER
        fill.color = Color.argb((22 + 18 * pressed).toInt(), 255, 255, 255)
        c.drawRoundRect(box, r, r, fill)
        dash.color = Color.argb(90, 255, 255, 255)
        dash.strokeWidth = 2f * dens
        c.drawRoundRect(box, r, r, dash)
        val k = box.height() * 0.09f
        fill.color = Color.argb(60, 255, 255, 255)
        c.drawCircle(box.centerX(), box.centerY(), k * 1.6f, fill)
        stroke.color = Color.WHITE
        stroke.strokeWidth = k * 0.22f
        c.drawLine(box.centerX() - k * 0.7f, box.centerY(), box.centerX() + k * 0.7f, box.centerY(), stroke)
        c.drawLine(box.centerX(), box.centerY() - k * 0.7f, box.centerX(), box.centerY() + k * 0.7f, stroke)
        c.restore()
    }

    // ------------------------------------------------------------------ the widgets themselves

    /** The widget under its settings card, not drawn while the card covers it. */
    var coveredKey: String? = null

    /** A widget just added: it lands in its slot. */
    private var landKey: String? = null
    private var landAt = 0L

    fun landWidget(key: String) {
        landKey = key
        landAt = SystemClock.uptimeMillis()
        wake()
    }

    private val hostMatrix = Matrix()
    private val hostPoint = FloatArray(2)
    /** Each hosted widget's slot-to-screen transform this frame, for the overlay. */
    private val shownAt = HashMap<String, Matrix>()

    /**
     * Each hosted widget put where its slot is drawn this frame: the same turn, slide, shrink
     * and entrance as the page around it, as a view's own transform (pivot at its corner, so
     * the corner is where the matrix sends the slot's), and hidden while its page is away.
     *
     * The view is laid out at the widget's own home screen size (the activity sets it) and
     * scaled up to the slot, as iPhone's StandBy enlarges its widgets: laid out at the slot's
     * size instead, a widget made for ~176dp stretched its layout over ~440dp and fell apart.
     *
     * Editing, the current page's widgets jiggle, and the one held follows the finger whatever
     * page is under it.
     */
    private fun placeHosted(w: Float, h: Float) {
        val now = SystemClock.uptimeMillis()
        val current = Math.round(page)
        shownAt.keys.retainAll(hosted.keys)
        for ((key, v) in hosted) {
            val id = pageOf(key) ?: continue
            val slot = slotOf(key) ?: continue
            val p = pageIds.indexOf(id) + 1
            val natural = v.layoutParams.width
            val drag = key == dragKey
            val a = if (p == 0) 0f else if (drag) 1f else pageMatrix(p, pageM)
            if (a <= 0f || natural <= 1) {
                if (v.visibility != INVISIBLE) v.visibility = INVISIBLE
                shownAt.remove(key)
                continue
            }
            val box = slotRect(w, h, slot)
            val t = stagger(orderOf(slot))
            hostMatrix.set(toScreen)
            if (drag) {
                hostMatrix.preTranslate(fingerX - grabX - box.left, fingerY - grabY - box.top)
                val lift = liftScale(now)
                hostMatrix.preScale(lift, lift, box.centerX(), box.centerY())
            } else {
                hostMatrix.preConcat(pageM)
                val k = jiggle(now)
                if (k > 0f && p == current) {
                    // Editing, the page's widgets sit back a little; eased in and out with the mode.
                    hostMatrix.preScale(1f - 0.03f * k, 1f - 0.03f * k, box.centerX(), box.centerY())
                }
                if (key == menuKey && menuOpen) {
                    val lift = liftScale(now)
                    hostMatrix.preScale(lift, lift, box.centerX(), box.centerY())
                }
                if (key == settleKey) {
                    // Let go: back into its slot from where the finger left it.
                    val f = 1f - ease((now - settleAt) / SETTLE_MS.toFloat())
                    hostMatrix.preTranslate(settleDx * f, settleDy * f)
                    hostMatrix.preScale(1f + 0.06f * f, 1f + 0.06f * f, box.centerX(), box.centerY())
                }
                if (key == pressKey) {
                    val ps = pressScale(now)
                    hostMatrix.preScale(ps, ps, box.centerX(), box.centerY())
                }
                if (key == landKey) {
                    val ls = 0.8f + 0.2f * LIFT.getInterpolation(((now - landAt) / LAND_MS.toFloat()).coerceIn(0f, 1f))
                    hostMatrix.preScale(ls, ls, box.centerX(), box.centerY())
                }
                val se = 1.14f - 0.14f * t
                hostMatrix.preScale(se, se, box.centerX(), box.centerY())
            }
            shownAt.getOrPut(key) { Matrix() }.set(hostMatrix)
            hostPoint[0] = box.left
            hostPoint[1] = box.top
            hostMatrix.mapPoints(hostPoint)
            val scale = hostMatrix.mapRadius(1f) * box.width() / natural
            v.pivotX = 0f
            v.pivotY = 0f
            v.translationX = hostPoint[0]
            v.translationY = hostPoint[1]
            // The matrix's own turn, which the jiggle adds a little to.
            val vals = FloatArray(9).also { hostMatrix.getValues(it) }
            v.rotation = Math.toDegrees(kotlin.math.atan2(vals[Matrix.MSKEW_Y].toDouble(),
                vals[Matrix.MSCALE_X].toDouble())).toFloat()
            v.scaleX = scale
            v.scaleY = scale
            v.alpha = if (key == coveredKey) 0f else t * a * (if (key == landKey) ((now - landAt) / (LAND_MS / 2f)).coerceIn(0f, 1f) else 1f)
            v.translationZ = if (drag) 16f * dens else 0f
            // Only the page on its way out blurs, as drawPage does.
            val blur = if (p == leaving && ov <= 0f && !drag) PAGE_BLUR_DP * dens * abs(p - page) / scale else 0f
            val behind = if (key == menuKey) 0f else menuK
            v.setRenderEffect(behindEffect(maxOf(blur, MENU_BLUR_DP * dens * behind / scale), behind))
            if (v.visibility != VISIBLE) v.visibility = VISIBLE
        }
    }

    // ------------------------------------------------------------------ editing: shared

    /** Widgets jiggling with their "-" (held one, or none); [editAt] is when that last changed. */
    private var wEdit = false
        set(v) {
            if (field != v) editAt = SystemClock.uptimeMillis()
            field = v
        }
    private var editAt = 0L

    /** How far into the jiggle, 0 to 1: eased in when the mode opens and out when it closes. */
    private fun jiggle(now: Long): Float {
        val e = ease((now - editAt) / JIGGLE_MS.toFloat())
        return if (wEdit) e else 1f - e
    }

    /** The widget a finger is on before its hold is up: pressed in, as iPhone's haptic touch. */
    private var pressKey: String? = null
    private var pressing = false
    private var pressAt = 0L
    private var releaseFrom = 1f

    private fun pressScale(now: Long): Float {
        val x = (now - pressAt) / (if (pressing) HOLD_MS else RELEASE_MS).toFloat()
        return if (pressing) 1f - PRESS * ease(x) else releaseFrom + (1f - releaseFrom) * ease(x)
    }

    /** A finger went down on a widget: it starts to sink in under it. */
    fun pressWidget(key: String) {
        pressKey = key
        pressing = true
        pressAt = SystemClock.uptimeMillis()
        wake()
    }

    /** The finger left or moved on before the hold was up: the widget comes back up. */
    fun releasePress() {
        if (!pressing) return
        val now = SystemClock.uptimeMillis()
        releaseFrom = pressScale(now)
        pressing = false
        pressAt = now
        wake()
    }

    /** The held widget lifts off its slot with a little overshoot, from where it was pressed. */
    private var liftAt = 0L

    private fun liftScale(now: Long) = 1f - PRESS +
        (0.06f + PRESS) * LIFT.getInterpolation(((now - liftAt) / LIFT_MS.toFloat()).coerceIn(0f, 1f))

    /** A widget let go away from a slot settles back into its own from that offset. */
    private var settleKey: String? = null
    private var settleAt = 0L
    private var settleDx = 0f
    private var settleDy = 0f

    /** The widget pages' overview, 0 to 1 on the way in. */
    private var ov = 0f
    private var ovAnim: ValueAnimator? = null
    private var overview = false

    /** Either edit mode, or a widget's menu: touches go to [editTouch], the widgets' own included. */
    val editing get() = wEdit || overview || menuOpen

    // ------------------------------------------------------------------ a widget's menu

    /**
     * A widget's menu, as iPhone's: held long enough, the widget lifts and everything else blurs
     * and dims while the activity shows the menu beside it ([onMenu], null when it goes). Moved
     * on while still held, the menu goes and the widget follows the finger as in edit mode.
     */
    var onMenu: ((key: String?, box: RectF?) -> Unit)? = null
    private var menuKey: String? = null
    private var menuOpen = false
    private var menuAt = 0L
    /** The gesture that opened the menu is still down, and where it went down. */
    private var holdLive = false
    private var holdX = 0f
    private var holdY = 0f
    private var menuK = 0f
    private var appliedMenuK = 0f

    private fun menuFade(now: Long): Float {
        val e = ease((now - menuAt) / MENU_MS.toFloat())
        return if (menuOpen) e else if (menuKey == null) 0f else 1f - e
    }

    /** Blurred by [radius] and dimmed by [dim] (0 to 1), or nothing. */
    private fun behindEffect(radius: Float, dim: Float): RenderEffect? {
        if (radius <= 0.5f && dim <= 0f) return null
        val d = 1f - MENU_DIM * dim
        val dark = RenderEffect.createColorFilterEffect(android.graphics.ColorMatrixColorFilter(
            android.graphics.ColorMatrix().apply { setScale(d, d, d, 1f) }))
        return if (radius <= 0.5f) dark
        else RenderEffect.createChainEffect(dark, RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.DECAL))
    }

    /** Held long enough on a widget: it lifts, and its menu opens; the finger may stay down. */
    fun beginWidgetHold(key: String, rawX: Float, rawY: Float) {
        updateMatrix()
        val pt = floatArrayOf(rawX, rawY)
        toPage.mapPoints(pt)
        val slot = slotOf(key) ?: return
        swallow = true
        pressing = false
        pressKey = null
        if (settleKey == key) settleKey = null
        menuKey = key
        menuOpen = true
        menuAt = SystemClock.uptimeMillis()
        liftAt = menuAt
        holdLive = true
        holdX = pt[0]
        holdY = pt[1]
        performHapticFeedback(HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE)
        wake()
        onMenu?.invoke(key, RectF(slotRect(pageW, pageH, slot)))
    }

    /** The menu goes; the widget settles back into its slot unless a finger has it. */
    fun closeMenu() {
        if (!menuOpen) return
        menuOpen = false
        menuAt = SystemClock.uptimeMillis()
        val key = menuKey
        if (key != null && key != dragKey) {
            settleKey = key
            settleAt = menuAt
            settleDx = 0f
            settleDy = 0f
        }
        onMenu?.invoke(null, null)
        wake()
    }

    /** The gesture that opened the menu: moved on, it drags the widget instead; let go, it is done. */
    private fun holdTouch(e: MotionEvent) {
        val key = menuKey ?: return
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (hypot(e.x - holdX, e.y - holdY) > 10 * dens) {
                holdLive = false
                wEdit = true
                pick(key, holdX, holdY)
                // Already lifted: the drag carries on from there.
                liftAt = SystemClock.uptimeMillis() - LIFT_MS
                closeMenu()
                widgetTouch(e)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                holdLive = false
                swallow = false
            }
        }
    }

    /** The gesture that opened an edit mode is spent; the next one starts from its own down. */
    private var swallow = false

    private val badgeR get() = 13f * dens

    /**
     * Every touch while editing, from this view or from the layer over it, in screen
     * coordinates. Kept to the page at rest: nothing here swipes pages, except the held widget
     * at an edge.
     */
    fun editTouch(raw: MotionEvent) {
        wake()
        updateMatrix()
        val e = MotionEvent.obtain(raw).apply { transform(toPage) }
        try {
            if (holdLive) return holdTouch(e)
            if (e.actionMasked == MotionEvent.ACTION_DOWN) swallow = false
            if (swallow && dragKey == null) return
            if (overview) overviewTouch(e) else widgetTouch(e)
        } finally {
            e.recycle()
        }
    }

    private fun tapOf(e: MotionEvent) = e.actionMasked == MotionEvent.ACTION_UP &&
        hypot(e.x - downX, e.y - downY) < 12 * dens

    // ------------------------------------------------------------------ editing: widgets

    /** The widget following the finger, where the finger took it, and where the finger is. */
    private var dragKey: String? = null
    private var grabX = 0f
    private var grabY = 0f
    private var fingerX = 0f
    private var fingerY = 0f
    private var dropKey: String? = null
    /** A "-" a finger went down on, and the edge the held widget waits at since when. */
    private var removeKey: String? = null
    private var edgeSince = 0L
    private var edgeDir = 0

    private fun pick(key: String, x: Float, y: Float) {
        val slot = slotOf(key) ?: return
        val box = slotRect(pageW, pageH, slot)
        dragKey = key
        liftAt = SystemClock.uptimeMillis()
        if (settleKey == key) settleKey = null
        grabX = x - box.left
        grabY = y - box.top
        fingerX = x
        fingerY = y
        downX = x
        downY = y
        edgeDir = 0
    }

    private fun widgetTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                removeKey = badgeAt(e.x, e.y)
                if (removeKey == null) hostedAt(e.x, e.y)?.let { pick(it, e.x, e.y) }
            }
            MotionEvent.ACTION_MOVE -> {
                val key = dragKey ?: return
                fingerX = e.x
                fingerY = e.y
                dropKey = dropTargetFor(key, e.x, e.y)
                edgeTurn(e.y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val key = dragKey
                val drop = dropKey
                dragKey = null
                dropKey = null
                edgeDir = 0
                if (key != null && drop == null) slotOf(key)?.let { slot ->
                    val box = slotRect(pageW, pageH, slot)
                    settleKey = key
                    settleAt = SystemClock.uptimeMillis()
                    settleDx = fingerX - grabX - box.left
                    settleDy = fingerY - grabY - box.top
                }
                if (key != null && drop != null && e.actionMasked == MotionEvent.ACTION_UP) {
                    performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    onWidgetDrop?.invoke(key, drop)
                } else if (key != null && !tapOf(e)) {
                    // Put back: if a held widget turned the page, back to its own.
                    val home = pageIds.indexOf(pageOf(key)) + 1
                    if (home > 0 && Math.round(page) != home) snapTo(home.toFloat())
                } else if (removeKey != null && tapOf(e) && removeKey == badgeAt(e.x, e.y)) {
                    performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    onRemoveWidget?.invoke(removeKey!!)
                } else if (key == null && tapOf(e)) {
                    wEdit = false
                }
                removeKey = null
                swallow = false
            }
        }
    }

    /** Held at the top or bottom edge for a moment: the previous or next widget page. */
    private fun edgeTurn(y: Float) {
        val dir = when {
            y < pageH * EDGE -> -1
            y > pageH * (1f - EDGE) -> 1
            else -> 0
        }
        val now = SystemClock.uptimeMillis()
        if (dir != edgeDir) {
            edgeDir = dir
            edgeSince = now
            return
        }
        if (dir == 0 || now - edgeSince < EDGE_MS) return
        val target = Math.round(page) + dir
        if (isWidgetPage(target)) {
            snapTo(target.toFloat())
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        edgeSince = now
    }

    /** The hosted widget on the page at rest under a point. */
    private fun hostedAt(x: Float, y: Float): String? {
        val p = Math.round(page)
        val id = idOf(p) ?: return null
        return slotsOf(p).map { slotKey(id, it) }
            .firstOrNull { it in hosted && slotRect(pageW, pageH, slotOf(it)!!).contains(x, y) }
    }

    /** The "-" under a point, of a widget on the page at rest. */
    private fun badgeAt(x: Float, y: Float): String? {
        val p = Math.round(page)
        val id = idOf(p) ?: return null
        for (slot in slotsOf(p)) {
            val key = slotKey(id, slot)
            if (key !in hosted) continue
            val b = badgeCenter(slotRect(pageW, pageH, slot))
            if (hypot(x - b.x, y - b.y) < badgeR * 1.8f) return key
        }
        return null
    }

    private fun badgeCenter(box: RectF) = PointF(box.left + box.height() * 0.06f, box.top + box.height() * 0.06f)

    /**
     * Where the held widget would go if let go here, on the page at rest: a slot of its size
     * (a 2x2 one may also take the left half of an empty page), not its own.
     */
    private fun dropTargetFor(key: String, x: Float, y: Float): String? {
        val p = Math.round(page)
        if (abs(page - p) > 0.05f) return null
        val id = idOf(p) ?: return null
        val wide = slotOf(key) == WIDE
        for (slot in slotsOf(p)) {
            if (!slotRect(pageW, pageH, slot).contains(x, y)) continue
            val target = slotKey(id, slot)
            if (target == key) return null
            return when {
                (slot == WIDE) == wide -> target
                !wide && slot == WIDE && target !in hosted -> slotKey(id, 0)
                else -> null
            }
        }
        return null
    }

    // ------------------------------------------------------------------ editing: pages

    /** The order shown while a page is dragged, its live cell positions, and the drag. */
    private var ovOrder: List<Int> = emptyList()
    private val thumbPos = HashMap<Int, PointF>()
    private var ovPressId: Int? = null
    private var ovDragId: Int? = null
    private var ovDeleteId: Int? = null
    private var ovDx = 0f
    private var ovDy = 0f

    private fun enterOverview() {
        if (pageIds.isEmpty()) return
        wEdit = false
        overview = true
        swallow = true
        ovOrder = pageIds
        thumbPos.clear()
        ovScrollAnim?.cancel()
        ovScroll = (Math.round(page) - 1).coerceIn(0, pageIds.size - 1).toFloat()
        animateOverview(1f)
    }

    private fun exitOverview(goTo: Int? = null) {
        overview = false
        if (blurOpen) openBlur(false)
        ovDragId = null
        if (goTo != null) {
            snap?.cancel()
            page = goTo.toFloat()
        }
        animateOverview(0f)
    }

    private fun animateOverview(to: Float) {
        ovAnim?.cancel()
        ovAnim = ValueAnimator.ofFloat(ov, to).apply {
            duration = 380
            interpolator = PathInterpolator(0.3f, 0f, 0.1f, 1f)
            addUpdateListener { ov = it.animatedValue as Float }
            start()
        }
    }

    /** The column, iPhone's: one card centred at a time, the next showing below it. */
    private var ovScroll = 0f
    private var ovScrollFrom = 0f
    private var ovScrolling = false
    private var ovScrollAnim: ValueAnimator? = null
    private var ovBackPressed = false
    private val clearCard = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // Not CLEAR, which ignores alpha: a hole fades in with its card.
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT)
    }

    private fun cardStep() = pageH * OV_SCALE + OV_GAP_DP * dens
    private fun scrolledPx() = ovScroll * cardStep()

    /** Card [j] of the column, before it is scrolled. */
    private fun cell(j: Int, out: RectF): Float {
        val tw = pageW * OV_SCALE
        val th = pageH * OV_SCALE
        val left = (pageW - tw) / 2f
        val top = (pageH - th) / 2f + j * cardStep()
        out.set(left, top, left + tw, top + th)
        return OV_SCALE
    }

    /** The column settles on card [to]. */
    private fun scrollTo(to: Float) {
        ovScrollAnim?.cancel()
        ovScrollAnim = ValueAnimator.ofFloat(ovScroll, to).apply {
            duration = 300
            interpolator = PathInterpolator(0.3f, 0f, 0.1f, 1f)
            addUpdateListener { ovScroll = it.animatedValue as Float }
            start()
        }
    }

    /** The way back, under the status glyph and in line with it, iPhone's. */
    private fun backX() = pageW - BACK_SIDE_DP * dens
    private fun backY() = BACK_TOP_DP * dens
    private fun backR() = BACK_R_DP * dens * (0.7f + 0.3f * ov) * (if (ovBackPressed) 0.92f else 1f)
    private fun backHit(x: Float, y: Float) = hypot(x - backX(), y - backY()) < (BACK_R_DP + 8f) * dens

    /** Every widget's look, light or dark, under the way back; lit up while they are light. */
    private fun lookY() = LOOK_TOP_DP * dens
    private fun lookR() = BACK_R_DP * dens * (0.7f + 0.3f * ov) * (if (ovLookPressed) 0.92f else 1f)
    private fun lookHit(x: Float, y: Float) = hypot(x - backX(), y - lookY()) < (BACK_R_DP + 5f) * dens
    private var ovLookPressed = false
    private val lookSun = resources.getDrawable(R.drawable.ic_look_sun, null).mutate()
    private val lookMoon = resources.getDrawable(R.drawable.ic_look_moon, null).mutate()

    /** The widgets' background, under that: tapped, it grows down into a slider ([blurT], 0 to 1). */
    private fun blurY() = BLUR_TOP_DP * dens
    private fun blurR() = BACK_R_DP * dens * (0.7f + 0.3f * ov)
    private fun blurBottom() = blurY() + blurR() + BLUR_GROW_DP * dens * blurT
    private fun blurHit(x: Float, y: Float) = hypot(x - backX(), y - blurY()) < (BACK_R_DP + 5f) * dens
    private fun sliderHit(x: Float, y: Float) = abs(x - backX()) < (BACK_R_DP + 12f) * dens &&
        y > blurY() + BACK_R_DP * dens && y < blurBottom() + 12f * dens
    private var blurOpen = false
    private var blurT = 0f
    private var blurAnim: ValueAnimator? = null
    private var ovBlurPressed = false
    private var ovSliding = false
    /** The gesture that closed the slider by tapping off it: it does nothing else. */
    private var ovClosing = false
    private val blurIcon = resources.getDrawable(R.drawable.ic_widget_blur, null).mutate()

    private fun openBlur(open: Boolean) {
        blurOpen = open
        blurAnim?.cancel()
        blurAnim = ValueAnimator.ofFloat(blurT, if (open) 1f else 0f).apply {
            duration = 300
            interpolator = EASE_CARD
            addUpdateListener { blurT = it.animatedValue as Float }
            start()
        }
    }

    private fun slideTo(y: Float, done: Boolean) {
        widgetGlass = ((BLUR_TRACK_BOTTOM_DP * dens - y) / ((BLUR_TRACK_BOTTOM_DP - BLUR_TRACK_TOP_DP) * dens)).coerceIn(0f, 1f)
        onWidgetGlass?.invoke(widgetGlass, done)
    }

    /** The slider's gestures; true if this one is its, and the column should not hear of it. */
    private fun sliderTouch(e: MotionEvent): Boolean {
        val end = e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            ovSliding = blurOpen && sliderHit(e.x, e.y)
            // The buttons beside it still do their own job, and close it on the way.
            val button = lookHit(e.x, e.y) || backHit(e.x, e.y)
            ovClosing = blurOpen && !ovSliding && !blurHit(e.x, e.y) && !button
            if (blurOpen && button) openBlur(false)
            if (ovClosing) openBlur(false)
            if (ovSliding) slideTo(e.y, false)
            return ovSliding || ovClosing
        }
        if (ovSliding) {
            slideTo(e.y, end)
            if (end) ovSliding = false
            return true
        }
        if (ovClosing) {
            if (end) ovClosing = false
            return true
        }
        return false
    }

    /** The page the column is on, as a page number. */
    private fun focusedPage(): Int {
        val id = ovOrder.getOrNull(Math.round(ovScroll)) ?: return 0
        return pageIds.indexOf(id) + 1
    }

    /**
     * Black around the cards. The backdrop is a view under this one, so each card is a hole in
     * the black, and the page's blurred background shows through it as it does at full size. The
     * black is solid all the way in and out, so the background only ever shows inside the card
     * shrinking or growing; a card that isn't that one fades in. From the add page no card is
     * shrinking, so there the black fades instead.
     */
    private fun drawOutsideCards(c: Canvas, w: Float, h: Float) {
        val save = c.saveLayer(null, null)
        c.drawColor(Color.argb(if (isWidgetPage(Math.round(page))) 255 else (255 * ov).toInt(), 0, 0, 0))
        val r = h * OV_CORNER
        for (p in 1..pageIds.size) {
            val a = pageMatrix(p, pageM)
            if (a <= 0f) continue
            c.save()
            c.concat(pageM)
            clearCard.alpha = (255 * a).toInt()
            c.drawRoundRect(0f, 0f, w, h, r, r, clearCard)
            c.restore()
        }
        c.restoreToCount(save)
    }

    /** Held still on a card: from here the finger carries it to another place in the column. */
    private val ovHold = Runnable {
        val id = ovPressId ?: return@Runnable
        ovDragId = id
        animateCard(1.06f, LIFT_MS, LIFT)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private val cellBox = RectF()

    /** Each page's cell eased toward where the current order puts it. */
    private fun stepThumbs() {
        val order = ovOrder
        for ((j, id) in order.withIndex()) {
            cell(j, cellBox)
            val pos = thumbPos.getOrPut(id) { PointF(cellBox.left, cellBox.top) }
            pos.x += (cellBox.left - pos.x) * 0.3f
            pos.y += (cellBox.top - pos.y) * 0.3f
        }
    }

    private fun thumbMatrix(id: Int, out: Matrix) {
        val k = cell(0, cellBox)
        val pos = thumbPos[id] ?: run {
            cell(ovOrder.indexOf(id).coerceAtLeast(0), cellBox)
            PointF(cellBox.left, cellBox.top)
        }
        val y = pos.y - scrolledPx()
        out.setScale(k, k)
        out.postTranslate(pos.x, y)
        if (id == ovCardId) out.postScale(ovCardScale, ovCardScale, pos.x + pageW * k / 2f, y + pageH * k / 2f)
        if (id == ovDragId) out.postTranslate(ovDx, ovDy)
    }

    /** The card under a finger: it sinks while held, lifts when picked up, settles when let go. */
    private var ovCardId: Int? = null
    private var ovCardScale = 1f
    private var ovCardAnim: ValueAnimator? = null

    private fun animateCard(to: Float, ms: Long, curve: android.animation.TimeInterpolator) {
        ovCardAnim?.cancel()
        ovCardAnim = ValueAnimator.ofFloat(ovCardScale, to).apply {
            duration = ms
            interpolator = curve
            addUpdateListener { ovCardScale = it.animatedValue as Float }
            start()
        }
    }

    /** Where a card's "-" sits: on the arc of its rounded corner, not the corner of its box. */
    private fun cardBadge(r: RectF): PointF {
        val inset = r.height() * OV_CORNER * 0.293f
        return PointF(r.right - inset, r.top + inset)
    }

    /** A page's cell in the overview, where it is drawn now. */
    private fun thumbRect(id: Int): RectF {
        thumbMatrix(id, thumbM)
        val r = RectF(0f, 0f, pageW, pageH)
        thumbM.mapRect(r)
        return r
    }

    private fun drawThumbCard(c: Canvas, w: Float, h: Float) {
        val r = h * OV_CORNER
        fill.color = Color.argb((26 * ov).toInt(), 255, 255, 255)
        c.drawRoundRect(0f, 0f, w, h, r, r, fill)
    }

    private fun overviewTouch(e: MotionEvent) {
        if (sliderTouch(e)) return
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                ovScrollAnim?.cancel()
                ovScrollFrom = ovScroll
                ovScrolling = false
                ovBackPressed = backHit(e.x, e.y)
                ovLookPressed = lookHit(e.x, e.y)
                ovBlurPressed = blurHit(e.x, e.y)
                ovDeleteId = if (ovBackPressed || ovLookPressed || ovBlurPressed) null else ovOrder.firstOrNull { id ->
                    val b = cardBadge(thumbRect(id))
                    hypot(e.x - b.x, e.y - b.y) < badgeR * 1.8f
                }
                ovPressId = if (ovDeleteId != null || ovBackPressed || ovLookPressed || ovBlurPressed) null
                else ovOrder.lastOrNull { thumbRect(it).contains(e.x, e.y) }
                ovPressId?.let {
                    postDelayed(ovHold, HOLD_MS)
                    ovCardId = it
                    animateCard(1f - PRESS, HOLD_MS, EASE_CARD)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val id = ovDragId
                if (id == null) {
                    if (hypot(e.x - downX, e.y - downY) > 12 * dens) {
                        removeCallbacks(ovHold)
                        if (!ovScrolling) animateCard(1f, RELEASE_MS, EASE_CARD)
                        ovScrolling = true
                    }
                    if (ovScrolling) ovScroll = (ovScrollFrom - (e.y - downY) / cardStep())
                        .coerceIn(0f, (ovOrder.size - 1).coerceAtLeast(0).toFloat())
                    return
                }
                ovDx = e.x - downX
                ovDy = e.y - downY
                // The cell nearest the finger is where it goes; the rest close up around it.
                val n = ovOrder.size
                var best = 0
                var bestD = Float.MAX_VALUE
                for (j in 0 until n) {
                    cell(j, cellBox)
                    val d = hypot(e.x - cellBox.centerX(), e.y - (cellBox.centerY() - scrolledPx()))
                    if (d < bestD) { bestD = d; best = j }
                }
                if (ovOrder.indexOf(id) != best) {
                    val before = thumbRect(id)
                    ovOrder = ovOrder.toMutableList().apply { remove(id); add(best, id) }
                    // The dragged cell is drawn at its new place plus the drag: keep it under
                    // the finger by moving where the drag is measured from.
                    thumbPos[id]?.let { pos ->
                        cell(best, cellBox)
                        pos.set(cellBox.left, cellBox.top)
                    }
                    val after = thumbRect(id)
                    downX += after.left - before.left
                    downY += after.top - before.top
                    ovDx = e.x - downX
                    ovDy = e.y - downY
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val dragged = ovDragId
                val pressed = ovPressId
                val delete = ovDeleteId
                val back = ovBackPressed
                val look = ovLookPressed
                ovLookPressed = false
                val blur = ovBlurPressed
                ovBlurPressed = false
                val scrolled = ovScrolling
                removeCallbacks(ovHold)
                if (pressed != null && !scrolled) animateCard(1f, SETTLE_MS, EASE_CARD)
                // Let go: it eases into its cell from where the finger left it.
                dragged?.let { thumbPos[it]?.offset(ovDx, ovDy) }
                ovScrolling = false
                ovBackPressed = false
                ovDragId = null
                ovPressId = null
                ovDeleteId = null
                ovDx = 0f
                ovDy = 0f
                swallow = false
                if (scrolled) {
                    // A short flick is enough to go on to the next card.
                    val lean = if (ovScroll > ovScrollFrom) 0.3f else -0.3f
                    scrollTo(Math.round(ovScroll + lean).coerceIn(0, ovOrder.size - 1).toFloat())
                    return
                }
                if (e.actionMasked != MotionEvent.ACTION_UP) return
                when {
                    dragged != null -> if (ovOrder != pageIds) {
                        pageIds = ovOrder
                        onPagesChanged?.invoke(pageIds)
                    }
                    delete != null && tapOf(e) -> {
                        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        ovOrder = ovOrder - delete
                        thumbPos.remove(delete)
                        onDeletePage?.invoke(delete)
                        if (ovOrder.isEmpty()) exitOverview(0)
                        else scrollTo(ovScroll.coerceAtMost((ovOrder.size - 1).toFloat()))
                    }
                    look -> if (lookHit(e.x, e.y)) {
                        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        widgetsDark = !widgetsDark
                        onWidgetsDark?.invoke(widgetsDark)
                    }
                    blur -> if (blurHit(e.x, e.y)) {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        openBlur(!blurOpen)
                    }
                    pressed != null && tapOf(e) -> exitOverview(pageIds.indexOf(pressed) + 1)
                    (back && backHit(e.x, e.y)) || tapOf(e) -> exitOverview(focusedPage())
                }
            }
        }
    }

    // ------------------------------------------------------------------ overlay

    private val overlayM = Matrix()

    /**
     * What has to be over the widgets, drawn by the layer that holds them, in its (the
     * screen's) coordinates: the "-" badges, the slot a held widget would land in, and a
     * stack's dots while it is swiped.
     */
    fun drawOverlay(c: Canvas) {
        val w = pageW
        val h = pageH
        if (w <= 0f) return
        c.save()
        c.concat(toScreen)
        val k = jiggle(SystemClock.uptimeMillis())
        if (k > 0f && !overview) {
            // The "-"s pop in with the jiggle and fade out with it.
            val pop = if (wEdit) LIFT.getInterpolation(k) else 1f
            val p = Math.round(page)
            idOf(p)?.let { id ->
                for (slot in slotsOf(p)) {
                    val key = slotKey(id, slot)
                    if (key !in hosted || key == dragKey) continue
                    val m = shownAt[key] ?: continue
                    val b = badgeCenter(slotRect(w, h, slot))
                    val pt = floatArrayOf(b.x, b.y)
                    m.mapPoints(pt)
                    toPage.mapPoints(pt)
                    drawBadge(c, pt[0], pt[1], k, pop)
                }
            }
            dropKey?.let { key ->
                val slot = slotOf(key) ?: return@let
                if (pageMatrix(Math.round(page), overlayM) <= 0f) return@let
                c.save()
                c.concat(overlayM)
                val box = slotRect(w, h, slot)
                stroke.color = Color.WHITE
                stroke.strokeWidth = 3f * dens
                val r = box.height() * CORNER
                c.drawRoundRect(box, r, r, stroke)
                c.restore()
            }
        }
        if (ov > 0.05f) {
            for (id in ovOrder) {
                val b = cardBadge(thumbRect(id))
                drawBadge(c, b.x, b.y, ov)
            }
            drawOverviewChrome(c, w, h)
        }
        drawStackDots(c, w, h)
        volume.draw(c, w)
        // Top right whichever way the phone lies, as on iPhone; the camera is mid-side, clear of it.
        status.draw(c, w, shown.coerceIn(0f, 1f), true)
        c.restore()
    }

    /** The column's way back, and its dots beside the card, the one it is on lit. */
    private fun drawOverviewChrome(c: Canvas, w: Float, h: Float) {
        val x0 = backX()
        val y0 = backY()
        val r = backR()
        drawGlass(c, x0, y0 - r, y0 + r, r)
        stroke.color = Color.argb((255 * ov).toInt(), 255, 255, 255)
        stroke.strokeWidth = 2f * dens
        val arm = r * 0.36f
        val tip = x0 - arm * 0.5f
        c.drawLine(tip + arm, y0 - arm, tip, y0, stroke)
        c.drawLine(tip, y0, tip + arm, y0 + arm, stroke)
        val ly = lookY()
        val lr = lookR()
        drawGlass(c, x0, ly - lr, ly + lr, lr)
        // Lit while they are light.
        fill.color = Color.argb(((if (widgetsDark) 0 else 235) * ov).toInt(), 255, 255, 255)
        c.drawCircle(x0, ly, lr, fill)
        val half = (lr * 0.62f).toInt()
        // A moon while they are dark, a sun while they are light.
        val lookIcon = if (widgetsDark) lookMoon else lookSun
        lookIcon.setBounds(x0.toInt() - half, ly.toInt() - half, x0.toInt() + half, ly.toInt() + half)
        lookIcon.setTint(if (widgetsDark) Color.WHITE else Color.rgb(28, 28, 30))
        lookIcon.alpha = (255 * ov).toInt()
        lookIcon.draw(c)
        drawBlurButton(c, x0)
        val n = ovOrder.size
        if (n < 2) return
        val x = (w + w * OV_SCALE) / 2f + 18 * dens
        val on = Math.round(ovScroll)
        for (j in 0 until n) {
            fill.color = Color.argb(((if (j == on) 255 else 90) * ov).toInt(), 255, 255, 255)
            c.drawCircle(x, h / 2f + (j - (n - 1) / 2f) * 12 * dens, 3 * dens, fill)
        }
    }

    /** The buttons' own glass, the same whatever the background is: only black is ever behind them to blur. */
    private fun drawGlass(c: Canvas, x: Float, top: Float, bottom: Float, r: Float) {
        fill.color = Color.argb((GLASS_ALPHA * ov).toInt(), 255, 255, 255)
        c.drawRoundRect(x - r, top, x + r, bottom, r, r, fill)
        val edge = 0.75f * dens
        stroke.color = Color.argb((GLASS_EDGE_ALPHA * ov).toInt(), 255, 255, 255)
        stroke.strokeWidth = edge
        c.drawRoundRect(x - r + edge / 2f, top + edge / 2f, x + r - edge / 2f, bottom - edge / 2f, r, r, stroke)
    }

    /** The background button, and as it grows, its slider: a track, filled from below to the level. */
    private fun drawBlurButton(c: Canvas, x: Float) {
        val y = blurY()
        val r = blurR()
        drawGlass(c, x, y - r, blurBottom(), r)
        // Lit while open, as the look button is while light.
        fill.color = Color.argb(((if (blurOpen) 235 else 0) * blurT * ov).toInt(), 255, 255, 255)
        c.drawCircle(x, y, r, fill)
        val half = (r * 0.62f).toInt()
        blurIcon.setBounds(x.toInt() - half, y.toInt() - half, x.toInt() + half, y.toInt() + half)
        blurIcon.setTint(if (blurOpen && blurT > 0.5f) Color.rgb(28, 28, 30) else Color.WHITE)
        blurIcon.alpha = (255 * ov).toInt()
        blurIcon.draw(c)
        if (blurT <= 0.01f) return
        val a = blurT * ov
        val tw = 11f * dens
        val top = BLUR_TRACK_TOP_DP * dens
        val bottom = BLUR_TRACK_BOTTOM_DP * dens
        val at = bottom - (bottom - top) * widgetGlass
        val cr = 8f * dens
        fill.color = Color.argb((60 * a).toInt(), 255, 255, 255)
        c.drawRoundRect(x - tw, top, x + tw, bottom, cr, cr, fill)
        c.save()
        c.clipRect(x - tw, at, x + tw, bottom)
        fill.color = Color.argb((235 * a).toInt(), 255, 255, 255)
        c.drawRoundRect(x - tw, top, x + tw, bottom, cr, cr, fill)
        c.restore()
    }

    private fun drawBadge(c: Canvas, x: Float, y: Float, alpha: Float = 1f, scale: Float = 1f) {
        val r = badgeR * scale
        fill.color = Color.argb((235 * alpha).toInt(), 58, 58, 60)
        c.drawCircle(x, y, r, fill)
        stroke.color = Color.argb((255 * alpha).toInt(), 255, 255, 255)
        stroke.strokeWidth = 2.4f * dens * scale
        c.drawLine(x - r * 0.45f, y, x + r * 0.45f, y, stroke)
    }

    private var stackDotsKey: String? = null
    private var stackDotsUntil = 0L

    /** A stack was swiped: its dots show for a moment. */
    fun showStackDots(key: String) {
        stackDotsKey = key
        stackDotsUntil = SystemClock.uptimeMillis() + DOTS_LINGER_MS
        wake(DOTS_LINGER_MS + DOTS_FADE_MS * 2)
    }

    /** The dots' fade, 0 to 1, the stack they belong to, and the last frame that stepped it. */
    private var dotsA = 0f
    private var dotsShownKey: String? = null
    private var dotsFrame = 0L

    /**
     * A column of dots beside the slot's right edge. They fade and slide in out of the widget's
     * edge, and fade back when done; the light follows the stack as it scrolls, from one dot to
     * the next, the lit one a little larger.
     */
    private fun drawStackDots(c: Canvas, w: Float, h: Float) {
        val now = SystemClock.uptimeMillis()
        // A held stack shows its dots for as long as its menu (or editor) is up.
        val held = if (menuOpen) menuKey else null
        val want = held ?: stackDotsKey?.takeIf { now <= stackDotsUntil && !editing }
        if (want != null && want != dotsShownKey) {
            dotsShownKey = want
            dotsA = 0f
        }
        val dt = (now - dotsFrame).coerceIn(0L, 50L) / DOTS_FADE_MS.toFloat()
        dotsFrame = now
        dotsA = (dotsA + if (want != null) dt else -dt).coerceIn(0f, 1f)
        val key = dotsShownKey ?: return
        if (dotsA <= 0f) return
        val (index, count) = stackInfo(key) ?: return
        if (count < 2) return
        val slot = slotOf(key) ?: return
        val m = shownAt[key] ?: return
        val pos = stackPosOf(key) ?: index.toFloat()
        val box = slotRect(w, h, slot)
        val e = ease(dotsA)
        val r = 3.4f * dens
        val pitch = 12f * dens
        // A column just off the slot's right edge, as iPhone's, as the stack runs up and down.
        val x = box.right + (4f + 6f * e) * dens
        for (i in 0 until count) {
            val lit = 1f - abs(i - pos).coerceAtMost(1f)
            val pt = floatArrayOf(x, box.centerY() + (i - (count - 1) / 2f) * pitch)
            m.mapPoints(pt)
            toPage.mapPoints(pt)
            fill.color = Color.argb(((110 + 145 * lit) * dotsA).toInt(), 255, 255, 255)
            c.drawCircle(pt[0], pt[1], r * (1f + 0.25f * lit) * (0.6f + 0.4f * e), fill)
        }
    }

    companion object {
        /** The whole 4x2 slot of a page; 0 and 1 are its 2x2 halves. */
        const val WIDE = 2
        /** The slots a page can have: its two halves and the whole. */
        val SLOTS = intArrayOf(0, 1, WIDE)
        /** Corner radius of a slot and of the widget in it, as a share of its height. */
        const val CORNER = 0.2f
        /** A widget page's margin above and below its widgets: clear of the status corner. */
        private const val EDGE_DP = 34f
        private const val GAP_DP = 36f

        /** A slot's name, page id and place: what the widgets in it are saved under. */
        fun slotKey(page: Int, slot: Int) = if (slot == WIDE) "p${page}w" else "p${page}s$slot"

        fun pageOf(key: String) = key.drop(1).takeWhile { it.isDigit() }.toIntOrNull()

        fun slotOf(key: String): Int? = when {
            key.endsWith("w") -> WIDE
            else -> key.substringAfterLast('s', "").toIntOrNull()
        }

        /** Widget pages at most. */
        const val MAX_PAGES = 10
        private const val DOTS_LINGER_MS = 1_200L
        private const val DOTS_FADE_MS = 220L
        private const val BURN_IN_FADE_MS = 1_200L
        /** An [idleAfterMs] that never comes: added to the clock, it still cannot overflow. */
        const val NEVER_MS = 1L shl 50
        private const val BURN_OUT_FADE_MS = 400L
        /** A hold this long, without moving, opens an edit mode. */
        const val HOLD_MS = 450L
        /** The page overview's cards: their size against the page, the gap, and their corners. */
        private const val OV_SCALE = 0.62f
        private const val OV_GAP_DP = 22f
        private const val OV_CORNER = 0.12f
        /** The way back: its radius, and its centre from the top and the side (the status glyph's). */
        private const val BACK_R_DP = 18f
        private const val BACK_TOP_DP = 96f
        private const val BACK_SIDE_DP = 41f
        private const val LOOK_TOP_DP = 144f
        /** The blur button, how far it grows into its slider, and the slider's track. */
        private const val BLUR_TOP_DP = 192f
        private const val BLUR_GROW_DP = 145f
        private const val BLUR_TRACK_TOP_DP = 218f
        private const val BLUR_TRACK_BOTTOM_DP = 345f
        /** A widget's menu: its fade, and how far what is behind it blurs and dims. */
        private const val MENU_MS = 260L
        private const val MENU_BLUR_DP = 18f
        private const val GLASS_ALPHA = 46
        private const val GLASS_EDGE_ALPHA = 40
        private const val MENU_DIM = 0.35f
        /** How far a widget sinks in under a hold; its way back up, lift, settle and jiggle fade. */
        private const val PRESS = 0.05f
        private const val RELEASE_MS = 220L
        private const val LIFT_MS = 380L
        private const val SETTLE_MS = 320L
        private const val LAND_MS = 420L
        private const val JIGGLE_MS = 260L
        internal val LIFT = android.view.animation.OvershootInterpolator(2.2f)
        private val EASE_CARD = PathInterpolator(0.3f, 0f, 0.1f, 1f)
        /** The held widget turns the page from this share of the page's height at an edge, ... */
        private const val EDGE = 0.12f
        /** ... after waiting there this long. */
        private const val EDGE_MS = 600L
        /** How small a page gets on its way out (and in), at a whole page away. */
        private const val PAGE_SHRINK = 0.3f
        /** How far it travels meanwhile, as a share of the page height. */
        private const val PAGE_SLIDE = 1f
        /** Blur of a page a whole page from rest, in dp. */
        private const val PAGE_BLUR_DP = 28f
    }
}
