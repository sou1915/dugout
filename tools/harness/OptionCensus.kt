package harness

import com.dugout.career.sim.MatchSim
import com.dugout.career.sim.Decide
import com.dugout.career.sim.OptKind
import com.dugout.career.sim.Pitch
import kotlin.math.abs

/**
 * OPTIONCENSUS, the part of it that exists at step 4.
 *
 * §5 of the brief sets five numbers that gate the decision layer. Four of them
 * — between-cell distance, within-cell entropy — are about the distribution of
 * CHOSEN option kinds, and there is no chooser yet, so they cannot be measured
 * and are not faked here.
 *
 * The fifth can be measured now and is the one that matters most at this step:
 *
 *     median option-set size >= 6,  decisions offered <= 2 options <= 10%
 *
 * Because the option SET is not a property of the decision layer at all. It is
 * a property of where everybody else is standing, which is off-ball movement's
 * job. That is the brief's own argument for why roles come before the chooser:
 * a man offered three options every time will play the same ball every time,
 * whatever scores them, and no work on the scorer would ever find it.
 *
 * An option here is a team-mate between 6 and 42 m away with no opponent within
 * 2.5 m of the line to him. Counted, never scored — there is nothing in this
 * harness that ranks one above another.
 *
 *   java -cp build/gate.jar harness.OptionCensusKt [matches]
 */

private const val ZONES = 3      // own third / middle / final third
private const val PRESSURES = 3  // none / closed down / pressed

private val ZONE_NAME = arrayOf("own third", "middle third", "final third")
private val PRESS_NAME = arrayOf("none >8m", "closed 4-8m", "pressed <4m")

private class Cell {
    val counts = ArrayList<Int>()
    val kinds = IntArray(OptKind.entries.size)
    var argmax = 0
    var total = 0
    fun add(n: Int) { counts.add(n) }
    fun chose(k: OptKind, wasArgmax: Boolean) {
        kinds[k.ordinal]++; total++; if (wasArgmax) argmax++
    }
    fun kindShares(): DoubleArray {
        val out = DoubleArray(kinds.size)
        if (total == 0) return out
        for (i in kinds.indices) out[i] = kinds[i].toDouble() / total
        return out
    }
    val n: Int get() = counts.size
    val median: Double get() {
        if (counts.isEmpty()) return 0.0
        val s = counts.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m].toDouble() else (s[m - 1] + s[m]) / 2.0
    }
    val starved: Double get() =
        if (counts.isEmpty()) 0.0 else 100.0 * counts.count { it <= 2 } / counts.size
}

fun main(args: Array<String>) {
    val matches = if (args.isNotEmpty()) args[0].toInt() else 30

    val grid = Array(ZONES) { Array(PRESSURES) { Cell() } }
    val all = Cell()

    for (i in 0 until matches) {
        val sim = MatchSim(1000L + i)
        sim.onChoice = { m, chosen, _, pressure ->
            val ax = Pitch.attX(m.side, m.x)
            val zone = when {
                ax < Pitch.LENGTH / 3f -> 0
                ax < 2f * Pitch.LENGTH / 3f -> 1
                else -> 2
            }
            val press = when {
                pressure > 8f -> 0
                pressure > 4f -> 1
                else -> 2
            }
            grid[zone][press].chose(chosen.kind, false)
            all.chose(chosen.kind, false)
        }
        sim.onCarry = { m, options, pressure ->
            val ax = Pitch.attX(m.side, m.x)
            val zone = when {
                ax < Pitch.LENGTH / 3f -> 0
                ax < 2f * Pitch.LENGTH / 3f -> 1
                else -> 2
            }
            val press = when {
                pressure > 8f -> 0
                pressure > 4f -> 1
                else -> 2
            }
            grid[zone][press].add(options)
            all.add(options)
        }
        sim.play()
    }

    println("OPTIONCENSUS — $matches matches, the option set on every touch")
    println()
    println("  An option is a team-mate 6-42 m away with a clear line to him.")
    println("  Counted, not scored: there is no decision layer, and this number")
    println("  is a property of off-ball movement rather than of any chooser.")
    println()

    println(String.format("%-14s %-14s %8s %10s %10s", "zone", "pressure", "touches", "median", "<=2 opts"))
    println("-".repeat(60))
    for (z in 0 until ZONES) for (p in 0 until PRESSURES) {
        val c = grid[z][p]
        if (c.n == 0) continue
        println(String.format("%-14s %-14s %8d %10.1f %9.1f%%",
            ZONE_NAME[z], PRESS_NAME[p], c.n, c.median, c.starved))
    }

    println()
    println(String.format("overall: %d touches, median %.1f options, %.1f%% offered <=2",
        all.n, all.median, all.starved))
    println()

    // ------------------------------------------------- the conditional bars
    println()
    println("CHOSEN OPTION KINDS by cell — the conditional test of §5")
    println("Within a cell the distribution must be CONCENTRATED (low entropy);")
    println("between cells it must be FAR APART. Variety and noise share a histogram,")
    println("so only the split table can tell them apart.")
    println()
    println(String.format("%-14s %-14s %9s %10s", "zone", "pressure", "entropy", "top kind"))
    println("-".repeat(52))
    val populated = ArrayList<Pair<String, DoubleArray>>()
    val entropies = ArrayList<Double>()
    for (z in 0 until ZONES) for (p in 0 until PRESSURES) {
        val c = grid[z][p]
        if (c.total < 200) continue
        val h = Decide.normalisedEntropy(c.kinds)
        entropies.add(h)
        populated.add("${ZONE_NAME[z]}/${PRESS_NAME[p]}" to c.kindShares())
        val top = OptKind.entries[c.kinds.indices.maxByOrNull { c.kinds[it] }!!]
        println(String.format("%-14s %-14s %9.2f %10s", ZONE_NAME[z], PRESS_NAME[p], h, top.name))
    }

    val tvs = ArrayList<Double>()
    for (i in populated.indices) for (j in i + 1 until populated.size) {
        var d = 0.0
        val a = populated[i].second
        val b = populated[j].second
        for (k in a.indices) d += abs(a[k] - b[k])
        tvs.add(d / 2.0)
    }
    val medianTv = if (tvs.isEmpty()) 0.0 else tvs.sorted()[tvs.size / 2]
    val medianH = if (entropies.isEmpty()) 0.0 else entropies.sorted()[entropies.size / 2]

    println()
    val medianOk = all.median >= 6.0
    val starvedOk = all.starved <= 10.0
    val tvOk = medianTv >= 0.30
    val hOk = medianH <= 0.60
    println("§5 bar — median option-set size >= 6:         ${if (medianOk) "PASS" else "FAIL"} (${all.median})")
    println("§5 bar — decisions offered <=2 options <=10%: ${if (starvedOk) "PASS" else "FAIL"} " +
        String.format("(%.1f%%)", all.starved))
    println("§5 bar — median between-cell TV >= 0.30:      ${if (tvOk) "PASS" else "FAIL"} " +
        String.format("(%.2f)", medianTv))
    println("§5 bar — median within-cell entropy <= 0.60:  ${if (hOk) "PASS" else "FAIL"} " +
        String.format("(%.2f)", medianH))
    println()
    if (medianOk && starvedOk && tvOk && hOk) {
        println("The off-ball layer is offering choices. The decision layer may be tuned")
        println("against these cells once it exists — the other §5 bars need a chooser.")
    } else {
        println("NOT CLEARED. Per §5 and §6, the decision layer is not tuned until it is:")
        println("every number out of a chooser fed a starved option set measures the")
        println("wrong thing, and no work on the scorer would ever find it.")
    }
}
