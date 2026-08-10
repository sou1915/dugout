package com.dugout.career.sim

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln

/** What a man can try. Each is a concrete act, never a category. */
enum class OptKind {
    PASS_FEET, PASS_SPACE, THROUGH_BALL, SWITCH, CROSS, CUT_BACK, CARRY, SHOT, CLEAR
}

/**
 * One thing he could do, with a target, a delivery and a time.
 *
 * Not "pass" — "driven pass, to Smith, into the space at (72,14), arriving in
 * 1.1 s". That single change is what makes the event vocabulary of §4
 * reachable: a cut-back and a byline cross differ by two numbers here, rather
 * than by a classifier bolted on afterwards to invent variety the football does
 * not have.
 */
class Option(
    @JvmField val kind: OptKind,
    @JvmField val receiver: Man?,
    @JvmField val tx: Float,
    @JvmField val ty: Float,
    @JvmField val mps: Float,
    @JvmField val loft: Float
) {
    @JvmField var reward = 0f
    @JvmField var risk = 0f
    @JvmField var conformance = 1f

    /**
     * Expected value, SIGNED, with no role weighting in it.
     *
     * The first version was `(reward - risk) * conformance`, and it was
     * inverted wherever the expected value was negative — which is most
     * options, since holding the ball has a value you forfeit by playing it.
     * Multiplying -0.004 by a poacher's shot intent of 2.6 makes the shot
     * WORSE, so every intent weight pushed the man away from the thing his role
     * was supposed to make him try. RoleCheck found it: six pairs of roles were
     * indistinguishable on both signatures at once.
     *
     * Conformance belongs on the CHOICE, not on the score — a prior multiplies
     * a probability, not a utility. It is applied in [Decide.choose].
     */
    val utility: Float get() = reward - risk
}

/**
 * THE DECISION LAYER — build-order step 5.
 *
 * The predecessor played one pass a match because its scorer was a
 * hand-weighted sum of distances and it MAXIMISED it. Re-weighting the sum was
 * tried twice and moved nothing. So the fix here is not a better sum; it is
 * four separate things:
 *
 *   1. an option is a concrete act, so the option SET has real variety in it;
 *   2. reward comes from an externally anchored value surface, never from a
 *      sum of distances and never from the engine's own play (see Value.kt);
 *   3. perception REMOVES options rather than penalising them — a man who
 *      cannot see the switch does something else, he is not "worse at" it;
 *   4. the choice is a softmax over utility with a decisiveness temperature,
 *      so a maximiser is the T -> 0 limit and T is never 0.
 *
 * And the blunt caveat, because it is the thing that actually matters: softmax
 * cannot rescue a degenerate model. If one option dominates every possession, a
 * temperature just adds noise around the same choice. Variety in what is chosen
 * is downstream of the option set, which is downstream of off-ball movement —
 * which is why roles were built first, and why `OptionCensus` reports the set
 * size next to the choice distribution.
 */
object Decide {

    const val MAX_OPTIONS = 24

    /** How far a man will try to pass at all. */
    private const val MAX_PASS_M = 55f

    /**
     * Options a man cannot SEE are not generated.
     *
     * §2.8: attributes gate execution, and a low-vision midfielder does not see
     * the switch so he plays the simple ball instead. There are no attributes
     * yet, so this is the situational half only — pressure and body angle — and
     * it is written as a filter on the option set rather than a penalty on a
     * score, so that adding a vision attribute later changes one number here
     * and nothing else in the engine.
     */
    private fun canSee(carrier: Man, pressure: Float, tx: Float, ty: Float): Boolean {
        val dx = tx - carrier.x
        val dy = ty - carrier.y
        val d = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (d < 0.5f) return false
        // Under pressure his head is down and the far side stops existing.
        if (pressure < 3f && d > 28f) return false
        if (pressure < 5f && d > 42f) return false
        return true
    }

    /**
     * Generate what is available. Bounded, and cheap: at most two variants per
     * team-mate, a handful of carries, a shot when there is one, and a
     * clearance when he is in trouble.
     */
    fun generate(
        sim: MatchSim, carrier: Man, pressure: Float, out: ArrayList<Option>
    ) {
        out.clear()
        val side = carrier.side
        val myAttX = Pitch.attX(side, carrier.x)

        for (t in sim.men) {
            if (t === carrier || t.side != side || t.isKeeper) continue
            val d = Physics.dist(carrier.x, carrier.y, t.x, t.y)
            if (d > MAX_PASS_M) continue
            if (!canSee(carrier, pressure, t.x, t.y)) continue
            if (out.size >= MAX_OPTIONS) break

            val tAttX = Pitch.attX(side, t.x)
            val lateral = abs(t.y - carrier.y)
            val forward = tAttX - myAttX

            // To feet — the simple ball.
            val kind = when {
                lateral > 28f && abs(forward) < 18f -> OptKind.SWITCH
                tAttX > 80f && lateral > 12f && myAttX > 78f && tAttX < myAttX -> OptKind.CUT_BACK
                myAttX > 68f && (carrier.y < 20f || carrier.y > 48f) && lateral > 10f -> OptKind.CROSS
                else -> OptKind.PASS_FEET
            }
            val loft = when (kind) {
                OptKind.CROSS -> 14f
                OptKind.SWITCH -> 11f
                else -> 1.5f
            }
            out.add(Option(kind, t, t.x, t.y, speedFor(d, loft), loft))

            // Into the space ahead of him — a different act with a different risk.
            if (out.size < MAX_OPTIONS && forward > -4f) {
                val ahead = 9f
                val sx = Pitch.absX(side, tAttX + ahead)
                val sy = t.y
                if (canSee(carrier, pressure, sx, sy)) {
                    val through = tAttX + ahead > 74f && forward > 6f
                    out.add(Option(
                        if (through) OptKind.THROUGH_BALL else OptKind.PASS_SPACE,
                        t, sx, sy, speedFor(Physics.dist(carrier.x, carrier.y, sx, sy), 1f), 1f
                    ))
                }
            }
        }

        // Carry — he can also just take it himself.
        for (k in -1..1) {
            if (out.size >= MAX_OPTIONS) break
            val ax = (myAttX + 12f).coerceAtMost(Pitch.LENGTH - 2f)
            val ay = (Pitch.attY(side, carrier.y) + k * 10f).coerceIn(2f, Pitch.WIDTH - 2f)
            out.add(Option(OptKind.CARRY, null,
                Pitch.absX(side, ax), Pitch.absY(side, ay), 7f, 0f))
        }

        /*
         * VARIANTS OF ONE ACT MUST NOT BE OFFERED AS SEPARATE OPTIONS.
         *
         * Measured, and it cost a working commit. Shot quality was attempted by
         * generating a "placed" option at the far corner and a "driven" one
         * nearer the middle. A softmax over two options of the same KIND gives
         * that kind twice the weight, so shots went 27 -> 50 a match on their
         * own, and blocks, saves, goals and corners all moved the wrong way.
         *
         * Aiming away from the keeper was the right idea — blocks fell 16.5 ->
         * 4.75, which is what avoiding the bodies in the middle looks like —
         * but with a single corner-aimed option the headline rows were still
         * worse than not doing it: shots 34.6, blocks 2.20, goals 1.35 -> 1.00.
         * Reverted, and recorded here so it is not rediscovered.
         *
         * The lesson generalises: how hard and where exactly a man strikes it
         * belongs in EXECUTION. If it is offered as a candidate, the option set
         * quietly becomes a vote on kinds and the chooser is measuring the
         * generator instead of the football.
         */
        // Shot, when there is a goal to shoot at.
        if (myAttX > 62f && out.size < MAX_OPTIONS) {
            val gx = Pitch.absX(side, Pitch.LENGTH - 0.5f)
            out.add(Option(OptKind.SHOT, null, gx, Pitch.WIDTH * 0.5f, 27f, 3f))
        }

        // Get rid of it — always available, and correctly awful in good areas.
        if (out.size < MAX_OPTIONS) {
            val ax = (myAttX + 40f).coerceAtMost(Pitch.LENGTH - 2f)
            out.add(Option(OptKind.CLEAR, null,
                Pitch.absX(side, ax), Pitch.absY(side, Pitch.WIDTH * 0.5f), 24f, 20f))
        }
    }

    private fun speedFor(d: Float, loft: Float): Float =
        (7f + d * 0.42f + loft * 0.25f).coerceIn(8f, 30f)

    /** Score every option on axes that are kept separate until the last moment. */
    fun score(sim: MatchSim, carrier: Man, options: ArrayList<Option>) {
        val side = carrier.side
        val here = Value.possessionValue(Pitch.attX(side, carrier.x), carrier.y)

        for (o in options) {
            val tAttX = Pitch.attX(side, o.tx)
            val d = Physics.dist(carrier.x, carrier.y, o.tx, o.ty)

            // --- how likely it fails, first: reward depends on it
            var pFail = when (o.kind) {
                OptKind.SHOT -> 0f
                // A hoof goes to NOBODY. The first version had this at 0.35,
                // which told the model a clearance keeps the ball two times in
                // three. Whether a team-mate is near where it lands is the
                // whole question.
                OptKind.CLEAR ->
                    if (sim.matesWithin(o.tx, o.ty, 12f, side) > 0) 0.55f else 0.85f
                OptKind.CARRY -> 0.18f + sim.opponentsWithin(o.tx, o.ty, 6f) * 0.09f
                else -> 0.06f + d * 0.006f + sim.opponentsNearLine(carrier, o.tx, o.ty) * 0.14f
            }
            if (o.kind == OptKind.THROUGH_BALL) pFail += 0.16f
            if (o.kind == OptKind.CROSS) pFail += 0.20f
            pFail = pFail.coerceIn(0f, 0.95f)

            /*
             * EXPECTED VALUE, not "value there minus a penalty".
             *
             * The first version scored reward as V(target) - V(here) and
             * subtracted a risk term for where the ball would be lost. That
             * ignores the thing that makes losing it expensive: you had the
             * ball and now you do not. Because turnover cost at the far end of
             * the pitch is tiny, a hoof upfield came out nearly free, and
             * OptionCensus duly reported CLEAR as the most chosen act in both
             * the middle and the final third — men hoofing it from the edge of
             * the opposition box.
             *
             * The honest form keeps the possession you forfeit inside the sum:
             *
             *     EV = (1 - pFail) * V(target)  -  V(here)  -  pFail * cost
             */
            o.reward = if (o.kind == OptKind.SHOT) {
                /*
                 * A shot prices in the bodies in the way.
                 *
                 * Without this a shot was generated whenever a man was past
                 * 62 m and scored on the anchored surface as though the goal
                 * were empty, which produced seventy saves a match against a
                 * real seven or eight. A defender standing in the line is the
                 * single largest term in whether a shot is worth taking.
                 */
                val blockers = sim.opponentsNearLine(carrier, o.tx, o.ty)
                val through = 1f / (1f + 1.35f * blockers)
                Value.shotValue(Pitch.attX(side, carrier.x), carrier.y) * through - here
            } else {
                (1f - pFail) * Value.possessionValue(tAttX, o.ty) - here
            }
            o.risk = pFail * Value.turnoverCost(tAttX, o.ty) * RISK_WEIGHT

            // --- conformance: the ONLY channel by which tactics enter
            o.conformance = carrier.role.intentFor(o.kind)
        }
    }

    private const val RISK_WEIGHT = 1.6f

    /**
     * Choose among them — never maximise.
     *
     * P(i) proportional to exp(u_i / T). A maximiser is T -> 0 and T is never 0;
     * a composed man with time on the ball runs cold and close to optimal, and
     * a man with someone in his ear runs hot and is genuinely erratic. That is
     * one mechanism doing the work three systems used to do badly.
     */
    fun choose(options: ArrayList<Option>, temperature: Float, rng: Rng): Option {
        if (options.size == 1) return options[0]
        var best = -Float.MAX_VALUE
        for (o in options) if (o.utility > best) best = o.utility

        var sum = 0.0
        val w = DoubleArray(options.size)
        for (i in options.indices) {
            // The role's prior multiplies the WEIGHT, which is what a
            // multiplicative prior means. It cannot flip a sign, and a role
            // that doubles an intent doubles how often he attempts it.
            val e = options[i].conformance *
                exp(((options[i].utility - best) / temperature).toDouble())
            w[i] = e
            sum += e
        }
        var r = rng.nextFloat().toDouble() * sum
        for (i in options.indices) {
            r -= w[i]
            if (r <= 0) return options[i]
        }
        return options[options.size - 1]
    }

    /**
     * How decisive he is right now. Pressure and time on the ball, for as long
     * as there are no attributes; composure and decisions belong here next.
     */
    fun temperature(pressure: Float): Float =
        (0.055f - 0.0035f * pressure.coerceIn(0f, 12f)).coerceAtLeast(0.012f)

    /** Shannon entropy of a distribution, normalised by the uniform. */
    fun normalisedEntropy(counts: IntArray): Double {
        var n = 0
        var k = 0
        for (c in counts) { n += c; if (c > 0) k++ }
        if (n == 0 || k <= 1) return 0.0
        var h = 0.0
        for (c in counts) if (c > 0) {
            val p = c.toDouble() / n
            h -= p * ln(p)
        }
        return h / ln(k.toDouble())
    }
}
