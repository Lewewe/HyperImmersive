package com.os4.immersive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StandbyDetectorTest {
    private val g = 9.81f

    /** Feeds a steady pose every 50 ms from [from] to [to]. */
    private fun StandbyDetector.hold(from: Long, to: Long, charging: Boolean,
        x: Float, y: Float, z: Float, wobble: Float = 0f): StandbyDetector.State {
        var t = from
        var s = state
        var i = 0
        while (t <= to) {
            val w = if (i++ % 2 == 0) wobble else -wobble
            s = update(t, charging, x + w, y, z)
            t += 50
        }
        return s
    }

    @Test fun entersAfterFiveStillSecondsOnItsSideWhileCharging() {
        val d = StandbyDetector()
        // On a stand: on its side, leaning back 30 degrees.
        val x = g * 0.866f; val z = g * 0.5f
        assertEquals(StandbyDetector.State.ARMING, d.hold(0, 4_000, true, x, 0f, z))
        assertEquals(StandbyDetector.State.ACTIVE, d.hold(4_050, 5_800, true, x, 0f, z))
    }

    @Test fun leaningFarBackStillCounts() {
        // z is not a condition: a phone reclined 65 degrees on a low stand.
        val d = StandbyDetector()
        assertEquals(StandbyDetector.State.ACTIVE, d.hold(0, 5_600, true, g * 0.42f, 0f, g * 0.9f))
    }

    @Test fun neverEntersWithoutCharging() {
        val d = StandbyDetector()
        assertEquals(StandbyDetector.State.IDLE, d.hold(0, 8_000, false, g, 0f, 0f))
    }

    @Test fun flatOrUprightIsNotLandscape() {
        assertEquals(StandbyDetector.State.IDLE, StandbyDetector().hold(0, 8_000, true, 0f, 0f, g))
        assertEquals(StandbyDetector.State.IDLE, StandbyDetector().hold(0, 8_000, true, 0f, g, 0f))
    }

    @Test fun aShakingHandDoesNotCount() {
        val d = StandbyDetector()
        assertEquals(StandbyDetector.State.IDLE, d.hold(0, 8_000, true, g, 0f, 0f, wobble = 1.2f))
    }

    @Test fun movementRestartsTheDwell() {
        val d = StandbyDetector()
        d.hold(0, 3_000, true, g, 0f, 0f)
        d.hold(3_050, 3_300, true, g, 0f, 0f, wobble = 2f)
        assertEquals(StandbyDetector.State.ARMING, d.hold(3_350, 7_500, true, g, 0f, 0f))
    }

    @Test fun leavesWhenUprightOrUnplugged() {
        val d = StandbyDetector()
        d.hold(0, 6_000, true, g, 0f, 0f)
        assertEquals(StandbyDetector.State.ACTIVE, d.state)
        // A short tilt is forgiven...
        d.hold(6_050, 6_500, true, 0f, g, 0f)
        assertEquals(StandbyDetector.State.ACTIVE, d.hold(6_550, 6_700, true, g, 0f, 0f))
        // ...a turn back to portrait is not.
        assertEquals(StandbyDetector.State.IDLE, d.hold(6_750, 7_700, true, 0f, g, 0f))
        d.hold(8_000, 14_000, true, g, 0f, 0f)
        assertEquals(StandbyDetector.State.IDLE, d.hold(14_050, 14_100, false, g, 0f, 0f))
    }

    @Test
    fun portraitMeansAnEndDownNotASideOrFlat() {
        assertTrue(StandbyDetector.isPortrait(0.5f, 9.5f))
        assertTrue(StandbyDetector.isPortrait(0.5f, -9.5f))
        assertFalse(StandbyDetector.isPortrait(8.2f, 0.05f))
        assertFalse(StandbyDetector.isPortrait(0.2f, 0.3f))
    }
}
