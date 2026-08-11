package harness

import com.dugout.career.sim.MatchSim
import com.dugout.career.sim.Physics
import kotlin.math.abs

/**
 * ONE PASS, END TO END — the trace that settles a contradiction.
 *
 * An isolated probe of this engine's flight physics is accurate to under a
 * metre. The match reports 10.21 m between where the ball stopped and where it
 * was aimed. Both cannot be true of the same strike.
 *
 * Four causal guesses were wrong in one session, so this is not a fifth. Every
 * number that enters a delivery is printed, worst first, with the three that
 * decide it side by side:
 *
 *   solvedFor  the distance the pace was solved for   (carrier -> aim)
 *   needed     the distance the ball actually had to cover  (ball -> shot)
 *   travelled  how far it went                        (ball -> rest)
 *
 * If travelled tracks solvedFor rather than needed, the bug is that the pace is
 * computed from the wrong pair of points. If travelled tracks neither, the
 * physics is not doing in the match what it does in the probe. And if the
 * columns do not add up to the reported gap at all, the measurement is the
 * thing that is wrong — which is a finding, not a disappointment.
 *
 *   java -cp build/gate.jar harness.PassTraceKt [matches]
 */
private class TraceRow(
    val kind: String, val solvedFor: Float, val needed: Float, val travelled: Float,
    val mps: Float, val loft: Float, val restVsShot: Float, val carrierVsBall: Float,
    val jitter: Float, val restVsMan: Float
)

fun main(args: Array<String>) {
    val matches = if (args.isNotEmpty()) args[0].toInt() else 6
    val rows = ArrayList<TraceRow>()

    for (i in 0 until matches) {
        val sim = MatchSim(1000L + i)
        sim.onDelivery = { d ->
            rows.add(TraceRow(
                kind = d.kind,
                solvedFor = Physics.dist(d.carrierX, d.carrierY, d.aimX, d.aimY),
                needed = Physics.dist(d.ballX, d.ballY, d.shotX, d.shotY),
                travelled = Physics.dist(d.ballX, d.ballY, d.restX, d.restY),
                mps = d.mps, loft = d.loft,
                restVsShot = Physics.dist(d.restX, d.restY, d.shotX, d.shotY),
                carrierVsBall = Physics.dist(d.carrierX, d.carrierY, d.ballX, d.ballY),
                jitter = Physics.dist(d.aimX, d.aimY, d.shotX, d.shotY),
                restVsMan = Physics.dist(d.restX, d.restY, d.manX, d.manY)
            ))
        }
        sim.play()
    }

    println("=".repeat(112))
    println("PASSTRACE — ${rows.size} intended passes over $matches matches")
    println("=".repeat(112))

    fun mean(f: (TraceRow) -> Float) = if (rows.isEmpty()) 0.0 else rows.sumOf { f(it).toDouble() } / rows.size

    println()
    println("MEANS — the whole population, before looking at any single pass")
    println(String.format("  solved for            %7.2f m   the pace was computed for this distance", mean { it.solvedFor }))
    println(String.format("  actually needed       %7.2f m   ball -> where it was struck at", mean { it.needed }))
    println(String.format("  actually travelled    %7.2f m", mean { it.travelled }))
    println(String.format("  rest vs shot          %7.2f m   <- this is the 'ball vs aim' number", mean { it.restVsShot }))
    println(String.format("  of which, jitter      %7.2f m   delivery error moved the target", mean { it.jitter }))
    println(String.format("  carrier vs ball       %7.2f m   he strikes it from where it LAY", mean { it.carrierVsBall }))
    println(String.format("  rest vs the man       %7.2f m   the headline miss", mean { it.restVsMan }))

    println()
    println("  travelled - needed    %7.2f m   + means it went past, - means it fell short"
        .format(mean { it.travelled - it.needed }))
    println("  needed - solvedFor    %7.2f m   the pace was solved for the wrong pair of points"
        .format(mean { it.needed - it.solvedFor }))

    println()
    println("BY ACT — where the error actually lives")
    println(String.format("  %-14s %7s %10s %10s %11s %11s", "kind", "n", "solvedFor", "travelled", "over/under", "rest-vs-shot"))
    println("  " + "-".repeat(70))
    for ((k, g) in rows.groupBy { it.kind }.entries.sortedByDescending { it.value.size }) {
        println(String.format("  %-14s %7d %10.2f %10.2f %11.2f %11.2f",
            k, g.size,
            g.sumOf { it.solvedFor.toDouble() } / g.size,
            g.sumOf { it.travelled.toDouble() } / g.size,
            g.sumOf { (it.travelled - it.needed).toDouble() } / g.size,
            g.sumOf { it.restVsShot.toDouble() } / g.size))
    }

    println()
    println("THE TEN WORST, by how far the ball finished from where it was struck at")
    println(String.format("  %-13s %7s %7s %7s %6s %6s %8s %7s",
        "kind", "solved", "needed", "went", "mps", "loft", "restVsShot", "jitter"))
    println("  " + "-".repeat(70))
    for (r in rows.sortedByDescending { it.restVsShot }.take(10)) {
        println(String.format("  %-13s %7.1f %7.1f %7.1f %6.1f %6.1f %8.1f %7.1f",
            r.kind, r.solvedFor, r.needed, r.travelled, r.mps, r.loft, r.restVsShot, r.jitter))
    }

    println()
    val overshoot = rows.count { it.travelled > it.needed + 2f }
    val undershoot = rows.count { it.travelled < it.needed - 2f }
    println(String.format("  overshot by >2 m: %.1f%%    undershot by >2 m: %.1f%%",
        100.0 * overshoot / rows.size, 100.0 * undershoot / rows.size))
    println("=".repeat(112))
}
