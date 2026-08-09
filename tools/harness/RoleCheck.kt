package harness

import com.dugout.career.sim.Duty
import com.dugout.career.sim.Formation
import com.dugout.career.sim.MatchSim
import com.dugout.career.sim.Pitch
import com.dugout.career.sim.RoleId
import kotlin.math.abs

/**
 * THE ROLE ACCEPTANCE TEST.
 *
 * The brief: "two sides identical except for one role change must produce
 * visibly different heat maps and different event rates. If they do not, the
 * role is not implemented — it is decoration."
 *
 * So: one slot, one anchor, everything else held fixed, and the role under
 * test swapped. The heat map of THAT MAN is accumulated and every pair of
 * roles is compared. The output is a separation matrix, and the honest part of
 * it is the list of pairs that fail — a role that cannot be told apart from its
 * neighbour is decoration however good its name is.
 *
 *   java -cp build/gate.jar harness.RoleCheckKt [matches]
 */

/** The slot under test — a central midfield anchor in 4-3-3, lane 2.7 band 2.8. */
private const val SLOT = 7

/**
 * The heat map is measured on a FINER grid than the tactical 5x6.
 *
 * The first version used the game's own grid and reported sixteen pairs of
 * roles as identical — including two whose mean position differed by 4.6 m.
 * That was the ruler, not the football: a tactical band is 17.5 m deep, so a
 * five metre shift moves almost nobody across a boundary and shows up as zero.
 * A grid that cannot resolve the difference it is asked about will always
 * answer "no difference", which is the most dangerous kind of measurement.
 *
 * 10 x 12 gives cells of about 6.8 x 8.8 m. The centroid distance in metres is
 * printed next to it, because it needs no grid at all.
 */
private const val FL = 10
private const val FB = 12

private class Heat {
    val cell = DoubleArray(FL * FB)
    var samples = 0.0
    var sumX = 0.0
    var sumY = 0.0
    var touches = 0.0

    fun add(ax: Float, ay: Float) {
        val l = ((ay / Pitch.WIDTH) * FL).toInt().coerceIn(0, FL - 1)
        val b = ((ax / Pitch.LENGTH) * FB).toInt().coerceIn(0, FB - 1)
        cell[l * FB + b] += 1.0
        sumX += ax; sumY += ay; samples += 1.0
    }

    /** Normalised so two roles are compared on shape, not on sample count. */
    fun normalised(): DoubleArray {
        val out = DoubleArray(cell.size)
        if (samples <= 0) return out
        for (i in cell.indices) out[i] = cell[i] / samples
        return out
    }

    val meanX: Double get() = if (samples == 0.0) 0.0 else sumX / samples
    val meanY: Double get() = if (samples == 0.0) 0.0 else sumY / samples
}

/** Total variation distance between two normalised heat maps, 0..1. */
private fun separation(a: DoubleArray, b: DoubleArray): Double {
    var s = 0.0
    for (i in a.indices) s += abs(a[i] - b[i])
    return s / 2.0
}

private fun run(role: RoleId, matches: Int): Heat {
    val base = Formation.preset("4-3-3")
    val shape = Formation.withRole(base, SLOT, role)
    val heat = Heat()

    for (i in 0 until matches) {
        val sim = MatchSim(1000L + i, homeShape = shape)
        val man = sim.men.first { it.side == 0 && it.slot.id == SLOT }
        sim.onCarry = { m, _, _ -> if (m === man) heat.touches += 1.0 }
        var n = 0
        sim.play { _, _ ->
            heat.add(Pitch.attX(0, man.x), Pitch.attY(0, man.y))
            n++
        }
    }
    return heat
}

fun main(args: Array<String>) {
    val matches = if (args.isNotEmpty()) args[0].toInt() else 6

    // Every role that could plausibly occupy a midfield slot, plus the ones the
    // brief names. Testing them all at one anchor is what makes the comparison
    // fair; picking a flattering slot per role would be marking my own homework.
    val roles = listOf(
        RoleId.HOLDING_MID, RoleId.BOX_TO_BOX, RoleId.DEEP_PLAYMAKER,
        RoleId.ADVANCED_PLAYMAKER, RoleId.INVERTED_FB, RoleId.OVERLAPPING_FB,
        RoleId.TOUCHLINE_WINGER, RoleId.INSIDE_FORWARD,
        RoleId.POACHER, RoleId.TARGET_MAN, RoleId.FALSE_NINE,
        RoleId.BALL_PLAYING_DEFENDER, RoleId.STOPPER, RoleId.COVER
    )

    println("ROLECHECK — slot $SLOT of a 4-3-3, $matches matches per role")
    println("Everything is held fixed except the role. Same anchor, same shape,")
    println("same seeds, same opposition — so any difference below is the role.")
    println()

    val heat = LinkedHashMap<RoleId, Heat>()
    for (r in roles) heat[r] = run(r, matches)

    println(String.format("%-24s %9s %9s %10s", "role", "own-x m", "lane y m", "touches"))
    println("-".repeat(56))
    for (r in roles) {
        val h = heat.getValue(r)
        println(String.format("%-24s %9.2f %9.2f %10.1f",
            r.name, h.meanX, h.meanY, h.touches / matches))
    }

    // ------------------------------------------------- separation matrix
    val norm = roles.associateWith { heat.getValue(it).normalised() }
    val FLOOR = 0.15

    println()
    println("SEPARATION — total variation between heat maps on a ${FL}x${FB} grid")
    println("0 = identical, 1 = disjoint. A pair below $FLOOR is two names for the same man.")
    println()
    print(String.format("%-22s", ""))
    for (r in roles) print(String.format("%5s", r.name.take(4)))
    println()
    for (a in roles) {
        print(String.format("%-22s", a.name.take(21)))
        for (b in roles) {
            if (a == b) { print("    -"); continue }
            print(String.format("%5.2f", separation(norm.getValue(a), norm.getValue(b))))
        }
        println()
    }

    val fails = ArrayList<String>()
    for (i in roles.indices) for (j in i + 1 until roles.size) {
        val a = heat.getValue(roles[i])
        val b = heat.getValue(roles[j])
        val s = separation(norm.getValue(roles[i]), norm.getValue(roles[j]))
        val metres = kotlin.math.hypot(a.meanX - b.meanX, a.meanY - b.meanY)
        if (s < FLOOR) fails.add(String.format(
            "%.2f  %-22s vs %-22s  (centroids %.2f m apart)",
            s, roles[i].name, roles[j].name, metres))
    }

    println()
    if (fails.isEmpty()) {
        println("VERDICT: every pair separates. No role is decoration at this slot.")
    } else {
        println("VERDICT: ${fails.size} pair(s) below the floor — these are DECORATION:")
        fails.sorted().forEach { println("  $it") }
    }

    // ------------------------------------------------------------- duty
    println()
    println("DUTY — the same role, pushed. Duty is a transform, not a fourth table.")
    val base = Formation.preset("4-3-3")
    for (d in Duty.entries) {
        val shape = Formation(base.name,
            base.slots.map { if (it.id == SLOT) it.copy(role = RoleId.BOX_TO_BOX, duty = d) else it },
            base.block)
        val h = Heat()
        for (i in 0 until matches) {
            val sim = MatchSim(1000L + i, homeShape = shape)
            val man = sim.men.first { it.side == 0 && it.slot.id == SLOT }
            sim.onCarry = { m, _, _ -> if (m === man) h.touches += 1.0 }
            sim.play { _, _ ->
                val ax = Pitch.attX(0, man.x)
                h.add(ax, Pitch.attY(0, man.y))
            }
        }
        println(String.format("  BOX_TO_BOX / %-8s own-x %6.2f m   touches %6.1f",
            d.name, h.meanX, h.touches / matches))
    }
}
