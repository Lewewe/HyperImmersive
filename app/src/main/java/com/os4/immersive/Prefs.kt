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

    private const val PAGES = "custom_pages"

    /** Pages the user added below the built-in ones (empty for now, to be filled later). */
    fun customPages(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        .getInt(PAGES, 0)

    fun setCustomPages(c: Context, n: Int) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        .edit().putInt(PAGES, n).apply()
}
