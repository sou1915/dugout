package com.dugout.career.sim

import kotlin.math.abs
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
     * HIS OWN ESTIMATE THAT THIS WORKS, 0..1.
     *
     * Success is defined per act, not in general: a pass succeeds when the man
     * he aimed at takes it, a carry succeeds when he still has it twelve metres
     * later, a shot succeeds when it goes in. A single "success" number across
     * kinds would be meaningless, and a percentage nobody can check is worse
     * than no percentage — so [MindCheck] measures every one of these against
     * what actually happened, bucketed.
     *
     * It was a local variable called `pFail` buried inside the scorer before.
     * Making it a field is the difference between a model and a hunch: what is
     * a field can be printed, drawn on a frame, and calibrated.
     */
    @JvmField var pSuccess = 0f

    /** How many times he has already done this exact thing in this match. */
    @JvmField var repeats = 0

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
     * a probability, not a utility. It is applied in [Mind.pick].
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
 *   4. the choice is made by the man himself, in [Mind], by ranking the
 *      options on expected value and taking his best with a little randomness
 *      — a pure maximiser is the zero-randomness limit and it is never zero.
 *
 * And the blunt caveat, because it is the thing that actually matters: no
 * chooser can rescue a degenerate model. If one option dominates every
 * possession, randomness just adds noise around the same choice. Variety in what is chosen
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
            /*
             * LEAD THE PASS — re-tested with the overshoot confound removed.
             *
             * Aim at where he WILL be: his position plus his velocity over the
             * flight time. Tested once before and returned nothing, but that
             * ran while every pass overshot by five metres.
             */
            val mps = speedFor(d, loft)
            val flight = (d / mps).coerceIn(0f, 2.5f)
            out.add(Option(kind, t,
                (t.x + t.vx * flight).coerceIn(1f, Pitch.LENGTH - 1f),
                (t.y + t.vy * flight).coerceIn(1f, Pitch.WIDTH - 1f),
                mps, loft))

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
        /*
         * DO NOT GATE AN OPTION ON THE SAME PREDICATE THAT RESOLVES IT.
         *
         * Attempted and reverted. Blocks run at 61% of shots against a real
         * 30%, and penalising the shot's reward does not fix it — with
         * 1/(1+1.35b) a blocked shot still beats the alternatives, because
         * almost every option has negative expected value once the possession
         * you forfeit is subtracted.
         *
         * So the shot was gated on `nearestOnLine == null` — perception
         * removing the option, per §2.8. But that is the SAME test the block
         * uses, evaluated on the same tick, so a shot could only be generated
         * when it was impossible to block. EventCensus said so in one line:
         * NEVER FIRES: SHOT_BLOCKED. And the rest collapsed with it —
         * shots 27.25 -> 8.95, goals 1.35 -> 0.85, corners 1.90 -> 0.00.
         *
         * The shooter must judge the lane IMPERFECTLY: a gate looser than the
         * block test, so he shoots through half-gaps and is sometimes wrong.
         * Same shape of error as the rulers earlier in this session — a check
         * that cannot disagree with what it is checking measures nothing.
         */
        /*
         * ATTEMPT 3 AT THE BLOCK RATE — the mechanism works, the trade does not.
         *
         * A perception radius NARROWER than the block half-width (0.34 m against
         * 0.75 m) is the right shape: he shoots through a half-gap he thinks is
         * on, the block fires on a wider body, and the difference between the
         * two numbers IS the misjudgement. It fixed the row it aimed at:
         *
         *   SHOT_BLOCKED   16.50 -> 7.15    (target 8-10, and 42% of shots
         *                                    against 61% before, real 30%)
         *
         * But it took the shot count down with it and the headline row went the
         * wrong way:
         *
         *   shots          27.25 -> 17.00   (band 22-27)
         *   goals           1.38 ->  0.85   (target 2.6-2.9)
         *
         * Reverted on the same rule that reverted the aim attempt: goals moving
         * away from band is not paid for by one other row moving toward it.
         *
         * What it proves: the block rate is fixable and this is how. What it
         * exposes: shot VOLUME and block RATE are currently the same lever,
         * because the only way a man declines to shoot is by not seeing one.
         * They separate when he has a better option to take instead — which is
         * the loose ball and the defending shape, not another radius.
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

    /**
     * SOLVE THE STRIKE FOR THE DISTANCE — do not guess it.
     *
     * This was `7 + d*0.42 + loft*0.25`, an invented curve. A ball rolling
     * under ROLL_DRAG loses speed as v*(1 - drag*dt) per step, so the distance
     * it still has to travel is the sum of that series: v / drag. The speed
     * that stops a ball at d is therefore d * drag, and the old curve was
     * giving 15.4 m/s for a 20 m pass where 12.4 stops it there — a systematic
     * five metre overshoot on every pass in the match.
     *
     * That is where the 8.57 m mean miss comes from. It is not delivery error
     * (cutting it 26 -> 9 moved the miss 0.34 m) and it is not the receiver
     * moving (leading the pass moved it 0.14 m). The ball was never aimed to
     * STOP at him.
     */
    private fun speedFor(d: Float, loft: Float): Float =
        (d * Physics.ROLL_DRAG + 2.5f + loft * 0.35f).coerceIn(6f, 30f)

    /*
     * SCORING AND CHOOSING USED TO LIVE HERE. THEY LIVE IN A MAN NOW.
     *
     * `score` and `choose` were static functions on this object, which meant
     * every player in the league shared one scorer and one chooser and could
     * carry nothing of his own between possessions. That is why nothing could
     * remember anything: there was no object per man to remember it in.
     *
     * They are now [Mind], one per player, holding his own percentages and his
     * own memory of the match. This object keeps the part that is genuinely
     * shared — what options EXIST from a position on the pitch, which is
     * geometry and the same for everybody.
     *
     * The old softmax-with-a-temperature chooser is deleted rather than left
     * beside the new one. A second decision path that nothing calls is exactly
     * the dead code this project keeps finding in its predecessor; the argument
     * for why ranking replaced it is in [Mind.pick].
     */

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
