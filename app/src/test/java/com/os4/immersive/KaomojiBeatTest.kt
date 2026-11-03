package com.os4.immersive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KaomojiBeatTest {
    /** [hit]: every 500ms a kick over a low level, at about 30 frames a second. */
    private fun run(beat: KaomojiBeat, hit: Boolean, from: Long, to: Long): Int {
        var beats = 0
        var last = beat.at
        var t = from
        while (t < to) {
            beat.feed(if (hit && t % 500 < 33) 0.9f else 0.2f, t)
            if (beat.at != last) { beats++; last = beat.at }
            t += 33
        }
        return beats
    }

    @Test fun findsEveryKickAndTurns() {
        val beat = KaomojiBeat()
        val direction = beat.direction
        assertTrue(run(beat, true, 1_000, 11_000) in 19..21)
        beat.feed(0.9f, 11_000)
        assertTrue(direction == 1)
    }

    @Test fun aSteadyLevelIsNoBeat() {
        assertEquals(0, run(KaomojiBeat(), false, 1_000, 11_000))
    }
}
