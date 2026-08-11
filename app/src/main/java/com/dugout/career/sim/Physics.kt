package com.dugout.career.sim

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Ball flight and bodies, in METRES.
 *
 * The model is carried over from the predecessor, where it was the part of the
 * engine that was written and sound: a struck ball gets a VELOCITY, never a
 * destination, and where it ends up is then a consequence rather than a
 * decision. That is what lets a pass be over-hit, run out of play, or be
 * reached by the wrong man — three things the predecessor's outcome-first
 * passing could not express.
 *
 * The one change from the original is units. The predecessor worked in 0..100
 * pitch units with metre conversion constants on every axis; this works in
 * metres throughout, because the tactical layer is specified in metres and two
 * unit systems in one engine is a bug waiting for a tired afternoon. The tuned
 * constants below are per-second rates and dimensionless ratios, so they carry
 * across unchanged.
 */
object Physics {

    /** Rolling resistance on grass, per second, as a fraction of speed lost. */
    const val ROLL_DRAG = 0.62f
    /** Air drag off the ground. Much lower than rolling. */
    const val AIR_DRAG = 0.09f
    const val G = 9.81f
    /** How much vertical speed survives a bounce. */
    const val RESTITUTION = 0.52f
    /** How much horizontal speed survives a bounce. */
    const val BOUNCE_KEEP = 0.78f
    /** The hardest strike in football, m/s. */
    const val MAX_STRIKE = 36f

    /** Below this it has stopped. */
    private const val AT_REST = 0.25f

    /**
     * HOW MUCH HORIZONTAL SPEED A BOUNCE COSTS — proportional to the landing.
     *
     * [BOUNCE_KEEP] used to be applied flat: every contact with the ground took
     * 22% of the ball's pace, however gently it came down. At a 0.1 s tick a
     * ball with a small loft lands, hops a few centimetres and lands again — up
     * to ten times a second — and 0.78^10 is 8%. The ball simply died.
     *
     * PassTrace found it by act and a probe pinned it exactly. Distance was NOT
     * MONOTONIC IN SPEED, which no ball has ever done:
     *
     *   loft 1.0   speed 14 ->  16.16 m
     *              speed 16 ->   4.31 m    hit it harder, it goes a quarter as far
     *              speed 20 ->   5.40 m
     *              speed 22 ->  32.90 m
     *
     * The cliff is the `vz < -0.6` threshold: below it the ball settles and
     * rolls, above it it starts bouncing and every bounce robs it. So
     * PASS_SPACE and THROUGH_BALL, struck at loft 1.0, finished 10.6 m and
     * 13.5 m SHORT of where they were aimed — while a cross at loft 14 and a
     * switch at loft 11, both well clear of the cliff, were accurate to under a
     * metre. That split by act is what pointed at loft rather than at distance.
     *
     * It also broke [solveStrike] without either being wrong on its own terms:
     * bisection assumes the function it searches is monotonic, and given a
     * cliff it converges confidently on nonsense. That is why the solver
     * returned the SAME 21.83 m/s for a 10 m pass, a 20 m pass and a 32 m one.
     *
     * A gentle landing now costs almost nothing and a thumping one costs the
     * full 22%, which is continuous and is what a ball does.
     */
    private fun bounceKeep(vz: Float): Float {
        val severity = (-vz / HARD_LANDING).coerceIn(0f, 1f)
        return 1f - (1f - BOUNCE_KEEP) * severity
    }

    /** Downward speed at which a bounce costs the full [BOUNCE_KEEP], m/s. */
    private const val HARD_LANDING = 5f

    fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float =
        hypot((ax - bx).toDouble(), (ay - by).toDouble()).toFloat()

    /**
     * Advance the ball by [dt] seconds. Returns true while it is still moving,
     * so a caller can tell a live ball from a dead one without a second test.
     */
    fun stepBall(b: Ball, dt: Float): Boolean {
        if (dt <= 0f) return false

        if (b.height > 0f || b.vz != 0f) {
            b.vz -= G * dt
            b.height += b.vz * dt
            if (b.height <= 0f) {
                b.height = 0f
                if (b.vz < -0.6f) {
                    val keepH = bounceKeep(b.vz)
                    b.vz = -b.vz * RESTITUTION
                    b.vx *= keepH
                    b.vy *= keepH
                } else {
                    b.vz = 0f
                }
            }
        }

        val airborne = b.height > 0.12f
        val keep = max(0f, 1f - (if (airborne) AIR_DRAG else ROLL_DRAG) * dt)
        b.vx *= keep
        b.vy *= keep

        b.x += b.vx * dt
        b.y += b.vy * dt

        val speed = hypot(b.vx.toDouble(), b.vy.toDouble()).toFloat()
        if (speed < AT_REST && b.height <= 0f) {
            b.vx = 0f; b.vy = 0f
            return false
        }
        return true
    }

    /**
     * Strike it: a direction, a speed in m/s, and a launch angle in degrees.
     *
     * The caller says how hard and where AT, never where TO.
     */
    fun strike(b: Ball, dirX: Float, dirY: Float, mps: Float, launchDeg: Float) {
        val len = hypot(dirX.toDouble(), dirY.toDouble()).toFloat()
        if (len < 1e-4f) return
        val speed = min(mps, MAX_STRIKE)
        val rad = launchDeg * (Math.PI.toFloat() / 180f)
        val flat = speed * cos(rad.toDouble()).toFloat()
        b.vx = (dirX / len) * flat
        b.vy = (dirY / len) * flat
        b.vz = speed * sin(rad.toDouble()).toFloat()
        b.height = max(b.height, 0f)
    }

    /**
     * Where the ball will come to rest, and when.
     *
     * This is what makes ARRIVE BY possible for a moving ball: a man can ask
     * "can I be there by then?" rather than running at an aim point the ball
     * merely passes through. Simulated rather than solved, because a bounce
     * has no closed form — but it is a handful of steps at decision rate, not
     * a grid scan per tick.
     *
     * Writes x, y, seconds into [out].
     */
    /**
     * WHAT SPEED STOPS A BALL AT [d] METRES, GIVEN A LOFT — solved, not guessed.
     *
     * This replaces `d * ROLL_DRAG + 2.5 + loft * 0.35`, which was itself a
     * correction of an earlier guess and was wrong in a way nobody could see
     * from reading it. Probed directly, striking a ball and watching where it
     * came to rest:
     *
     *   aimed  loft   speed   stopped   error
     *      20   0.0   14.90     22.17   +2.17
     *      20  11.0   18.75     26.17   +6.17     a switch
     *      20  14.0   19.80     32.73  +12.73     a cross
     *      30  14.0   26.00     49.48  +19.48
     *      20  20.0   21.90     47.75  +27.75     a clearance
     *      30  20.0   28.10     73.23  +43.23
     *
     * The `+ loft * 0.35` had the SIGN OF THE WORLD WRONG. A lofted ball spends
     * its journey in the air, where the drag is a fraction of the rolling drag,
     * so it needs LESS pace to cover a distance and not more. Every cross,
     * every switch and every clearance in this engine has been struck as if the
     * air were made of grass.
     *
     * That is where the 7.13 m of "ball vs where it was aimed" comes from, and
     * with it three things that looked like separate problems: a clearance that
     * retained possession 1.6% of the time because it flew forty metres past
     * anybody, a cross that found a team-mate 14% of the time, and a delivery
     * miss that survived three attempts to fix it at the arrival end.
     *
     * There is no closed form for this — a bounce has none — so it is SOLVED by
     * bisection against [restPoint], the same simulation the engine uses to
     * decide where men should run. Built once into a grid and interpolated, so
     * the cost is a few hundred short simulations at class load and two lookups
     * per option after that. A number derived from the physics cannot drift
     * away from the physics, which is the whole reason not to fit a curve.
     */
    fun solveStrike(d: Float, loft: Float): Float {
        val dd = ((d - D_LO) / D_STEP).coerceIn(0f, (D_N - 1).toFloat())
        val ll = ((loft - L_LO) / L_STEP).coerceIn(0f, (L_N - 1).toFloat())
        val di = dd.toInt().coerceAtMost(D_N - 2)
        val li = ll.toInt().coerceAtMost(L_N - 2)
        val fd = dd - di
        val fl = ll - li
        val a = grid[li][di] + (grid[li][di + 1] - grid[li][di]) * fd
        val b = grid[li + 1][di] + (grid[li + 1][di + 1] - grid[li + 1][di]) * fd
        return (a + (b - a) * fl).coerceIn(SOLVE_LO, SOLVE_HI)
    }

    private const val D_LO = 2f
    private const val D_STEP = 1f
    private const val D_N = 61          // 2 m .. 62 m
    private const val L_LO = 0f
    /**
     * Half-metre loft rows. The function is wobbly between bounce regimes, so
     * interpolating across a whole unit of loft cost up to 4.6 m on the 1.5
     * that every short pass uses. Rows are cheap; a wrong pass is not.
     */
    private const val L_STEP = 0.5f
    private const val L_N = 49          // loft 0 .. 24
    /** The engine's tick. MatchSim.DT, repeated here so Physics owns no import. */
    private const val SOLVE_DT = 0.1f
    private const val SOLVE_LO = 4f
    private const val SOLVE_HI = 32f

    /** [loft][distance] -> the pace that stops it there. */
    private val grid: Array<FloatArray> = Array(L_N) { li ->
        val loft = L_LO + li * L_STEP
        FloatArray(D_N) { di -> bisect(D_LO + di * D_STEP, loft) }
    }

    /**
     * Bisect with the SAME INTEGRATOR THE MATCH USES.
     *
     * The first version solved against [restPoint], which steps at 0.05 s
     * because it is answering a different question. The match steps at 0.1 s,
     * and with exponential drag a coarser step keeps more speed — so a solution
     * calibrated on the fine integrator systematically overshot in the engine,
     * by up to sixteen metres on a lofted ball. A solver that does not run the
     * physics it is solving is fitting a curve again with extra steps.
     */
    private fun bisect(d: Float, loft: Float): Float {
        /*
         * SCANNED, NOT BISECTED — because the function is not monotonic.
         *
         * Making the bounce proportional turned a 16 m cliff into a 4 m wobble,
         * but a wobble is still enough to send a bisection to the wrong root,
         * and a solver that silently depends on an assumption nobody checks is
         * how this bug survived in the first place. A scan needs no assumption
         * at all: try every pace, keep the one that lands nearest. It costs 140
         * short flights per grid cell, once, at class load.
         */
        val probe = Ball()
        var best = SOLVE_LO
        var bestErr = Float.MAX_VALUE
        var v = SOLVE_LO
        while (v <= SOLVE_HI) {
            probe.place(0f, 0f)
            strike(probe, 1f, 0f, v, loft)
            var steps = 0
            while (stepBall(probe, SOLVE_DT) && steps++ < 400) { /* fly */ }
            val err = abs(probe.x - d)
            if (err < bestErr) { bestErr = err; best = v }
            v += SCAN_STEP
        }
        return best
    }

    /** Pace resolution of the scan, m/s. */
    private const val SCAN_STEP = 0.2f

    fun restPoint(b: Ball, out: FloatArray) {
        var x = b.x; var y = b.y
        var vx = b.vx; var vy = b.vy; var vz = b.vz; var h = b.height
        var t = 0f
        val dt = 0.05f
        var steps = 0
        while (steps++ < 200) {
            val speed = hypot(vx.toDouble(), vy.toDouble()).toFloat()
            if (speed < AT_REST && h <= 0f) break
            if (h > 0f || vz != 0f) {
                vz -= G * dt
                h += vz * dt
                if (h <= 0f) {
                    h = 0f
                    if (vz < -0.6f) {
                        val keepH = bounceKeep(vz)
                        vz = -vz * RESTITUTION; vx *= keepH; vy *= keepH
                    } else vz = 0f
                }
            }
            val keep = max(0f, 1f - (if (h > 0.12f) AIR_DRAG else ROLL_DRAG) * dt)
            vx *= keep; vy *= keep
            x += vx * dt; y += vy * dt
            t += dt
            if (x < -3f || x > Pitch.LENGTH + 3f || y < -3f || y > Pitch.WIDTH + 3f) break
        }
        out[0] = x; out[1] = y; out[2] = t
    }

    /**
     * How long a man takes to reach a point, allowing for the fact that he is
     * already moving.
     *
     * The whole difference between a distance and a real evaluation of space:
     * a defender sprinting the wrong way is FURTHER from a cell than one
     * standing still at the same range, and only a time-based model says so.
     */
    fun timeToReach(
        px: Float, py: Float, vx: Float, vy: Float,
        tx: Float, ty: Float, topSpeed: Float, accel: Float
    ): Float {
        val dx = tx - px
        val dy = ty - py
        val d = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (d < 0.01f) return 0f
        val ux = dx / d
        val uy = dy / d
        val along = vx * ux + vy * uy
        val deficit = max(0f, topSpeed - along)
        val tAccel = deficit / max(0.5f, accel)
        val dAccel = max(0f, along) * tAccel + 0.5f * accel * tAccel * tAccel
        return if (dAccel >= d) {
            val a = 0.5f * accel
            val bq = max(0f, along)
            val disc = bq * bq + 4f * a * d
            (-bq + sqrt(disc.toDouble()).toFloat()) / (2f * a)
        } else {
            tAccel + (d - dAccel) / max(0.5f, topSpeed)
        }
    }

    /**
     * Move a man toward a point at a CHOSEN speed, under acceleration and turn
     * limits. He cannot reverse on the spot and he cannot carry full pace
     * through a hard turn, so his path is an arc.
     *
     * Note what this does not do: it does not decide how fast he goes. That is
     * the caller's, and at step 2 the caller derives it from a deadline — see
     * [Man.arriveBy]. A speed chosen by a table of distance bands is what made
     * the predecessor's men arrive at a corner after it had been taken.
     */
    fun stepBody(p: Man, tx: Float, ty: Float, want: Float, dt: Float): Float {
        val dx = tx - p.x
        val dy = ty - p.y
        val d = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (d < 0.05f) {
            p.vx *= max(0f, 1f - 6f * dt)
            p.vy *= max(0f, 1f - 6f * dt)
            p.x += p.vx * dt; p.y += p.vy * dt
            p.speed = 0f
            return 0f
        }

        val heading = atan2(dy.toDouble(), dx.toDouble()).toFloat()
        if (!p.headingSet) { p.heading = heading; p.headingSet = true }
        var delta = heading - p.heading
        while (delta > Math.PI) delta -= (Math.PI * 2).toFloat()
        while (delta < -Math.PI) delta += (Math.PI * 2).toFloat()

        val speed = hypot(p.vx.toDouble(), p.vy.toDouble()).toFloat()
        val maxTurn = p.turnRate * dt / (1f + speed * 0.22f)
        p.heading += delta.coerceIn(-maxTurn, maxTurn)

        // slow for a hard turn, and to arrive rather than overshoot
        val turnCost = 1f - min(0.6f, abs(delta) * 0.34f)
        val arrive = min(1f, 0.3f + d / 3f)
        val target = want * turnCost * arrive

        val newSpeed =
            if (speed < target) min(target, speed + p.accel * dt)
            else max(target, speed - p.accel * 1.6f * dt)

        val hx = cos(p.heading.toDouble()).toFloat()
        val hy = sin(p.heading.toDouble()).toFloat()
        p.vx = hx * newSpeed
        p.vy = hy * newSpeed
        val stepX = p.vx * dt
        val stepY = p.vy * dt
        p.x += stepX
        p.y += stepY
        p.speed = newSpeed
        return hypot(stepX.toDouble(), stepY.toDouble()).toFloat()
    }
}

/** The ball, as an object with a velocity. Metres and m/s throughout. */
class Ball {
    @JvmField var x = 0f
    @JvmField var y = 0f
    @JvmField var height = 0f
    @JvmField var vx = 0f
    @JvmField var vy = 0f
    @JvmField var vz = 0f

    fun place(px: Float, py: Float) {
        x = px; y = py; height = 0f; vx = 0f; vy = 0f; vz = 0f
    }

    val moving: Boolean get() = vx != 0f || vy != 0f || vz != 0f || height > 0f
}
