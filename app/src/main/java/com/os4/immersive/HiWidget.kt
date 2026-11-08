package com.os4.immersive

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.format.DateFormat
import android.view.View
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * HyperImmersive's own StandBy widgets, drawn by hand: an analog clock, a digital one with its
 * seconds in a ring and the month (2x2), and a big clock and the day with the month (4x2). They take a slot the way a system widget did - a view in the slot's frame,
 * laid out at [WidgetSizes] and scaled by StandbyView - since the hosted system widgets were not
 * stable enough (2026-10-30). A slot saves their [kind], negative so it never reads as an
 * AppWidget id left from before.
 */
class HiWidget(context: Context, val kind: Int, private val live: Boolean = true) : View(context) {

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val bold = Typeface.create(Typeface.DEFAULT, 700, false)
    private val heavy = Typeface.create(Typeface.DEFAULT, 800, false)
    private val medium = Typeface.create(Typeface.DEFAULT, 500, false)
    private val accent = Color.rgb(255, 149, 0)
    private val red = Color.rgb(255, 69, 58)
    private val box = RectF()
    /** The city a clock shows the time of; null, the phone's own. */
    var city: WidgetCities.City? = null
        set(v) {
            field = v
            invalidate()
        }

    /** On a dark card, in light ink; or the other way round. */
    var dark = defaultDark(kind)
        set(v) {
            field = v
            card()
            invalidate()
        }

    /** How solid its background is, 0 clear to 1 solid. */
    var glass = 1f
        set(v) {
            field = v
            card()
        }

    private fun card() {
        val full = if (dark) DARK_CARD else Color.WHITE
        setBackgroundColor(Color.argb((255 * glass).toInt(), Color.red(full), Color.green(full), Color.blue(full)))
    }

    /** To [on], fading through nothing, as page editing changes every widget at once. */
    fun fadeDark(on: Boolean) {
        animate().alpha(0f).setDuration(120).withEndAction {
            dark = on
            animate().alpha(1f).setDuration(180).start()
        }.start()
    }

    private fun ink(alpha: Int = 255) = if (dark) Color.argb(alpha, 255, 255, 255) else Color.argb(alpha, 0, 0, 0)

    private fun time() = LocalTime.now(city?.zone ?: java.time.ZoneId.systemDefault())
    private val titlePaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, 500, false)
    }

    /** Once a second, on the second, while attached; only the clock's hand needs more than that. */
    private val tick = object : Runnable {
        override fun run() {
            // Idle, a slot's widget waits for StandBy's beat: a tick of its own would be a frame more.
            if (!idle || parent !is StackFrame) beat()
            postDelayed(this, 1000 - System.currentTimeMillis() % 1000)
        }
    }

    /** Draws again, if there is anything new to show. */
    fun beat() {
        // A sleeping kaomoji only needs drawing again for a new song or a pause.
        val still = idle && (kind == KAOMOJI || kind == KAOMOJI_WIDE) && kaomojiFor == kaomojiState()
        if (!still) invalidate()
    }

    init {
        card()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Not live (the picker's icons), it is drawn once and costs nothing after.
        if (live) tick.run()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        when (kind) {
            ANALOG -> drawAnalog(c, w / 2f, h / 2f, min(w, h) * 0.42f)
            CALENDAR -> drawCalendar(c, box.apply { set(0f, 0f, w, h) }, min(w, h))
            CALENDAR_WIDE -> drawCalendarWide(c, w, h)
            BIG_CLOCK -> drawBigClock(c, w, h)
            SECONDS -> drawSeconds(c, min(w, h))
            KAOMOJI, KAOMOJI_WIDE -> drawKaomoji(c, w, h)
            PHOTO, PHOTO_WIDE -> drawPhoto(c, w, h)
        }
    }

    /** The photo widget's photo and the part of it shown, as [Prefs.photo] has it. */
    var photoChoice: Pair<Long, RectF>? = null
        set(v) {
            field = v
            photo = null
            photoFor = null
            invalidate()
        }
    private var photo: android.graphics.Bitmap? = null
    private var photoFor: Pair<Long, RectF>? = null
    private var photoMissing = false
    private val photoDst = RectF()
    private val photoPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** Decoded once for its size, off the main thread. */
    private fun loadPhoto(choice: Pair<Long, RectF>) {
        if (photoFor === choice || width == 0) return
        photoFor = choice
        val app = context.applicationContext
        val w = width
        val h = height
        Thread {
            val read = Photos.cropped(app, choice.first, choice.second, w, h)
            post {
                if (photoFor !== choice) return@post
                photo = read
                photoMissing = read == null
                invalidate()
            }
        }.start()
    }

    /** The chosen part filling the card; with none chosen (the picker), a sun over a hill. */
    private fun drawPhoto(c: Canvas, w: Float, h: Float) {
        val choice = photoChoice
        if (!live || choice == null) {
            c.drawColor(Color.rgb(90, 135, 255))
            fill.color = Color.rgb(255, 214, 10)
            c.drawCircle(w * 0.7f, h * 0.32f, min(w, h) * 0.13f, fill)
            fill.color = Color.rgb(52, 199, 89)
            c.drawOval(-w * 0.2f, h * 0.6f, w * 1.2f, h * 1.6f, fill)
            return
        }
        if (!Photos.allowed(context) || photoMissing) {
            text.textAlign = Paint.Align.CENTER
            text.typeface = Typeface.DEFAULT
            text.color = ink(140)
            text.textSize = h * 0.07f
            c.drawText(context.getString(if (photoMissing) R.string.photo_missing else R.string.photo_access_off),
                w / 2f, h / 2f, text)
            return
        }
        loadPhoto(choice)
        val p = photo ?: return
        photoDst.set(0f, 0f, w, h)
        c.drawBitmap(p, null, photoDst, photoPaint)
    }

    private var calm = 1f
    private var calmAt = 0L
    private var mirror = 1f
    private var kaomojiFor = ""
    private fun kaomojiState() = "${MediaArt.key}|${MediaArt.running}|$idle"

    /**
     * HMC-E's kaomoji companion (LyricView.drawBpmCompanion): the track's face grows with each
     * beat and turns on it, and dozes off when the beat stops. Idle at StandBy's slowest refresh
     * it sleeps; the wide one has the title under it.
     */
    private fun drawKaomoji(c: Canvas, w: Float, h: Float) {
        kaomojiFor = kaomojiState()
        val now = android.os.SystemClock.uptimeMillis()
        val beatAt = Spectrum.beat.at
        val since = if (beatAt == 0L) Long.MAX_VALUE else now - beatAt
        val calmWant = if (!idle && MediaArt.running && since <= CALM_AFTER_MS) 0f else 1f
        calm = if (idle) calmWant else calm + (calmWant - calm) * min(1f, (now - calmAt) / 350f)
        calmAt = now
        if (!idle) mirror = Spectrum.beat.direction.toFloat()
        val scale = if (idle || calmWant == 1f) 1f else 1f + 0.09f * kotlin.math.exp(-since / 190f)
        val face = FACES[Math.floorMod(MediaArt.key.hashCode(), FACES.size)]
        val wide = kind == KAOMOJI_WIDE
        val cy = if (wide) h * 0.42f else h / 2f
        text.typeface = Typeface.DEFAULT
        text.textAlign = Paint.Align.CENTER
        text.textSize = h * (if (wide) 0.34f else 0.26f)
        // Room for the biggest beat, and for the wider of the two faces.
        text.textSize *= min(1f, w * 0.86f / (1.09f * maxOf(text.measureText(face), text.measureText(CALM_FACE))))
        val baseline = cy - (text.descent() + text.ascent()) / 2f
        c.save()
        c.scale(mirror * scale, scale, w / 2f, cy)
        if (calm < 0.999f) {
            text.color = ink((209 * (1f - calm)).toInt())
            c.drawText(face, w / 2f, baseline, text)
        }
        if (calm > 0.001f) {
            text.color = ink((209 * calm).toInt())
            c.drawText(CALM_FACE, w / 2f, baseline, text)
        }
        c.restore()
        if (wide && MediaArt.title.isNotEmpty()) {
            titlePaint.textSize = h * 0.11f
            titlePaint.color = ink(184)
            c.drawText(android.text.TextUtils.ellipsize(MediaArt.title, titlePaint, w * 0.86f,
                android.text.TextUtils.TruncateAt.END).toString(), w / 2f, h * 0.84f, titlePaint)
        }
        if (live && !idle) postInvalidateOnAnimation()
    }

    private fun drawAnalog(c: Canvas, cx: Float, cy: Float, radius: Float) {
        val now = time()
        // A sweep at the full rate; idle, the idle beat steps it a second at a time.
        val sec = now.second + if (idle) 0f else now.nano / 1e9f
        val min = now.minute + sec / 60f
        val hr = (now.hour % 12) + min / 60f

        for (i in 0 until 60) {
            val a = Math.toRadians(i * 6.0)
            val hour = i % 5 == 0
            val inner = radius - (if (hour) 0.09f else 0.045f) * radius
            stroke.color = if (hour) ink() else ink(110)
            stroke.strokeWidth = (if (hour) 0.022f else 0.01f) * radius
            c.drawLine(cx + (sin(a) * inner).toFloat(), cy - (cos(a) * inner).toFloat(),
                cx + (sin(a) * radius).toFloat(), cy - (cos(a) * radius).toFloat(), stroke)
        }
        text.textAlign = Paint.Align.CENTER
        text.color = ink()
        text.typeface = medium
        text.textSize = radius * 0.2f
        for (n in 1..12) {
            val a = Math.toRadians(n * 30.0)
            val rr = radius * 0.74f
            val x = cx + (sin(a) * rr).toFloat()
            val y = cy - (cos(a) * rr).toFloat() - (text.descent() + text.ascent()) / 2f
            c.drawText(n.toString(), x, y, text)
        }
        city?.let {
            text.textSize = radius * 0.11f
            text.color = ink(170)
            c.drawText(it.short(context), cx, cy + radius * 0.42f, text)
        }
        hand(c, cx, cy, hr * 30f, radius * 0.5f, radius * 0.06f, ink())
        hand(c, cx, cy, min * 6f, radius * 0.8f, radius * 0.045f, ink())
        hand(c, cx, cy, sec * 6f, radius * 0.88f, radius * 0.016f, accent, tail = radius * 0.15f)
        fill.color = accent
        c.drawCircle(cx, cy, radius * 0.035f, fill)
        fill.color = if (dark) Color.BLACK else Color.WHITE
        c.drawCircle(cx, cy, radius * 0.015f, fill)
        if (live && !idle) postInvalidateOnAnimation()
    }

    private fun hand(c: Canvas, cx: Float, cy: Float, deg: Float, len: Float, width: Float,
                     color: Int, tail: Float = 0f) {
        val a = Math.toRadians(deg.toDouble())
        stroke.color = color
        stroke.strokeWidth = width
        c.drawLine(cx - (sin(a) * tail).toFloat(), cy + (cos(a) * tail).toFloat(),
            cx + (sin(a) * len).toFloat(), cy - (cos(a) * len).toFloat(), stroke)
    }

    /** The month from Monday, six weeks with the days around it dimmed, the weekend blue. */
    private fun drawCalendar(c: Canvas, box: RectF, side: Float) {
        val today = LocalDate.now()
        val locale = Locale.getDefault()
        val pad = side * 0.1f
        text.textAlign = Paint.Align.LEFT
        text.typeface = bold
        text.color = ink()
        text.textSize = side * 0.115f
        c.drawText("${today.year}/${today.monthValue}", box.left + side * 0.12f, box.top + side * 0.208f, text)

        val colW = (side - 2 * pad) / 7f
        val rowH = side * 0.094f
        val gridTop = box.top + side * 0.34f
        val blue = CALENDAR_BLUE
        text.textAlign = Paint.Align.CENTER
        text.typeface = medium
        text.textSize = side * 0.028f
        text.color = ink(225)
        for (i in 0 until 7) {
            c.drawText(java.time.DayOfWeek.of(i + 1).getDisplayName(TextStyle.SHORT, locale).uppercase(locale),
                box.left + pad + colW * (i + 0.5f), box.top + side * 0.318f, text)
        }
        val start = today.withDayOfMonth(1).let { it.minusDays(it.dayOfWeek.value - 1L) }
        text.textSize = side * 0.048f
        for (i in 0 until 42) {
            val date = start.plusDays(i.toLong())
            val x = box.left + pad + colW * (i % 7 + 0.5f)
            val y = gridTop + rowH * (i / 7 + 0.5f)
            val isToday = date == today
            if (isToday) {
                fill.color = blue
                val r = rowH * 0.2f
                c.drawRoundRect(x - colW * 0.47f, y - rowH * 0.47f, x + colW * 0.47f, y + rowH * 0.47f, r, r, fill)
            }
            val tint = if (isToday) Color.WHITE else if (i % 7 >= 5) blue else ink()
            text.color = Color.argb(if (date.month == today.month) 255 else 70,
                Color.red(tint), Color.green(tint), Color.blue(tint))
            c.drawText(date.dayOfMonth.toString(), x, y - (text.descent() + text.ascent()) / 2f, text)
        }
    }

    /** The seconds ring's ticks, each as x, y to x, y, for the side they were worked out at. */
    private val secondTicks = FloatArray(60 * 4)
    private var ticksFor = 0f
    private val digitBox = android.graphics.Rect()

    /** 60 ticks evenly along a rounded square, from the top going clockwise, pointing at the centre. */
    private fun layTicks(side: Float) {
        ticksFor = side
        val m = side * 0.04f
        val r = side * 0.16f
        val far = side - m
        val path = android.graphics.Path().apply {
            moveTo(side / 2f, m)
            arcTo(far - 2 * r, m, far, m + 2 * r, -90f, 90f, false)
            arcTo(far - 2 * r, far - 2 * r, far, far, 0f, 90f, false)
            arcTo(m, far - 2 * r, m + 2 * r, far, 90f, 90f, false)
            arcTo(m, m, m + 2 * r, m + 2 * r, 180f, 90f, false)
            close()
        }
        val measure = android.graphics.PathMeasure(path, false)
        val pos = FloatArray(2)
        val len = side * 0.034f
        for (i in 0 until 60) {
            measure.getPosTan(measure.length * i / 60f, pos, null)
            val dx = side / 2f - pos[0]
            val dy = side / 2f - pos[1]
            val d = kotlin.math.hypot(dx, dy)
            secondTicks[i * 4] = pos[0]
            secondTicks[i * 4 + 1] = pos[1]
            secondTicks[i * 4 + 2] = pos[0] + dx / d * len
            secondTicks[i * 4 + 3] = pos[1] + dy / d * len
        }
    }

    /**
     * The time large and narrow, black on white, its seconds a ring of ticks round it: the one the
     * second is on darkest, the ones behind it fading away all the way round to the next.
     */
    private fun drawSeconds(c: Canvas, side: Float) {
        val now = time()
        val h24 = DateFormat.is24HourFormat(context)
        val hour = (if (h24) now.hour else (now.hour + 11) % 12 + 1).toString()
        val minute = "%02d".format(now.minute)

        if (ticksFor != side) layTicks(side)
        val sec = now.second + if (idle) 0f else now.nano / 1e9f
        stroke.strokeWidth = side * 0.011f
        for (i in 0 until 60) {
            val age = ((sec - i) % 60f + 60f) % 60f
            // The last second before it comes round, the tick darkens from faint: the head has no jump.
            val k = if (age > 59f) age - 59f else 1f - age / 59f
            stroke.color = ink((SECOND_FAINT + (SECOND_DARK - SECOND_FAINT) * k).toInt())
            c.drawLine(secondTicks[i * 4], secondTicks[i * 4 + 1], secondTicks[i * 4 + 2], secondTicks[i * 4 + 3], stroke)
        }

        val digits = "$hour:$minute"
        text.textAlign = Paint.Align.CENTER
        text.typeface = bold
        text.color = ink()
        text.textSize = side * 0.3f
        // Sized to fit inside the ring.
        text.textSize *= min(1f, side * 0.68f / text.measureText(digits))
        text.getTextBounds("0", 0, 1, digitBox)
        c.drawText(digits, side / 2f, side / 2f - digitBox.exactCenterY(), text)
        city?.let {
            val under = side / 2f + digitBox.height() / 2f
            text.typeface = medium
            text.textSize = side * 0.055f
            text.color = ink(150)
            c.drawText(it.short(context), side / 2f, under + side * 0.09f, text)
        }
        if (live && !idle) postInvalidateOnAnimation()
    }

    /** The day on the left, large under its name; on the right the month as the 2x2 one has it. */
    private fun drawCalendarWide(c: Canvas, w: Float, h: Float) {
        val today = LocalDate.now()
        val locale = Locale.getDefault()
        text.textAlign = Paint.Align.LEFT
        text.typeface = medium
        text.color = CALENDAR_BLUE
        text.textSize = h * 0.062f
        c.drawText(today.dayOfWeek.getDisplayName(TextStyle.FULL, locale).uppercase(locale), h * 0.12f, h * 0.17f, text)
        text.typeface = Typeface.DEFAULT
        text.color = ink()
        text.textSize = h * 0.34f
        c.drawText(today.dayOfMonth.toString(), h * 0.11f, h * 0.47f, text)
        drawEvents(c, h * 0.12f, h * 0.62f, w - h - h * 0.12f, h)
        drawCalendar(c, box.apply { set(w - h, 0f, w, h) }, h)
    }

    private var events: List<CalendarEvents.Event>? = null
    private var eventsAt = 0L
    private var eventsLoading = false

    /** Read off the main thread, at most once a minute: draws come far more often. */
    private fun loadEvents() {
        val now = android.os.SystemClock.uptimeMillis()
        if (eventsLoading || (eventsAt != 0L && now - eventsAt < EVENTS_EVERY_MS)) return
        eventsLoading = true
        eventsAt = now
        val app = context.applicationContext
        Thread {
            val read = CalendarEvents.today(app)
            post {
                eventsLoading = false
                events = read
                invalidate()
            }
        }.start()
    }

    /** What is left of today, at [x], from [top], [width] wide: at most two, each with a bar in the accent. */
    private fun drawEvents(c: Canvas, x: Float, top: Float, width: Float, h: Float) {
        if (!live) return
        text.textAlign = Paint.Align.LEFT
        text.typeface = Typeface.DEFAULT
        val allowed = CalendarEvents.allowed(context)
        if (allowed) loadEvents()
        val list = events
        if (!allowed || list.isNullOrEmpty()) {
            if (allowed && list == null) return
            text.color = ink(140)
            text.textSize = h * 0.058f
            c.drawText(context.getString(if (allowed) R.string.calendar_no_events else R.string.calendar_no_access), x, top + h * 0.17f, text)
            return
        }
        val rowH = h * 0.145f
        val h24 = DateFormat.is24HourFormat(context)
        // shortcut: only the first two of today's events show, a count of the rest if people miss them.
        val clock = java.time.format.DateTimeFormatter.ofPattern(if (h24) "HH:mm" else "h:mm a")
        for ((i, e) in list.take(2).withIndex()) {
            val y = top + rowH * i
            fill.color = CALENDAR_BLUE
            val bar = h * 0.012f
            c.drawRoundRect(x, y + h * 0.012f, x + bar, y + rowH - h * 0.024f, bar / 2f, bar / 2f, fill)
            val tx = x + h * 0.035f
            text.typeface = medium
            text.color = ink()
            text.textSize = h * 0.052f
            c.drawText(android.text.TextUtils.ellipsize(e.title, android.text.TextPaint(text), width - h * 0.035f,
                android.text.TextUtils.TruncateAt.END).toString(), tx, y + h * 0.055f, text)
            text.typeface = Typeface.DEFAULT
            text.color = ink(140)
            text.textSize = h * 0.042f
            c.drawText(e.start?.format(clock) ?: context.getString(R.string.calendar_all_day), tx, y + h * 0.105f, text)
        }
    }

    private fun drawBigClock(c: Canvas, w: Float, h: Float) {
        val now = time()
        val h24 = DateFormat.is24HourFormat(context)
        val hour = if (h24) now.hour else (now.hour + 11) % 12 + 1
        val digits = (if (h24) "%02d" else "%d").format(hour) + ":" + "%02d".format(now.minute)
        val today = LocalDate.now(city?.zone ?: java.time.ZoneId.systemDefault())
        text.textAlign = Paint.Align.CENTER
        text.typeface = bold
        text.textSize = h * 0.12f
        text.color = red
        c.drawText(city?.short(context) ?: (today.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()) +
            " " + today.dayOfMonth), w / 2f, h * 0.24f, text)
        text.typeface = heavy
        text.color = if (dark) Color.rgb(255, 214, 160) else Color.BLACK
        text.textSize = h * 0.56f
        // Narrowed to fit, should a 12-hour "12:00" or a wide font overrun the slot.
        text.textSize *= min(1f, w * 0.9f / text.measureText(digits))
        val baseline = h * 0.6f - (text.descent() + text.ascent()) / 2f
        c.drawText(digits, w / 2f, baseline, text)
    }

    /** A widget the picker offers: its app's group, name, line (string resources) and whether it is 4x2. */
    class Kind(val id: Int, val group: Int, val name: Int, val about: Int, val wide: Boolean)

    companion object {
        const val ANALOG = -1
        const val CALENDAR = -2
        const val BIG_CLOCK = -3
        const val KAOMOJI = -4
        const val KAOMOJI_WIDE = -5
        const val SECONDS = -6
        const val CALENDAR_WIDE = -7
        const val PHOTO = -8
        const val PHOTO_WIDE = -9
        private const val EVENTS_EVERY_MS = 60_000L
        private val CALENDAR_BLUE = Color.rgb(90, 135, 255)
        private val DARK_CARD = Color.rgb(28, 28, 30)
        /** The seconds ring's darkest and faintest tick, as alpha of black. */
        private const val SECOND_DARK = 200f
        private const val SECOND_FAINT = 18f
        private const val CALM_FACE = "(˘ω˘)"
        private const val CALM_AFTER_MS = 4_000L
        // HMC-E's BpmEstimator.FACES.
        private val FACES = arrayOf("(•ω•)ﾉ♪", "(っ•ω•)っ", "ヾ(◕ω◕)", "(๑•ω•)ﾉ",
            "(◕ω◕✿)", "♡(•ω•)", "(ﾉ˘ω˘)ﾉ", "(•ω•)ﾉ✧")

        /** StandBy is idle at its slowest refresh, set by [StandbyView]. */
        @Volatile var idle = false

        val ALL = listOf(
            Kind(ANALOG, R.string.group_clock, R.string.kind_clock, R.string.kind_clock_about, false),
            Kind(SECONDS, R.string.group_clock, R.string.kind_seconds, R.string.kind_seconds_about, false),
            Kind(BIG_CLOCK, R.string.group_clock, R.string.kind_digital, R.string.kind_digital_about, true),
            Kind(CALENDAR, R.string.group_calendar, R.string.kind_month, R.string.kind_month_about, false),
            Kind(CALENDAR_WIDE, R.string.group_calendar, R.string.kind_day_month, R.string.kind_day_month_about, true),
            Kind(KAOMOJI, R.string.group_kaomoji, R.string.kind_friend, R.string.kind_friend_about, false),
            Kind(KAOMOJI_WIDE, R.string.group_kaomoji, R.string.kind_friend_song, R.string.kind_friend_song_about, true),
            Kind(PHOTO, R.string.group_photos, R.string.kind_photo, R.string.kind_photo_about, false),
            Kind(PHOTO_WIDE, R.string.group_photos, R.string.kind_photo, R.string.kind_photo_about, true))

        /** No light or dark to set: its corner button chooses another photo instead. */
        fun isPhoto(kind: Int) = kind == PHOTO || kind == PHOTO_WIDE

        fun isKnown(kind: Int) = ALL.any { it.id == kind }

        /** The clocks, which can be set to a city. */
        fun hasCity(kind: Int) = kind == ANALOG || kind == SECONDS || kind == BIG_CLOCK

        /** The look a widget starts with, until it is set. */
        fun defaultDark(kind: Int) = kind != SECONDS
    }
}
