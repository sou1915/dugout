package com.dugout.career.sim

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

        /** A struck ball shorter than this is short. Length is a fact. */
        const val SHORT_PASS_M = 24f

        const val CROSSBAR_M = 2.44f
    }

    /** Outcomes. Presentation draws go on [presentation] and never on this. */
    private val outcome = Rng(seed)

    @Suppress("unused")
    private val presentation = Rng(seed xor 0x5eed_0000_0000_0001L)

    val ball = Ball()
    val men = ArrayList<Man>(22)
    @JvmField val events = EventLog()

    private val anchorAx = Array(2) { FloatArray(11) }
    private val anchorAy = Array(2) { FloatArray(11) }

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

    /** Who struck it last, and from where — the two facts a touch resolves against. */
    private var lastStriker: Man? = null
    private var strikeX = 0f
    private var strikeY = 0f

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
    @JvmField var arriveGapSum = 0.0
    @JvmField var arriveGapCount = 0

    init {
        for (side in 0..1) {
            val shape = if (side == 0) homeShape else awayShape
            shape.compile(inPossession = false, outAx = anchorAx[side], outAy = anchorAy[side])
            for (s in shape.slots) men.add(Man(side, s))
        }
        resetPositions()
        kickOff(0)
        updateClaim()
    }

    private fun resetPositions() {
        for (m in men) {
            val ax = anchorAx[m.side][m.slot.id]
            val ay = anchorAy[m.side][m.slot.id]
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
     * PLACEHOLDER, AND IT IS MARKED AS ONE.
     *
     * Something has to put the ball in motion so that ball flight, the block
     * that follows it and the event plumbing can be exercised and measured.
     * This is not a decision layer, it does not score options, and it must be
     * DELETED at step 5 rather than grown into one. Every rate the census
     * prints is a rate produced by this, which is why the census says so at the
     * top in capital letters.
     */
    private fun strikeOn(m: Man) {
        val forward = if (m.side == 0) 1f else -1f
        val tx = (m.x + forward * outcome.range(8f, 46f)).coerceIn(2f, Pitch.LENGTH - 2f)
        val ty = outcome.range(3f, Pitch.WIDTH - 3f)
        val mps = outcome.range(11f, 26f)
        val loft = if (outcome.nextFloat() < 0.30f) outcome.range(8f, 22f) else outcome.range(0f, 4f)
        if (cornerPending) { events.fire(Ev.CORNER_TAKEN, m.side); cornerPending = false }
        lastStriker = m
        strikeX = ball.x
        strikeY = ball.y
        restartSide = -1
        Physics.strike(ball, tx - ball.x, ty - ball.y, mps, loft)
        stillFor = 0f
    }

    /**
     * A man has got to the ball. Report the two facts that are true regardless
     * of what anybody intended: how far it travelled, and whose it is now.
     */
    private fun resolveTouch(m: Man) {
        val s = lastStriker ?: return
        val d = Physics.dist(strikeX, strikeY, ball.x, ball.y)
        events.fire(if (d < SHORT_PASS_M) Ev.PASS_SHORT else Ev.PASS_LONG, s.side)
        if (m.side == s.side) events.fire(Ev.PASS_COMPLETED, s.side)
        else events.fire(Ev.INTERCEPTION, m.side)
        lastStriker = null
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
            val t = Physics.timeToReach(m.x, m.y, m.vx, m.vy, rest[0], rest[1], m.topSpeed, m.accel)
            if (t < bestT) { bestT = t; best = m }
        }
        claimant = best
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
            val ax = anchorAx[m.side][m.slot.id]
            val ay = anchorAy[m.side][m.slot.id]
            val ballAx = Pitch.attX(m.side, ball.x)
            val ballAy = Pitch.attY(m.side, ball.y)
            val tAx = (ax + (ballAx - Pitch.LENGTH * 0.5f) * SLIDE_X)
                .coerceIn(1.5f, Pitch.LENGTH - 1.5f)
            val tAy = (ay + (ballAy - Pitch.WIDTH * 0.5f) * SLIDE_Y)
                .coerceIn(1.5f, Pitch.WIDTH - 1.5f)
            m.aim(Pitch.absX(m.side, tAx), Pitch.absY(m.side, tAy), max(1.2f, rest[2]))
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
        if (ticks % CLAIM_EVERY == 0) updateClaim()
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

    /** Fill [into] with this instant's shape. */
    fun sample(into: ShapeSnapshot) {
        for (side in 0..1) {
            java.util.Arrays.fill(into.occupancy[side], 0f)
            var lo = Float.MAX_VALUE
            var hi = -Float.MAX_VALUE
            var goalSide = 0
            val inOwnBox = Pitch.inOwnBox(side, ball.x, ball.y)
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
