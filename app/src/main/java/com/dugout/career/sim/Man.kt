package com.dugout.career.sim

import kotlin.math.max

/**
 * One of the twenty-two. Metres and m/s.
 *
 * There are no attributes here yet and that is deliberate: step 2 is eleven
 * men who move to targets, and a man who differs from his team-mate by a
 * rating would make the shape harder to read before there is anything to read.
 * Attributes arrive when they gate something — see the brief, §2.8.
 */
class Man(
    @JvmField val side: Int,
    @JvmField val slot: Slot
) {
    @JvmField var x = 0f
    @JvmField var y = 0f
    @JvmField var vx = 0f
    @JvmField var vy = 0f
    @JvmField var speed = 0f
    @JvmField var heading = 0f
    @JvmField var headingSet = false

    /** Where he is trying to be, absolute metres, and when he means to be there. */
    @JvmField var targetX = 0f
    @JvmField var targetY = 0f
    @JvmField var deadline = 1f

    /** Resolved once, not looked up per tick. */
    @JvmField val role: Role = Roles[slot.role]
    private val duty: DutyMod = Roles.mod(slot.duty)

    /** How far the block's slide may carry him from his anchor. */
    @JvmField val leashM: Float = role.leashM * duty.leashScale

    /**
     * How reluctant he is to go a long way for it, 0..1.
     *
     * The first version of this was a flat penalty in seconds and it was
     * WRONG — measured, not guessed. At a three second scale against claim
     * times of one to three seconds it stopped being a tilt and became a veto:
     * `RoleCheck` showed ten of fourteen roles taking 0.0 touches a match, so
     * most roles could never produce an event at all.
     *
     * The honest model is that reluctance scales with DISTANCE. A poacher is as
     * quick as anyone to a ball at his feet; what he will not do is sprint
     * thirty metres back for one. So this multiplies a distance term at the
     * claim, and a man near the ball is never handicapped for his role.
     */
    @JvmField val reluctance: Float =
        1f - (role.chase * duty.chaseScale).coerceIn(0f, 1f)

    @JvmField val maxAttX: Float = role.maxAttX

    val isKeeper: Boolean get() = slot.isKeeper

    val topSpeed: Float get() = if (isKeeper) 6.4f else 7.6f
    val accel: Float get() = if (isKeeper) 4.4f else 5.2f
    val turnRate: Float get() = if (isKeeper) 4.6f else 5.4f

    fun place(px: Float, py: Float) {
        x = px; y = py
        vx = 0f; vy = 0f; speed = 0f
        targetX = px; targetY = py
    }

    /**
     * ARRIVE BY, not RUN HARD.
     *
     * The predecessor answered "how fast should this man run?" with a table of
     * distance bands times a hand-tuned urgency, and that is why its men
     * reached a corner after it had been taken: the multiplier does not know
     * the deadline. So invert it. A target comes with a TIME, and the speed is
     * derived:
     *
     *     needed = distance / secondsLeft,  clamped to his physical maximum
     *
     * A man 40 m from the six-yard box with 8 seconds runs at 5 m/s and
     * arrives. The same man with 30 seconds walks. Nobody sprints because a
     * number said 0.95.
     */
    fun step(dt: Float): Float {
        val d = Physics.dist(x, y, targetX, targetY)
        val secondsLeft = max(0.2f, deadline)
        val needed = d / secondsLeft
        val want = needed.coerceIn(0f, topSpeed)
        deadline = max(0f, deadline - dt)
        return Physics.stepBody(this, targetX, targetY, want, dt)
    }

    fun aim(tx: Float, ty: Float, seconds: Float) {
        targetX = tx.coerceIn(-1f, Pitch.LENGTH + 1f)
        targetY = ty.coerceIn(-1f, Pitch.WIDTH + 1f)
        deadline = seconds
    }
}

/**
 * Deterministic RNG.
 *
 * Two streams, per the brief: [outcome] decides football, [presentation]
 * decides anything that is only looked at. A new cosmetic draw goes on the
 * presentation stream and the fingerprint then proves it did — which is the
 * only reason a cosmetic change can ever be trusted to be cosmetic.
 *
 * SplitMix64 rather than java.util.Random so the sequence is ours and cannot
 * drift with a platform.
 */
class Rng(seed: Long) {
    private var s = seed

    fun nextLong(): Long {
        s += -0x61c8864680b583ebL
        var z = s
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    /** 0 until n. */
    fun nextInt(n: Int): Int {
        require(n > 0)
        return ((nextLong() ushr 1) % n).toInt()
    }

    /** 0f until 1f. */
    fun nextFloat(): Float = ((nextLong() ushr 11).toDouble() / (1L shl 53).toDouble()).toFloat()

    fun range(lo: Float, hi: Float): Float = lo + (hi - lo) * nextFloat()
}
