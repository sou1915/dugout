package harness

import com.dugout.career.sim.Ev
import com.dugout.career.sim.EvGroup
import com.dugout.career.sim.MatchSim
import kotlin.math.sqrt

/**
 * THE CENSUS — every event in the vocabulary, counted, against the §5 bands.
 *
 * This is the harness that would have caught every defect in the predecessor on
 * day one. Its most valuable output is not the rows that have numbers; it is
 * the column of zeros, each one a named and addressable job, and the WIRED
 * column that stops a zero being ambiguous.
 *
 *   java -cp build/gate.jar harness.EventCensusKt [matches]
 */

private class Stat {
    private var sum = 0.0
    private var sumSq = 0.0
    private var n = 0
    fun add(v: Double) { sum += v; sumSq += v * v; n++ }
    val mean: Double get() = if (n == 0) 0.0 else sum / n
    /** 95% half-width on the mean. */
    val ci: Double get() {
        if (n < 2) return 0.0
        val varr = (sumSq - sum * sum / n) / (n - 1)
        return 1.96 * sqrt(varr / n)
    }
}

/** An acceptance row from §5. [band] is null when the target is a share. */
private class Target(
    val label: String,
    val lo: Double,
    val hi: Double,
    val unit: String = "a match",
    val value: (Map<Ev, Stat>) -> Double?,
    val wired: () -> Boolean
)

private fun rate(m: Map<Ev, Stat>, e: Ev) = m.getValue(e).mean

fun main(args: Array<String>) {
    val matches = if (args.isNotEmpty()) args[0].toInt() else 60

    val stats = HashMap<Ev, Stat>()
    for (e in Ev.ALL) stats[e] = Stat()

    for (i in 0 until matches) {
        val sim = MatchSim(1000L + i)
        sim.play()
        for (e in Ev.ALL) stats.getValue(e).add(sim.events[e].toDouble())
    }

    println("=".repeat(78))
    println("EVENT CENSUS — $matches matches, both sides combined")
    println("=".repeat(78))
    println()
    println("READ THIS BEFORE READING A NUMBER BELOW.")
    println()
    println("  There is no decision layer. The thing that moves the ball is the")
    println("  marked placeholder in MatchSim: it strikes at a RANDOM point, at a")
    println("  random pace. So every rate here is a rate produced by a random ball,")
    println("  and none of it is evidence about football. What it is evidence about")
    println("  is the plumbing: that an event has a site that fires it, that the")
    println("  census can count it, and that the count is not zero by accident.")
    println()
    println("  Rows marked '-' in WIRED have no site in the engine at all. They are")
    println("  the job list. A wired row reading 0.00 is a bug; an unwired row")
    println("  reading 0.00 is a to-do, and the two must never look the same.")
    println()

    var lastGroup: EvGroup? = null
    println(String.format("%-24s %5s %10s %12s", "event", "wired", "a match", "95% CI"))
    println("-".repeat(78))
    for (e in Ev.ALL) {
        if (e.group != lastGroup) {
            println("  -- ${e.group}")
            lastGroup = e.group
        }
        val s = stats.getValue(e)
        println(String.format(
            "%-24s %5s %10.2f %12s",
            e.name, if (e.wired) "yes" else "-", s.mean,
            if (e.wired) String.format("±%.2f", s.ci) else ""
        ))
    }

    // ---------------------------------------------------------- acceptance
    val shotsWired = Ev.WIRED.any { it.group == EvGroup.SHOOTING && it != Ev.GOAL }
    val passes = { m: Map<Ev, Stat> -> rate(m, Ev.PASS_SHORT) + rate(m, Ev.PASS_LONG) }

    val targets = listOf(
        Target("goals", 2.6, 2.9, value = { rate(it, Ev.GOAL) }, wired = { true }),
        Target("shots", 22.0, 27.0, value = { null }, wired = { shotsWired }),
        Target("headers at goal", 3.0, 4.0, value = { null }, wired = { false }),
        Target("crosses", 28.0, 34.0, value = { null }, wired = { false }),
        Target("corners", 9.0, 11.0, value = { rate(it, Ev.CORNER_WON) }, wired = { true }),
        Target("passes attempted", 800.0, 950.0, value = { passes(it) }, wired = { true }),
        Target("pass completion", 78.0, 85.0, "%",
            value = { val a = passes(it); if (a <= 0) null else 100.0 * rate(it, Ev.PASS_COMPLETED) / a },
            wired = { true }),
        Target("interceptions", 16.0, 22.0, value = { rate(it, Ev.INTERCEPTION) }, wired = { true }),
        Target("tackles", 30.0, 36.0, value = { null }, wired = { false }),
        Target("take-ons attempted", 30.0, 40.0, value = { null }, wired = { false }),
        Target("fouls", 20.0, 24.0, value = { null }, wired = { false }),
        Target("offsides", 4.0, 6.0, value = { null }, wired = { false })
    )

    println()
    println("=".repeat(78))
    println("ACCEPTANCE — GAME_BRIEF.md §5")
    println("=".repeat(78))
    println(String.format("%-22s %10s %16s %s", "row", "measured", "target", "verdict"))
    println("-".repeat(78))
    var fails = 0
    var todo = 0
    for (t in targets) {
        val wired = t.wired()
        val v = if (wired) t.value(stats) else null
        val verdict = when {
            !wired || v == null -> { todo++; "-  not wired yet" }
            v in t.lo..t.hi -> "PASS"
            else -> { fails++; "FAIL" }
        }
        println(String.format(
            "%-22s %10s %16s %s",
            t.label,
            if (v == null) "-" else String.format("%.2f", v),
            String.format("%.1f-%.1f %s", t.lo, t.hi, t.unit),
            verdict
        ))
    }

    println()
    println("  A verdict of PASS above means a number produced by a random ball")
    println("  happened to land inside a band. It is a coincidence, not a success,")
    println("  and it will move the moment the placeholder is replaced. Until there")
    println("  is a decision layer, treat every verdict in this table as noise —")
    println("  the table is here so that it EXISTS and is wired to real counters,")
    println("  not so that anyone can start tuning against it.")

    println()
    val wiredCount = Ev.WIRED.size
    println("vocabulary: $wiredCount of ${Ev.ALL.size} events wired, ${Ev.ALL.size - wiredCount} to go")
    println("acceptance: $fails failing, $todo not wired yet, ${targets.size - fails - todo} passing")
    println()
    println("A wired event that never fires is the real finding here. Check:")
    val deadWired = Ev.WIRED.filter { stats.getValue(it).mean == 0.0 }
    if (deadWired.isEmpty()) {
        println("  none — every wired event fires.")
    } else {
        deadWired.forEach { println("  NEVER FIRES: ${it.name} is wired and read 0.00.") }
        println()
        println("  A site exists and nothing reaches it. That is either a bug in the")
        println("  site or a route the football cannot currently take — and the second")
        println("  is worth as much as the first, because it names what is missing.")
    }
}
