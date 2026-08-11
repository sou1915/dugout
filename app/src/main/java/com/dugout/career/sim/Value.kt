package com.dugout.career.sim

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot

/**
 * WHAT THE BALL IS WORTH AT A PLACE — and the circle this must not close.
 *
 * §4 of the brief is explicit about the trap here. The cheap way to get a value
 * surface is to derive it from the engine's own play: count how often
 * possession at each cell ends in a goal and feed it back. That is circular,
 * and it fails in one direction: an engine that only ever plays one pass will
 * watch that pass succeed, learn it is the most valuable thing available, and
 * hand the decision layer a number that DEFENDS the degeneracy. A measurement
 * that agrees with the defect is worse than no measurement.
 *
 * So this grid is the EXTERNAL ANCHOR and nothing else. It is written from
 * real-football priors — shot conversion by location, possession-to-goal rates
 * by zone — and no line of it is fitted to anything this engine has produced.
 *
 * Two rules, from §4, and they are rules rather than preferences:
 *
 *   1. Engine play may refine the interior. It may NOT move the anchor.
 *   2. A grid is never re-derived from a policy that failed the §5 acceptance
 *      table. Pass the table, THEN re-derive. Never "re-derive to help it pass".
 *
 * Rule 2 is why there is no derivation code here at all yet: the acceptance
 * table does not pass, so the engine has not earned the right to teach itself
 * what is valuable.
 *
 * PROVISIONAL, exactly like the §5 bands: these constants are a reasoned prior,
 * not a fit. They must be checked against a real dataset before anything is
 * locked, and corrected here rather than worked around downstream.
 */
object Value {

    /**
     * Chance a shot from here is scored, given a clear enough sight of goal.
     *
     * Anchored on published shot-conversion figures: about 0.76 from the
     * penalty spot, roughly 0.35-0.45 from the six-yard area centrally, about
     * 0.07 from the penalty spot's distance out at the D, and 0.02-0.04 from
     * 25 yards. The exponential decay with distance and the angle term are the
     * standard shape; the coefficients reproduce those anchors.
     */
    fun shotValue(attX: Float, y: Float): Float {
        val gx = Pitch.LENGTH
        val gy = Pitch.WIDTH * 0.5f
        val d = hypot((gx - attX).toDouble(), (y - gy).toDouble()).toFloat()
        if (d < 0.5f) return 0.95f

        // How much of the goal he can actually see, in radians.
        val half = Pitch.GOAL_WIDTH * 0.5f
        val a1 = atan2((y - (gy - half)).toDouble(), (gx - attX).toDouble())
        val a2 = atan2((y - (gy + half)).toDouble(), (gx - attX).toDouble())
        val open = abs(a1 - a2).toFloat()

        val v = exp((-0.105f * d).toDouble()).toFloat() * (0.35f + 2.1f * open)
        return v.coerceIn(0.001f, 0.95f)
    }

    /**
     * Chance this possession ends in a goal with the ball here.
     *
     * Anchored on possession-to-goal rates by zone: order 0.005 in your own
     * third, 0.02 around halfway, 0.05-0.08 in the final third, and 0.15 or so
     * inside the box. It is deliberately much flatter than [shotValue] — having
     * the ball in a good area is worth a fraction of a shot from it, and a
     * value model that forgets this is what makes an engine shoot from
     * everywhere.
     */
    fun possessionValue(attX: Float, y: Float): Float {
        val prog = (attX / Pitch.LENGTH).coerceIn(0f, 1f)
        val central = 1f - (abs(y - Pitch.WIDTH * 0.5f) / (Pitch.WIDTH * 0.5f)) * 0.55f
        val base = 0.004f + 0.075f * prog * prog * prog
        return (base * central + shotValue(attX, y) * 0.16f).coerceIn(0.001f, 0.6f)
    }

    /**
     * What it costs US if we lose it here — the value to THEM of the turnover,
     * which is the same surface read from the other end.
     *
     * This is how rest defence comes to matter through the model rather than
     * through a special case: losing it in your own half with men committed
     * forward is expensive because the opponent's possession value there is
     * high, and losing it in their corner is nearly free.
     */
    fun turnoverCost(attX: Float, y: Float): Float =
        possessionValue(Pitch.LENGTH - attX, y)
}
