package com.os4.immersive

import android.content.ContentUris
import android.content.Context
import android.provider.CalendarContract.Instances
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** Today's events from the phone's calendars, for the 4x2 calendar widget. */
object CalendarEvents {
    /** [start] is null for an all-day event. */
    class Event(val title: String, val start: java.time.LocalTime?)

    fun allowed(c: Context) =
        c.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** What is left of today, all-day events first; null if it cannot be read. */
    fun today(c: Context): List<Event>? = runCatching {
        val zone = ZoneId.systemDefault()
        val day = LocalDate.now(zone)
        val nowMs = System.currentTimeMillis()
        val from = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        // A day either side: an all-day event's bounds are midnights in UTC, not here.
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from - DAY_MS)
            ContentUris.appendId(it, to + DAY_MS)
        }.build()
        val out = ArrayList<Event>()
        c.contentResolver.query(uri, arrayOf(Instances.TITLE, Instances.BEGIN, Instances.END, Instances.ALL_DAY),
            "${Instances.VISIBLE} = 1", null, "${Instances.ALL_DAY} DESC, ${Instances.BEGIN} ASC")?.use { q ->
            while (q.moveToNext()) {
                val begin = q.getLong(1)
                val end = q.getLong(2)
                val title = q.getString(0).orEmpty().ifBlank { "(No title)" }
                if (q.getInt(3) == 1) {
                    val first = Instant.ofEpochMilli(begin).atOffset(ZoneOffset.UTC).toLocalDate()
                    val last = Instant.ofEpochMilli(end).atOffset(ZoneOffset.UTC).toLocalDate()
                    if (day >= first && day < last) out += Event(title, null)
                } else if (begin < to && end > nowMs) {
                    // Begun before today, it is shown from the start of the day.
                    out += Event(title, Instant.ofEpochMilli(maxOf(begin, from)).atZone(zone).toLocalTime())
                }
            }
        }
        out
    }.getOrNull()

    private const val DAY_MS = 86_400_000L
}
