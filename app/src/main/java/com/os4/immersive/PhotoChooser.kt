package com.os4.immersive

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * A photo widget's photo, chosen inside StandBy itself (the system's photo picker cannot show over
 * the lock screen): the library as a grid, newest first, then the chosen one framed - moved and
 * scaled under a frame of the widget's shape ([aspect], width over height). Laid out landscape and
 * turned with the page by the activity, as [WidgetPicker] is. [onDone] has the photo and the part
 * of it inside the frame, as fractions of the photo.
 */
@SuppressLint("ViewConstructor")
class PhotoChooser(context: Context, private val aspect: Float, private val onDone: (Long, RectF) -> Unit,
                   private val onClose: () -> Unit) : FrameLayout(context) {

    private val dens = resources.displayMetrics.density
    private val app = context.applicationContext
    private val panel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val title = text(20f, Color.WHITE, 700)
    private val action = text(17f, Color.rgb(255, 69, 58), 600)
    /** The page settings' way back: a chevron in a round button. */
    private val back = object : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(72, 72, 74) }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 2 * dens
            strokeCap = Paint.Cap.ROUND
        }
        override fun onDraw(c: Canvas) {
            val r = minOf(width, height) / 2f
            val x0 = width / 2f
            val y0 = height / 2f
            c.drawCircle(x0, y0, r, fill)
            val arm = r * 0.36f
            val tip = x0 - arm * 0.5f
            c.drawLine(tip + arm, y0 - arm, tip, y0, stroke)
            c.drawLine(tip, y0, tip + arm, y0 + arm, stroke)
        }
    }.apply {
        visibility = GONE
        setOnClickListener { showGrid() }
    }
    private val body = FrameLayout(context)
    private val loader = Executors.newFixedThreadPool(2)
    private val thumbs = LruCache<Long, Bitmap>(THUMBS_KEPT)
    private var ids = LongArray(0)
    private var days = emptyArray<java.time.LocalDate>()
    private var rows = emptyList<Row>()

    init {
        setBackgroundColor(Color.argb(150, 0, 0, 0))
        setOnClickListener { close() }
        panel.apply {
            // The screen's own corner, less the margin: rounded alongside the display's edge.
            val screen = context.display?.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_LEFT)?.radius ?: 0
            background = GradientDrawable().apply {
                setColor(Color.rgb(44, 44, 46))
                cornerRadius = maxOf(28 * dens, (screen - dp(MARGIN_DP)).toFloat())
            }
            isClickable = true
            setPadding(dp(20), dp(16), dp(20), dp(20))
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                // As far in from the sides as the words are down from the top.
                setPadding(dp(HEADER_INSET_DP), 0, dp(HEADER_INSET_DP), 0)
                clipToPadding = false
                addView(back, LinearLayout.LayoutParams(dp(BACK_DP), dp(BACK_DP)).apply {
                    // Its own margin: as far in from the side as it is down from the top.
                    marginStart = -dp(HEADER_INSET_DP)
                    marginEnd = dp(14)
                })
                addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(action)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
            addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(10) })
        }
        addView(panel, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            setMargins(dp(MARGIN_DP), dp(MARGIN_DP), dp(MARGIN_DP), dp(MARGIN_DP))
        })
        showGrid()
        alpha = 0f
        animate().alpha(1f).setDuration(FADE_MS).start()
    }

    private fun showGrid() {
        title.setText(R.string.photo_choose)
        action.setText(R.string.photo_cancel)
        action.setOnClickListener { close() }
        back.visibility = GONE
        body.removeAllViews()
        if (!Photos.allowed(context)) return message(R.string.photo_access_off)
        val list = android.widget.ListView(context).apply {
            divider = null
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
        }
        val box = FrameLayout(context).apply {
            addView(list)
            addView(DayScroller(list))
        }
        body.addView(box)
        box.alpha = 0f
        box.animate().alpha(1f).setDuration(FADE_MS).start()
        loader.execute {
            val read = Photos.library(app)
            post {
                ids = LongArray(read.size) { read[it].first }
                days = Array(read.size) { java.time.Instant.ofEpochMilli(read[it].second)
                    .atZone(java.time.ZoneId.systemDefault()).toLocalDate() }
                if (read.isEmpty()) return@post message(R.string.photo_none)
                val cols = maxOf(1, (body.width + dp(GAP_DP)) / (dp(THUMB_DP) + dp(GAP_DP)))
                rows = sections(cols)
                list.adapter = Rows(cols)
            }
        }
    }

    /** Each day a header, then its photos [cols] to a row. */
    private fun sections(cols: Int): List<Row> {
        val out = ArrayList<Row>()
        var i = 0
        while (i < ids.size) {
            val day = days[i]
            var end = i
            while (end < ids.size && days[end] == day) end++
            out += Row(day, i, 0)
            for (k in i until end step cols) out += Row(day, k, minOf(cols, end - k))
            i = end
        }
        return out
    }

    private fun dayTitle(day: java.time.LocalDate): String {
        val today = java.time.LocalDate.now()
        return when (day) {
            today -> context.getString(R.string.photo_today)
            today.minusDays(1) -> context.getString(R.string.photo_yesterday)
            else -> dateText(day, if (day.year == today.year) "MMMd" else "yMMMd")
        }
    }

    private fun dateText(day: java.time.LocalDate, skeleton: String): String {
        val locale = resources.configuration.locales[0]
        return java.time.format.DateTimeFormatter.ofPattern(
            android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(day)
    }

    /** Grown out of the tapped [cell] while the grid fades. */
    private fun showCrop(id: Long, cell: View) {
        title.setText(R.string.photo_crop)
        action.setText(R.string.photo_done)
        back.visibility = VISIBLE
        val crop = CropView(context, aspect)
        action.setOnClickListener {
            val part = crop.part() ?: return@setOnClickListener
            onDone(id, part)
            close()
        }
        val grid = body.getChildAt(0)
        grid.isEnabled = false
        body.addView(crop)
        // Where the cell is in the body, through the list and its row.
        var x = 0f
        var y = 0f
        var v: View = cell
        while (v !== body) {
            x += v.x
            y += v.y
            v = v.parent as View
        }
        crop.pivotX = x + cell.width / 2f
        crop.pivotY = y + cell.height / 2f
        crop.scaleX = cell.width / body.width.toFloat().coerceAtLeast(1f)
        crop.scaleY = crop.scaleX
        crop.alpha = 0f
        crop.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(GROW_MS).setInterpolator(EASE).start()
        grid.animate().alpha(0f).setDuration(GROW_MS / 2).withEndAction { body.removeView(grid) }.start()
        loader.execute {
            val b = Photos.preview(app, id, PREVIEW_PX)
            // Not if Back has left it already.
            post { if (crop.parent == null) return@post else if (b == null) message(R.string.photo_missing) else crop.bitmap = b }
        }
    }

    private fun message(res: Int) {
        body.removeAllViews()
        body.addView(text(15f, Color.argb(140, 255, 255, 255), 400).apply {
            setText(res)
            gravity = Gravity.CENTER
        })
    }

    fun close() {
        if (!isEnabled) return
        isEnabled = false
        loader.shutdownNow()
        // Out of focus as it fades.
        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = CLOSE_MS
            addUpdateListener {
                val t = it.animatedValue as Float
                alpha = 1f - t
                val r = CLOSE_BLUR_DP * dens * t
                panel.setRenderEffect(if (r < 0.5f) null
                    else android.graphics.RenderEffect.createBlurEffect(r, r, android.graphics.Shader.TileMode.DECAL))
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) = onClose()
            })
            start()
        }
    }

    /** The grid's cells: square, each thumbnail read off the main thread and kept for a while. */
    /** A day's header ([count] 0), or [count] of its photos from [start]. */
    private class Row(val day: java.time.LocalDate, val start: Int, val count: Int)

    private inner class Rows(private val cols: Int) : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(i: Int) = rows[i]
        override fun getItemId(i: Int) = i.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(i: Int) = if (rows[i].count == 0) 0 else 1
        override fun isEnabled(i: Int) = false

        override fun getView(i: Int, convert: View?, parent: ViewGroup?): View {
            val row = rows[i]
            if (row.count == 0) return ((convert as? TextView) ?: text(17f, Color.WHITE, 700).apply {
                setPadding(dp(4), dp(14), 0, dp(8))
            }).apply { text = dayTitle(row.day) }
            val line = (convert as? LinearLayout) ?: LinearLayout(context).apply {
                setPadding(0, 0, 0, dp(GAP_DP))
                repeat(cols) { c ->
                    addView(thumb(), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (c > 0) marginStart = dp(GAP_DP)
                    })
                }
            }
            for (c in 0 until cols) {
                val v = line.getChildAt(c) as ImageView
                if (c >= row.count) {
                    v.visibility = INVISIBLE
                    v.tag = null
                    continue
                }
                v.visibility = VISIBLE
                val id = ids[row.start + c]
                v.tag = id
                v.setOnClickListener { showCrop(id, v) }
                val kept = thumbs.get(id)
                v.setImageBitmap(kept)
                if (kept == null && !loader.isShutdown) loader.execute {
                    val b = Photos.preview(app, id, THUMB_PX) ?: return@execute
                    post {
                        thumbs.put(id, b)
                        if (v.tag == id) v.setImageBitmap(b)
                    }
                }
            }
            return line
        }

        private fun thumb() = object : ImageView(context) {
            override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, w)
        }.apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.rgb(58, 58, 60))
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) =
                    o.setRoundRect(0, 0, v.width, v.height, THUMB_CORNER_DP * dens)
            }
            clipToOutline = true
        }
    }

    /**
     * The gallery's quick way through: a handle on the right, shown while the list moves; held and
     * dragged, it runs the list and says the day it is on beside it.
     */
    private inner class DayScroller(private val list: android.widget.ListView) : View(context) {
        private val handleW = 32 * dens
        private val handleH = 48 * dens
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 72, 72, 74) }
        private val pillFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 58, 58, 60) }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 2 * dens
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val words = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 15f, resources.displayMetrics)
            typeface = Typeface.create(Typeface.DEFAULT, 500, false)
        }
        private var frac = 0f
        private var dragging = false
        private var grab = 0f
        private val hide = Runnable { animate().alpha(0f).setDuration(FADE_MS).start() }

        init {
            alpha = 0f
            list.setOnScrollListener(object : android.widget.AbsListView.OnScrollListener {
                override fun onScrollStateChanged(v: android.widget.AbsListView, state: Int) {}
                override fun onScroll(v: android.widget.AbsListView, first: Int, shown: Int, total: Int) {
                    if (dragging || total <= shown || shown == 0) return
                    frac = (first / (total - shown).toFloat()).coerceIn(0f, 1f)
                    // Not for the first layout: only once the list has really moved.
                    if (first > 0 || v.getChildAt(0)?.top?.let { it < 0 } == true) show()
                    invalidate()
                }
            })
        }

        private fun show() {
            animate().cancel()
            alpha = 1f
            removeCallbacks(hide)
            if (!dragging) postDelayed(hide, HIDE_MS)
        }

        private fun top() = frac * (height - handleH)

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val y = top()
                    if (alpha < 0.5f || e.x < width - handleW - 12 * dens || e.y < y - 8 * dens || e.y > y + handleH + 8 * dens)
                        return false
                    dragging = true
                    grab = e.y - y
                    parent.requestDisallowInterceptTouchEvent(true)
                    show()
                }
                MotionEvent.ACTION_MOVE -> if (dragging) {
                    frac = ((e.y - grab) / (height - handleH)).coerceIn(0f, 1f)
                    val n = list.adapter?.count ?: 0
                    if (n > 0) list.setSelection(Math.round(frac * (n - 1)))
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    show()
                }
            }
            invalidate()
            return true
        }

        override fun onDraw(c: Canvas) {
            val r = width - 2 * dens
            val l = r - handleW
            val t = top()
            c.drawRoundRect(l, t, r, t + handleH, handleW / 2, handleW / 2, fill)
            // Up and down, one over the other.
            val cx = (l + r) / 2
            val cy = t + handleH / 2
            val arm = 5 * dens
            val gap = 4 * dens
            c.drawLine(cx - arm, cy - gap, cx, cy - gap - arm, stroke)
            c.drawLine(cx, cy - gap - arm, cx + arm, cy - gap, stroke)
            c.drawLine(cx - arm, cy + gap, cx, cy + gap + arm, stroke)
            c.drawLine(cx, cy + gap + arm, cx + arm, cy + gap, stroke)
            if (!dragging) return
            val row = rows.getOrNull(list.firstVisiblePosition) ?: return
            val label = dateText(row.day, "yMMMMd")
            val pw = words.measureText(label) + 32 * dens
            val ph = 40 * dens
            val pr = l - 10 * dens
            val pt = cy - ph / 2
            c.drawRoundRect(pr - pw, pt, pr, pt + ph, ph / 2, ph / 2, pillFill)
            c.drawText(label, pr - pw + 16 * dens, cy - (words.ascent() + words.descent()) / 2, words)
        }
    }

    /**
     * The photo under a frame of the widget's shape, moved with a finger and scaled with two; it
     * always covers the frame. [part] is what the frame holds, as fractions of the photo.
     */
    private class CropView(context: Context, private val aspect: Float) : View(context) {
        var bitmap: Bitmap? = null
            set(v) {
                field = v
                fit()
            }
        private val frame = RectF()
        private var s = 1f
        private var minS = 1f
        private var tx = 0f
        private var ty = 0f
        private val m = Matrix()
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val dim = Paint().apply { color = Color.argb(160, 0, 0, 0) }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.WHITE
            strokeWidth = 2 * resources.displayMetrics.density
        }
        private val hole = Path()
        private var lastX = 0f
        private var lastY = 0f
        private val pinch = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val to = (s * d.scaleFactor).coerceIn(minS, minS * MAX_ZOOM)
                tx = d.focusX - (d.focusX - tx) * to / s
                ty = d.focusY - (d.focusY - ty) * to / s
                s = to
                clamp()
                return true
            }
        })

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) = fit()

        /** The frame as large as fits, the photo just covering it, centred. */
        private fun fit() {
            val b = bitmap ?: return invalidate()
            if (width == 0) return
            val fw = minOf(width * 0.92f, height * 0.92f * aspect)
            val fh = fw / aspect
            frame.set((width - fw) / 2f, (height - fh) / 2f, (width + fw) / 2f, (height + fh) / 2f)
            minS = maxOf(fw / b.width, fh / b.height)
            s = minS
            tx = frame.centerX() - b.width * s / 2f
            ty = frame.centerY() - b.height * s / 2f
            hole.rewind()
            val r = fh * StandbyView.CORNER
            hole.addRoundRect(frame, r, r, Path.Direction.CW)
            invalidate()
        }

        private fun clamp() {
            val b = bitmap ?: return
            tx = tx.coerceIn(frame.right - b.width * s, frame.left)
            ty = ty.coerceIn(frame.bottom - b.height * s, frame.top)
            invalidate()
        }

        fun part(): RectF? {
            val b = bitmap ?: return null
            val w = b.width * s
            val h = b.height * s
            return RectF((frame.left - tx) / w, (frame.top - ty) / h, (frame.right - tx) / w, (frame.bottom - ty) / h)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            pinch.onTouchEvent(e)
            // The fingers' middle, so a pinch's own drift moves it too and a finger lifting does not jump it.
            var x = 0f
            var y = 0f
            val up = if (e.actionMasked == MotionEvent.ACTION_POINTER_UP) e.actionIndex else -1
            var n = 0
            for (i in 0 until e.pointerCount) if (i != up) { x += e.getX(i); y += e.getY(i); n++ }
            x /= n.coerceAtLeast(1)
            y /= n.coerceAtLeast(1)
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    tx += x - lastX
                    ty += y - lastY
                    clamp()
                }
            }
            lastX = x
            lastY = y
            return true
        }

        override fun onDraw(c: Canvas) {
            val b = bitmap ?: return
            m.setScale(s, s)
            m.postTranslate(tx, ty)
            c.drawBitmap(b, m, paint)
            c.save()
            c.clipOutPath(hole)
            c.drawRect(tx, ty, tx + b.width * s, ty + b.height * s, dim)
            c.restore()
            c.drawPath(hole, edge)
        }
    }

    private fun text(sp: Float, color: Int, weight: Int) = TextView(context).apply {
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        typeface = Typeface.create(Typeface.DEFAULT, weight, false)
    }

    private fun dp(v: Int) = (v * dens).toInt()

    private companion object {
        const val FADE_MS = 200L
        const val CLOSE_MS = 320L
        const val CLOSE_BLUR_DP = 24f
        const val GROW_MS = 360L
        const val MARGIN_DP = 14
        const val BACK_DP = 36
        const val HEADER_INSET_DP = 10
        const val THUMB_CORNER_DP = 10f
        val EASE = android.view.animation.PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
        const val THUMB_DP = 110
        const val GAP_DP = 4
        const val HIDE_MS = 1500L
        const val THUMB_PX = 256
        const val PREVIEW_PX = 1600
        const val THUMBS_KEPT = 120
        const val MAX_ZOOM = 5f
    }
}
