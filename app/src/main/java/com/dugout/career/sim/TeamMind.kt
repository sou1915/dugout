package com.dugout.career.sim

/**
 * THE TEAM'S MIND — one per side, above the eleven.
 *
 * [Mind] gave every man his own head. This is the thing a team has that no
 * player has: a view of the whole afternoon. Before it, "the team" was five
 * static numbers in [Block] — two widths, two depths and a line height — that
 * knew nothing about the score, the clock, or the fact that we had been pinned
 * in our own third for two minutes.
 *
 * THE ONE RULE IT MUST NEVER BREAK.
 *
 * A team mind may not overrule a player's choice. It may do exactly two things:
 * change the WEIGHT of an option, and hand an OFF-BALL man a target. It never
 * reaches into [Mind.decide] and substitutes an act.
 *
 * That is not a stylistic preference. The moment a team can force a player's
 * decision, his percentages stop describing anything he does, and MindCheck's
 * calibration — the only thing standing between this engine and a page of
 * invented numbers — becomes unmeasurable. Weights and targets keep every claim
 * testable.
 *
 * THREE LAYERS, THREE SWITCHES.
 *
 * They are separate systems that happen to live in one class, and each has its
 * own flag so a harness can run one without the others. This session measured
 * four separate things under confounds because there was no way to hold the
 * rest of the engine still; [Draw.freeze] fixed that for randomness, and these
 * flags fix it for tactics.
 *
 *   1. DISPOSITION  slow numbers the whole side shares — how direct, how high —
 *                   read off territory, clock and score.
 *   2. MEMORY       the side as one record: which channel we have attacked and
 *                   how it went. Held in the supervisor, like a man's own.
 *   3. ACTS         a play with a lifetime that commits several men at once.
 *                   The press. This is the layer you can SEE.
 */
class TeamMind(@JvmField val side: Int) {

    // ================================================================ 1. DISPOSITION

    /** Multiplies the weight of a forward act. 1.0 is neutral. */
    @JvmField var directness = 1f

    /** Multiplies the weight of a safe act. */
    @JvmField var caution = 1f

    /** Metres the defensive line pushes up (+) or drops (-). */
    @JvmField var lineShift = 0f

    /** How willing the side is to leave its shape and hunt the ball, 0..1. */
    @JvmField var aggression = 0.5f

    /**
     * Where the ball has been living, in OUR attacking frame, smoothed.
     *
     * The one input that is alive today. Score and clock are read below and are
     * very nearly dormant — at 0.23 goals a match almost no game has a scoreline
     * in it — so they are wired, honest, and reported as near-zero by TeamCheck
     * rather than quietly pretending to drive anything. Same discipline as a
     * wired event that never fires: the zero must be visible.
     */
    private var territory = Pitch.LENGTH * 0.5f

    /** Called every tick. Cheap on purpose: one multiply and one add. */
    fun observe(sim: MatchSim) {
        val ax = Pitch.attX(side, sim.ball.x)
        territory += (ax - territory) * TERRITORY_ALPHA
    }

    /**
     * Re-read the match. Every five seconds, not every tick — a disposition
     * that changes fifty times a second is not a disposition.
     */
    fun read(sim: MatchSim) {
        if (!DISPOSITION) {
            directness = 1f; caution = 1f; lineShift = 0f; aggression = 0.5f
            return
        }

        /*
         * PINNED, OR CAMPED.
         *
         * -1 is living in our own box, +1 is living in theirs. This is the
         * signal a real coach reads first and it needs no goals to exist, which
         * is why it carries the layer while the scoreline cannot.
         */
        val terr = ((territory - Pitch.LENGTH * 0.5f) / (Pitch.LENGTH * 0.35f))
            .coerceIn(-1f, 1f)

        /*
         * CHASING, OR PROTECTING — wired, and currently almost dormant.
         *
         * Weighted by how late it is: being a goal down matters at 85 minutes
         * and barely matters at 10. TeamCheck prints how often this is non-zero
         * so that the day it starts mattering is visible, and so that nobody
         * reads a dead input as a working one in the meantime.
         */
        val diff = (sim.goals[side] - sim.goals[1 - side]).coerceIn(-3, 3)
        val late = (sim.clock / MatchSim.SECONDS).coerceIn(0f, 1f)
        val urgency = (-diff * late).coerceIn(-1f, 1f)   // + means we are chasing

        // Pinned back, or chasing a game: get more direct and squeeze up.
        directness = (1f + 0.55f * urgency - 0.30f * terr).coerceIn(0.55f, 1.9f)
        caution = (1f - 0.35f * urgency + 0.20f * terr).coerceIn(0.55f, 1.6f)
        lineShift = (7f * urgency + 4f * terr).coerceIn(-8f, 10f)
        aggression = (0.5f + 0.35f * urgency - 0.25f * terr).coerceIn(0.15f, 0.95f)
    }

    /**
     * The team's prior on a kind of act. Multiplies a weight in [Mind.pick] —
     * never a utility, for the reason written there.
     */
    fun intentFor(k: OptKind): Float = when (k) {
        OptKind.THROUGH_BALL, OptKind.PASS_SPACE, OptKind.CROSS,
        OptKind.CUT_BACK, OptKind.SHOT -> directness
        OptKind.PASS_FEET, OptKind.SWITCH -> caution
        OptKind.CLEAR, OptKind.CARRY -> 1f
    }

    // ==================================================================== 2. MEMORY

    /**
     * WHICH SIDE OF THE PITCH HAS BEEN WORKING.
     *
     * A man remembers his own passes. A team remembers where its attacks went
     * and what came of them, which is a thing no individual can know — the left
     * winger cannot see that eleven balls down his flank have produced nothing.
     *
     * It lives in the same supervisor as everything else, under `.WE.` codes, so
     * the census shows a team's record beside a man's and beside every random
     * draw. And, as ever, because occurrences are counted per code, adding this
     * whole layer cannot disturb one value drawn anywhere else.
     */
    fun channelOf(side: Int, y: Float): Int {
        val ay = Pitch.attY(side, y)
        return when {
            ay < Pitch.WIDTH / 3f -> 0
            ay < Pitch.WIDTH * 2f / 3f -> 1
            else -> 2
        }
    }

    /** Record how an attack down a channel finished. */
    fun noteChannel(draw: Draw, ch: Int, kept: Boolean) {
        draw.note("S$side.WE.CH$ch." + if (kept) "KEPT" else "LOST")
    }

    /**
     * How attractive that channel is right now, given how it has gone.
     *
     * Damped hard by sample count, so a side does not abandon its left wing on
     * the evidence of two passes, and clamped both ways so it can tilt a match
     * and never dictate one. With no memory at all this returns exactly 1 and
     * the layer is a no-op, which is what the switch below turns it into.
     */
    fun channelBias(draw: Draw, ch: Int): Float {
        if (!MEMORY) return 1f
        var totKept = 0; var totN = 0
        var kept = 0; var n = 0
        for (c in 0..2) {
            val k = draw.count("S$side.WE.CH$c.KEPT")
            val l = draw.count("S$side.WE.CH$c.LOST")
            totKept += k; totN += k + l
            if (c == ch) { kept = k; n = k + l }
        }
        if (n < 4 || totN < 12) return 1f
        val rate = kept.toFloat() / n
        val overall = totKept.toFloat() / totN
        val confidence = n.toFloat() / (n + MEMORY_PRIOR)
        return (1f + MEMORY_K * (rate - overall) * confidence).coerceIn(0.55f, 1.55f)
    }

    // ====================================================================== 3. ACTS

    /**
     * A PLAY: one decision, several men, a lifetime.
     *
     * This is the layer that answers §1 of the brief — a tactic must be VISIBLE
     * on the pitch. A disposition changes numbers; a press changes the picture.
     * Three men leave their stations at the same instant and converge, and
     * either it works or you can see why it did not.
     *
     * Deliberately not a general "play system" with a script language in it.
     * The predecessor's lesson is that a framework built before its second
     * instance exists is a framework fitted to one case and renamed. There is
     * one play. The second one is what will say what the abstraction should be.
     */
    @JvmField var pressUntil = -1f
    private val pressing = ArrayList<Man>(3)
    private val pressTargetX = FloatArray(3)
    private val pressTargetY = FloatArray(3)

    /** True while men are committed and have not been released. */
    val pressLive: Boolean get() = pressing.isNotEmpty()

    /** Who is committed right now. For the renderer, and for nothing else. */
    val pressers: List<Man> get() = pressing

    /**
     * Should we go now?
     *
     * Called at claim cadence, not every tick. The trigger is geometry plus one
     * coded roll whose threshold is the disposition's aggression — so a side
     * that is chasing a game presses more, through the same channel everything
     * else uses rather than through a special case.
     */
    fun considerPress(sim: MatchSim, victim: Man?, clock: Float) {
        if (!ACTS) { release(); return }

        /*
         * A PRESS HUNTS ACROSS PASSES. IT DOES NOT END AT THE FIRST TOUCH.
         *
         * The first version released every man the moment a possession settled,
         * and PressFilm showed what that looks like: three men ringed at +0.0s,
         * ringed at +0.5s, and back in their stations at +1.0s. A press that
         * ends when the opponent completes one pass is not a press, it is a
         * flinch — and no aggregate in TeamCheck would ever have said so. It
         * took five pictures.
         *
         * So while they still have it, the play RETARGETS onto whoever has it
         * now. It ends three ways: we win the ball, they play through us and
         * the clock runs out, or the ball leaves the area worth pressing.
         */
        if (pressLive) {
            if (clock > pressUntil) { release(); pressUntil = clock + PRESS_COOLDOWN; return }
            if (victim != null && victim.side != side) aimAt(sim, victim)
            return
        }

        if (clock < pressUntil) return          // cooling off
        if (victim == null || victim.side == side) return
        if (sim.possessionSide == side) return

        // Press HIGH. Chasing the ball inside our own third is not a press, it
        // is a defensive shape, and that already exists.
        val ballAx = Pitch.attX(side, sim.ball.x)
        if (ballAx < PRESS_MIN_AX) return

        // Only worth it if he has time. A man already closed down is being
        // pressed by whoever is closing him.
        if (sim.pressureOn(victim) < PRESS_MIN_SPACE) return

        val roll = sim.draw.next("S$side.TEAM.PRESS@${(clock * 10).toInt()}")
        if (roll > aggression * PRESS_RATE) return

        if (!aimAt(sim, victim)) return
        pressUntil = clock + PRESS_SECONDS
        sim.events.fire(Ev.PRESS_TRIGGERED, side)
    }

    /**
     * Point the three committed men at the man on the ball and his two nearest
     * outlets. The first goes at him; the others stand in the cover shadow,
     * which is what makes it a press rather than three men chasing one ball.
     */
    private fun aimAt(sim: MatchSim, victim: Man): Boolean {
        val near = sim.men.asSequence()
            .filter { it.side == side && !it.isKeeper }
            .sortedBy { Physics.dist(it.x, it.y, victim.x, victim.y) }
            .take(3).toList()
        if (near.size < 3) return false

        val outlets = sim.men.asSequence()
            .filter { it.side == victim.side && it !== victim && !it.isKeeper }
            .sortedBy { Physics.dist(it.x, it.y, victim.x, victim.y) }
            .take(2).toList()

        pressing.clear()
        pressing.addAll(near)
        pressTargetX[0] = victim.x
        pressTargetY[0] = victim.y
        for (i in 1..2) {
            val o = outlets.getOrNull(i - 1)
            if (o == null) {
                pressTargetX[i] = victim.x + (Pitch.absX(side, 0f) - victim.x) * 0.15f
                pressTargetY[i] = victim.y
            } else {
                pressTargetX[i] = victim.x + (o.x - victim.x) * PRESS_SHADOW
                pressTargetY[i] = victim.y + (o.y - victim.y) * PRESS_SHADOW
            }
        }

        /*
         * TWO MEN IN THE SAME PLACE ARE ONE MAN.
         *
         * PressFilm showed this before any counter could: two of the three
         * ringed men standing on top of each other, because both cover shadows
         * are drawn toward the two nearest outlets and those two are often side
         * by side. One of them is then doing nothing at all — and no aggregate
         * in TeamCheck can tell a three-man press from a two-man press with a
         * passenger. It took one picture.
         *
         * If they collide, the second steps square. Crude, and it is the right
         * crude: what matters is that the second body covers a different lane,
         * not which lane exactly.
         */
        if (Physics.dist(pressTargetX[1], pressTargetY[1],
                pressTargetX[2], pressTargetY[2]) < PRESS_SPREAD) {
            val dx = pressTargetX[2] - victim.x
            val dy = pressTargetY[2] - victim.y
            val len = kotlin.math.hypot(dx.toDouble(), dy.toDouble())
                .toFloat().coerceAtLeast(0.01f)
            pressTargetX[2] = (pressTargetX[2] - dy / len * PRESS_SPREAD)
                .coerceIn(1f, Pitch.LENGTH - 1f)
            pressTargetY[2] = (pressTargetY[2] + dx / len * PRESS_SPREAD)
                .coerceIn(1f, Pitch.WIDTH - 1f)
        }
        return true
    }

    /** Did the play give this man somewhere to be? Writes into [out]. */
    fun pressTargetFor(m: Man, out: FloatArray): Boolean {
        if (!ACTS) return false
        val i = pressing.indexOf(m)
        if (i < 0) return false
        out[0] = pressTargetX[i]
        out[1] = pressTargetY[i]
        return true
    }

    /** Everyone goes back to their station. */
    fun release() { pressing.clear() }

    /**
     * A possession of theirs just settled while we were pressing.
     *
     * Only a WON ball ends the play. If they kept it, the press carries on and
     * [considerPress] moves the three men onto whoever has it now — that is the
     * difference between pressing and closing down once.
     */
    fun pressResolved(sim: MatchSim, won: Boolean, clock: Float) {
        if (!pressLive || !won) return
        sim.events.fire(Ev.PRESS_WON, side)
        release()
        pressUntil = clock + PRESS_COOLDOWN
    }

    companion object {
        /**
         * THREE SWITCHES, SO THREE SYSTEMS CAN BE TOLD APART.
         *
         * All true in the engine. A harness turns one off, measures, and turns
         * it back on; TeamCheck does exactly that and prints one column per
         * layer. Without this every measurement of "the team mind" would be a
         * measurement of all three at once, which is how this session already
         * lost four results to confounds.
         */
        @JvmField var DISPOSITION = true
        @JvmField var MEMORY = true
        @JvmField var ACTS = true

        /** Reset after a harness experiment. */
        fun allOn() { DISPOSITION = true; MEMORY = true; ACTS = true }

        /** Ticks between disposition reads. 50 ticks is five seconds. */
        const val READ_EVERY = 50

        /** How fast territory forgets. Roughly a thirty second memory at 10 Hz. */
        const val TERRITORY_ALPHA = 0.0033f

        /** How hard a channel's record tilts the choice. */
        const val MEMORY_K = 0.8f
        /** Sample count at which the tilt is half its full strength. */
        const val MEMORY_PRIOR = 12f

        /** Metres up the pitch below which a press is just defending. */
        const val PRESS_MIN_AX = 38f
        /** He must have at least this much space for pressing him to mean anything. */
        const val PRESS_MIN_SPACE = 4.5f
        /**
         * Ceiling on how often the trigger can fire, before aggression scales
         * it. A var rather than a const so it can be SWEPT — this value was
         * chosen by measuring five of them, and the sweep has to be repeatable.
         *
         * 24 matches each, press only, everything else held still:
         *
         *   rate   goals   passes  completion  intercept  presses
         *   0.55    1.50    811.7      61.0%       236.2    460.0
         *   0.30    0.46    823.8      65.1%       214.3    372.8
         *   0.15    0.42    859.4      70.7%       184.0    263.6
         *   0.07    0.71    899.2      74.7%       170.2    158.3
         *   0.03    0.29    916.4      78.0%       151.3     77.8
         *   off     0.27    939.3      80.3%       140.1      0.0
         *
         * Completion is monotone and clean. Goals is not — at a quarter of a
         * goal a match, 24 matches is six goals, and every row between 0.03 and
         * 0.30 is one number's worth of noise. Only 0.55 clears it: 36 goals
         * against six, which is a real difference and not a lucky seed.
         *
         * 0.55 SHIPS, and the reason is not that it wins the goals row. It is
         * that it is the only one of these with an external anchor: 460 presses
         * a match is 230 a side, and event data puts a real team between 150
         * and 250 pressures. The others are quieter than football is.
         *
         * WHAT IT COSTS: completion 80.3% -> 61%, twenty points below a band it
         * had only just entered. That is not the press being wrong. A won ball
         * here is instant and total, because there is no loose ball — the man
         * who reaches it first simply has it — so every press that gets near
         * the ball converts fully. The same missing model prices a clearance at
         * 1.6%. It is one hole, and it is now showing up in three places.
         */
        @JvmField var PRESS_RATE = 0.55f
        /** How long men stay committed. */
        const val PRESS_SECONDS = 2.5f
        /** Dead time after one resolves, so a side does not press forever. */
        const val PRESS_COOLDOWN = 1.5f
        /** How far along the line to the outlet a presser stands. */
        const val PRESS_SHADOW = 0.62f
        /** Metres two committed men must not stand within. */
        const val PRESS_SPREAD = 5f
    }
}
