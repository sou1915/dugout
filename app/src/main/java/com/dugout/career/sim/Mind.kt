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
     * Most of them are no longer coefficients at all. A pass, a carry and a
     * clearance are priced by the same RACE the engine uses to resolve every
     * touch — who reaches the ball first, in seconds — so they cannot drift
     * away from what the engine actually does. Only the shot is still a flat
     * judgement, and it is deliberately left wrong; the reason is under it.
     *
     * The one term visibly missing is being closed down: a man with a defender
     * on his shoulder completes fewer passes than a man in acres, and pressure
     * does not appear here at all.
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
             * A HOOF IS A RACE WITH NOBODY'S NAME ON IT.
             *
             * This was 0.45 with a team-mate near where it lands and 0.15
             * without — a headcount, and measurably a fantasy: MindCheck put
             * 2,185 of them against what happened and 3.7% stayed ours. It was
             * left wrong on purpose while the act itself was misimplemented,
             * because fitting the number would have deleted clearances from
             * football rather than modelled them.
             *
             * That reason has expired. The race model prices exactly the
             * question a hoof asks — our best man against their best man, to
             * the place it lands — and it is the same mechanism the pass, the
             * carry and every claim in the match already use. Not a fitted
             * constant: the engine's own contest rule, asked about a ball with
             * no name on it.
             *
             * It matters more than it looks. Clearances had become the SECOND
             * most chosen act in the game, 2,268 of them, each one ending a
             * possession 96% of the time. A price that wrong on an act that
             * common is not a rounding error, it is the shape of the match.
             */
            OptKind.CLEAR ->
                (0.10f + 0.70f * sim.headStartAt(side, o.tx, o.ty)).coerceIn(0.03f, 0.9f)
            /*
             * ONCE, THESE WERE FLAT CONSTANTS AND WERE LEFT KNOWINGLY WRONG.
             *
             * Carry 0.82 against a measured 42%, clearance 0.45/0.15 against a
             * measured 1.6%. They were fitted to what MindCheck measured — and
             * the football got worse over 40 matches against 40:
             *
             *              fitted   left alone   band
             *   passes     1196.4        940.4   800-950
             *   completion  74.8%        80.3%   78-85%
             *   clearances   0.40        59.30   (a match with no clearances)
             *
             * The reason was that both acts were MISIMPLEMENTED: a "carry" was
             * a ball struck twelve metres into space with nobody's name on it,
             * and a clearance died instantly because there was no loose ball.
             * Fitting a price to that deletes the act rather than modelling it.
             *
             * Both have since been built properly — a man holds the ball now,
             * and a contest can break — so the prices are no longer constants
             * at all. They are races, below, and the gap closed by itself. That
             * is the whole argument for leaving a number visibly wrong instead
             * of tuning it: the wrongness was pointing at the act.
             */
            /*
             * AND THE SAME RACE DECIDES A CARRY.
             *
             * Pricing passes honestly made the one act still priced by a flat
             * constant the most attractive thing on the pitch: passes fell to
             * 493 a match and carries rose to 4,713 of 9,000 decisions. A model
             * is only as good as its WORST-priced option, because the chooser
             * finds it — that is the whole lesson of the predecessor's engine
             * arriving at one pass a match, in mirror image.
             *
             * So this is not a re-fitted constant either. It is the same
             * question asked of the man himself: he strikes it twelve metres
             * into space, and either he gets there first or somebody else does.
             * `opponentsWithin` was a headcount; this is a race, in seconds,
             * decided by the rule the engine already uses for every touch.
             */
            OptKind.CARRY ->
                (0.92f - REACH_COST * (1f - sim.headStart(man, o.tx, o.ty)))
                    .coerceIn(0.05f, 0.98f)
            else -> {
                /*
                 * WHO GETS THERE FIRST — the term that was missing entirely.
                 *
                 * `opponentsNearLine` prices being CUT OUT on the way. It does
                 * not price the ball arriving somewhere an opponent simply
                 * reaches before your man does, and MindCheck now says that is
                 * where almost all of it goes: of 3,832 failed passes into
                 * space, 86.9% ended with "opponent touched it first" and 1.1%
                 * with offside. A clear lane into a crowded spot was priced as
                 * a good ball.
                 *
                 * I very nearly fixed something else. Every price got much more
                 * optimistic-looking the moment tackles landed, and the obvious
                 * story was that his mind models one way of losing the ball and
                 * the world now has five. The table says tackles account for
                 * NONE of it — a tackle happens after a team-mate has already
                 * received, so it is not a failed pass at all. The story was
                 * wrong and only the split told me.
                 *
                 * The model is not a new fitted weight. It is the engine's OWN
                 * contest rule, which decides every claim in the match: who
                 * gets there first, in seconds. A half-second head start for
                 * your man is a good ball; a half-second head start for his
                 * marker is not.
                 */
                var p = 0.94f - d * 0.006f - sim.opponentsNearLine(man, o.tx, o.ty) * 0.14f
                p -= REACH_COST * (1f - sim.headStart(o.receiver, o.tx, o.ty))

                /*
                 * AND HE KNOWS WHERE THE LINE IS — FOR THE MAN, NOT THE BALL.
                 *
                 * 23% of every failed through ball was offside and nothing here
                 * had heard of the law. A player who cannot see an offside line
                 * plays men into it all afternoon and never learns, because his
                 * percentage does not contain the thing that decides it. Not a
                 * penalty: if the man is beyond the second-last defender when
                 * the ball is struck, the flag goes up. That is the rule, not a
                 * risk.
                 *
                 * THE FIRST VERSION TESTED THE WRONG POINT, and OptionMix found
                 * it in one line: THROUGH_BALL offered 592 times a match and
                 * taken ZERO. Not rare — never.
                 *
                 * Offside is judged on where the RECEIVER stands at the moment
                 * the ball is played. This tested where the BALL WAS AIMED —
                 * and a through ball is aimed nine metres beyond him on
                 * purpose, because that is what the act IS. So the mind flagged
                 * every through ball as offside while the rule in MatchSim,
                 * which tests the man, would have allowed it. The engine and
                 * the player were reading the same law off different pages.
                 *
                 * A ball into space beyond the last defender, run onto by a man
                 * who was level when it was struck, is the most legal thing in
                 * football and it had been made impossible.
                 */
                val r = o.receiver
                if (r != null && sim.wouldBeOffside(side, r.x, r.y)) p = OFFSIDE_PRICE
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

            /*
             * A CARRY DOES NOT FORFEIT THE BALL, AND ITS RISK WAS COUNTED TWICE.
             *
             * Every other act here gives the ball away: you strike it, and it
             * either finds someone or it does not. A carry gives it away to
             * nobody — he still has it at the end of it. The only way a carry
             * loses possession is that somebody comes and takes it, and that is
             * modelled in its own place, in MatchSim.challenged(), with its own
             * odds and its own foul and its own card.
             *
             * So charging a carry a full turnover cost priced the same danger a
             * second time, and it is why carrying happened 129 times a match
             * against a real 976 (docs/ANCHORS.md). In football a carry is the
             * most common action on the pitch NOT because it gains much — it
             * gains almost nothing — but because it is nearly free. Our model
             * agreed it gains almost nothing and then charged it like a pass.
             *
             * The residue is not zero: carrying into a crowd really is worse
             * than carrying into space, and pSuccess already measures that as a
             * race. It is charged at a fraction of a pass's weight, and that
             * fraction is the one number here — everything else follows from
             * removing a double count.
             */
            val weight = if (o.kind == OptKind.CARRY) CARRY_RISK else RISK_WEIGHT
            o.risk = (1f - p) *
                Value.turnoverCost(Pitch.attX(side, o.tx), o.ty) * weight

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
         * What a carry is charged instead, because losing it is a separate
         * event with its own model.
         *
         * Swept against the REAL carry count rather than against a row anyone
         * finds convenient, 14 matches each:
         *
         *   risk  carries  strk/pos  1-strike  completion  goals  passes
         *   1.60    127.9      2.58     40.9%       59.3%   5.50    709
         *   0.80    201.6      2.86     36.9%       60.1%   4.79    705
         *   0.40    338.1      3.65     29.6%       57.1%   3.00    652
         *   0.25    371.4      4.10     28.8%       56.6%   2.64    633
         *   0.10    404.7      4.54     25.8%       58.2%   2.93    617
         *   0.00    420.6      4.51     27.3%       57.3%   3.57    610
         *   REAL    976.0      8.18      9.4%       84.9%   3.04   1183
         *
         * Everything moves the right way and nothing was told to. Carrying
         * three times more often, possessions holding together twice as long,
         * one-strike possessions falling by a third — all of it out of deleting
         * a double count, with the chooser, the randomness and the roles
         * untouched.
         *
         * 0.10 rather than 0.00, because zero would say carrying into a crowd
         * is exactly as safe as carrying into space, and it is not — pSuccess
         * already measures that as a race and this is what makes it count for
         * anything.
         */
        @JvmField var CARRY_RISK = 0.10f

        /**
         * What a ball played to a man standing offside is worth. Not zero: the
         * engine gives the flag a 0.4 m tolerance and a linesman is a linesman.
         */
        const val OFFSIDE_PRICE = 0.05f

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
         * How much of the price a lost race to the ball costs.
         *
         * At 1.0 a pass into a spot the opponent reaches first would be priced
         * at zero, which is too strong — he can still shield it, or the ball
         * can run kindly.
         *
         * Swept, 20 matches each. Calibration error is the mean gap between
         * what players predicted and what happened, weighted by how often each
         * act was chosen:
         *
         *   cost   calib err   goals  passes  completion  tackles
         *   0.15       24.9%    1.70   558.2      59.8%     30.0
         *   0.25       20.5%    2.20   614.3      60.6%     28.0
         *   0.35       17.5%    2.75   641.0      61.4%     32.2
         *   0.45       15.5%    5.10   656.4      61.6%     27.7
         *   0.60       13.7%    4.20   675.6      62.0%     26.6
         *
         * 0.35 SHIPS, AND CALIBRATION DOES NOT AGREE. It falls monotonically
         * with this number, so it does not pick an interior point at all — it
         * just says "higher", and it would keep saying that to a mind so
         * pessimistic it never passed. That is the flaw in using an internal
         * consistency measure to choose: a model can agree with itself better
         * while playing worse football.
         *
         * Goals and tackles are anchored OUTSIDE this engine, and at 0.35 both
         * sit inside their §5 bands — goals 2.75 against 2.6-2.9 and tackles
         * 32.2 against 30-36, the first time two rows have passed at once. The
         * external anchor wins, and the tension is written down rather than
         * hidden, because 13.7% is a better calibration number and somebody
         * will find it later and wonder.
         */
        @JvmField var REACH_COST = 0.35f

        /**
         * The occurrence counter inside [Draw] is per code, so this one string
         * is safe to reuse for every possession: each man's pick is its own
         * address and gets its own value every time it fires.
         */
        private const val CODE_PICK = "MIND.PICK.S"
    }
}
