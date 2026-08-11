package harness

import com.dugout.career.sim.MatchSim
import com.dugout.career.sim.OptKind
import com.dugout.career.sim.Pitch

/**
 * WHAT IS OFFERED AGAINST WHAT IS TAKEN.
 *
 * EventCensus counts what happened. OptionCensus measures the SIZE of the
 * option set. Neither answers the question that decides everything the chooser
 * does: of the acts a man could have picked, which ones does he actually pick?
 *
 * It exists because of a wrong guess. Crosses run at about 2.6 a match against
 * a real 28-34, and the natural explanation is that the generator rarely offers
 * one — a man has to be wide and past the final third for a cross to exist at
 * all. That is measurable, and it is false: a cross is offered 691 times a
 * match and chosen 13. The bottleneck is the CHOICE, not the generator, and
 * every hour that would have gone into loosening the generator would have been
 * spent on the wrong half of the engine.
 *
 *   java -cp build/gate.jar harness.OptionMixKt [matches]
 */
fun main(args: Array<String>) {
    val matches = if (args.isNotEmpty()) args[0].toInt() else 12

    val offered = HashMap<OptKind, Long>()
    val taken = HashMap<OptKind, Long>()
    var decisions = 0L
    var wideAdvanced = 0L

    for (i in 0 until matches) {
        val sim = MatchSim(1000L + i)
        sim.onAppraisal = { m, options, chosen ->
            decisions++
            for (o in options) offered.merge(o.kind, 1L, Long::plus)
            taken.merge(chosen.kind, 1L, Long::plus)
            if (Pitch.attX(m.side, m.x) > 68f && (m.y < 20f || m.y > 48f)) wideAdvanced++
        }
        sim.play()
    }

    val n = matches.toLong()
    println("=".repeat(78))
    println("OPTION MIX — $matches matches")
    println("=".repeat(78))
    println()
    println(String.format("%-14s %11s %10s %13s %12s",
        "act", "offered", "taken", "taken/offered", "share of all"))
    println("-".repeat(78))
    val allTaken = taken.values.sum().coerceAtLeast(1L)
    for (k in OptKind.entries) {
        val o = offered[k] ?: 0L
        val t = taken[k] ?: 0L
        if (o == 0L && t == 0L) continue
        println(String.format("%-14s %11d %10d %12.1f%% %11.1f%%",
            k.name, o / n, t / n,
            if (o == 0L) 0.0 else 100.0 * t / o,
            100.0 * t / allTaken))
    }
    println()
    println("decisions a match                 ${decisions / n}")
    println("of them wide and past 68 m        ${wideAdvanced / n}")
    println()
    println("  A low taken/offered is not a verdict on its own — a man is given")
    println("  eight passes to feet and can take one, so that column reads low for")
    println("  anything with many variants. The SHARE column is the one to read")
    println("  against real football, and the pair together says whether an act is")
    println("  rare because it is never available or rare because it never wins.")
    println("=".repeat(78))
}
