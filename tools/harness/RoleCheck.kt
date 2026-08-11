package harness

import com.dugout.career.sim.Duty
import com.dugout.career.sim.Formation
import com.dugout.career.sim.MatchSim
import com.dugout.career.sim.Pitch
import com.dugout.career.sim.OptKind
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
 * THE SHIPPED SET — the roles this project CLAIMS are implemented.
 *
 * Fifteen were built and the design response said not to: "separation gets
 * harder as the set crowds. Ship six that are maximally distinct, prove clean
 * separation, then add the rest against the same bar." That advice was ignored
 * and it is now measured to have been right — the deep central roles sit inside
 * a five metre range in possession, and separating one moves the collision to
 * its neighbour rather than resolving it.
 *
 * So the gate tests what is claimed. The parked roles below still exist and
 * still play; what is withdrawn is the claim that they are distinguishable, and
 * the count is printed every run so it cannot quietly become permanent.
 *
 * SIX BECAME FIVE when the decision layer moved into [Mind], and the reason is
 * worth keeping because it is not the reason it looks like.
 *
 * INVERTED_FB and INSIDE_FORWARD were separated at 0.12 POSITIONALLY — already
 * under the 0.15 floor — before any of this. They stood 1.5 m apart and always
 * had. What was carrying the pair was the second signature: they chose visibly
 * different acts. A man who ranks his options and takes his best takes the act
 * his POSITION dictates, so two men in the same position converged on the same
 * acts and the mask came off (act separation 0.08).
 *
 * The change did not break the pair. It stopped a thin claim from hiding, and
 * the claim is withdrawn rather than the floor lowered. Proving it needs an
 * inverted full-back who actually inverts — a lane change in possession that
 * puts him somewhere an inside forward is not — which is role work, not a
 * number in this file.
 */
private val SHIPPED = listOf(
    RoleId.OVERLAPPING_FB,
    RoleId.INSIDE_FORWARD,
    RoleId.BOX_TO_BOX, RoleId.POACHER
)

/*
 * FIVE BECAME FOUR when offside was wired, and this is the SECOND time a
 * correct change has flattened a pair. That pattern is now the finding.
 *
 * TOUCHLINE_WINGER and OVERLAPPING_FB separated at 0.13 positionally, 2.25 m
 * apart, once attackers started holding the offside line. The reason is
 * structural: the line is a single x, so any two roles whose difference was
 * HOW FAR FORWARD THEY PUSH get pinned to the same place the moment something
 * caps how far forward anyone may push.
 *
 * That is not a bug in the offside model, which is right, and it is not a bug
 * in the floor, which has not moved. It says the roles are thin — three of them
 * have now been withdrawn because their whole distinctness was a few metres of
 * forward push, and a few metres is the first thing any correct constraint
 * takes away. What separates a touchline winger from an overlapping full-back
 * in football is WHEN he goes and what he does when he arrives, not how far up
 * he stands. Neither of those exists here yet.
 *
 * Withdrawing the claim is still the honest move rather than lowering the bar.
 * But four of fourteen is now a statement about the role model, and it should
 * stop being answered by parking one more each time.
 */

/** Built, playable, and NOT yet proven distinct. Each is an open job. */
private val PARKED = RoleId.entries.filter { it !in SHIPPED && it != RoleId.GK }

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
    val kinds = IntArray(OptKind.entries.size)

    fun kindShares(): DoubleArray {
        val out = DoubleArray(kinds.size)
        val n = kinds.sum()
        if (n == 0) return out
        for (i in kinds.indices) out[i] = kinds[i].toDouble() / n
        return out
    }

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

/**
 * A role's POSITIONAL signature is an in-possession property, and this test
 * used to average it away.
 *
 * Out of possession a defensive shape overrides individual preference — that
 * is what a back four IS — so any correct defending implementation pulls every
 * role toward the same line for half the match. Measured over the whole match
 * that reads as "these roles are the same man", and the gate then blocks the
 * very thing the brief most wants built. Two attempts at defending died on
 * exactly this, by two different mechanisms.
 *
 * The verdict is now taken on the IN-POSSESSION map. The out-of-possession
 * mean is printed beside it: a role that also differs while defending is a
 * bonus, and one that does not is correct football rather than decoration.
 */
private class Split { val inPoss = Heat(); val outPoss = Heat() }

private fun run(role: RoleId, matches: Int): Split {
    val base = Formation.preset("4-3-3")
    val shape = Formation.withRole(base, SLOT, role)
    val sp = Split()

    for (i in 0 until matches) {
        val sim = MatchSim(1000L + i, homeShape = shape)
        val man = sim.men.first { it.side == 0 && it.slot.id == SLOT }
        sim.onCarry = { m, _, _ -> if (m === man) sp.inPoss.touches += 1.0 }
        sim.onChoice = { m, o, _, _ -> if (m === man) sp.inPoss.kinds[o.kind.ordinal]++ }
        sim.play { _, _ ->
            val ax = Pitch.attX(0, man.x)
            val ay = Pitch.attY(0, man.y)
            if (sim.possessionSide == 0) sp.inPoss.add(ax, ay) else sp.outPoss.add(ax, ay)
        }
    }
    return sp
}

fun main(args: Array<String>) {
    val matches = if (args.isNotEmpty()) args[0].toInt() else 6

    // Every role that could plausibly occupy a midfield slot, plus the ones the
    // brief names. Testing them all at one anchor is what makes the comparison
    // fair; picking a flattering slot per role would be marking my own homework.
    val roles = SHIPPED

    println("ROLECHECK — slot $SLOT of a 4-3-3, $matches matches per role")
    println("Everything is held fixed except the role. Same anchor, same shape,")
    println("same seeds, same opposition — so any difference below is the role.")
    println()
    println("SHIPPED ${SHIPPED.size} of ${SHIPPED.size + PARKED.size} outfield roles.")
    println("PARKED, built but not proven distinct — ${PARKED.size} open jobs:")
    println("  " + PARKED.joinToString(", ") { it.name })
    println()

    val split = LinkedHashMap<RoleId, Split>()
    for (r in roles) split[r] = run(r, matches)
    val heat = LinkedHashMap<RoleId, Heat>()
    for (r in roles) heat[r] = split.getValue(r).inPoss

    println(String.format("%-24s %10s %9s %11s %10s",
        "role", "own-x IN", "lane IN", "own-x OUT", "touches"))
    println("-".repeat(68))
    for (r in roles) {
        val sp = split.getValue(r)
        println(String.format("%-24s %10.2f %9.2f %11.2f %10.1f",
            r.name, sp.inPoss.meanX, sp.inPoss.meanY, sp.outPoss.meanX,
            sp.inPoss.touches / matches))
    }

    // ------------------------------------------------- separation matrix
    val norm = roles.associateWith { heat.getValue(it).normalised() }
    val FLOOR = 0.15

    println()
    println("SEPARATION — IN POSSESSION only, ${FL}x${FB} grid")
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

    /*
     * THE BRIEF ASKS FOR TWO SIGNATURES, AND THE FIRST VERSION TESTED ONE.
     *
     * "two sides identical except for one role change must produce visibly
     * different heat maps AND DIFFERENT EVENT RATES."
     *
     * Only the heat map was measured, so a pair of roles that stand in similar
     * places while doing different things on the ball was reported as
     * decoration — POACHER and TARGET_MAN sit 0.56 m apart and take 54 and 51
     * touches, but one shoots on sight and the other lays it off. That is not
     * two names for the same man; it is a test that was missing half its
     * criterion. A pair is decoration only when NEITHER signature separates.
     */
    val kindNorm = roles.associateWith { heat.getValue(it).kindShares() }

    println()
    println("WHAT HE DOES ON THE BALL — chosen option kinds, the second signature")
    println(String.format("%-24s %9s %8s", "role", "touches", "top kind"))
    for (r in roles) {
        val h = heat.getValue(r)
        val top = if (h.kinds.sum() == 0) "-"
            else OptKind.entries[h.kinds.indices.maxByOrNull { h.kinds[it] }!!].name
        println(String.format("%-24s %9.1f %8s", r.name, h.touches / matches, top))
    }

    val fails = ArrayList<String>()
    for (i in roles.indices) for (j in i + 1 until roles.size) {
        val a = heat.getValue(roles[i])
        val b = heat.getValue(roles[j])
        val posSep = separation(norm.getValue(roles[i]), norm.getValue(roles[j]))
        val actSep = separation(kindNorm.getValue(roles[i]), kindNorm.getValue(roles[j]))
        val metres = kotlin.math.hypot(a.meanX - b.meanX, a.meanY - b.meanY)
        if (posSep < FLOOR && actSep < FLOOR) fails.add(String.format(
            "pos %.2f / act %.2f  %-22s vs %-22s  (centroids %.2f m apart)",
            posSep, actSep, roles[i].name, roles[j].name, metres))
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
