package harness

import com.dugout.career.sim.Ev
import com.dugout.career.sim.MatchSim

/**
 * THE SIX ROWS, IN CI, WITH THE DIRECTION EACH ONE IS EXPECTED TO MOVE.
 *
 * EventCensus prints eighty-seven events and a §5 table; that is the right
 * artefact for an audit and the wrong one for a reviewer deciding whether a
 * commit helped. This is the short form: six rows, each with where it is, where
 * it should be, and — the part that makes it a calibration tool rather than a
 * report — WHICH WAY IT IS EXPECTED TO MOVE NEXT and why.
 *
 * A stated direction is a falsifiable claim. If a commit moves a row the way
 * this file predicts, the model behind it was right; if it moves the other way,
 * the model was wrong and that is worth more than the row itself. Every wrong
 * causal guess in this project's history would have been caught a day earlier
 * by writing the direction down before running the change.
 *
 * Targets are §5 where §5 is right, and docs/ANCHORS.md where real event data
 * says §5 is wrong. Both are named per row, because two of these bands are
 * measurably not football.
 *
 *   java -cp build/gate.jar harness.ScoreboardKt [matches]
 */
private class Metric(
    val name: String,
    val lo: Double,
    val hi: Double,
    val real: String,
    val source: String,
    val expect: String,
    val value: (MatchSim) -> Double
)

fun main(args: Array<String>) {
    val matches = if (args.isNotEmpty()) args[0].toInt() else 30

    val rows = listOf(
        Metric("shots", 22.0, 27.0, "26.8", "§5 + anchors",
            "DOWN. Not by shooting better — mean xG per shot is already 0.108 " +
                "against a real 0.110 — but by the mix: 36 a match come from " +
                "11-22 m where football takes 14, and only 3 from inside 11 m " +
                "where football takes 7.5. The engine cannot get close to goal.")
        { s -> (s.events[Ev.SHOT_PLACED] + s.events[Ev.SHOT_LONG_RANGE] +
            s.events[Ev.SHOT_HEADER]).toDouble() },

        Metric("goals", 2.6, 2.9, "3.04-3.29", "§5",
            "UP. Shots underperform their own xG threefold: 0.108 predicted, " +
                "0.036 delivered. Every shot is struck at the CENTRE of the " +
                "goal, which is where the goalkeeper stands.")
        { s -> s.events[Ev.GOAL].toDouble() },

        Metric("pass completion %", 78.0, 85.0, "84.9", "§5 + anchors",
            "UP. It is the cap on everything structural — a possession lasts " +
                "as long as passes keep arriving, and at 57% it ends after four " +
                "where football manages eight.")
        { s ->
            val a = (s.events[Ev.PASS_SHORT] + s.events[Ev.PASS_LONG]).toDouble()
            if (a == 0.0) 0.0 else 100.0 * s.events[Ev.PASS_COMPLETED] / a
        },

        Metric("interceptions", 16.0, 22.0, "-", "§5",
            "DOWN, and mostly as a consequence: it falls when completion rises. " +
                "The definition is already right — a man in the line at the " +
                "strike — so what is left is that too many passes fail at all.")
        { s -> s.events[Ev.INTERCEPTION].toDouble() },

        Metric("corners", 9.0, 11.0, "~10", "§5",
            "UP. Doubled once carrying worked, because a ball that arrives has " +
                "to be put behind. The rest needs the deflection, which does " +
                "not exist: a defender can only send it behind with his head.")
        { s -> s.events[Ev.CORNER_WON].toDouble() },

        Metric("offsides", 4.0, 6.0, "2.83", "anchors — §5 band is high",
            "UP a little, toward 2.83 rather than toward the §5 band. The mind " +
                "now avoids the line more carefully than a footballer does.")
        { s -> s.events[Ev.OFFSIDE].toDouble() }
    )

    val sums = DoubleArray(rows.size)
    for (i in 0 until matches) {
        val sim = MatchSim(1000L + i)
        sim.play()
        for ((j, r) in rows.withIndex()) sums[j] += r.value(sim)
    }

    println("=".repeat(96))
    println("SCOREBOARD — $matches matches")
    println("=".repeat(96))
    println()
    println(String.format("  %-20s %10s %14s %10s   %s",
        "row", "measured", "target", "real", "in band"))
    println("  " + "-".repeat(92))
    for ((j, r) in rows.withIndex()) {
        val v = sums[j] / matches
        val ok = v in r.lo..r.hi
        println(String.format("  %-20s %10.2f %14s %10s   %s",
            r.name, v, String.format("%.1f-%.1f", r.lo, r.hi), r.real,
            if (ok) "PASS" else "no"))
    }
    println()
    println("EXPECTED DIRECTION — written down before the change, so it can be wrong")
    println()
    for ((j, r) in rows.withIndex()) {
        val v = sums[j] / matches
        println(String.format("  %s   now %.2f, target %.1f-%.1f  [%s]",
            r.name, v, r.lo, r.hi, r.source))
        for (line in wrap(r.expect, 84)) println("      $line")
        println()
    }
    println("=".repeat(96))
}

private fun wrap(s: String, width: Int): List<String> {
    val out = ArrayList<String>()
    val sb = StringBuilder()
    for (w in s.split(" ")) {
        if (sb.length + w.length + 1 > width) { out.add(sb.toString()); sb.setLength(0) }
        if (sb.isNotEmpty()) sb.append(' ')
        sb.append(w)
    }
    if (sb.isNotEmpty()) out.add(sb.toString())
    return out
}
