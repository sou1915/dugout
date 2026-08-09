package gate

/**
 * The two fingerprints of GAME_BRIEF.md §1.2, and the readable breakdown that
 * the shape fingerprint is required to ship with.
 *
 * Nothing here knows what a football is. It is given records and it hashes
 * them, so that when the hash moves the gate can say which statistic moved and
 * by how much rather than only that something did.
 */

// ---------------------------------------------------------------- FNV-1a

/** FNV-1a, 64 bit. The same hash the predecessor's gate used. */
class Fnv {
    private var h = -0x340d631b7bdddcdbL          // 0xcbf29ce484222325

    fun put(v: Long): Fnv {
        var x = v
        repeat(8) {
            h = (h xor (x and 0xff)) * 0x100000001b3L
            x = x ushr 8
        }
        return this
    }

    fun put(v: Int) = put(v.toLong())

    fun hex(): String = java.lang.Long.toHexString(h).padStart(16, '0')
}

// ------------------------------------------------------- the result record

/**
 * What the result fingerprint hashes, per match: score, shots, cards and
 * corners for both sides. Deliberately the same fields as the predecessor's,
 * so a number from either project means the same thing.
 */
data class MatchResult(
    val homeGoals: Int, val awayGoals: Int,
    val homeShots: Int, val awayShots: Int,
    val homeCards: Int, val awayCards: Int,
    val homeCorners: Int, val awayCorners: Int
) {
    fun feed(f: Fnv) {
        f.put(homeGoals).put(awayGoals)
        f.put(homeShots).put(awayShots)
        f.put(homeCards).put(awayCards)
        f.put(homeCorners).put(awayCorners)
    }
}

// -------------------------------------------------------- the shape record

/**
 * One quantised statistic in the shape digest.
 *
 * [quantum] is the size of one step in the statistic's own unit — metres for a
 * depth, men for a count. Everything is compared and hashed in whole quanta, so
 * a change smaller than the quantum is not a change, and the breakdown can say
 * "block depth 31.5 m -> 34.0 m, +5 quanta" instead of showing float noise.
 */
class ShapeStat(val name: String, val quantum: Double) {
    private var sum = 0.0
    private var n = 0

    fun add(raw: Double) { sum += raw; n++ }

    val mean: Double get() = if (n == 0) 0.0 else sum / n
    val quanta: Long get() = Math.round(mean / quantum)
    val observations: Int get() = n
}

/**
 * The positional digest: mean lane occupancy per band, block depth and
 * goal-side count in the box, per team, per five-minute bucket, quantised.
 *
 * The components are built up front and in a fixed order, so the hash is a
 * function of the football and not of iteration order or of which statistics
 * happened to be observed in a given run.
 */
class ShapeDigest {
    private val order = ArrayList<ShapeStat>()
    private val byName = HashMap<String, ShapeStat>()

    fun stat(name: String, quantum: Double): ShapeStat =
        byName.getOrPut(name) { ShapeStat(name, quantum).also { order.add(it) } }

    fun components(): List<ShapeStat> = order

    fun hex(): String {
        val f = Fnv()
        for (s in order) { for (c in s.name) f.put(c.code); f.put(s.quanta) }
        return f.hex()
    }

    /** `shape.<name>=<quanta>` lines for the baseline file. */
    fun asBaselineLines(): List<String> =
        order.map { "shape.${it.name}=${it.quanta}" }

    companion object {
        /**
         * The component set, declared in one place so the digest a run
         * produces and the digest a baseline records cannot drift apart.
         *
         * Step 2 fills these in as eleven men move; step 1 only declares them.
         */
        fun declare(): ShapeDigest {
            val d = ShapeDigest()
            for (team in arrayOf("home", "away")) {
                d.stat("$team.block_depth_m", 0.5)
                d.stat("$team.goal_side_in_box", 0.25)
                for (lane in LANES) for (band in 0 until 6)
                    d.stat("$team.occupancy.$lane.b$band", 0.25)
            }
            return d
        }

        val LANES = arrayOf("lw", "lhs", "c", "rhs", "rw")
    }
}

// ------------------------------------------------------------- the diff

class Moved(val name: String, val was: Long, val now: Long, val quantum: Double) {
    val delta: Long get() = now - was
    fun line(): String {
        val unit = if (quantum >= 0.5) "m" else "men"
        return String.format(
            "  %-28s %8.2f -> %8.2f %-4s  %+d quanta",
            name, was * quantum, now * quantum, unit, delta
        )
    }
}

/**
 * Compare a digest against a recorded baseline and return what moved, worst
 * first. A component the baseline has never seen is reported as moved from
 * zero rather than silently ignored — a new statistic changes the hash, and
 * the reader is owed the reason.
 */
fun diff(digest: ShapeDigest, baseline: Map<String, Long>): List<Moved> {
    val out = ArrayList<Moved>()
    for (s in digest.components()) {
        val was = baseline["shape.${s.name}"] ?: 0L
        if (was != s.quanta) out.add(Moved(s.name, was, s.quanta, s.quantum))
    }
    out.sortByDescending { Math.abs(it.delta) }
    return out
}

/**
 * The breakdown §1.2 requires. A hash that only says "different" is a shrug,
 * and a gate re-recorded on a shrug is not a gate.
 */
fun printBreakdown(moved: List<Moved>, out: Appendable = System.out) {
    if (moved.isEmpty()) {
        out.append("  (no component moved — the hash changed without the football changing,\n")
        out.append("   which means the component set itself was edited)\n")
        return
    }
    out.append("  ${moved.size} statistic(s) moved, worst first:\n")
    for (m in moved.take(20)) { out.append(m.line()); out.append('\n') }
    if (moved.size > 20) out.append("  ... and ${moved.size - 20} more\n")
}
