package harness

import com.dugout.career.sim.MatchSim
import com.dugout.career.sim.OptKind
import com.dugout.career.sim.Pitch
import com.dugout.career.sim.Physics
import kotlin.math.hypot

/**
 * WHY TWICE AS MANY SHOTS — the diagnosis, not a fix.
 *
 * The engine takes about 47 shots a match and converts 4%. Real football takes
 * 26.8 and converts 12.3%. Two rows wrong in opposite directions is almost
 * always one fact, and the fact is not the goalkeeper: it is WHICH shots get
 * taken.
 *
 * "Twice as many" is a sentence with no shape. This gives it one, by bucketing
 * both populations the same way and putting them side by side:
 *
 *   OFFERED   how often the generator even proposes a shot from there
 *   TAKEN     how often the man picks it
 *   TAKE RATE the decision layer's appetite, which is the thing under review
 *   xG        what he thought it was worth
 *
 * The real column is measured from StatsBomb Open Data, Bundesliga 2023/24, 14
 * matches, 375 non-penalty shots — extracted by tools/data/anchors.py and
 * quoted here so the comparison sits in one table.
 *
 * NOTHING HERE CHANGES THE ENGINE. It is a measurement, and the shape it prints
 * is the argument for whatever the fix turns out to be.
 *
 *   java -cp build/gate.jar harness.ShotAuditKt [matches]
 */
private val EDGES = floatArrayOf(6f, 11f, 16f, 22f, 30f, 40f)
private val LABELS = arrayOf("0-6", "6-11", "11-16", "16-22", "22-30", "30-40", "40+")

/** Bundesliga 2023/24, 14 matches, 375 non-penalty shots. Shots a match. */
private val REAL_PER_MATCH = doubleArrayOf(1.43, 6.07, 7.36, 7.14, 4.07, 0.50, 0.21)
private val REAL_XG = doubleArrayOf(0.360, 0.184, 0.116, 0.048, 0.026, 0.011, 0.004)
private const val REAL_TOTAL = 26.8
private const val REAL_CONVERSION = 12.3

private fun bucketOf(d: Float): Int {
    var i = 0
    for ((j, hi) in EDGES.withIndex()) if (d > hi) i = j + 1
    return i
}

fun main(args: Array<String>) {
    val matches = if (args.isNotEmpty()) args[0].toInt() else 20

    val offered = LongArray(7)
    val taken = LongArray(7)
    val xgSum = DoubleArray(7)
    var goals = 0L
    var shots = 0L

    for (i in 0 until matches) {
        val sim = MatchSim(1000L + i)
        sim.onAppraisal = { m, options, chosen ->
            // Distance from the man to the centre of the goal he attacks.
            val gx = Pitch.absX(m.side, Pitch.LENGTH)
            val gy = Pitch.WIDTH * 0.5f
            val d = Physics.dist(m.x, m.y, gx, gy)
            val b = bucketOf(d)
            if (options.any { it.kind == OptKind.SHOT }) offered[b]++
            if (chosen.kind == OptKind.SHOT) {
                taken[b]++
                xgSum[b] += chosen.pSuccess.toDouble()
                shots++
            }
        }
        sim.play()
        goals += sim.events[com.dugout.career.sim.Ev.GOAL].toLong()
    }

    val n = matches.toDouble()
    println("=".repeat(96))
    println("SHOT AUDIT — $matches matches, against real football bucketed identically")
    println("=".repeat(96))
    println()
    println(String.format("%9s %10s %9s %10s %9s %11s %9s",
        "distance", "offered", "TAKEN", "take rate", "our xG", "REAL taken", "REAL xG"))
    println("-".repeat(96))
    for (b in 0..6) {
        if (offered[b] == 0L && taken[b] == 0L && REAL_PER_MATCH[b] == 0.0) continue
        println(String.format("%8s m %10.1f %9.2f %9.1f%% %9.3f %11.2f %9.3f",
            LABELS[b], offered[b] / n, taken[b] / n,
            if (offered[b] == 0L) 0.0 else 100.0 * taken[b] / offered[b],
            if (taken[b] == 0L) 0.0 else xgSum[b] / taken[b],
            REAL_PER_MATCH[b], REAL_XG[b]))
    }
    println("-".repeat(96))
    val ourXg = if (shots == 0L) 0.0 else xgSum.sum() / shots
    println(String.format("%8s   %10s %9.2f %9s %9.3f %11.2f %9.3f",
        "TOTAL", "", shots / n, "", ourXg, REAL_TOTAL, 0.110))
    println()
    println(String.format("  conversion   ours %.1f%%   real %.1f%%",
        if (shots == 0L) 0.0 else 100.0 * goals / shots, REAL_CONVERSION))
    println()
    println("HOW TO READ IT")
    println()
    println("  A bucket where TAKEN is far above REAL and xG is low is a man")
    println("  shooting from somewhere a footballer would not. A bucket where")
    println("  OFFERED is enormous and the take rate is small is the generator")
    println("  being loose but the chooser holding — which is not the problem,")
    println("  because an option nobody takes costs nothing.")
    println()
    println("  The mean xG per shot is the single number to watch. Real football")
    println("  is 0.110. A side taking twice as many shots at half the xG is not")
    println("  shooting more, it is shooting WORSE, and no goalkeeper setting can")
    println("  fix a shot that should not have been struck.")
    println("=".repeat(96))
}
