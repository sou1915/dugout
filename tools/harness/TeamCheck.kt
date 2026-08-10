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
 * THE FIRST ROW IS THE IMPORTANT ONE. With all three off, the engine must
 * produce the fingerprint it had BEFORE any of this existed, byte for byte. If
 * it does not, some part of the team mind is doing something when it is
 * switched off, and no other row in this table can be trusted.
 *
 *   java -cp build/gate.jar harness.TeamCheckKt [matches]
 */

/** What the fingerprint was on the commit before the team mind existed. */
private const val BEFORE_RESULT = "8b9bf19a56c2afe3"

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
    println("DOES SWITCHING IT ALL OFF GIVE BACK THE ENGINE THAT EXISTED BEFORE IT?")
    println("=".repeat(100))
    println()

    /*
     * This is the one claim in this file that has a right answer.
     *
     * With every layer off, the three systems must be perfectly inert: the
     * disposition returns neutral numbers, the memory returns exactly 1, and
     * the press never draws. Nothing else in the engine changed in that commit,
     * so the fingerprint must be the one recorded before it — and it is a full
     * gate rather than a print, because if a "disabled" system is still doing
     * something then every other row above was measured under a confound.
     *
     * It is also the supervisor's promise being cashed for the third time. The
     * team mind added a new random draw (the press trigger) and a whole new
     * family of memory codes, and under a sequential Rng that alone would have
     * changed every match ever played.
     */
    // The recorded number is 200 seeds through the same fields the gate hashes.
    // Comparing anything else to it is comparing two different measurements,
    // which is a mistake this harness made on its first run.
    val control = run(gate.EngineRegistry.MATCHES, false, false, false).second
    val same = control == BEFORE_RESULT
    println("  with all three off   $control   (${gate.EngineRegistry.MATCHES} seeds)")
    println("  before the team mind $BEFORE_RESULT   (${gate.EngineRegistry.MATCHES} seeds)")
    println()
    if (!same) {
        println("  VERDICT: A SWITCHED-OFF LAYER IS STILL DOING SOMETHING.")
        println("  Nothing else in this table can be read until that is true.")
        println("=".repeat(100))
        System.exit(1)
    }
    println("  VERDICT: identical. The three layers are genuinely separable, and each")
    println("           row above is one system measured on its own.")
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
