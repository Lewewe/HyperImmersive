package com.os4.immersive

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsAndPrefsTest {
    private fun line(start: Int) = HmcLyrics.Line("l$start", start, start + 900, false, null, null, null, null, null)

    @Test
    fun indexAtPicksTheLineBeingSung() {
        HmcLyrics.set(emptyList())
        assertEquals(-1, HmcLyrics.indexAt(5_000))
        HmcLyrics.set(listOf(line(1_000), line(2_000), line(3_000)))
        assertEquals(-1, HmcLyrics.indexAt(999))
        assertEquals(0, HmcLyrics.indexAt(1_000))
        assertEquals(1, HmcLyrics.indexAt(2_500))
        assertEquals(2, HmcLyrics.indexAt(3_000))
        assertEquals(2, HmcLyrics.indexAt(60_000))
        HmcLyrics.set(emptyList())
    }

    @Test
    fun lyricsMatchOnlyTheirOwnTrack() {
        HmcLyrics.set(listOf(line(0)), HmcLyrics.look, "com.spotify.music", "Song")
        assertEquals(true, HmcLyrics.matches("com.spotify.music", " song "))
        assertEquals(false, HmcLyrics.matches("com.spotify.music", "Other"))
        assertEquals(false, HmcLyrics.matches("com.google.android.youtube", "Song"))
        HmcLyrics.set(emptyList(), HmcLyrics.look, "", "")
        assertEquals(false, HmcLyrics.matches("", ""))
    }

    @Test
    fun stackReadsBothStoredForms() {
        assertEquals(listOf(-1), Prefs.parseStack(-1))
        assertEquals(emptyList<Int>(), Prefs.parseStack(0))
        assertEquals(listOf(-1, -3, -2), Prefs.parseStack("-1,-3,-2"))
        assertEquals(listOf(-2), Prefs.parseStack("-2,x"))
        assertEquals(emptyList<Int>(), Prefs.parseStack(""))
        assertEquals(emptyList<Int>(), Prefs.parseStack(null))
    }
}
