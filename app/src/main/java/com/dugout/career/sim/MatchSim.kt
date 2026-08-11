package com.dugout.career.sim

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * What the shape fingerprint is computed from, sampled once a second.
 *
 * Plain numbers, so the simulation does not have to know that a gate exists.
 */
class ShapeSnapshot {
    /** Distance from a side's deepest outfield man to his highest, metres. */
    @JvmField val blockDepth = FloatArray(2)
    /** Men goal-side of the ball when it is in that side's own box. */
    @JvmField val goalSide = FloatArray(2)
    @JvmField val goalSideValid = BooleanArray(2)
    /** Outfield men per lane x band, in each side's attacking frame. */
    @JvmField val occupancy = Array(2) { FloatArray(Pitch.LANES * Pitch.BANDS) }
}

/**
 * BUILD ORDER STEPS 2 AND 3: a pitch, ball flight, twenty-two men who move to
 * targets, and every event the engine can honestly report.
 *
 * There is still no decision layer. What moves the ball is a marked
 * placeholder, so the only events that fire are the ones the engine ACTUALLY
 * determines — geometry, and who touched it. Nothing here classifies a random
 * strike into a through ball or a cross, because §7 of the brief says not to
 * write a classifier that invents variety the football does not have, and a
 * cross requires an intent that does not exist yet.
 *
 * The result is a census that is mostly zeros. That is the deliverable.
 */
class MatchSim(
    seed: Long,
    val homeShape: Formation = Formation.preset("4-3-3"),
    val awayShape: Formation = Formation.preset("4-4-2")
) {

    companion object {
        const val DT = 0.1f
        const val MINUTES = 90
        const val SECONDS = MINUTES * 60
        const val HALF_SECONDS = SECONDS / 2
        const val BUCKET_SECONDS = 300
        const val BUCKETS = SECONDS / BUCKET_SECONDS

        /**
         * How much of the ball's displacement from the centre the block
         * follows. Not a tuned magic number so much as the thing that makes a
         * block a block: at 0 the shape ignores the ball, at 1 every man
         * chases it.
         */
        const val SLIDE_X = 0.55f
        const val SLIDE_Y = 0.45f

        /** Ticks between claim-time recomputations. 3 ticks is 0.3 s. */
        const val CLAIM_EVERY = 3

        /** Ticks between asking a carrying man again. 5 ticks is half a second. */
        const val CARRY_EVERY = 5
        /** How long anyone may keep walking with it before he must decide. */
        const val CARRY_MAX_S = 3.0f

        /** How near his man a ball must die to count as having reached him. */
        const val INTERCEPT_GAP = 7f

        /** Seconds of advantage that count as winning the race outright. */
        const val HEAD_START_WINDOW = 0.6f

        /** Metres from the line of a pass within which a defender can read it. */
        const val READS_IT_M = 2.5f

        /** How far beyond the second-last man is actually offside, metres. */
        const val OFFSIDE_TOLERANCE = 0.4f
        /** How far short of the line an attacker aims to stay. */
        const val HOLD_MARGIN = 1.2f

        /** A struck ball shorter than this is short. Length is a fact. */
        const val SHORT_PASS_M = 24f

        /** How close an opponent must be for a first touch to be contested. */
        const val CONTEST_M = 3.0f

        /** Within this an opponent can challenge for the ball, metres. */
        const val TACKLE_M = 2.4f

        /** A ball that got at least this high can be attacked in the air. */
        const val AERIAL_MIN_H = 1.6f
        /** How near where it lands a man must be to go up for it. */
        const val AERIAL_M = 3.2f
        /** How often two men going up together give a foul away. */
        const val AERIAL_FOUL = 0.13f
        /**
         * How often a challenge is made at all, at zero range.
         *
         * 0.55 gave 37.6 a match against a §5 band of 30-36; 0.48 landed
         * inside it. That is the only number here chosen to hit a row, and it
         * is defensible for this quantity specifically — unlike a physical
         * constant, "how often a defender goes in" has no meaning independent
         * of how often defenders go in, and the band is its anchor.
         *
         * RE-SET WHEN CARRYING ARRIVED, because 0.48 was measured in a world
         * where a man held the ball for one tick. Now he holds it for up to
         * three seconds and is challenged on every re-ask, so the same rate
         * produced 58.9 tackles a match. Re-swept, 14 matches each:
         *
         *   rate  tackles  fouls  goals  completion  strk/pos
         *   0.48     58.9   8.50   2.00       63.5%      5.01
         *   0.30     34.9   4.86   2.43       62.3%      5.10
         *   0.20     23.1   2.29   1.93       62.3%      5.18
         *   0.12     14.4   2.21   2.29       62.0%      5.23
         *   REAL     30.8  22.00   3.04       84.9%      8.18
         *
         * 0.30 ships: 34.9 against a real 30.8 and a band of 30-36.
         *
         * Fouls stay far short at 4.86 against 22, and no rate here can fix
         * that — a foul rate that turned 35 tackles into 22 fouls would be 0.63
         * per challenge, which is not football. Real matches draw their fouls
         * from off-ball challenges, shirt pulls, aerial duels and late contact,
         * and this engine still has exactly one source. The gap is the missing
         * sources.
         */
        @JvmField var TACKLE_RATE = 0.30f
        /** A slide wins it more often and fouls far more often. */
        const val WIN_STAND = 0.55f
        const val WIN_SLIDE = 0.62f
        const val FOUL_STAND = 0.09f
        const val FOUL_SLIDE = 0.22f
        /** Booking rate for a foul that stops something, and for one that does not. */
        const val CARD_STOPPING = 0.16f
        const val CARD_ROUTINE = 0.05f
        /**
         * Chance a dead-heat contest breaks loose.
         *
         * Swept, 30 seeds each, nothing else moving:
         *
         *   rate  goals  passes  completion  intercept  loose  recoveries
         *   0.55   1.27   780.4      57.1%      100.6   83.4       325.0
         *   0.35   1.10   799.0      59.5%      109.8   53.3       308.6
         *   0.20   1.00   805.2      60.9%      114.7   31.3       292.4
         *   0.10   0.73   819.7      61.5%      119.9   16.2       282.4
         *   0.00   0.93   826.6      63.0%      128.3    0.0       267.4
         *
         * Completion is monotone and clean; goals trend up but 30 matches at
         * one a match is thirty goals, so 1.10 and 1.27 are the same number.
         *
         * 0.35 SHIPS. The anchor is duel counts — a top-flight match contains
         * something like 90-110 ground duels, a good share of them 50-50s for a
         * broken ball — which puts a defensible range somewhere between 50 and
         * 85 of these, i.e. between 0.35 and 0.55 here. Both ends are arguable,
         * the goals difference between them is noise, so the tie-break goes to
         * the end that costs less of the two rows already out of band. That is
         * a judgement, and it is stated rather than buried: the anchor is the
         * softest one in this codebase.
         *
         * 0.20 would put passes attempted back inside its band at 805. It is
         * not taken. Choosing a physical constant because it moves an
         * acceptance row is the exact circularity the brief forbids, and the
         * row is only one unit outside.
         */
        @JvmField var LOOSE_BASE = 0.35f
        /** Metres of head start that damp that chance by 1/e. */
        const val LOOSE_SCALE = 1.6f
        /** How hard a broken ball squirts away. */
        const val LOOSE_PACE_LO = 3f
        const val LOOSE_PACE_HI = 9f

        const val CROSSBAR_M = 2.44f

        /** How far goal-side of the ball the deepest defender sits, metres. */
        const val DEFEND_STANDOFF = 8f
        /** Metres a defending man may stand upfield of the ball. */
        const val GOAL_SIDE_ALLOWANCE = 4f

        /** Metres of delivery error at full error. */
        const val DELIVERY_SCALE = 11f

        /** Half the width a body actually blocks, metres. */
        const val BLOCK_HALF_WIDTH = 0.75f

        /** How far across his line a keeper gets to a struck ball, metres. */
        const val GK_REACH = 2.5f
        /** A ball along the ground is easier to go down to. */
        const val GK_LOW_BONUS = 0.7f

        /** Angles a supporting man tries around the carrier. */
        const val SUPPORT_ANGLES = 7

        /**
         * How far toward the support point he goes. Not 1.0: at 1.0 the three
         * nearest men stop being a left-back, a holding midfielder and a
         * winger and become three identical satellites, and RoleCheck would
         * report them as decoration — correctly.
         */
        const val SUPPORT_BLEND = 0.55f
    }

    /**
     * Outcomes, resolved BY CODE rather than by queue position.
     *
     * Every draw names itself — match, tick, player, purpose — so adding a new
     * random decision anywhere leaves every existing one byte-identical. Under
     * the old sequential Rng a single new draw shifted the entire match, which
     * made "what did my change actually do?" unanswerable. DrawCheck gates the
     * property and CI enforces it.
     */
    @JvmField val draw = Draw(seed)

    @Suppress("unused")
    private val presentation = Rng(seed xor 0x5eed_0000_0000_0001L)

    val ball = Ball()
    val men = ArrayList<Man>(22)

    /**
     * ONE MIND PER SIDE, ABOVE THE ELEVEN.
     *
     * It never chooses an act for anybody — it changes the weight of an option
     * and it hands off-ball men somewhere to be. See [TeamMind].
     */
    @JvmField val teams = arrayOf(TeamMind(0), TeamMind(1))
    @JvmField val events = EventLog()

    /** [side][0 = without the ball, 1 = with it][slot] */
    private val anchorAx = Array(2) { Array(2) { FloatArray(11) } }
    private val anchorAy = Array(2) { Array(2) { FloatArray(11) } }

    /**
     * Which side has it, as a BINARY flag derived from who struck it last.
     *
     * This is not the six-phase model — that is step 6. It is the minimum a
     * role needs to have an in-possession signature at all: without it, an
     * overlapping full-back and an inverted full-back are the same man.
     */
    val possessionSide: Int get() = lastStriker?.side ?: restartSide

    /** Set by a harness to sample the option set. Never read by the engine. */
    @JvmField var onCarry: ((Man, Int, Float) -> Unit)? = null

    @JvmField val goals = IntArray(2)
    @JvmField val shots = IntArray(2)
    @JvmField val cards = IntArray(2)
    @JvmField val corners = IntArray(2)

    var clock = 0f
        private set

    private val rest = FloatArray(3)
    private var claimant: Man? = null

    /** Who is next to touch it. What a press aims at. */
    val nextOnBall: Man? get() = claimant
    private var stillFor = 0f
    private var ticks = 0
    private var wasMoving = false

    /** Deepest compiled anchor per side — where the defensive line starts. */
    private val deepestAx = FloatArray(2)

    private val pressBuf = FloatArray(2)
    private val supportX = FloatArray(22)
    private val supportY = FloatArray(22)
    private val supportSet = BooleanArray(22)

    /** Who struck it last, and from where — the two facts a touch resolves against. */
    private var lastStriker: Man? = null
    private var lastKind: OptKind? = null
    private var lastReceiver: Man? = null
    private var strikeX = 0f
    private var strikeY = 0f

    /**
     * WHO WAS STANDING IN THE LINE OF IT WHEN IT WAS STRUCK.
     *
     * An interception is a defender reading a pass and taking it. It is not a
     * defender jogging over to a ball that ran out of steam eight metres short
     * of anybody — that is a recovery, and real football counts them apart.
     *
     * The old test could not tell the difference, because it asked a question
     * about WHERE THE BALL DIED: "did it stop more than seven metres from the
     * intended man?". With a mean delivery miss of 8.6 m that is true of about
     * half of all passes, so roughly half of every turnover was filed as an
     * interception and the row read 205 a match against a band of 16-22.
     *
     * The right question is about the DEFENDER, and it has to be asked at the
     * moment of the strike, before anyone knows how it ends: was he within
     * reach of the line the ball was going to travel? That cannot be answered
     * after the fact, which is why it is recorded here.
     */
    private val inTheLine = BooleanArray(22)

    private val optionBuf = ArrayList<Option>(Decide.MAX_OPTIONS + 2)

    /** Set by a harness to sample the CHOSEN option. Never read by the engine. */
    @JvmField var onChoice: ((Man, Option, Int, Float) -> Unit)? = null

    /** The whole priced table, for anything that wants to show his thinking. */
    @JvmField var onAppraisal: ((Man, ArrayList<Option>, Option) -> Unit)? = null

    /**
     * WHAT HE PREDICTED, AND WHAT HAPPENED — (kind, his percentage, did it work).
     *
     * This is the only thing that stops [Mind.pSuccess] being decoration. A
     * player announcing "78%" is worth nothing until 78% of those come off, and
     * the predecessor is full of numbers between 0 and 1 that were never once
     * held against an outcome. `MindCheck` buckets these and prints predicted
     * against actual.
     */
    @JvmField var onOutcome: ((OptKind, Float, Boolean, String) -> Unit)? = null

    /** What the man on the ball last committed to, still waiting on an answer. */
    private var pendingKind: OptKind? = null
    private var pendingP = 0f
    private var pendingSide = -1
    private var pendingY = 0f

    /**
     * Resolve the open prediction, once. A second call before another strike
     * does nothing, so every site that ends a possession can say its piece
     * without the sites having to know about each other.
     */
    /**
     * @param worked did the striker's intention come off
     * @param decided did the ball actually end up with SOMEBODY
     *
     * The two are not the same question, and conflating them credited a press
     * with winning balls it had only broken up: a spilled ball answers the
     * passer's prediction (no, it did not reach him) while deciding nothing at
     * all about who has it. PRESS_WON read 45 a match on that mistake.
     */
    /**
     * @param why WHICH mechanism ended it. Added because I was about to fix a
     *   cause I had not measured.
     *
     * Every price a player puts on an act got much worse the moment tackles and
     * offside existed — PASS_SPACE from 8 points out to 27. The obvious story is
     * that his mind models one way of losing the ball and the world now has
     * five. That story is probably right and it is still a GUESS: three other
     * things changed in the same stretch, including the strike physics, and
     * guessing at causes is what has cost this session four reverted commits.
     *
     * So the engine says which one, and MindCheck prints the split per act.
     */
    private fun settle(worked: Boolean, decided: Boolean = true, why: String = "-") {
        val k = pendingKind ?: return
        pendingKind = null
        onOutcome?.invoke(k, pendingP, worked, why)

        // The same fact, filed twice: once as a man's prediction coming off,
        // once as the side's record of a channel. The second is the only thing
        // a team knows that no player in it can see.
        if (pendingSide in 0..1) {
            val t = teams[pendingSide]
            t.noteChannel(draw, t.channelOf(pendingSide, pendingY), worked)
            if (decided) teams[1 - pendingSide].pressResolved(this, worked.not(), clock)
        }
    }

    /** A dead ball belongs to one side until it is put back in play. */
    private var restartSide = -1
    private var cornerPending = false
    private var halfDone = false

    /**
     * ARRIVE BY, measured where it means something.
     *
     * The mean gap between a man and his target across a whole match measures
     * a chase, because the target slides with the ball every tick. The number
     * that matters is the gap AT THE MOMENT THE BALL ARRIVES — the predecessor
     * had exactly this measurement at a corner (7.6 m before the fix, 3.6 m
     * after) and it was the number that proved the fix.
     */
    /**
     * WHERE THE BALL ARRIVES relative to the man it was aimed at.
     *
     * Measurement only, and it settled a question three commits of guessing
     * could not. Mean miss 8.57 m; only 36.2% of intended passes land within
     * two metres of the man.
     *
     * A controlled experiment then ran: the delivery error scale was cut from
     * 26 m to 9 m — inherited from the inverted formula and far too large — and
     * the miss BARELY MOVED, 8.57 -> 8.23, completion 69.5 -> 70.6. So the miss
     * is not delivery error at all.
     *
     * It is that a pass is aimed at where the receiver IS, and he has moved by
     * the time it arrives. The ball is being played behind him. Leading the
     * pass — aiming at his position plus his velocity over the flight time — is
     * the fix, and it is a piece of football rather than a constant.
     */
    @JvmField var deliverySum = 0.0
    @JvmField var deliveryCount = 0
    @JvmField var deliveryNear = 0

    /**
     * THE 8.6 METRES, SPLIT IN TWO. This is the measurement nobody had taken.
     *
     * Three separate attempts have now been made to fix pass completion by
     * changing what happens when the ball ARRIVES — a 50-50 contest, a loose
     * ball, and a man taking it in flight — and all three hit the same wall,
     * because a ball that was never near the intended man cannot be rescued by
     * any rule about who touches it.
     *
     * But "the miss is 8.6 m" does not say whose fault it is, and the two
     * possible culprits need different fixes:
     *
     *   ballVsAim   where the ball STOPPED against where it was AIMED. This is
     *               physics — strike speed, drag, delivery jitter.
     *   aimVsMan    where it was aimed against where the man ACTUALLY WAS when
     *               it got there. This is prediction — he was led to a place he
     *               did not go.
     *
     * They sum to the miss. Whichever is larger is the real open job, and
     * everything else about passing has been guesswork until this number
     * exists.
     */
    /**
     * ONE PASS, END TO END. The trace, not another hypothesis.
     *
     * Two measurements of the same strike disagree: an isolated probe of the
     * flight physics is accurate to under a metre, and the match reports 10.21
     * m between where the ball stopped and where it was aimed. Both cannot be
     * true, so one of them is lying, and after four wrong causal guesses in one
     * session the way to find out is not a fifth guess.
     *
     * Every number that goes into a delivery is recorded here and printed by
     * PassTrace, worst first. The three that matter are `solvedFor` — the
     * distance the pace was computed for — `needed`, the distance the ball
     * actually had to cover from where it lay, and `travelled`. If those three
     * do not account for the gap, the gap is in the measurement itself, and
     * that is a finding too.
     */
    class Delivery {
        @JvmField var carrierX = 0f
        @JvmField var carrierY = 0f
        /** Where the ball lay when it was struck — NOT the same as the man. */
        @JvmField var ballX = 0f
        @JvmField var ballY = 0f
        /** The option's target, before delivery error. */
        @JvmField var aimX = 0f
        @JvmField var aimY = 0f
        /** Where it was actually struck at, after error. */
        @JvmField var shotX = 0f
        @JvmField var shotY = 0f
        @JvmField var mps = 0f
        @JvmField var loft = 0f
        @JvmField var restX = 0f
        @JvmField var restY = 0f
        @JvmField var manX = 0f
        @JvmField var manY = 0f
        @JvmField var kind = ""
    }

    private val delivery = Delivery()

    /** Set by a harness. Fires once per intended pass, when it resolves. */
    @JvmField var onDelivery: ((Delivery) -> Unit)? = null

    @JvmField var ballVsAimSum = 0.0
    @JvmField var aimVsManSum = 0.0
    @JvmField var splitCount = 0
    private var aimX = 0f
    private var aimY = 0f

    @JvmField var arriveGapSum = 0.0
    @JvmField var arriveGapCount = 0

    init {
        for (side in 0..1) {
            val shape = if (side == 0) homeShape else awayShape
            shape.compile(false, anchorAx[side][0], anchorAy[side][0])
            shape.compile(true, anchorAx[side][1], anchorAy[side][1])
            for (s in shape.slots) men.add(Man(side, s))
        }
        for (side in 0..1) {
            var lo = Float.MAX_VALUE
            for (m in men) if (m.side == side && !m.isKeeper)
                if (anchorAx[side][0][m.slot.id] < lo) lo = anchorAx[side][0][m.slot.id]
            deepestAx[side] = lo
        }
        resetPositions()
        kickOff(0)
        updateClaim()
    }

    private fun resetPositions() {
        for (m in men) {
            val ax = anchorAx[m.side][0][m.slot.id]
            val ay = anchorAy[m.side][0][m.slot.id]
            m.place(Pitch.absX(m.side, ax), Pitch.absY(m.side, ay))
        }
    }

    private fun kickOff(side: Int) {
        ball.place(Pitch.LENGTH * 0.5f, Pitch.WIDTH * 0.5f)
        events.fire(Ev.KICK_OFF, side)
        deadBall(side)
    }

    /** Park the ball for [side] to put back in play. */
    private fun deadBall(side: Int) {
        carrier = null
        carryFor = 0f
        settle(pendingKind != OptKind.SHOT && side == pendingSide, why = "dead ball")
        restartSide = side
        lastStriker = null
        lastReceiver = null
        stillFor = 0f
        wasMoving = false
    }

    /**
     * THE MAN ON THE BALL DECIDES.
     *
     * The step 2 placeholder that struck at a random point is GONE, as it was
     * promised to be — deleted at step 5 rather than grown into a decision
     * layer, which is how the predecessor ended up with a hand-weighted sum it
     * could not fix by re-weighting.
     *
     * He now generates concrete options, scores them on four axes kept
     * separate, and one is chosen by softmax rather than maximised. What he
     * intended is then handed to the execution model: the ball is STRUCK with a
     * weight and a direction and becomes an object again, so whether it reaches
     * the man he picked is physics and claim times, not a success roll.
     */
    /**
     * HE HAS THE BALL. Now, and for as long as he keeps it.
     *
     * This is the change the whole engine was waiting for. Until now a man
     * RECEIVED AND STRUCK IN THE SAME TICK: there was no state in which anybody
     * possessed the ball, only a ball in flight and a ball being hit. Measured
     * against real event data (docs/ANCHORS.md), that showed up everywhere at
     * once:
     *
     *                          real    this engine
     *   strikes a possession   8.18    1.98
     *   one-strike possessions 9.4%    48.8%
     *   possessions a match     151    459
     *   CARRIES a match         976    0
     *
     * A carry is nearly as common as a pass in football and there were none
     * here, because a carry needs somewhere to live: a man who owns the ball
     * across ticks. Take-ons, shielding, turning, drawing a foul and holding
     * play up all need the same thing, which is why nine events sat unwired
     * with no route to reach them.
     *
     * So he is asked repeatedly now, every [CARRY_EVERY] ticks — roughly a
     * touch every few strides — and one of the answers is "keep it".
     */
    private fun onBall(m: Man) {
        if (cornerPending) { events.fire(Ev.CORNER_TAKEN, m.side); cornerPending = false }

        val pressure = pressureOn(m)
        Decide.generate(this, m, pressure, optionBuf)
        onCarry?.invoke(m, optionBuf.size, pressure)

        if (optionBuf.isEmpty()) {
            // Nothing available at all. Honest, and rare — it is a clearance.
            val ax = (Pitch.attX(m.side, m.x) + 40f).coerceAtMost(Pitch.LENGTH - 2f)
            optionBuf.add(Option(OptKind.CLEAR, null,
                Pitch.absX(m.side, ax), Pitch.WIDTH * 0.5f, 24f, 20f))
        }

        // The engine does not score anything. It hands him what is available
        // and he answers — with his own percentages and his own memory of what
        // he has already tried in this match.
        val chosen = m.mind.decide(this, optionBuf, pressure, draw)
        onChoice?.invoke(m, chosen, optionBuf.size, pressure)
        onAppraisal?.invoke(m, optionBuf, chosen)

        /*
         * KEEPING IT IS A REAL ANSWER NOW, not a ball struck into space.
         *
         * The old CARRY struck the ball twelve metres ahead with nobody's name
         * on it, which is why MindCheck priced it at 79% and measured 32%: it
         * was a bad long pass wearing the word "carry". Here he simply keeps
         * it, walks it toward the same target, and is asked again shortly.
         *
         * The bound matters. Without one a man in space carries forever and the
         * match becomes one long dribble, so it ends when he has held it long
         * enough — and every re-ask can end it sooner.
         */
        if (chosen.kind == OptKind.CARRY && carryFor < CARRY_MAX_S) {
            if (carrier !== m) {
                carrier = m
                carryFor = 0f
                events.fire(Ev.CARRY, m.side)
                // Owning it ends the flight: nothing is in the air any more.
                settle(worked = true)
                lastStriker = m
                lastKind = OptKind.CARRY
                lastReceiver = null
            }
            carryTargetX = chosen.tx
            carryTargetY = chosen.ty
            return
        }
        carrier = null
        carryFor = 0f

        // He has committed. What he thinks will happen is now on the record and
        // waiting to be contradicted.
        settle(false, why = "never resolved")
        pendingKind = chosen.kind
        pendingP = chosen.pSuccess
        pendingSide = m.side
        pendingY = chosen.ty

        fireIntent(m, chosen)

        // Execution is separate from choice. He aims; error, flight and whoever
        // reaches it first decide what actually happens.
        /*
         * INVERTED, AND LOAD-BEARING.
         *
         * `pressureOn` returns METRES TO THE NEAREST OPPONENT, so a large value
         * means a man is FREE. The first version read `1 - 0.055 * pressure`,
         * giving a man in acres of space the biggest delivery error and a man
         * being closed down a perfect pass — exactly backwards.
         *
         * It could not be fixed alone: the wild passing was the only thing
         * standing in for a goalkeeper who did not exist, and correcting it by
         * itself took the score to 73.9 goals a match. It lands here together
         * with the save model below, which is why this is one commit.
         */
        val space = pressure.coerceIn(0f, 14f)
        val err = (0.185f - 0.0125f * space).coerceAtLeast(0.012f)
        // Re-tested AFTER the overshoot was fixed. The first test of this
        // scale returned nothing, but it ran while every pass was overshooting
        // by five metres, which swamped it — a null result measured under a
        // confound is not a null result.
        val who = "T$ticks.S${m.side}P${m.slot.id}"
        val jitterX = draw.range("$who.DELIVERY_X", -1f, 1f) * err * DELIVERY_SCALE
        val jitterY = draw.range("$who.DELIVERY_Y", -1f, 1f) * err * DELIVERY_SCALE
        val tx = (chosen.tx + jitterX).coerceIn(-4f, Pitch.LENGTH + 4f)
        val ty = (chosen.ty + jitterY).coerceIn(-4f, Pitch.WIDTH + 4f)

        // A BLOCK IS GEOMETRY. If a defender's body is in the line of the
        // shot, it hits him — and the ball carries on from there as a loose
        // one, which is where rebounds come from.
        if (chosen.kind == OptKind.SHOT) {
            val blocker = nearestOnLine(m, chosen.tx, chosen.ty)
            if (blocker != null) {
                events.fire(Ev.SHOT_BLOCKED, blocker.side)
                settle(false, why = "blocked")
                ball.place(blocker.x, blocker.y)
                val bc = "T$ticks.S${blocker.side}P${blocker.slot.id}.BLOCK"
                Physics.strike(
                    ball,
                    draw.range("$bc.X", -1f, 1f), draw.range("$bc.Y", -1f, 1f),
                    draw.range("$bc.PACE", 4f, 11f), draw.range("$bc.LOFT", 0f, 14f)
                )
                lastStriker = blocker
                lastKind = OptKind.CLEAR
                // The man the SHOT was never aimed at must not stay on the
                // books — a stale receiver here quietly poisons the delivery
                // measurement with the distance to somebody irrelevant.
                lastReceiver = null
                strikeX = ball.x
                strikeY = ball.y
                restartSide = -1
                stillFor = 0f
                return
            }
        }

        markTheLine(m, tx, ty)
        markOffside(m, chosen.receiver)
        lastStriker = m
        lastKind = chosen.kind
        lastReceiver = chosen.receiver
        aimX = tx
        aimY = ty
        if (onDelivery != null) {
            delivery.carrierX = m.x; delivery.carrierY = m.y
            // ball.x, NOT strikeX — strikeX is assigned two lines below this,
            // so reading it here recorded where the PREVIOUS ball was struck.
            // That one stale field read 14.03 m and I believed it: it looked
            // exactly like a man kicking a ball he was nowhere near, and I
            // wrote the fix for that before checking the instrument. The
            // instrument was the thing that was broken.
            delivery.ballX = ball.x; delivery.ballY = ball.y
            delivery.aimX = chosen.tx; delivery.aimY = chosen.ty
            delivery.shotX = tx; delivery.shotY = ty
            delivery.mps = chosen.mps; delivery.loft = chosen.loft
            delivery.kind = chosen.kind.name
        }
        strikeX = ball.x
        strikeY = ball.y
        restartSide = -1
        Physics.strike(ball, tx - ball.x, ty - ball.y, chosen.mps, chosen.loft)
        stillFor = 0f
        peakHeight = 0f
    }

    /**
     * Record which opponents were close enough to the line to read it.
     *
     * Point-to-segment distance, the same geometry [laneClear] uses, against
     * the same threshold a body blocks a shot at. A man beyond it did not
     * intercept anything; he collected it.
     */
    private fun markTheLine(striker: Man, tx: Float, ty: Float) {
        java.util.Arrays.fill(inTheLine, false)
        val dx = tx - ball.x
        val dy = ty - ball.y
        val len2 = dx * dx + dy * dy
        if (len2 < 0.01f) return
        for (i in men.indices) {
            val o = men[i]
            if (o.side == striker.side) continue
            val t = (((o.x - ball.x) * dx + (o.y - ball.y) * dy) / len2).coerceIn(0f, 1f)
            inTheLine[i] = Physics.dist(o.x, o.y, ball.x + dx * t, ball.y + dy * t) < READS_IT_M
        }
    }

    /**
     * WAS THE MAN HE PLAYED IT TO BEYOND THE LAST DEFENDER WHEN HE STRUCK IT?
     *
     * Offside is the only law in football that is decided at a single INSTANT —
     * the moment the ball is played, not the moment it arrives — and that is
     * exactly why it could not be bolted on later. Every other event in this
     * engine resolves when the ball comes to rest. This one has to be recorded
     * at the strike and remembered, like the passing line above it.
     *
     * The second-last opponent is the line, the halfway line caps it, and a man
     * level is on. There is no phase-of-play judgement and no interfering-with-
     * play test, because both need an intent model this engine does not have.
     * What is here is the geometry, which is most of the law and all of the
     * part that shapes a defensive line.
     *
     * Wiring it does something no counter shows: it makes the block's HEIGHT
     * cost something. Until now a high line was free — there was no punishment
     * for stepping up and no reward for playing anyone off. That is a tactical
     * lever that was inert in both directions.
     */
    private fun markOffside(striker: Man, receiver: Man?) {
        offsideAgainst = null
        val r = receiver ?: return
        if (r === striker) return

        val side = striker.side
        val rAttX = Pitch.attX(side, r.x)
        // His own half is always onside.
        if (rAttX <= Pitch.LENGTH * 0.5f) return

        // The second-last opponent, keeper included. First and second deepest
        // in OUR attacking frame means highest x for them.
        var last = -Float.MAX_VALUE
        var second = -Float.MAX_VALUE
        for (o in men) {
            if (o.side == side) continue
            val ax = Pitch.attX(side, o.x)
            if (ax > last) { second = last; last = ax } else if (ax > second) second = ax
        }
        if (second == -Float.MAX_VALUE) return

        // Level is on, so a strict inequality with a boot's worth of tolerance.
        if (rAttX > second + OFFSIDE_TOLERANCE) {
            offsideAgainst = r
            events.fire(Ev.OFFSIDE_TRAP_SPRUNG, 1 - side)
        }
    }

    /** Flagged at the strike, given when he touches it. */
    private var offsideAgainst: Man? = null

    /** The second-last opponent, per side, in that side's attacking frame. */
    private val offsideLine = FloatArray(2)

    /**
     * WHERE EACH SIDE MAY RUN TO — recomputed at claim cadence.
     *
     * Wiring the law without this produced 35.7 offsides a match against a band
     * of 4-6, and the number was correct: eleven men were standing wherever
     * their role anchor put them, six times a match beyond the last defender,
     * because nothing in the engine had ever heard of the line. A law that only
     * punishes is half a law. In football it SHAPES the run — a striker holds
     * his shoulder against the last man and times his move — and that shaping
     * is most of what the offside rule actually does to a game.
     *
     * This is also the first thing that makes a high defensive line COST
     * something. Until now stepping up was free in both directions: no
     * punishment for the defenders, no reward for playing anyone off.
     */
    private fun updateOffsideLines() {
        for (side in 0..1) {
            var last = -Float.MAX_VALUE
            var second = -Float.MAX_VALUE
            for (o in men) {
                if (o.side == side) continue
                val ax = Pitch.attX(side, o.x)
                if (ax > last) { second = last; last = ax } else if (ax > second) second = ax
            }
            offsideLine[side] =
                if (second == -Float.MAX_VALUE) Pitch.LENGTH else second
        }
    }

    /** How high this delivery got, so a landing knows whether it was aerial. */
    private var peakHeight = 0f

    /** Who is walking the ball, and for how long. */
    private var carrier: Man? = null
    private var carryFor = 0f
    private var carryTargetX = 0f
    private var carryTargetY = 0f

    /** For anything that needs to know the ball is owned rather than loose. */
    val carrierMan: Man? get() = carrier

    /**
     * Walk it. He moves at his own pace toward where he meant to take it and
     * the ball travels at his feet — neither in flight nor at rest, which is
     * the third state this engine did not have.
     */
    private fun stepCarry(m: Man) {
        carryFor += DT
        m.aim(carryTargetX, carryTargetY, max(0.4f, CARRY_MAX_S - carryFor))
        stillFor = 0f
    }

    /**
     * THE AERIAL DUEL — the second way a man can touch a football.
     *
     * Everything in this engine so far happens on the floor: a ball is struck,
     * it rolls, and it is claimed at rest. A ball in the air was a ball nobody
     * could do anything about, which quietly removed a whole third of the sport
     * — the cross, the corner, the long ball forward, the defensive header, and
     * with them the second largest source of fouls in a real match.
     *
     * It resolves where the ball LANDS, at the moment it lands, between the
     * nearest man of each side. Who wins is the same race the rest of the
     * engine uses, and what he does with it is decided by WHERE HE IS rather
     * than by whose side he is on: in the opponent's box he heads it at goal,
     * in his own he hammers it away, and anywhere else he flicks it on.
     *
     * That positional rule is the reason this needs no special cases. A
     * centre-half who has gone up for a corner heads it at goal because he is
     * standing in the six-yard box, not because anybody told him he was
     * attacking.
     */
    private fun aerialDuel(): Boolean {
        var mine: Man? = null
        var theirs: Man? = null
        var dMine = AERIAL_M
        var dTheirs = AERIAL_M
        val poss = possessionSide
        for (o in men) {
            if (o.isKeeper) continue
            val d = Physics.dist(o.x, o.y, ball.x, ball.y)
            if (o.side == poss) { if (d < dMine) { dMine = d; mine = o } }
            else if (d < dTheirs) { dTheirs = d; theirs = o }
        }
        val a = mine
        val b = theirs
        if (a == null && b == null) return false

        val code = "T$ticks.AERIAL"
        val winner: Man
        if (a != null && b != null) {
            // A contest, and a real one: being nearer helps and does not decide.
            val edge = ((dTheirs - dMine) / AERIAL_M).coerceIn(-1f, 1f)
            val pMine = (0.5f + 0.35f * edge).coerceIn(0.12f, 0.88f)
            winner = if (draw.next("$code.WIN") < pMine) a else b

            // Two men jumping into the same space is the second commonest way
            // to give a foul away in football, and this engine had one.
            if (draw.next("$code.FOUL") < AERIAL_FOUL) {
                val loser = if (winner === a) b else a
                foul(loser, winner)
                return false
            }
        } else winner = a ?: b!!

        val attX = Pitch.attX(winner.side, ball.x)
        val half = Pitch.WIDTH * 0.5f
        val central = abs(ball.y - half) < 20.16f
        ball.place(winner.x, winner.y)

        when {
            // In their box, and he can see the goal: a header at it.
            attX > Pitch.LENGTH - 18f && central -> {
                events.fire(Ev.SHOT_HEADER, winner.side)
                shots[winner.side]++
                val gx = Pitch.absX(winner.side, Pitch.LENGTH - 0.5f)
                markTheLine(winner, gx, half)
                lastStriker = winner
                lastKind = OptKind.SHOT
                lastReceiver = null
                strikeX = ball.x; strikeY = ball.y
                aimX = gx; aimY = half
                Physics.strike(ball, gx - ball.x, half - ball.y, 16f, 2f)
            }
            // In his own third: get rid of it, high and long.
            attX < 30f -> {
                events.fire(Ev.CLEARANCE_HEADED, winner.side)
                val tx = Pitch.absX(winner.side, attX + 35f)
                lastStriker = winner
                lastKind = OptKind.CLEAR
                lastReceiver = null
                strikeX = ball.x; strikeY = ball.y
                aimX = tx; aimY = ball.y
                Physics.strike(ball, tx - ball.x,
                    draw.range("$code.CLR", -8f, 8f), 18f, 12f)
            }
            // Everywhere else: a nod on into the space in front.
            else -> {
                events.fire(Ev.FLICK_ON, winner.side)
                val tx = Pitch.absX(winner.side, attX + 12f)
                lastStriker = winner
                lastKind = OptKind.PASS_SPACE
                lastReceiver = null
                strikeX = ball.x; strikeY = ball.y
                aimX = tx; aimY = ball.y
                Physics.strike(ball, tx - ball.x,
                    draw.range("$code.FLK", -5f, 5f), 9f, 4f)
            }
        }
        restartSide = -1
        stillFor = 0f
        return true
    }

    /** What he MEANT, reported as an event. What happened is resolved later. */
    private fun fireIntent(m: Man, o: Option) {
        val attX = Pitch.attX(m.side, m.x)
        when (o.kind) {
            OptKind.THROUGH_BALL -> events.fire(Ev.THROUGH_BALL, m.side)
            OptKind.SWITCH -> events.fire(Ev.PASS_SWITCH, m.side)
            OptKind.CUT_BACK -> events.fire(Ev.CUT_BACK, m.side)
            OptKind.CROSS ->
                events.fire(if (attX > 88f) Ev.CROSS_BYLINE else Ev.CROSS_EARLY, m.side)
            OptKind.CARRY -> events.fire(Ev.CARRY, m.side)
            OptKind.CLEAR -> events.fire(Ev.CLEARANCE_HOOFED, m.side)
            OptKind.SHOT -> {
                shots[m.side]++
                events.fire(
                    if (Physics.dist(m.x, m.y, Pitch.absX(m.side, Pitch.LENGTH), Pitch.WIDTH * 0.5f) > 25f)
                        Ev.SHOT_LONG_RANGE else Ev.SHOT_PLACED,
                    m.side
                )
            }
            OptKind.PASS_FEET, OptKind.PASS_SPACE -> Unit  // length decides these on arrival
        }
    }

    /**
     * THE BALL CAN BELONG TO NOBODY.
     *
     * Everything else in this engine was built on the assumption that it
     * cannot. A struck ball came to rest, the nearest man had it, and that was
     * the end of the possession. Which means an incomplete pass was a turnover
     * by construction — there was no third outcome — and three separate rows
     * were broken by the same absence:
     *
     *   interceptions  224 a match against a 16-22 band
     *   clearances     retained possession 1.6% of the time, measured
     *   completion     63% under a press, because a press that got near the
     *                  ball converted every single time
     *
     * So: when a man reaches it with an opponent close enough to contest, the
     * touch can break. The ball is struck a short way, nobody's name is on it —
     * `lastStriker` is cleared, so [possessionSide] reads -1, which is a state
     * the engine already had a value for and had never once been in — and both
     * sides go after it.
     *
     * The tighter the contest, the more likely it breaks. That is the whole
     * model, and it is deliberately the whole model: a fifty-fifty is a
     * fifty-fifty because two men arrive together, not because of a table of
     * strength ratings that do not exist yet.
     *
     * Returns true if it broke loose and the possession did NOT resolve.
     */
    private fun spilled(m: Man): Boolean {
        // Who is close enough to make it a contest at all?
        var rival: Man? = null
        var rivalGap = Float.MAX_VALUE
        for (o in men) {
            if (o.side == m.side) continue
            val d = Physics.dist(o.x, o.y, ball.x, ball.y)
            if (d < rivalGap) { rivalGap = d; rival = o }
        }
        val r = rival ?: return false
        if (rivalGap > CONTEST_M) return false

        val mine = Physics.dist(m.x, m.y, ball.x, ball.y)
        val edge = abs(rivalGap - mine)
        val pLoose = LOOSE_BASE * exp((-edge / LOOSE_SCALE).toDouble()).toFloat()

        val code = "T$ticks.S${m.side}P${m.slot.id}.CONTEST"
        if (draw.next(code) > pLoose) return false
        breakLoose("T$ticks.LOOSE")
        return true
    }

    /**
     * NOBODY HAS IT. Used by a contest that broke and by a challenge that came
     * out with nothing.
     *
     * The pass that was in flight did not reach anybody, so the striker's
     * prediction is answered and the length of it is still a fact worth
     * counting — an incomplete pass is a pass.
     */
    private fun breakLoose(code: String) {
        carrier = null
        carryFor = 0f
        val s = lastStriker
        if (s != null) {
            val d = Physics.dist(strikeX, strikeY, ball.x, ball.y)
            val wasPass = lastKind != OptKind.SHOT && lastKind != OptKind.CLEAR &&
                lastKind != OptKind.CARRY
            if (wasPass) {
                events.fire(if (d < SHORT_PASS_M) Ev.PASS_SHORT else Ev.PASS_LONG, s.side)
                events.fire(Ev.PASS_MISPLACED, s.side)
            }
            settle(worked = false, decided = false, why = "broke loose")
        }

        events.fire(Ev.LOOSE_BALL, -1)
        // Away from them, roughly, and not far. A broken ball is a scramble,
        // not a clearance.
        Physics.strike(
            ball,
            draw.range("$code.X", -1f, 1f), draw.range("$code.Y", -1f, 1f),
            draw.range("$code.PACE", LOOSE_PACE_LO, LOOSE_PACE_HI),
            draw.range("$code.LOFT", 0f, 2.5f)
        )
        lastStriker = null
        lastKind = null
        lastReceiver = null
        restartSide = -1
        stillFor = 0f
    }

    /**
     * SOMEBODY COMES AND TAKES IT OFF HIM — the tackle, the foul, the card.
     *
     * Until this existed a man could not be dispossessed by anybody DOING
     * anything. The only way to lose the ball was to strike it badly, so the
     * whole defensive half of football was a geometry problem: stand in the
     * right place and wait for a pass to arrive near you.
     *
     * The window is the instant he receives it. That is not a simplification of
     * football so much as a consequence of this engine's clock: a man receives
     * and plays in the same tick, so there is no carrying phase to interrupt.
     * When there is a real carry, this becomes a check that runs every tick he
     * has it, and nothing else here has to change.
     *
     * Four outcomes, because a challenge has four:
     *
     *   nothing        nobody was close enough, or nobody went in
     *   WON            the ball is his now, and he plays it
     *   LOOSE          neither of them came out with it — the ball breaks, and
     *                  it breaks through the same [breakLoose] a 50-50 uses
     *   FOUL           he took the man. Free kick, sometimes a card, and inside
     *                  the box a penalty
     *
     * Returns true when the possession ended here and he does NOT get to play.
     */
    private fun challenged(m: Man): Boolean {
        if (m.isKeeper || restartSide >= 0) return false

        var rival: Man? = null
        var gap = TACKLE_M
        for (o in men) {
            if (o.side == m.side || o.isKeeper) continue
            val d = Physics.dist(o.x, o.y, m.x, m.y)
            if (d < gap) { gap = d; rival = o }
        }
        val t = rival ?: return false

        val code = "T$ticks.S${t.side}P${t.slot.id}.TACKLE"
        // Closer means more likely to go in at all.
        val pGo = (TACKLE_RATE * (1f - gap / TACKLE_M)).coerceIn(0f, 1f)
        if (draw.next("$code.GO") > pGo) return false

        // A lunge from range is a slide; from close, a standing challenge.
        val slide = gap > TACKLE_M * 0.55f
        events.fire(if (slide) Ev.TACKLE_SLIDING else Ev.TACKLE_STANDING, t.side)

        /*
         * A SLIDE IS MORE LIKELY TO WIN IT AND MUCH MORE LIKELY TO FOUL. That
         * is the whole trade a defender makes, and it is the only reason to
         * distinguish the two at all — a pair of names with identical odds
         * would be decoration, which is what RoleCheck exists to catch
         * elsewhere.
         */
        val pFoul = if (slide) FOUL_SLIDE else FOUL_STAND
        val pWin = if (slide) WIN_SLIDE else WIN_STAND
        val r = draw.next("$code.OUT")

        if (r < pFoul) { carrier = null; carryFor = 0f; foul(t, m); return true }
        if (r < pFoul + pWin) {
            carrier = null
            carryFor = 0f
            events.fire(Ev.DISPOSSESSED, m.side)
            lastStriker = null
            lastKind = null
            lastReceiver = null
            ball.place(t.x, t.y)
            onBall(t)
            return true
        }
        breakLoose("T$ticks.TACKLE_LOOSE")
        return true
    }

    /**
     * He took the man. Where it happened decides what it is worth.
     *
     * A foul is the first thing in this engine that STOPS the game in the
     * attacking side's favour, which is why it is also the first route to a
     * penalty — and a penalty is not special-cased into a goal here. The ball
     * is put on the spot and the taker's own [Mind] decides what to do with it,
     * against a value surface that already prices a shot from twelve yards at
     * about 0.76. The football decides; nothing is scripted.
     */
    private fun foul(offender: Man, victim: Man) {
        events.fire(Ev.FOUL, offender.side)

        val attX = Pitch.attX(victim.side, ball.x)
        val half = Pitch.WIDTH * 0.5f
        val inBox = attX > Pitch.LENGTH - 16.5f && abs(ball.y - half) < 20.16f

        /*
         * A CARD IS NOT A DICE ROLL ON EVERY FOUL. It is much likelier when a
         * man is taken from behind at pace, or when the foul stops something.
         * With no intent model yet, the honest stand-in is WHERE it happened:
         * a foul in your own third is stopping an attack, and referees book
         * that. Anything better needs an intent this engine does not have, and
         * inventing one to make a number move is how the predecessor drifted.
         */
        val stopping = attX > 55f
        val pCard = if (stopping) CARD_STOPPING else CARD_ROUTINE
        if (draw.next("T$ticks.S${offender.side}P${offender.slot.id}.CARD") < pCard) {
            events.fire(Ev.CARD_YELLOW, offender.side)
            cards[offender.side]++
        }

        if (inBox) {
            events.fire(Ev.FOUL_IN_BOX, offender.side)
            events.fire(Ev.PENALTY_AWARDED, victim.side)
            ball.place(
                Pitch.absX(victim.side, Pitch.LENGTH - 11f),
                half
            )
        } else {
            events.fire(Ev.FREE_KICK_DIRECT, victim.side)
        }
        deadBall(victim.side)
    }

    /**
     * A man has got to the ball. Report the two facts that are true regardless
     * of what anybody intended: how far it travelled, and whose it is now.
     */
    private fun resolveTouch(m: Man) {
        offsideAgainst?.let { flagged ->
            if (m === flagged) {
                events.fire(Ev.OFFSIDE, m.side)
                events.fire(Ev.FREE_KICK_INDIRECT, 1 - m.side)
                settle(worked = false, why = "offside")
                lastStriker = null
                lastKind = null
                lastReceiver = null
                offsideAgainst = null
                deadBall(1 - m.side)
                return
            }
        }

        val s = lastStriker ?: run {
            // Nobody played this to him. He went and got it — a thing that
            // could not happen in this engine until the ball was allowed to
            // belong to nobody.
            //
            // A DEAD BALL IS NOT A LOOSE BALL. Both clear `lastStriker`, so the
            // first version of this fired on every throw-in, goal kick and
            // kick-off and read 135 recoveries a match against 87 loose balls —
            // more recoveries than there were balls to recover, which is the
            // arithmetic tell that a counter is catching something else.
            if (restartSide < 0) events.fire(Ev.RECOVERY_RUN, m.side)
            return
        }
        val k = lastKind
        val d = Physics.dist(strikeX, strikeY, ball.x, ball.y)

        // A shot and a hoof are not passes, and counting them as such is how
        // the predecessor got 36 crosses a match out of goal kicks.
        val wasPass = k != OptKind.SHOT && k != OptKind.CLEAR && k != OptKind.CARRY
        if (wasPass) {
            events.fire(if (d < SHORT_PASS_M) Ev.PASS_SHORT else Ev.PASS_LONG, s.side)
            if (m.side == s.side) events.fire(Ev.PASS_COMPLETED, s.side)
            else events.fire(Ev.PASS_MISPLACED, s.side)
        }
        /*
         * AN INTERCEPTION IS A PASS CUT OUT, NOT EVERY CHANGE OF HANDS.
         *
         * This fired on every turnover and read 401.9 a match against a §5
         * target of 16-22 — the same shape of error as the save counter. A
         * defender reading a pass and taking it BEFORE it reaches the intended
         * man is an interception; scrapping for a ball that has already arrived
         * and gone loose is a recovery, and real football counts them apart.
         *
         * The rest are loose balls, which this engine has never modelled: every
         * struck ball is claimed the instant it stops, so an incomplete pass is
         * a turnover by construction. Naming them is the first step to fixing
         * that.
         */
        lastReceiver?.let { t ->
            val d = Physics.dist(ball.x, ball.y, t.x, t.y)
            deliverySum += d.toDouble()
            deliveryCount++
            if (d < 2f) deliveryNear++

            // The same miss, decomposed. See the fields for why.
            ballVsAimSum += Physics.dist(ball.x, ball.y, aimX, aimY).toDouble()
            aimVsManSum += Physics.dist(aimX, aimY, t.x, t.y).toDouble()
            splitCount++

            onDelivery?.let { cb ->
                delivery.restX = ball.x; delivery.restY = ball.y
                delivery.manX = t.x; delivery.manY = t.y
                cb(delivery)
            }
        }
        if (m.side != s.side) {
            /*
             * Three different things, told apart at last.
             *
             *   INTERCEPTION  he was in the line when it was struck and he took
             *                 it. He read the pass.
             *   RECOVERY_RUN  he was nowhere near it and went and got it. The
             *                 pass simply failed and he was closest to where it
             *                 ended up.
             *   DISPOSSESSED  he took it off the man it reached.
             */
            val i = men.indexOf(m)
            val target = lastReceiver
            val reachedHim = target != null &&
                Physics.dist(ball.x, ball.y, target.x, target.y) <= INTERCEPT_GAP
            events.fire(
                when {
                    i >= 0 && inTheLine[i] -> Ev.INTERCEPTION
                    reachedHim -> Ev.DISPOSSESSED
                    else -> Ev.RECOVERY_RUN
                },
                m.side
            )
        }
        // A shot is only ever answered at the goal line. Anything else here
        // means it did not go in.
        settle(
            k != OptKind.SHOT && m.side == s.side,
            why = if (m.side == s.side) "-" else "opponent touched it first"
        )

        lastStriker = null
        lastKind = null
        lastReceiver = null
    }

    /**
     * Has it left the field, and what restarts play?
     *
     * All geometry. A goal is the ball between the posts under the bar; a
     * corner and a goal kick differ only by who touched it last. None of it
     * needs a decision layer, which is why it can be honest at step 3.
     */
    private fun leftTheField(): Boolean {
        val half = Pitch.WIDTH * 0.5f

        if (ball.x < 0f || ball.x > Pitch.LENGTH) {
            val scorer = if (ball.x > Pitch.LENGTH) 0 else 1
            val defender = 1 - scorer
            val betweenPosts = ball.y > half - Pitch.GOAL_WIDTH * 0.5f &&
                ball.y < half + Pitch.GOAL_WIDTH * 0.5f
            if (betweenPosts && ball.height < CROSSBAR_M) {
                /*
                 * THE KEEPER IS A BODY, NOT A DICE ROLL.
                 *
                 * He saves it if he can REACH it — the same claim logic every
                 * other contest in this engine uses. He tracks the ball across
                 * his line, so a shot down the middle is routine and one into
                 * the corner beats him, and that falls out of where he is
                 * standing rather than out of a save percentage.
                 */
                val gk = men.first { it.side == defender && it.isKeeper }
                val across = abs(gk.y - ball.y)
                val reach = GK_REACH + (if (ball.height < 0.9f) GK_LOW_BONUS else 0f)
                if (across < reach) {
                    /*
                     * A SAVE IS A SHOT STOPPED, NOT ANY BALL STOPPED.
                     *
                     * This fired for every ball crossing between the posts —
                     * stray passes, clearances, deflections off a block — so it
                     * read 23 saves a match from about ten unblocked shots,
                     * which is arithmetically impossible and was the tell. The
                     * keeper still stops those balls; they are simply not
                     * saves, and counting them as such both inflated the save
                     * rate and suppressed goals by turning loose balls into
                     * keeper possession.
                     */
                    val wasShot = lastKind == OptKind.SHOT
                    events.fire(
                        if (!wasShot) Ev.KEEPER_CLAIM_CROSS
                        else if (across < 1.3f) Ev.SAVE_ROUTINE else Ev.SAVE_DIVING,
                        defender
                    )
                    settle(false, why = "keeper")
                    val gx = if (defender == 0) 7f else Pitch.LENGTH - 7f
                    ball.place(gx, ball.y.coerceIn(6f, Pitch.WIDTH - 6f))
                    deadBall(defender)
                    return true
                }
                events.fire(Ev.GOAL, scorer)
                settle(pendingKind == OptKind.SHOT, why = "goal")
                goals[scorer]++
                resetPositions()
                kickOff(defender)
                return true
            }
            val last = lastStriker
            // A corner needs the DEFENDING side to have put it behind, and the
            // placeholder only ever strikes forward — so no defender can send
            // the ball over his own line, and this branch is unreachable today.
            // EventCensus reports CORNER_WON as wired-and-never-fired, which is
            // the correct finding: what is missing is a deflection, a blocked
            // clearance and a defensive header, none of which exist yet.
            if (last != null && last.side == defender) {
                events.fire(Ev.CORNER_WON, scorer)
                corners[scorer]++
                val cx = if (scorer == 0) Pitch.LENGTH - 0.4f else 0.4f
                val cy = if (ball.y < half) 0.4f else Pitch.WIDTH - 0.4f
                ball.place(cx, cy)
                cornerPending = true
                deadBall(scorer)
            } else {
                events.fire(Ev.GOAL_KICK, defender)
                val gx = if (defender == 0) 5.5f else Pitch.LENGTH - 5.5f
                ball.place(gx, half)
                deadBall(defender)
            }
            return true
        }

        if (ball.y < 0f || ball.y > Pitch.WIDTH) {
            val last = lastStriker
            val to = if (last != null) 1 - last.side else 0
            events.fire(Ev.THROW_IN, to)
            ball.place(
                ball.x.coerceIn(0.4f, Pitch.LENGTH - 0.4f),
                if (ball.y < half) 0.4f else Pitch.WIDTH - 0.4f
            )
            deadBall(to)
            return true
        }
        return false
    }

    /** Who gets there first. No radius and no roll — the lowest claim time. */
    private fun updateClaim() {
        Physics.restPoint(ball, rest)
        var best: Man? = null
        var bestT = Float.MAX_VALUE
        for (m in men) {
            if (restartSide >= 0 && m.side != restartSide) continue
            if (m.isKeeper && Physics.dist(m.x, m.y, rest[0], rest[1]) > 18f) continue
            // Role enters here as seconds of reluctance over DISTANCE, never as
            // a chance of winning it. At his feet every role is equal; it is the
            // thirty metre chase a poacher declines.
            val d = Physics.dist(m.x, m.y, rest[0], rest[1])
            val t = Physics.timeToReach(m.x, m.y, m.vx, m.vy, rest[0], rest[1], m.topSpeed, m.accel) +
                m.reluctance * min(2f, d / 15f)
            if (t < bestT) { bestT = t; best = m }
        }
        claimant = best
    }

    /**
     * OFF-BALL SUPPORT — men move to OFFER themselves.
     *
     * Without this, roles set anchors and nobody creates a passing option, and
     * `OptionCensus` measured exactly that: a median option set of 4 against
     * the §5 floor of 6, and 22% of touches offered two options or fewer. A man
     * offered three options every time will play the same ball every time,
     * whatever eventually scores them — which is the brief's argument for why
     * this step comes before the chooser.
     *
     * This is not a decision layer. Nothing here ranks what the man on the ball
     * should DO; it moves the men who are not on it into places where a line
     * exists. The nearest few team-mates try a small set of angles around the
     * carrier and take the first that is clear, and the result is BLENDED with
     * the role anchor so a role still dominates where a man lives.
     */
    private fun updateSupport() {
        java.util.Arrays.fill(supportSet, false)
        val poss = possessionSide
        if (poss < 0) return
        val carrier = claimant ?: return

        // The three nearest of his own side, excluding him.
        var a = -1; var b = -1; var c = -1
        var da = Float.MAX_VALUE; var db = Float.MAX_VALUE; var dc = Float.MAX_VALUE
        for (i in men.indices) {
            val m = men[i]
            if (m.side != poss || m === carrier || m.isKeeper) continue
            val d = Physics.dist(m.x, m.y, ball.x, ball.y)
            when {
                d < da -> { c = b; dc = db; b = a; db = da; a = i; da = d }
                d < db -> { c = b; dc = db; b = i; db = d }
                d < dc -> { c = i; dc = d }
            }
        }

        for (i in intArrayOf(a, b, c)) {
            if (i < 0) continue
            val m = men[i]
            val r = (10f + m.leashM * 0.35f).coerceIn(11f, 20f)
            // Angles measured from the carrier, biased ahead of him.
            val forward = if (m.side == 0) 0f else Math.PI.toFloat()
            var bestX = Float.NaN; var bestY = 0f; var bestD = Float.MAX_VALUE
            for (k in 0 until SUPPORT_ANGLES) {
                val ang = forward + (k - SUPPORT_ANGLES / 2) * 0.7f
                val px = (carrier.x + kotlin.math.cos(ang.toDouble()).toFloat() * r)
                    .coerceIn(2f, Pitch.LENGTH - 2f)
                val py = (carrier.y + kotlin.math.sin(ang.toDouble()).toFloat() * r)
                    .coerceIn(2f, Pitch.WIDTH - 2f)
                if (!laneClear(carrier, px, py)) continue
                // Nearest to where his role already wants him: the role keeps
                // its signature, and support does not turn eleven men into one.
                val d = Physics.dist(px, py, m.targetX, m.targetY)
                if (d < bestD) { bestD = d; bestX = px; bestY = py }
            }
            if (!bestX.isNaN()) {
                supportSet[i] = true
                supportX[i] = bestX
                supportY[i] = bestY
            }
        }
    }

    private fun updateTargets() {
        val chaser = claimant
        for (m in men) {
            if (m === chaser) {
                // He is going to where the ball will STOP, by the time it gets
                // there. Running at where it is now is running at a place the
                // ball merely passes through.
                m.aim(rest[0], rest[1], max(0.35f, rest[2]))
                continue
            }
            if (m.isKeeper) {
                val gx = if (m.side == 0) 5.5f else Pitch.LENGTH - 5.5f
                val gy = Pitch.WIDTH * 0.5f + (ball.y - Pitch.WIDTH * 0.5f) * 0.22f
                m.aim(gx, gy, 1.5f)
                continue
            }
            val poss = if (possessionSide == m.side) 1 else 0
            val ax = anchorAx[m.side][poss][m.slot.id]
            val ay = anchorAy[m.side][poss][m.slot.id]
            val ballAx = Pitch.attX(m.side, ball.x)
            val ballAy = Pitch.attY(m.side, ball.y)

            // The slide is capped by HIS leash, which is what stops the shape
            // being a rigid lattice that translates. A poacher barely moves; a
            // box-to-box roams; and the block breathes instead of sliding.
            var dx = (ballAx - Pitch.LENGTH * 0.5f) * SLIDE_X
            var dy = (ballAy - Pitch.WIDTH * 0.5f) * SLIDE_Y
            val len = hypot(dx.toDouble(), dy.toDouble()).toFloat()
            if (len > m.leashM && len > 0.01f) {
                val k = m.leashM / len
                dx *= k; dy *= k
            }
            /*
             * DEFENDING IS A LINE BETWEEN THE BALL AND THE GOAL.
             *
             * Attempted four times before. The first three failed on their own
             * terms; the fourth was blocked by RoleCheck, which then measured
             * role separation over the WHOLE match — so a correct defensive
             * shape, which pulls every role toward one line, looked like
             * decoration. That criterion now measures in possession only, so
             * this can finally be judged on whether it defends.
             *
             * The line is computed from the BALL and the leash does not apply:
             * a leash limits how far a man roams from his station in
             * possession, never how far he runs home. The block compresses as
             * the ball nears the goal, because holding thirty metres of depth
             * in your own box leaves only the deepest man behind it.
             */
            var tAx: Float
            if (possessionSide >= 0 && possessionSide != m.side) {
                // The team's disposition moves the whole line, which is the
                // one thing a side does together without deciding to.
                val lineAx = (min(deepestAx[m.side], ballAx - DEFEND_STANDOFF) +
                    teams[m.side].lineShift).coerceAtLeast(3f)
                val squeeze = (ballAx / 42f).coerceIn(0.28f, 1f)
                val rel = (ax - deepestAx[m.side]) * squeeze
                tAx = (lineAx + rel).coerceAtMost(ballAx + GOAL_SIDE_ALLOWANCE)
                    .coerceIn(1.5f, Pitch.LENGTH - 1.5f).coerceAtMost(m.maxAttX)
            } else {
                tAx = (ax + dx).coerceIn(1.5f, Pitch.LENGTH - 1.5f).coerceAtMost(m.maxAttX)
                /*
                 * HOLD THE LINE. A man whose side has the ball does not run
                 * beyond the second-last defender and stand there — he holds
                 * his shoulder against him. Never past the halfway line, since
                 * his own half is always onside.
                 */
                /*
                 * ...UNTIL THE BALL IS PLAYED TO HIM. That is the timed run,
                 * and it is the third piece that makes the other two mean
                 * anything.
                 *
                 * Holding the line alone took completion 52.3% -> 44.3%,
                 * because a ball into space is aimed nine metres ahead of a man
                 * who is now forbidden to go there. In football he is onside at
                 * the instant it is struck and then he goes — which is exactly
                 * what the law permits and exactly what a through ball IS. The
                 * engine already knows who was played in.
                 */
                if (possessionSide == m.side && m !== lastReceiver) {
                    val limit = maxOf(Pitch.LENGTH * 0.5f, offsideLine[m.side] - HOLD_MARGIN)
                    if (tAx > limit) tAx = limit
                }
            }
            val tAy = (ay + dy).coerceIn(1.5f, Pitch.WIDTH - 1.5f)
            var wantX = Pitch.absX(m.side, tAx)
            var wantY = Pitch.absY(m.side, tAy)

            /*
             * A COLLECTIVE ACT OUTRANKS THE SHAPE, AND ONLY THE SHAPE.
             *
             * A pressing man leaves his station — that is the whole point of a
             * press, and a press that respected the block would be indis-
             * tinguishable from not pressing. It is still only an OFF-BALL
             * target: the man on the ball is untouched by any of this.
             */
            if (teams[m.side].pressTargetFor(m, pressBuf)) {
                m.aim(pressBuf[0], pressBuf[1], 0.9f)
                continue
            }

            val i = men.indexOf(m)
            if (supportSet[i]) {
                wantX += (supportX[i] - wantX) * SUPPORT_BLEND
                wantY += (supportY[i] - wantY) * SUPPORT_BLEND
            }
            m.aim(wantX, wantY, max(1.2f, rest[2]))
        }
    }

    private fun tick() {
        /*
         * A CARRIED BALL IS NEITHER IN FLIGHT NOR AT REST, so it takes the
         * whole tick before anything else looks at it. He keeps it until he is
         * tackled, until he chooses to play it, or until he has had it long
         * enough.
         */
        val held = carrier
        if (held != null) {
            for (t in teams) t.observe(this)
            if (ticks % TeamMind.READ_EVERY == 0) for (t in teams) t.read(this)
            if (ticks % CLAIM_EVERY == 0) { updateOffsideLines(); updateSupport() }
            ticks++
            stepCarry(held)
            updateTargets()
            for (m in men) m.step(DT)
            ball.place(held.x, held.y)

            if (ticks % CARRY_EVERY == 0 && carrier === held) {
                if (!challenged(held)) onBall(held)
            }
            if (carrier === held && carryFor >= CARRY_MAX_S) {
                // Time is up: he must do something with it.
                carrier = null
                carryFor = 0f
                onBall(held)
            }
            clock += DT
            return
        }

        val wasUp = ball.height
        var moving = Physics.stepBall(ball, DT)

        /*
         * IT CAME DOWN. Somebody attacks it in the air.
         *
         * A lofted ball had no contest at all until now: it flew, it landed, it
         * rolled, and whoever was nearest when it stopped simply had it. That is
         * why a cross found a team-mate 14% of the time and why corners read 2.45
         * a match against 9-11 — nobody could head one away, so a defender could
         * not put the ball behind his own line.
         */
        /*
         * THE PEAK, NOT THE PREVIOUS TICK.
         *
         * The first version asked whether the ball was above 1.6 m one tick ago
         * and on the floor now. At a tenth of a second that needs it to fall a
         * metre and a half in one step — fifteen metres a second downward — so
         * it essentially never happened, and EventCensus said so in three lines
         * before a single number could mislead anyone: NEVER FIRES, three
         * times. A ball descends through 1.6, 1.2, 0.8, 0.3, 0 over several
         * ticks, and by the tick it touches down it is no longer high.
         *
         * What decides whether a ball can be headed is how high it GOT, so that
         * is what is remembered.
         */
        if (ball.height > peakHeight) peakHeight = ball.height
        if (wasUp > 0.05f && ball.height <= 0.05f && restartSide < 0 &&
            peakHeight > AERIAL_MIN_H) {
            peakHeight = 0f
            if (aerialDuel()) moving = true
        }

        if (leftTheField()) {
            moving = false
            updateClaim()
        }

        // The rest point is a short simulation, so it runs at decision cadence
        // rather than once a frame. Recomputing it every tick costs ten million
        // physics steps a match and buys three hundredths of a second of
        // accuracy in where a man is heading.
        for (t in teams) t.observe(this)
        if (ticks % TeamMind.READ_EVERY == 0) for (t in teams) t.read(this)

        if (ticks % CLAIM_EVERY == 0) {
            updateOffsideLines()
            updateClaim(); updateSupport()
            // A press is decided at claim cadence, because what it aims at is
            // whoever is about to receive the ball.
            for (t in teams) t.considerPress(this, claimant, clock)
        }
        ticks++
        updateTargets()

        for (m in men) m.step(DT)

        if (!moving && wasMoving) {
            val c = claimant
            if (c != null) {
                arriveGapSum += Physics.dist(c.x, c.y, ball.x, ball.y).toDouble()
                arriveGapCount++
            }
        }
        wasMoving = moving

        if (!moving) {
            stillFor += DT
            val c = claimant
            if (c != null && Physics.dist(c.x, c.y, ball.x, ball.y) < 1.4f) {
                // He reaches it — but reaching it is not the same as having it.
                if (!spilled(c)) {
                    resolveTouch(c)
                    // He has it. Now somebody is allowed to come and take it
                    // off him — which nobody in this engine could do until now.
                    if (!challenged(c)) onBall(c)
                }
            } else if (stillFor > 6f) {
                /*
                 * THE DEADLOCK BREAKER WAS LETTING A MAN KICK A BALL HE WAS
                 * NOWHERE NEAR. This is the whole of the missing 10 metres.
                 *
                 * If nobody has reached the ball in six seconds, somebody has
                 * to play it or the match stops. The old line handed it to the
                 * claimant WHEREVER HE STOOD — and then [strikeOn] generated
                 * his options from HIS position while [Physics.strike] launched
                 * the ball from the BALL's position. PassTrace measured the gap
                 * between those two points at 14.39 m on average.
                 *
                 * Every consequence followed from that one line. The pace was
                 * solved for carrier -> target and the ball had to cover ball
                 * -> target, 4.96 m further on average; the direction was wrong
                 * by the same triangle; and "the ball does not stop where it
                 * was aimed" read 10.21 m while an isolated probe of the very
                 * same physics was accurate to under a metre. Both were true.
                 * They were describing different strikes.
                 *
                 * He has walked over and picked it up. Say so.
                 */
                val f = c ?: men[0]
                resolveTouch(f)
                ball.place(f.x, f.y)
                onBall(f)
            }
        }

        clock += DT
    }

    /**
     * How many team-mates he could actually reach — the OPTION SET, counted and
     * not scored.
     *
     * There is no decision layer, so nothing here ranks these or picks one.
     * That is the point: the size of this set is a property of where everybody
     * else is standing, which is off-ball movement's job, and the brief's
     * argument is that choice variety is downstream of it. If this number is
     * small, no decision layer can rescue it.
     */
    fun optionsFor(carrier: Man): Int {
        var n = 0
        for (t in men) {
            if (t === carrier || t.side != carrier.side || t.isKeeper) continue
            val d = Physics.dist(carrier.x, carrier.y, t.x, t.y)
            if (d < 6f || d > 42f) continue
            if (laneClear(carrier, t)) n++
        }
        return n
    }

    private fun laneClear(a: Man, b: Man): Boolean = laneClear(a, b.x, b.y)

    /** No opponent within 2.5 m of the straight line to a point. */
    private fun laneClear(a: Man, bx: Float, by: Float): Boolean {
        val dx = bx - a.x
        val dy = by - a.y
        val len2 = dx * dx + dy * dy
        if (len2 < 0.01f) return false
        for (o in men) {
            if (o.side == a.side) continue
            val t = (((o.x - a.x) * dx + (o.y - a.y) * dy) / len2).coerceIn(0f, 1f)
            if (Physics.dist(o.x, o.y, a.x + dx * t, a.y + dy * t) < 2.5f) return false
        }
        return true
    }

    /*
     * THE LOOSE BALL — attempted once as a 50-50, reverted, and now built a
     * second way. THE NOTE BELOW WAS RIGHT BOTH TIMES.
     *
     * A dying ball was given to the man with the lowest claim time, so an
     * incomplete pass was a turnover BY CONSTRUCTION. The attempt made it
     * contested: when a second man was within 0.9 s of the first, the ball
     * broke on the outcome stream weighted by the gap.
     *
     *   pass completion   69.46 -> 69.19    (target 78-85)
     *   INTERCEPTION     218.70 -> 216.55   (target 16-22)
     *   goals              1.35 ->   0.80   (target 2.6-2.9)
     *
     * Nothing moved except the score, downwards. Reverted.
     *
     * WHY IT MISSED, which is the useful part: completion asks whether the
     * first toucher is a TEAM-MATE, and the contest is between whoever happens
     * to be nearest where the ball died. Delivery error means that is rarely
     * the intended receiver, so the coin-flip is usually between two opponents,
     * or between an opponent and some other team-mate. Making possession more
     * random does not make it more accurate.
     *
     * The loose ball is real and still needed, but it is not upstream of
     * completion. Completion is upstream of IT: the ball has to arrive near the
     * man it was aimed at often enough for a contest to involve him. That is
     * delivery error and receiver movement, not the claim rule.
     *
     * -------------------------------------------------------------------
     * SECOND ATTEMPT, DIFFERENT MECHANISM, SAME WALL — and worth reading as a
     * warning before anyone tries a third.
     *
     * This one does not reassign the ball to a coin-flip winner. It lets the
     * touch BREAK: nobody gets it, `lastStriker` clears, [possessionSide] reads
     * -1, and both sides chase. That is a better model and it shipped. But
     * completion went 63.2% -> 57.1%, which is the note above coming true a
     * second time by a different route.
     *
     * The cause is one number: the mean delivery miss is 8.61 m, and only 36%
     * of intended passes land within two metres of the man. A contest at the
     * place a ball dies is therefore almost never "the receiver against his
     * marker" — it is two other people. Nothing done to the contest rule can
     * fix a pass that was never near anybody.
     *
     * So the honest statement of the open job is not "the loose ball" any more.
     * It is DELIVERY. The overshoot was found and fixed, the error scale was
     * swept, leading the pass was tested — and 8.61 m survived all three.
     *
     * -------------------------------------------------------------------
     * THIRD ATTEMPT: LET A MAN TAKE A MOVING BALL. Reverted.
     *
     * The diagnosis was good and the reasoning was sound: the ball was only
     * ever touched AT REST, so nobody in ninety minutes received one in stride,
     * and a defender standing in a lane always ended up nearest a ball that
     * stopped however fast it went past him. It was built properly — a swept
     * test against the segment travelled each tick so a driven pass could not
     * tunnel through a man, a control roll that fell with pace and rose for the
     * man it was played to, a grace period so a pass could leave the crowd
     * around the striker, and only the opposition plus the intended receiver
     * allowed to try, because a team-mate does not cut out his own side's pass.
     *
     * Swept over four reach radii, 24 matches each:
     *
     *   reach  goals  passes  completion  intercept  loose  miss
     *   1.10   1.25  1246.3      55.2%       280.1  218.9  7.80
     *   0.80   1.42  1104.1      55.8%       233.1  181.9  7.83
     *   0.60   1.17  1005.5      57.0%       198.1  146.4  7.82
     *   0.40   1.13   925.4      56.8%       173.2  117.2  8.12
     *   off    0.98   793.2      59.1%       109.3   53.5  8.60
     *
     * Every radius made completion WORSE and interceptions worse, and the miss
     * moved 8.60 -> 7.80 at best. The reason is the whole point: the men
     * getting these new touches are almost all OPPONENTS. The intended receiver
     * hardly ever gets one, because the ball's path does not come within a
     * metre of him — which is the same wall again, from a third direction.
     *
     * What survives is the diagnosis, sharpened: it is not the arrival rule. It
     * is that the ball does not go where the man is. So this commit ships the
     * measurement that decides WHY instead of another attempt at the symptom —
     * see [ballVsAimSum] and [aimVsManSum].
     */
    /**
     * WHO REACHES THIS SPOT FIRST — 1 if the receiver wins the race, 0 if he
     * loses it badly.
     *
     * Deliberately the same question [updateClaim] asks to decide every touch
     * in the match, answered with the same [Physics.timeToReach]. A player
     * judging a pass and the engine resolving it are then reasoning about the
     * same thing, which is the only way his percentage can ever be right.
     */
    /**
     * The same race, with nobody's name on the ball: OUR best against THEIR
     * best. What a hoof upfield actually is.
     */
    fun headStartAt(side: Int, tx: Float, ty: Float): Float {
        var mine = Float.MAX_VALUE
        var theirs = Float.MAX_VALUE
        for (o in men) {
            val t = Physics.timeToReach(o.x, o.y, o.vx, o.vy, tx, ty, o.topSpeed, o.accel)
            if (o.side == side) { if (t < mine) mine = t } else if (t < theirs) theirs = t
        }
        if (mine == Float.MAX_VALUE || theirs == Float.MAX_VALUE) return 0.5f
        return (0.5f + (theirs - mine) / (2f * HEAD_START_WINDOW)).coerceIn(0f, 1f)
    }

    /** Is a man there offside if the ball is played to him now? */
    fun wouldBeOffside(side: Int, tx: Float, ty: Float): Boolean {
        val ax = Pitch.attX(side, tx)
        if (ax <= Pitch.LENGTH * 0.5f) return false
        return ax > offsideLine[side] + OFFSIDE_TOLERANCE
    }

    fun headStart(receiver: Man?, tx: Float, ty: Float): Float {
        val r = receiver ?: return 0.5f
        val tMate = Physics.timeToReach(r.x, r.y, r.vx, r.vy, tx, ty, r.topSpeed, r.accel)
        var tOpp = Float.MAX_VALUE
        for (o in men) {
            if (o.side == r.side) continue
            val t = Physics.timeToReach(o.x, o.y, o.vx, o.vy, tx, ty, o.topSpeed, o.accel)
            if (t < tOpp) tOpp = t
        }
        if (tOpp == Float.MAX_VALUE) return 1f
        // Half a second either way is the whole of it: football is not decided
        // by who is nearer, it is decided by who is there.
        return (0.5f + (tOpp - tMate) / (2f * HEAD_START_WINDOW)).coerceIn(0f, 1f)
    }

    /** How many of [side]'s men sit within [r] of a point. */
    fun matesWithin(x: Float, y: Float, r: Float, side: Int): Int {
        var n = 0
        for (o in men) if (o.side == side && !o.isKeeper && Physics.dist(o.x, o.y, x, y) < r) n++
        return n
    }

    /** How many opponents sit within [r] of a point. */
    fun opponentsWithin(x: Float, y: Float, r: Float): Int {
        var n = 0
        for (o in men) if (Physics.dist(o.x, o.y, x, y) < r) n++
        return n
    }

    /** The opponent nearest the shooter that stands in the line of the ball. */
    fun nearestOnLine(a: Man, bx: Float, by: Float): Man? {
        val dx = bx - a.x
        val dy = by - a.y
        val len2 = dx * dx + dy * dy
        if (len2 < 0.01f) return null
        var best: Man? = null
        var bestT = Float.MAX_VALUE
        for (o in men) {
            if (o.side == a.side) continue
            val t = (((o.x - a.x) * dx + (o.y - a.y) * dy) / len2).coerceIn(0f, 1f)
            if (t <= 0.02f || t >= 0.98f) continue
            // A man is about half a metre wide with a leg out. 1.5 m was a
            // body three metres across and it blocked 84% of all shots against
            // a real 30%, taking the score to 0.65 a game.
            if (Physics.dist(o.x, o.y, a.x + dx * t, a.y + dy * t) < BLOCK_HALF_WIDTH && t < bestT) {
                bestT = t; best = o
            }
        }
        return best
    }

    /** How many opponents are close to the line from a man to a point. */
    fun opponentsNearLine(a: Man, bx: Float, by: Float): Int {
        val dx = bx - a.x
        val dy = by - a.y
        val len2 = dx * dx + dy * dy
        if (len2 < 0.01f) return 0
        var n = 0
        for (o in men) {
            if (o.side == a.side) continue
            val t = (((o.x - a.x) * dx + (o.y - a.y) * dy) / len2).coerceIn(0f, 1f)
            if (Physics.dist(o.x, o.y, a.x + dx * t, a.y + dy * t) < 2.5f) n++
        }
        return n
    }

    /** Metres to the nearest opponent. */
    fun pressureOn(m: Man): Float {
        var best = Float.MAX_VALUE
        for (o in men) {
            if (o.side == m.side) continue
            val d = Physics.dist(m.x, m.y, o.x, o.y)
            if (d < best) best = d
        }
        return best
    }

    /** Fill [into] with this instant's shape. */
    fun sample(into: ShapeSnapshot) {
        for (side in 0..1) {
            java.util.Arrays.fill(into.occupancy[side], 0f)
            var lo = Float.MAX_VALUE
            var hi = -Float.MAX_VALUE
            var goalSide = 0
            /*
             * "Defenders goal-side of a ball in their own box" means a ball THEY
             * DO NOT HAVE. The first version counted any ball in the box,
             * including their own keeper building out — a situation where having
             * men ahead of the ball is correct football, and which dragged the
             * average toward zero however well the side defended. The corrected
             * number is worse, not better: 0.00 of 10.
             */
            val inOwnBox = Pitch.inOwnBox(side, ball.x, ball.y) && possessionSide != side
            val ballAx = Pitch.attX(side, ball.x)

            for (m in men) {
                if (m.side != side || m.isKeeper) continue
                val ax = Pitch.attX(side, m.x)
                val ay = Pitch.attY(side, m.y)
                if (ax < lo) lo = ax
                if (ax > hi) hi = ax
                if (inOwnBox && ax < ballAx) goalSide++
                val lane = Pitch.laneOf(ay)
                val band = Pitch.bandOf(ax)
                into.occupancy[side][lane * Pitch.BANDS + band] += 1f
            }

            into.blockDepth[side] = if (hi > lo) hi - lo else 0f
            into.goalSideValid[side] = inOwnBox
            into.goalSide[side] = goalSide.toFloat()
        }
    }

    /**
     * Play the match. [onSample] is called once a second with the bucket index
     * and the shape at that instant.
     */
    fun play(onSample: ((Int, ShapeSnapshot) -> Unit)? = null) {
        val snap = ShapeSnapshot()
        var nextSample = 1f
        while (clock < SECONDS) {
            tick()
            if (!halfDone && clock >= HALF_SECONDS) {
                halfDone = true
                events.fire(Ev.HALF_TIME, -1)
                resetPositions()
                kickOff(1)
            }
            if (onSample != null && clock >= nextSample) {
                sample(snap)
                val bucket = min(BUCKETS - 1, (clock / BUCKET_SECONDS).toInt())
                onSample(bucket, snap)
                nextSample = clock + 1f
            }
        }
        events.fire(Ev.FULL_TIME, -1)
    }
}
