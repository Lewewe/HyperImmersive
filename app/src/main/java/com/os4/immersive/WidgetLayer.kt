package com.os4.immersive

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The hosted widgets, laid over [page] (StandbyView moves each one with its slot; a widget's
 * frame carries its slot key as its tag).
 *
 * A tap on a widget is the widget's. A swipe begun on one is taken from it (the widget gets a
 * cancel) once it is past the touch slop: along the page's up and down it is the stack's next
 * widget ([onStackScroll], following the finger) on a stack, else the page's, from its first down
 * on; across, the widget's.
 * Held still for [StandbyView.HOLD_MS] it starts the page's widget edit, and the rest of the
 * gesture drags it. While the page is editing every touch is the page's, widgets or not.
 *
 * A gesture on a widget is followed whether or not the widget takes its down: where it does,
 * through onInterceptTouchEvent; where it does not (no clickable view there), this layer takes
 * the down itself and follows it through onTouchEvent - left to the page under it instead, the
 * hold timer never heard the finger lift, fired after a swipe, and left a widget picked up
 * (2026-10-23). Touches on no widget are declined and reach the page under it directly.
 *
 * Also draws what has to be over the widgets ([StandbyView.drawOverlay]).
 */
class WidgetLayer(context: Context, private val page: StandbyView) : FrameLayout(context) {

    /**
     * A stack scrolled by the finger: how far it has gone along the page's up and down since it
     * went down (page pixels), and whether it has let go.
     */
    var onStackScroll: ((key: String, dy: Float, done: Boolean) -> Unit)? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var down: MotionEvent? = null
    private var downKey: String? = null
    private var mode = NONE
    /** This gesture's first intercepted event has been handed on (see [onInterceptTouchEvent]). */
    private var taken = false

    private val hold = Runnable {
        val key = downKey ?: return@Runnable
        val d = down ?: return@Runnable
        if (mode != NONE) return@Runnable
        mode = HOLD
        page.beginWidgetHold(key, d.x, d.y)
    }

    init {
        setWillNotDraw(false)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        page.drawOverlay(canvas)
    }

    private fun start(e: MotionEvent) {
        removeCallbacks(hold)
        down?.recycle()
        down = MotionEvent.obtain(e)
        downKey = keyAt(e.x, e.y)
        taken = false
        mode = if (page.editing) EDIT else NONE
        downKey?.let {
            if (mode != NONE) return@let
            postDelayed(hold, StandbyView.HOLD_MS)
            page.pressWidget(it)
        }
    }

    /** Past the slop with nothing decided yet: the page's swipe, a stack's, or the widget's own. */
    private fun decide(e: MotionEvent) {
        if (mode != NONE || e.actionMasked != MotionEvent.ACTION_MOVE) return
        val d = down ?: return
        val v = page.toPageVector(e.x - d.x, e.y - d.y)
        if (hypot(v.x, v.y) <= slop) return
        removeCallbacks(hold)
        page.releasePress()
        val key = downKey
        val stack = key?.let { page.stackInfo(it)?.second } ?: 0
        mode = when {
            abs(v.y) <= abs(v.x) -> CHILD
            key != null && stack > 1 -> {
                // Up and down a stack, as iPhone's StandBy: up brings the next widget.
                onStackScroll?.invoke(key, v.y, false)
                STACK
            }
            else -> {
                page.dispatchTouchEvent(d)
                PAGE
            }
        }
    }

    /** An event of a gesture this layer holds, to whoever it now belongs to. */
    private fun deliver(e: MotionEvent) {
        when (mode) {
            PAGE -> page.dispatchTouchEvent(e)
            HOLD, EDIT -> page.editTouch(e)
            STACK -> {
                val d = down
                val key = downKey
                if (d != null && key != null) {
                    val v = page.toPageVector(e.x - d.x, e.y - d.y)
                    val end = e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL
                    onStackScroll?.invoke(key, v.y, end)
                }
            }
        }
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
            removeCallbacks(hold)
            page.releasePress()
            mode = NONE
            taken = false
        }
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            start(e)
            return mode == EDIT
        }
        decide(e)
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
            removeCallbacks(hold)
            page.releasePress()
        }
        val take = mode == PAGE || mode == STACK || mode == HOLD
        // The event a ViewGroup first intercepts never reaches its own onTouchEvent - the child
        // gets a cancel instead - so it is handed on here: a hold let go without moving lost
        // its up this way, and the swipe its first move.
        if (take && !taken) {
            taken = true
            deliver(e)
        }
        return take || mode == EDIT
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            if (mode == EDIT) {
                page.editTouch(e)
                return true
            }
            // No widget took the down: keep the gesture, if it began on a widget, to follow it.
            return downKey != null
        }
        decide(e)
        deliver(e)
        return true
    }

    private val inverse = Matrix()

    /** The slot of the widget under a point, through each frame's own turn and scale. */
    private fun keyAt(x: Float, y: Float): String? {
        for (i in childCount - 1 downTo 0) {
            val child = getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            if (!child.matrix.invert(inverse)) continue
            val pt = floatArrayOf(x - child.left, y - child.top)
            inverse.mapPoints(pt)
            if (pt[0] in 0f..child.width.toFloat() && pt[1] in 0f..child.height.toFloat()) {
                return child.tag as? String
            }
        }
        return null
    }

    private companion object {
        const val NONE = 0
        const val CHILD = 1
        const val PAGE = 2
        const val STACK = 3
        const val HOLD = 4
        const val EDIT = 5
    }
}
