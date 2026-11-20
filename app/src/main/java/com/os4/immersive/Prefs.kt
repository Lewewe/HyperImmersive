package com.os4.immersive

import android.content.Context

/** The prototype's settings. */
object Prefs {
    private const val FILE = "standby"
    private const val BLUR = "blur_backdrop"

    /** Blurred artwork behind StandBy while music plays, instead of black. On by default. */
    fun blurBackdrop(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        .getBoolean(BLUR, true)

    fun setBlurBackdrop(c: Context, on: Boolean) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        .edit().putBoolean(BLUR, on).apply()

    private const val LYRICS_BESIDE = "lyrics_beside"

    /** The lyric opens beside the cover, in the title's column, instead of in its place. Off by default. */
    fun lyricsBeside(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        .getBoolean(LYRICS_BESIDE, false)

    fun setLyricsBeside(c: Context, on: Boolean) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        .edit().putBoolean(LYRICS_BESIDE, on).apply()

    private const val LYRICS_STYLE = "lyrics_style"

    /** How the lyric opens beside the cover: 0 the cover shrinks into the button's place, 1 the column opens for it. */
    fun lyricsStyle(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        .getInt(LYRICS_STYLE, 0)

    fun setLyricsStyle(c: Context, style: Int) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        .edit().putInt(LYRICS_STYLE, style.coerceIn(0, 1)).apply()

    private const val IDLE_SECONDS = "idle_seconds"

    /** How long without a touch before StandBy goes idle, as HMC-E's Extensions page set it; 0 is never. */
    fun idleSeconds(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        .getInt(IDLE_SECONDS, 60)

    fun setIdleSeconds(c: Context, seconds: Int) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        .edit().putInt(IDLE_SECONDS, if (seconds <= 0) 0 else seconds.coerceIn(10, 3600)).apply()

    private const val OFF_SECONDS = "off_seconds"

    /** How long without a touch before StandBy closes and the screen goes off; 0 is never. */
    fun offSeconds(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        .getInt(OFF_SECONDS, 0)

    fun setOffSeconds(c: Context, seconds: Int) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        .edit().putInt(OFF_SECONDS, if (seconds <= 0) 0 else seconds.coerceIn(10, 86_400)).apply()

    private const val HELD_OFF = "held_off"

    /** StandBy stays away until the phone has been upright; see [StandbyService.hold]. */
    fun heldOff(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        .getBoolean(HELD_OFF, false)

    fun setHeldOff(c: Context, on: Boolean) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        .edit().putBoolean(HELD_OFF, on).apply()

    private const val ENABLED = "enabled"

    /** The watcher should run, as HMC-E's Extensions page set it. On until turned off there. */
    fun enabled(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        .getBoolean(ENABLED, true)

    fun setEnabled(c: Context, on: Boolean) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        .edit().putBoolean(ENABLED, on).apply()


    /** How many pages an install from before page ids had added (its pages were 1..2+n). */
    private fun customPages(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        .getInt("custom_pages", 0)

    private const val PAGE_IDS = "page_ids"

    /**
     * The widget pages in order, by id - what a slot's key is made of, so reordering pages
     * moves no widget. Before pages could be reordered their index was their id: the two
     * built-in ones and the user's added ones, 1 upward, which is what an old install reads.
     */
    fun pageIds(c: Context): List<Int> =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(PAGE_IDS, null)
            ?.split(',')?.mapNotNull { it.toIntOrNull() }
            ?: (1..2 + customPages(c)).toList()

    fun setPageIds(c: Context, ids: List<Int>) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        .edit().putString(PAGE_IDS, ids.joinToString(",")).apply()

    /**
     * The widgets in a slot ([StandbyView.slotKey]), first to last; more than one is a stack.
     * A single id saved as an int is how a slot was kept before stacks.
     */
    fun stack(c: Context, slot: String): List<Int> =
        parseStack(c.getSharedPreferences(FILE, Context.MODE_PRIVATE).all["widget_$slot"])

    /** A slot's stored value, either form, as its ids. */
    internal fun parseStack(v: Any?): List<Int> = when (v) {
        is Int -> if (v != 0) listOf(v) else emptyList()
        is String -> v.split(',').mapNotNull { it.toIntOrNull() }
        else -> emptyList()
    }

    /** The city a slot's widget shows. shortcut: by slot, lost when the widget moves; move it along if that is missed. */
    fun city(c: Context, slot: String, kind: Int) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        .getString("city_${slot}_$kind", null)

    fun setCity(c: Context, slot: String, kind: Int, name: String?) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        .edit().putString("city_${slot}_$kind", name).apply()

    /** A photo widget's photo and the part of it shown (fractions of the photo); null until chosen. */
    fun photo(c: Context, slot: String, kind: Int): Pair<Long, android.graphics.RectF>? = runCatching {
        val (id, crop) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString("photo_${slot}_$kind", null)!!.split("|")
        val r = crop.split(",").map(String::toFloat)
        id.toLong() to android.graphics.RectF(r[0], r[1], r[2], r[3])
    }.getOrNull()

    fun setPhoto(c: Context, slot: String, kind: Int, id: Long, crop: android.graphics.RectF) =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString("photo_${slot}_$kind", "$id|${crop.left},${crop.top},${crop.right},${crop.bottom}").apply()

    /** How solid the widgets' backgrounds are, page editing's slider: 0 clear to 1 solid. */
    fun widgetGlass(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getFloat("widget_glass", 1f)

    fun setWidgetGlass(c: Context, level: Float) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        .edit().putFloat("widget_glass", level).apply()

    /** The look page editing last set for every widget, or null if it never did. */
    fun allDark(c: Context): Boolean? = c.getSharedPreferences(FILE, Context.MODE_PRIVATE).run {
        if (contains(ALL_DARK)) getBoolean(ALL_DARK, true) else null
    }

    /** Every widget to this look: over what each was set to, and for those added later. */
    fun setAllDark(c: Context, on: Boolean) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE).run {
        edit().apply {
            all.keys.filter { it.startsWith("dark_") }.forEach(::remove)
            putBoolean(ALL_DARK, on)
        }.apply()
    }

    private const val ALL_DARK = "all_dark"

    /** A slot's widget's look, kept as its city is. */
    fun dark(c: Context, slot: String, kind: Int) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        .getBoolean("dark_${slot}_$kind", allDark(c) ?: HiWidget.defaultDark(kind))

    fun setDark(c: Context, slot: String, kind: Int, on: Boolean) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        .edit().putBoolean("dark_${slot}_$kind", on).apply()

    fun setStack(c: Context, slot: String, ids: List<Int>) =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().apply {
            if (ids.isEmpty()) remove("widget_$slot") else putString("widget_$slot", ids.joinToString(","))
        }.apply()
}
