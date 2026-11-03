package com.os4.immersive

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * When to enter and leave StandBy, the way iPhone decides it: charging, on its side, held still.
 *
 * Pure (no Android types), so the thresholds are testable off the phone. Fed accelerometer
 * samples in m/s² with the time they were taken; answers with the state they lead to.
 *
 * - Landscape: judged in the screen's own plane, like iPhone - which long edge is down, not how
 *   far the phone leans back. Any recline counts; only a phone lying (nearly) flat has no
 *   edge down at all, which [MIN_PLANE] rules out.
 * - Still: across the last [STILL_WINDOW_MS] every axis stayed within [STILL_SPREAD]. A phone in
 *   a hand drifts more than that; one on a stand or a desk does not.
 * - Entering needs all three, unbroken, for [dwellMs]. Leaving needs the phone off charge, or
 *   out of landscape for [LEAVE_MS] - a nudge while it stands does not end it.
 */
class StandbyDetector(private val dwellMs: Long = DWELL_MS) {

    enum class State { IDLE, ARMING, ACTIVE }

    var state = State.IDLE
        private set

    /** 0..1 through the dwell while [State.ARMING], 1 while active. */
    var progress = 0f
        private set

    /** Last reading, for the debug screen. */
    var landscape = false
        private set
    var still = false
        private set

    private var armedAt = 0L
    private var notLandscapeSince = 0L

    private val times = LongArray(WINDOW_CAP)
    private val xs = FloatArray(WINDOW_CAP)
    private val ys = FloatArray(WINDOW_CAP)
    private val zs = FloatArray(WINDOW_CAP)
    private var head = 0
    private var count = 0

    fun reset() {
        state = State.IDLE
        progress = 0f
        count = 0
        head = 0
        armedAt = 0L
        notLandscapeSince = 0L
    }

    fun update(nowMs: Long, charging: Boolean, x: Float, y: Float, z: Float): State {
        push(nowMs, x, y, z)
        val g = sqrt(x * x + y * y + z * z)
        landscape = g > 1f && isLandscape(x, y, state == State.ACTIVE)
        still = isStill(nowMs)

        when (state) {
            State.IDLE, State.ARMING -> {
                if (!charging || !landscape || !still) {
                    state = State.IDLE
                    progress = 0f
                } else if (state == State.IDLE) {
                    state = State.ARMING
                    armedAt = nowMs
                    progress = 0f
                } else {
                    progress = ((nowMs - armedAt).toFloat() / dwellMs).coerceIn(0f, 1f)
                    if (nowMs - armedAt >= dwellMs) {
                        state = State.ACTIVE
                        progress = 1f
                        notLandscapeSince = 0L
                    }
                }
            }
            State.ACTIVE -> {
                if (!charging) {
                    state = State.IDLE
                    progress = 0f
                } else if (landscape) {
                    notLandscapeSince = 0L
                } else {
                    if (notLandscapeSince == 0L) notLandscapeSince = nowMs
                    if (nowMs - notLandscapeSince >= LEAVE_MS) {
                        state = State.IDLE
                        progress = 0f
                    }
                }
            }
        }
        return state
    }

    /** Charging stopped between samples: leave at once. */
    fun unplugged() {
        state = State.IDLE
        progress = 0f
    }

    private fun push(t: Long, x: Float, y: Float, z: Float) {
        times[head] = t; xs[head] = x; ys[head] = y; zs[head] = z
        head = (head + 1) % WINDOW_CAP
        if (count < WINDOW_CAP) count++
    }

    private fun isStill(now: Long): Boolean {
        var n = 0
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        var oldest = now
        for (i in 0 until count) {
            val k = (head - 1 - i + WINDOW_CAP) % WINDOW_CAP
            if (now - times[k] > STILL_WINDOW_MS) break
            oldest = times[k]
            n++
            minX = minOf(minX, xs[k]); maxX = maxOf(maxX, xs[k])
            minY = minOf(minY, ys[k]); maxY = maxOf(maxY, ys[k])
            minZ = minOf(minZ, zs[k]); maxZ = maxOf(maxZ, zs[k])
        }
        // Too few samples, or not yet a window's worth of them, says nothing about stillness.
        if (n < 3 || now - oldest < STILL_WINDOW_MS / 2) return false
        return maxX - minX <= STILL_SPREAD && maxY - minY <= STILL_SPREAD &&
            maxZ - minZ <= STILL_SPREAD
    }

    companion object {
        const val DWELL_MS = 5_000L
        const val LEAVE_MS = 800L
        const val STILL_WINDOW_MS = 1_000L
        /** m/s² of spread per axis that still counts as standing still. */
        const val STILL_SPREAD = 0.8f
        private const val WINDOW_CAP = 64

        /** m/s² of gravity left in the screen's plane: below this the phone is lying flat. */
        const val MIN_PLANE = 1.5f

        /**
         * Gravity's x and y. Entering wants the x share of the in-plane gravity at 0.85 or more
         * (within ~32° of on its side); staying, with [active], 0.6 - so the edge does not
         * flicker. z - the lean - is not looked at.
         */
        fun isPortrait(x: Float, y: Float): Boolean {
            val plane = kotlin.math.sqrt(x * x + y * y)
            return plane >= MIN_PLANE && abs(y) / plane >= 0.85f
        }

        fun isLandscape(x: Float, y: Float, active: Boolean): Boolean {
            val plane = kotlin.math.sqrt(x * x + y * y)
            if (plane < MIN_PLANE) return false
            return abs(x) / plane >= if (active) 0.6f else 0.85f
        }
    }
}
