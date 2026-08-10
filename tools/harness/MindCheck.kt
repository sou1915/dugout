package harness

import com.dugout.career.sim.MatchSim
import com.dugout.career.sim.OptKind
import kotlin.math.abs

/**
 * MINDCHECK — is the percentage real, and do the matches repeat themselves?
 *
 * Three questions, because the mind makes three claims and every one of them
 * could be decoration:
 *
 *   1. CALIBRATION. He says 78%. Do 78% of those come off? A number between 0
 *      and 1 that is never held against an outcome is the single most common
 *      lie in the predecessor's codebase, and it is undetectable by reading.
 *      Predicted is bucketed against actual here, and a bucket that is out by
 *      more than its own noise is printed as a miss.
 *
 *   2. REPETITION INSIDE A MATCH. If the same man plays the same ball to the
 *      same man forty times, the match is a loop with a scoreline attached.
 *      Measured as: what share of a man's decisions go to his single most
 *      played act.
 *
 *   3. DIFFERENCE BETWEEN MATCHES. Two seeds should not produce the same
 *      afternoon. Measured as the L1 distance between two matches' full
 *      (player, act) distributions, normalised so 0% is identical and 100% is
 *      no act in common.
 *
 *   java -cp build/gate.jar harness.MindCheckKt [matches]
 */

private class Bucket {
    var n = 0
    var predSum = 0.0
    var hits = 0
    val actual: Double get() = if (n == 0) 0.0 else hits.toDouble() / n
    val predicted: Double get() = if (n == 0) 0.0 else predSum / n
    /** 95% half-width on a proportion — how wrong the bucket is allowed to look. */
    val noise: Double get() {
        if (n < 2) return 1.0
        val p = actual.coerceIn(0.02, 0.98)
        return 1.96 * Math.sqrt(p * (1 - p) / n)
    }
}

fun main(args: Array<String>) {
    val matches = if (args.isNotEmpty()) args[0].toInt() else 30

    val bands = Array(10) { Bucket() }
    val perKind = HashMap<OptKind, Bucket>()
    for (k in OptKind.entries) perKind[k] = Bucket()

    // (player, act) counts per match, for the between-match distance.
    val profiles = ArrayList<HashMap<String, Int>>()
    // How concentrated one man's choices are on his single favourite act.
    var topShareSum = 0.0
    var topShareN = 0
    var repeatSum = 0.0
    var decisions = 0L
    var freshDecisions = 0L

    for (i in 0 until matches) {
        val sim = MatchSim(1000L + i)

        sim.onOutcome = { kind, p, worked ->
            val b = bands[(p * 10f).toInt().coerceIn(0, 9)]
            b.n++; b.predSum += p.toDouble(); if (worked) b.hits++
            val pk = perKind.getValue(kind)
            pk.n++; pk.predSum += p.toDouble(); if (worked) pk.hits++
        }
        sim.onChoice = { m, o, _, _ ->
            decisions++
            repeatSum += o.repeats.toDouble()
            if (o.repeats == 0) freshDecisions++
        }
        sim.play()

        // The supervisor IS the record. Everything a mind remembered is in it.
        val memory = sim.draw.census().filterKeys { it.contains(".DID.") }
        profiles.add(HashMap(memory))

        // Per man: how much of what he did was one single act?
        val byMan = HashMap<String, MutableList<Int>>()
        for ((code, n) in memory) {
            val man = code.substringBefore(".DID.")
            byMan.getOrPut(man) { ArrayList() }.add(n)
        }
        for ((_, counts) in byMan) {
            val total = counts.sum()
            if (total >= 10) { topShareSum += counts.max().toDouble() / total; topShareN++ }
        }
    }

    println("=".repeat(78))
    println("MINDCHECK — $matches matches")
    println("=".repeat(78))

    // ------------------------------------------------------- 1. calibration
    println()
    println("1. IS THE PERCENTAGE REAL?")
    println()
    println("   He predicts, the football answers. A bucket is a MISS when the gap")
    println("   between predicted and actual is bigger than the bucket's own noise.")
    println()
    println(String.format("   %-12s %8s %11s %10s %9s  %s",
        "he said", "times", "predicted", "actual", "±noise", ""))
    println("   " + "-".repeat(71))
    var misses = 0
    var scored = 0
    for (i in 0 until 10) {
        val b = bands[i]
        if (b.n < 30) continue
        scored++
        val gap = abs(b.predicted - b.actual)
        val miss = gap > b.noise + 0.05
        if (miss) misses++
        println(String.format("   %3d-%3d%%      %8d %10.1f%% %9.1f%% %8.1f%%  %s",
            i * 10, i * 10 + 10, b.n, b.predicted * 100, b.actual * 100, b.noise * 100,
            if (miss) if (b.actual > b.predicted) "too pessimistic" else "TOO CONFIDENT" else "ok"))
    }
    println()
    println(String.format("   %-14s %8s %11s %10s  %s", "act", "times", "predicted", "actual", ""))
    println("   " + "-".repeat(71))
    for (k in OptKind.entries) {
        val b = perKind.getValue(k)
        if (b.n == 0) continue
        val gap = abs(b.predicted - b.actual)
        println(String.format("   %-14s %8d %10.1f%% %9.1f%%  %s",
            k.name, b.n, b.predicted * 100, b.actual * 100,
            if (gap > b.noise + 0.05) String.format("out by %.0f points", gap * 100) else "ok"))
    }

    // ------------------------------------------------------- 2. repetition
    println()
    println("2. DOES A MATCH REPEAT ITSELF?")
    println()
    val topShare = if (topShareN == 0) 0.0 else topShareSum / topShareN
    println(String.format("   decisions taken                    %,d", decisions))
    println(String.format("   he had never played that ball yet  %.1f%%",
        100.0 * freshDecisions / maxOf(1, decisions)))
    println(String.format("   times he had played it before, mean %.2f", repeatSum / maxOf(1, decisions)))
    println(String.format("   his single most played act is      %.1f%% of what he does", topShare * 100))
    println()
    println("   The last row is the one that matters. At 100% a man has exactly one")
    println("   idea; the damping in Mind.REPEAT_DAMP exists to pull it down, and if")
    println("   this reads near 100% the damping is not working.")

    // -------------------------------------------- 3. are two matches alike?
    println()
    println("3. ARE TWO MATCHES THE SAME AFTERNOON?")
    println()
    var dSum = 0.0
    var dN = 0
    for (i in profiles.indices) for (j in i + 1 until profiles.size) {
        val a = profiles[i]; val b = profiles[j]
        var diff = 0L; var tot = 0L
        for (k in a.keys + b.keys) {
            val x = a[k] ?: 0; val y = b[k] ?: 0
            diff += abs(x - y).toLong(); tot += (x + y).toLong()
        }
        if (tot > 0) { dSum += diff.toDouble() / tot; dN++ }
    }
    val dist = if (dN == 0) 0.0 else dSum / dN
    println(String.format("   two matches differ in %.1f%% of their decisions", dist * 100))
    println()
    println("   PRINTED, NOT GATED, and the reason matters. There is no external")
    println("   figure for how different two football matches should be, so any bar")
    println("   here would be a number I made up — and a made-up bar that the engine")
    println("   is then tuned against is precisely how the predecessor drifted. It is")
    println("   watched for COLLAPSE: if this ever falls toward zero, matches have")
    println("   started converging on one script and something has gone wrong.")
    println()

    // What CAN be gated: the same seed twice is the same match, decision for
    // decision. That is not a preference, it is the supervisor's core claim,
    // and if it ever fails neither fingerprint means anything any more.
    val a = MatchSim(1000L).also { it.play() }.draw.census().filterKeys { it.contains(".DID.") }
    val b = MatchSim(1000L).also { it.play() }.draw.census().filterKeys { it.contains(".DID.") }
    val deterministic = a == b
    println("   same seed replayed: " +
        if (deterministic) "IDENTICAL, decision for decision."
        else "DIFFERENT — the mind is not deterministic.")

    println()
    println("=".repeat(78))
    /*
     * WHAT THIS HARNESS GATES, AND WHAT IT ONLY PRINTS.
     *
     * Gated, because both are properties with a right answer that does not
     * depend on anyone's taste:
     *   - the same seed replays identically;
     *   - no single act eats a man's whole match.
     *
     * Printed, because there is no honest bar yet:
     *   - the calibration table. Some of it is measurably wrong ON PURPOSE —
     *     SHOT is left uncalibrated while goals are out of band, and the note
     *     in Mind.pSuccess says why. Gating it would force that number to be
     *     fitted to a broken finishing model.
     */
    if (!deterministic) {
        println("VERDICT: THE SAME SEED PLAYED TWO DIFFERENT MATCHES.")
        println("=".repeat(78)); System.exit(1)
    }
    if (topShare >= 0.60) {
        println(String.format(
            "VERDICT: one act is %.1f%% of a man's match — he has a single idea.", topShare * 100))
        println("=".repeat(78)); System.exit(1)
    }
    println("VERDICT: replay is exact, and no man has a single idea.")
    if (misses > 0) println("         $misses calibration bucket(s) still off — printed above, not gated.")
    println("=".repeat(78))
}
