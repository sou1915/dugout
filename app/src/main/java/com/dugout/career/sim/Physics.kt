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
                    b.vz = -b.vz * RESTITUTION
                    b.vx *= BOUNCE_KEEP
                    b.vy *= BOUNCE_KEEP
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
                    if (vz < -0.6f) { vz = -vz * RESTITUTION; vx *= BOUNCE_KEEP; vy *= BOUNCE_KEEP }
                    else vz = 0f
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
