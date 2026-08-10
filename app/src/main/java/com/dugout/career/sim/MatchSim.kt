package com.dugout.career.sim

import kotlin.math.abs
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

        /** How far short of his man a ball must die to count as cut out. */
        const val INTERCEPT_GAP = 7f

        /** A struck ball shorter than this is short. Length is a fact. */
        const val SHORT_PASS_M = 24f

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
    private var stillFor = 0f
    private var ticks = 0
    private var wasMoving = false

    /** Deepest compiled anchor per side — where the defensive line starts. */
    private val deepestAx = FloatArray(2)

    private val supportX = FloatArray(22)
    private val supportY = FloatArray(22)
    private val supportSet = BooleanArray(22)

    /** Who struck it last, and from where — the two facts a touch resolves against. */
    private var lastStriker: Man? = null
    private var lastKind: OptKind? = null
    private var lastReceiver: Man? = null
    private var strikeX = 0f
    private var strikeY = 0f

    private val optionBuf = ArrayList<Option>(Decide.MAX_OPTIONS + 2)

    /** Set by a harness to sample the CHOSEN option. Never read by the engine. */
    @JvmField var onChoice: ((Man, Option, Int, Float) -> Unit)? = null

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
        restartSide = side
        lastStriker = null
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
    private fun strikeOn(m: Man) {
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

        Decide.score(this, m, optionBuf)
        val chosen = Decide.choose(
            optionBuf, Decide.temperature(pressure),
            draw.next("T$ticks.S${m.side}P${m.slot.id}.CHOOSE")
        )
        onChoice?.invoke(m, chosen, optionBuf.size, pressure)

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
                ball.place(blocker.x, blocker.y)
                val bc = "T$ticks.S${blocker.side}P${blocker.slot.id}.BLOCK"
                Physics.strike(
                    ball,
                    draw.range("$bc.X", -1f, 1f), draw.range("$bc.Y", -1f, 1f),
                    draw.range("$bc.PACE", 4f, 11f), draw.range("$bc.LOFT", 0f, 14f)
                )
                lastStriker = blocker
                lastKind = OptKind.CLEAR
                strikeX = ball.x
                strikeY = ball.y
                restartSide = -1
                stillFor = 0f
                return
            }
        }

        lastStriker = m
        lastKind = chosen.kind
        lastReceiver = chosen.receiver
        strikeX = ball.x
        strikeY = ball.y
        restartSide = -1
        Physics.strike(ball, tx - ball.x, ty - ball.y, chosen.mps, chosen.loft)
        stillFor = 0f
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
     * A man has got to the ball. Report the two facts that are true regardless
     * of what anybody intended: how far it travelled, and whose it is now.
     */
    private fun resolveTouch(m: Man) {
        val s = lastStriker ?: return
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
        }
        if (m.side != s.side) {
            val target = lastReceiver
            val cutOut = target != null &&
                Physics.dist(ball.x, ball.y, target.x, target.y) > INTERCEPT_GAP
            events.fire(if (cutOut) Ev.INTERCEPTION else Ev.DISPOSSESSED, m.side)
        }
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
                    val gx = if (defender == 0) 7f else Pitch.LENGTH - 7f
                    ball.place(gx, ball.y.coerceIn(6f, Pitch.WIDTH - 6f))
                    deadBall(defender)
                    return true
                }
                events.fire(Ev.GOAL, scorer)
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
                val lineAx = min(deepestAx[m.side], ballAx - DEFEND_STANDOFF).coerceAtLeast(3f)
                val squeeze = (ballAx / 42f).coerceIn(0.28f, 1f)
                val rel = (ax - deepestAx[m.side]) * squeeze
                tAx = (lineAx + rel).coerceAtMost(ballAx + GOAL_SIDE_ALLOWANCE)
                    .coerceIn(1.5f, Pitch.LENGTH - 1.5f).coerceAtMost(m.maxAttX)
            } else {
                tAx = (ax + dx).coerceIn(1.5f, Pitch.LENGTH - 1.5f).coerceAtMost(m.maxAttX)
            }
            val tAy = (ay + dy).coerceIn(1.5f, Pitch.WIDTH - 1.5f)
            var wantX = Pitch.absX(m.side, tAx)
            var wantY = Pitch.absY(m.side, tAy)

            val i = men.indexOf(m)
            if (supportSet[i]) {
                wantX += (supportX[i] - wantX) * SUPPORT_BLEND
                wantY += (supportY[i] - wantY) * SUPPORT_BLEND
            }
            m.aim(wantX, wantY, max(1.2f, rest[2]))
        }
    }

    private fun tick() {
        var moving = Physics.stepBall(ball, DT)

        if (leftTheField()) {
            moving = false
            updateClaim()
        }

        // The rest point is a short simulation, so it runs at decision cadence
        // rather than once a frame. Recomputing it every tick costs ten million
        // physics steps a match and buys three hundredths of a second of
        // accuracy in where a man is heading.
        if (ticks % CLAIM_EVERY == 0) { updateClaim(); updateSupport() }
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
                resolveTouch(c)
                strikeOn(c)
            } else if (stillFor > 6f) {
                val f = c ?: men[0]
                resolveTouch(f)
                strikeOn(f)
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
     * THE LOOSE BALL — attempted as a 50-50 contest, and it missed.
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
     */
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
