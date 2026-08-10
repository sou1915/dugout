package harness

import com.dugout.career.sim.Ev
import com.dugout.career.sim.MatchSim
import com.dugout.career.sim.TeamMind
import gate.Fnv

/**
 * TEAMCHECK — three collective systems, measured ONE AT A TIME.
 *
 * The team mind is three separate things wearing one name: a disposition, a
 * memory and a collective act. Run together they are one number that moved, and
 * this session has already lost four results to exactly that — a measurement
 * taken while something else was also changing is not a measurement.
 *
 * So each layer has a switch, and this harness runs the same fixed seeds with
 * one layer at a time.
 *
 * THE CONTROL ROW IS THE IMPORTANT ONE, and it is only worth anything if a
 * switched-off layer is genuinely INERT — drawing nothing, publishing nothing,
 * biasing nothing. That is gated below, as properties of the switches rather
 * than as a remembered scoreline.
 *
 *   java -cp build/gate.jar harness.TeamCheckKt [matches]
 */

private class Roll {
    var goals = 0.0; var shots = 0.0; var passes = 0.0; var completed = 0.0
    var interceptions = 0.0; var corners = 0.0
    var pressTriggered = 0.0; var pressWon = 0.0
    var wonAx = 0.0; var wonN = 0.0
    var n = 0
    fun completion() = if (passes == 0.0) 0.0 else 100.0 * completed / passes
}

private fun run(matches: Int, disposition: Boolean, memory: Boolean, acts: Boolean): Pair<Roll, String> {
    TeamMind.DISPOSITION = disposition
    TeamMind.MEMORY = memory
    TeamMind.ACTS = acts

    val r = Roll()
    val f = Fnv()
    for (i in 0 until matches) {
        val sim = MatchSim(1000L + i)
        sim.play()
        val e = sim.events
        r.goals += e[Ev.GOAL].toDouble()
        r.shots += (e[Ev.SHOT_PLACED] + e[Ev.SHOT_LONG_RANGE]).toDouble()
        r.passes += (e[Ev.PASS_SHORT] + e[Ev.PASS_LONG]).toDouble()
        r.completed += e[Ev.PASS_COMPLETED].toDouble()
        r.interceptions += e[Ev.INTERCEPTION].toDouble()
        r.corners += e[Ev.CORNER_WON].toDouble()
        r.pressTriggered += e[Ev.PRESS_TRIGGERED].toDouble()
        r.pressWon += e[Ev.PRESS_WON].toDouble()
        r.n++
        // Same fields the real gate hashes, so this number is comparable to it.
        f.put(sim.goals[0]).put(sim.goals[1])
            .put(sim.shots[0]).put(sim.shots[1])
            .put(sim.cards[0]).put(sim.cards[1])
            .put(sim.corners[0]).put(sim.corners[1])
    }
    TeamMind.allOn()
    return r to f.hex()
}

private fun row(label: String, r: Roll, fp: String) {
    val n = r.n.toDouble()
    println(String.format(
        "  %-22s %7.2f %8.1f %9.1f%% %9.1f %8.2f %8.2f   %s",
        label, r.goals / n, r.passes / n, r.completion(),
        r.interceptions / n, r.pressTriggered / n, r.pressWon / n, fp))
}

fun main(args: Array<String>) {
    val matches = if (args.isNotEmpty()) args[0].toInt() else 30

    println("=".repeat(100))
    println("TEAMCHECK — the collective mind, one layer at a time, $matches fixed seeds each")
    println("=".repeat(100))
    println()
    println(String.format("  %-22s %7s %8s %10s %9s %8s %8s   %s",
        "layers on", "goals", "passes", "completion", "intercept", "press", "won", "result fingerprint"))
    println("  " + "-".repeat(96))

    val (off, offFp) = run(matches, disposition = false, memory = false, acts = false)
    row("none  (control)", off, offFp)
    val (d, dFp) = run(matches, disposition = true, memory = false, acts = false)
    row("disposition", d, dFp)
    val (m, mFp) = run(matches, disposition = false, memory = true, acts = false)
    row("memory", m, mFp)
    val (a, aFp) = run(matches, disposition = false, memory = false, acts = true)
    row("acts (the press)", a, aFp)
    val (all, allFp) = run(matches, disposition = true, memory = true, acts = true)
    row("all three  (shipped)", all, allFp)

    println()
    println("READING THIS TABLE")
    println()
    println("  Each row is the SAME $matches seeds with one thing changed. A layer whose")
    println("  row is identical to the control is not a tactic, it is a comment — the")
    println("  same finding a dead lever gets in ShapeCheck, and it is the finding this")
    println("  harness exists to be able to make.")
    println()

    // ------------------------------------------------------- the real gate here
    println("=".repeat(100))
    println("IS A SWITCHED-OFF LAYER ACTUALLY INERT?")
    println("=".repeat(100))
    println()

    /*
     * THIS CHECK USED TO PIN A HISTORICAL CONSTANT, AND THAT WAS A MISTAKE.
     *
     * The first version asserted that with all three layers off, 200 matches
     * hash to 8b9bf19a56c2afe3 — the fingerprint from the commit before the
     * team mind existed. It passed, and the thing it proved was real and worth
     * proving: a whole new random draw and a whole new family of memory codes
     * had been added and 200 matches were byte-identical.
     *
     * But it is not a gate. It is a snapshot with an expiry date. The very next
     * legitimate change to the engine — the loose ball — made it fail while
     * nothing it was watching had gone wrong, which is the definition of a
     * false alarm, and a check that cries wolf is worse than no check because
     * the next person turns it off.
     *
     * What replaces it are properties of the SWITCHES themselves, which no
     * change to the football can invalidate:
     *
     *   ACTS off        the press must draw nothing and fire nothing. Not
     *                   "fire less" — the code must never appear in the
     *                   supervisor's census at all.
     *   DISPOSITION off every number it publishes must sit at its neutral
     *                   value for the whole ninety minutes.
     *   MEMORY off      the channel bias must be exactly 1, for every channel,
     *                   after a match's worth of evidence has accumulated.
     *
     * Each of those is a statement about inertness rather than about a
     * scoreline, and inertness is what the table above depends on.
     */
    var bad = 0

    TeamMind.DISPOSITION = false; TeamMind.MEMORY = false; TeamMind.ACTS = false
    var pressCodes = 0
    var pressEvents = 0
    var dispositionMoved = 0
    var biasMoved = 0
    for (i in 0 until 8) {
        val sim = MatchSim(1000L + i)
        sim.play { _, _ ->
            for (t in sim.teams) {
                if (t.directness != 1f || t.caution != 1f ||
                    t.lineShift != 0f || t.aggression != 0.5f) dispositionMoved++
                if (t.pressLive) pressEvents++
            }
        }
        pressCodes += sim.draw.census().keys.count { it.contains(".TEAM.PRESS") }
        pressEvents += sim.events[Ev.PRESS_TRIGGERED]
        for (t in sim.teams) for (ch in 0..2)
            if (t.channelBias(sim.draw, ch) != 1f) biasMoved++
    }
    TeamMind.allOn()

    fun claim(label: String, n: Int) {
        val ok = n == 0
        if (!ok) bad++
        println(String.format("  %-58s %s", label, if (ok) "inert" else "STILL ACTIVE ($n)"))
    }
    claim("ACTS off: press codes ever drawn", pressCodes)
    claim("ACTS off: press events ever fired / men ever committed", pressEvents)
    claim("DISPOSITION off: samples where a published number was not neutral", dispositionMoved)
    claim("MEMORY off: channels whose bias was not exactly 1", biasMoved)
    println()
    println("  all three off, $matches seeds:  $offFp")
    println("  Printed, not compared. It moves whenever the football legitimately")
    println("  moves, so an expected value for it would need re-recording on every")
    println("  commit — which is what the fingerprint gate is already for.")
    println()
    if (bad > 0) {
        println("  VERDICT: A SWITCHED-OFF LAYER IS STILL DOING SOMETHING.")
        println("  Nothing else in this table can be read until that is true.")
        println("=".repeat(100))
        System.exit(1)
    }
    println("  VERDICT: all three are genuinely inert when off, so each row above")
    println("           is one system measured on its own.")
    println("=".repeat(100))

    // ------------------------------------------------- what the press actually did
    println()
    println("THE PRESS — the layer you can see")
    println()
    val trig = a.pressTriggered / a.n
    val won = a.pressWon / a.n
    println(String.format("  triggered   %6.2f a match", trig))
    println(String.format("  ball won    %6.2f a match  (%.0f%% of presses)",
        won, if (trig == 0.0) 0.0 else 100.0 * won / trig))
    if (trig == 0.0) {
        println()
        println("  NEVER FIRES. A collective act that has a site and never reaches it is")
        println("  the same finding as a wired event reading 0.00, and it is a bug in the")
        println("  trigger, not a preference about pressing.")
        println("=".repeat(100))
        System.exit(1)
    }
    println()
    println("  A press is three men leaving their stations at the same instant. It is")
    println("  the first thing in this engine that a person can watch and name — the")
    println("  debug frames are the check that matters as much as this number.")
    println("=".repeat(100))
}
