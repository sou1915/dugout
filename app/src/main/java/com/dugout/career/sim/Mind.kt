package com.dugout.career.sim

/**
 * A MIND, ONE PER MAN ON THE GRASS.
 *
 * Every player owns one of these for the whole match. It does three things, and
 * the second is the one that did not exist before:
 *
 *  1. PRICES every option he has as an explicit PERCENTAGE — his own estimate
 *     that this particular act works. Not a score on an arbitrary scale: a
 *     number between 0 and 1 that a harness can hold up against what actually
 *     happened and call wrong.
 *
 *  2. REMEMBERS. Before choosing, it asks the randomness supervisor how many
 *     times this man has already played this exact ball in this match, and
 *     damps a repeat. That is the link the owner asked for between a decision
 *     and what has been recorded, and it is why two matches from two seeds do
 *     not converge on the same six passes.
 *
 *  3. CHOOSES the best one — with a little randomness, so the best is usually
 *     but not always taken.
 *
 * WHY THE MEMORY LIVES IN THE SUPERVISOR AND NOT IN A FIELD HERE.
 *
 * It could trivially be a HashMap on this object. Putting it in [Draw] buys two
 * things a field cannot. The census then shows decisions and random draws in one
 * table, so "this man played the same square ball eleven times" is visible in
 * the same place as "this code fired eleven times". And it is addressed by code,
 * so adding a new thing to remember cannot disturb anything already remembered —
 * which is the property the supervisor was built to have and this is the first
 * system to actually rely on it.
 *
 * WHAT THIS IS NOT.
 *
 * It is not "take the highest success percentage". That is a real trap and it
 * has to be said plainly: a five-metre ball backwards to the centre-half is
 * about 97% and the shot he has just worked is about 9%, so a mind that ranked
 * on raw success would pass backwards until the final whistle. It would be the
 * predecessor's failure with the sign flipped — one dominant act, chosen every
 * time, for a reason that sounded sensible.
 *
 * So the percentage is ranked against WHAT SUCCEEDING IS WORTH:
 *
 *     appeal  =  pSuccess x (what it gets us)  -  (1 - pSuccess) x (what it costs)
 *
 * The 9% shot is worth a goal and the 97% backpass is worth almost nothing, and
 * that is the whole of why one is ever taken over the other.
 */
class Mind(@JvmField val man: Man) {

    /**
     * How sharply he prefers his best option, before pressure adjusts it.
     * P(his nth choice) falls by this factor per rank, so at 0.30 he takes the
     * best about 70% of the time, the second about 21%, and occasionally does
     * something you would shout at him for.
     */
    private val order = IntArray(Decide.MAX_OPTIONS + 4)
    private val weight = DoubleArray(Decide.MAX_OPTIONS + 4)

    /**
     * THE PRICE HE PUTS ON ONE ACT — his estimate it works, 0..1.
     *
     * These coefficients are not new. They are the `pFail` terms that were
     * already inside the scorer, read the other way up so that the number has a
     * meaning a person can check — and, for the first time, checked: every one
     * of them is now held against 30 matches of outcomes by MindCheck.
     *
     * Several are measurably wrong and are DELIBERATELY left wrong. The reason
     * is under CARRY, with the numbers that were measured when they were fixed
     * instead.
     *
     * The one term visibly missing is being closed down — a man with a defender
     * on his shoulder completes fewer passes than a man in acres, and pressure
     * does not appear here at all. It is left out ON PURPOSE. It would move
     * every row in the census at the same time as the memory and the chooser,
     * and then none of the three could be told apart.
     */
    fun pSuccess(sim: MatchSim, o: Option): Float {
        val side = man.side
        val d = Physics.dist(man.x, man.y, o.tx, o.ty)
        return when (o.kind) {
            /*
             * A shot succeeds when it goes in. Nothing else is a successful
             * shot, and pricing it as "reaches the goalmouth" is how an engine
             * talks itself into seventy shots a match.
             *
             * THIS ONE IS DELIBERATELY NOT CALIBRATED, AND THE GAP IS LOUD.
             *
             * MindCheck measures shots predicted at 8.7% and converted at
             * 1.0%, and the rule that stops that gap being closed by moving the
             * number is in the brief: the value surface is externally anchored and is NEVER
             * re-derived from a policy that has failed the §5 acceptance table.
             * Goals read 0.56 a match against a target of 2.6-2.9, so 1.0% is
             * this engine being broken, not football being different.
             *
             * Fitting the mind to it would hide the defect twice over. Players
             * would stop shooting, the shot count would leave its band, and the
             * one number that currently says "the finishing model is wrong"
             * would agree with the model and go quiet. The gap stays visible
             * until goals are in band, and then it closes by itself.
             *
             * The same argument turned out to apply to every other price in
             * this function. See CARRY below for what happened when it was
             * ignored and they were fitted anyway.
             */
            OptKind.SHOT -> {
                val blockers = sim.opponentsNearLine(man, o.tx, o.ty)
                Value.shotValue(Pitch.attX(side, man.x), man.y) / (1f + 1.35f * blockers)
            }
            /*
             * A hoof goes to NOBODY. Whether a team-mate is near where it lands
             * is the entire question, and 0.45 against 0.15 is the answer.
             *
             * MEASURED, AND KNOWINGLY WRONG. MindCheck put 1,774 of these
             * against what happened: 1.6% are still ours afterwards, against
             * 18.8% predicted. The number is left alone anyway, for the reason
             * set out under CARRY below.
             */
            OptKind.CLEAR ->
                if (sim.matesWithin(o.tx, o.ty, 12f, side) > 0) 0.45f else 0.15f
            /*
             * A carry is priced at 82% and comes off 42% of the time.
             *
             * THE PRICES BELOW ARE MEASURABLY WRONG AND ARE LEFT WRONG. That is
             * a decision, it was tested, and this is the evidence.
             *
             * Every one of them was moved to what MindCheck measured — carry
             * 0.82 -> 0.30 over two rounds, clearance 0.45/0.15 -> 0.04/0.013,
             * cross -0.20 -> -0.58. The calibration table went almost entirely
             * green. The FOOTBALL got worse, over 40 matches against 40:
             *
             *              calibrated   left alone   band
             *   passes         1196.4        940.4   800-950
             *   completion      74.8%        80.3%   78-85%
             *   interceptions   227.8        139.9   16-22
             *   clearances       0.40        59.30   (a match with no
             *                                         clearances in it)
             *
             * The reason is the same one that protects the shot above, and it
             * generalises to every row: THESE ACTS ARE MISPRICED BECAUSE THEY
             * ARE MISIMPLEMENTED. A "carry" here is a ball struck twelve metres
             * into space with nobody's name on it, so of course it is lost 58%
             * of the time — a man running with the ball would not be. A
             * clearance dies at 1.6% because there is no loose ball and no
             * second ball, so it is claimed instantly by whoever is nearest,
             * and they are the opposition. Fitting the price to that does not
             * model a carry or a clearance; it deletes them, and the table
             * above is what deleting them costs.
             *
             * So the gap stays open and MindCheck prints it every build. It is
             * not a to-do list for these constants. It is a to-do list for the
             * ACTS — a real carry, and a loose ball — and when those land the
             * numbers move on their own.
             */
            OptKind.CARRY ->
                (0.82f - sim.opponentsWithin(o.tx, o.ty, 6f) * 0.09f).coerceIn(0.05f, 0.98f)
            else -> {
                var p = 0.94f - d * 0.006f - sim.opponentsNearLine(man, o.tx, o.ty) * 0.14f
                if (o.kind == OptKind.THROUGH_BALL) p -= 0.16f
                /*
                 * A cross was priced at 52% and finds a team-mate 14% of the
                 * time. Measured over 198 of them, and the -0.20 it used to
                 * carry was a guess that turned out to be a third of the truth.
                 * A ball hung into a crowded box is the hardest pass in the
                 * game and this engine has no aerial contest at all, so both
                 * halves of the gap are real.
                 */
                if (o.kind == OptKind.CROSS) p -= 0.20f
                p.coerceIn(0.05f, 0.98f)
            }
        }
    }

    /**
     * The code under which this man remembers having done this.
     *
     * Deliberately coarse: the KIND and WHO it went to, with no tick in it, so
     * that it accumulates across the match instead of being unique every time.
     * A tick in this string would make every code fire exactly once and the
     * memory would silently be no memory at all — the same shape as a wired
     * event that can never fire.
     */
    private fun memCode(o: Option): String {
        val to = o.receiver
        return if (to == null) "S${man.side}P${man.slot.id}.DID.${o.kind.name}"
        else "S${man.side}P${man.slot.id}.DID.${o.kind.name}>P${to.slot.id}"
    }

    /**
     * Price every option, then take the best with a little randomness.
     *
     * Returns the chosen one. Nothing outside this method decides anything.
     */
    fun decide(
        sim: MatchSim, options: ArrayList<Option>, pressure: Float, draw: Draw
    ): Option {
        val side = man.side
        val here = Value.possessionValue(Pitch.attX(side, man.x), man.y)

        for (o in options) {
            val p = pSuccess(sim, o)
            o.pSuccess = p
            o.repeats = draw.count(memCode(o))

            /*
             * EXPECTED VALUE, and the possession he forfeits is inside it.
             *
             * An earlier version scored reward as V(target) - V(here) and
             * subtracted a separate risk. That ignores the thing that makes
             * losing it expensive: you had the ball and now you do not. Because
             * a turnover in the opposition corner is nearly free, a hoof upfield
             * came out almost costless, and OptionCensus duly reported CLEAR as
             * the most chosen act in the final third — men hoofing it from the
             * edge of the box.
             */
            val worth = if (o.kind == OptKind.SHOT) 1f
                        else Value.possessionValue(Pitch.attX(side, o.tx), o.ty)
            o.reward = p * worth - here
            o.risk = (1f - p) *
                Value.turnoverCost(Pitch.attX(side, o.tx), o.ty) * RISK_WEIGHT

            /*
             * TACTICS ENTER HERE AND NOWHERE ELSE — now from two directions.
             *
             * His ROLE is what he is. His TEAM is what the side has decided
             * about this afternoon: how direct it wants to be, and which
             * channel has been working. Both are priors on the choice and
             * neither can reach into the act itself; a team mind that could
             * substitute an act would make his percentages describe nothing.
             *
             * They multiply, which is what independent priors do, and the
             * product is bounded because each factor is bounded.
             */
            val team = sim.teams[side]
            o.conformance = man.role.intentFor(o.kind) *
                team.intentFor(o.kind) *
                team.channelBias(draw, team.channelOf(side, o.ty))
        }

        val chosen = pick(options, pressure, draw)
        draw.note(memCode(chosen))
        return chosen
    }

    /**
     * BEST FIRST, WITH A LITTLE RANDOMNESS — by RANK, not by margin.
     *
     * The old chooser was a softmax over utility with a temperature. It worked,
     * but it was fragile in a way worth naming: expected values here live around
     * 0.004, so the temperature had to be 0.055 to mean anything, and any change
     * to the value surface silently changed how decisive every player in the
     * league was. A temperature that has to be retuned whenever the football
     * changes is a tuning knob pretending to be a model.
     *
     * Ranking fixes that. P(his nth choice) falls by a constant factor per rank,
     * so what matters is the ORDER of his options, which is what he actually
     * perceives. It cannot be broken by rescaling the value surface.
     *
     * Two multipliers ride on the weight rather than on the score, and both are
     * there because of the same bug found the hard way: most options have a
     * NEGATIVE expected value, since holding the ball has a worth you forfeit by
     * playing it, and multiplying a negative number by 2.6 makes it worse. Every
     * role intent came out inverted that way and six pairs of roles collapsed
     * into each other. A prior multiplies a probability. Never a utility.
     *
     *   conformance   his role's appetite for this kind of act
     *   repetition    1/(1+k n) — he has played this exact ball n times already
     */
    private fun pick(options: ArrayList<Option>, pressure: Float, draw: Draw): Option {
        val n = options.size
        if (n == 1) return options[0]

        // Rank by appeal, best first. Insertion sort over indices: n <= 26, and
        // it allocates nothing per possession.
        for (i in 0 until n) order[i] = i
        for (i in 1 until n) {
            val v = order[i]
            val u = options[v].utility
            var j = i - 1
            while (j >= 0 && options[order[j]].utility < u) { order[j + 1] = order[j]; j-- }
            order[j + 1] = v
        }

        /*
         * How decisive he is right now.
         *
         * `pressure` is METRES TO THE NEAREST OPPONENT, so a big number means he
         * is free. A man with time picks his best ball; a man with someone in
         * his ear is genuinely erratic. One mechanism, doing what composure and
         * decisions and a panic flag used to do badly and separately.
         */
        val decay = (0.55f - 0.028f * pressure.coerceIn(0f, 14f)).coerceIn(0.25f, 0.55f)

        var sum = 0.0
        var w = 1.0
        for (r in 0 until n) {
            val o = options[order[r]]
            val e = w * o.conformance / (1.0 + REPEAT_DAMP * o.repeats)
            weight[r] = e
            sum += e
            w *= decay
        }

        var roll = draw.next(CODE_PICK + man.side + "P" + man.slot.id).toDouble() * sum
        for (r in 0 until n) {
            roll -= weight[r]
            if (roll <= 0) return options[order[r]]
        }
        return options[order[0]]
    }

    companion object {
        /** How much a turnover weighs against what the ball is worth there. */
        const val RISK_WEIGHT = 1.6f

        /**
         * How hard a repeat is damped. At 0.35 the second identical ball is
         * three-quarters as attractive and the sixth is a little over half.
         *
         * It damps the CHOICE, not the percentage. Playing the same pass six
         * times does not make the seventh less likely to arrive — a defender
         * reading it would, and there is no defender who reads anything yet, so
         * claiming it as a probability would be inventing a mechanism that is
         * not in the engine.
         */
        const val REPEAT_DAMP = 0.35f

        /**
         * The occurrence counter inside [Draw] is per code, so this one string
         * is safe to reuse for every possession: each man's pick is its own
         * address and gets its own value every time it fires.
         */
        private const val CODE_PICK = "MIND.PICK.S"
    }
}
