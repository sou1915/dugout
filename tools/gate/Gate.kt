package gate

import java.io.File
import kotlin.system.exitProcess

/**
 * The CI gate of GAME_BRIEF.md §1.2 — two fingerprints over 200 fixed-seed
 * matches, with the readable breakdown when the shape hash moves.
 *
 * Step 1 has no engine, so the gate's correct behaviour today is to fail
 * loudly saying exactly that, and CI is red. A gate that reported success
 * against an empty engine would be worse than no gate: it would teach everyone
 * to read green as meaning nothing.
 */

private const val BASELINE = "tools/gate/baseline.properties"

private const val NO_ENGINE = 2
private const val NO_BASELINE = 3
private const val MOVED = 1
private const val REFUSED = 4

fun main(args: Array<String>) {
    when {
        args.contains("--selftest") -> exitProcess(selfTest())
        args.contains("--record") -> exitProcess(refuseToRecord())
        else -> exitProcess(gate())
    }
}

// ------------------------------------------------------------------ gate

private fun gate(): Int {
    val engine = EngineRegistry.engine
    if (engine == null) {
        println(
            """
            |GATE: FAIL — there is no engine to fingerprint.
            |
            |This is the expected result at build-order step 1. The shim, CI and
            |this skeleton exist; eleven men who move do not, so there is nothing
            |to hash and nothing to compare. CI is red on purpose and that is the
            |correct colour (GAME_BRIEF.md §6, steps 1 and 2).
            |
            |To turn it green, step 2 must:
            |  1. implement gate.Gateable on the match engine,
            |  2. point EngineRegistry.engine at it,
            |  3. and the OWNER records the first baseline into $BASELINE.
            |
            |An agent does not record a baseline. See §1.2.
            """.trimMargin()
        )
        return NO_ENGINE
    }

    val digest = ShapeDigest.declare()
    val results = Fnv()
    var seed = EngineRegistry.FIRST_SEED
    repeat(EngineRegistry.MATCHES) {
        engine.play(seed, digest).feed(results)
        seed++
    }
    val resultHash = results.hex()
    val shapeHash = digest.hex()

    val baseline = readBaseline()
    if (baseline == null) {
        println("GATE: FAIL — no baseline at $BASELINE.")
        println()
        println("  result fingerprint  $resultHash")
        println("  shape fingerprint   $shapeHash")
        println()
        println("The owner records the baseline, not the agent (§1.2). The lines are:")
        println()
        println("  result.hash=$resultHash")
        println("  shape.hash=$shapeHash")
        digest.asBaselineLines().forEach { println("  $it") }
        return NO_BASELINE
    }

    val wasResult = baseline["result.hash"]
    val wasShape = baseline["shape.hash"]
    val resultMoved = wasResult != resultHash
    val shapeMoved = wasShape != shapeHash

    if (!resultMoved && !shapeMoved) {
        println("GATE: PASS — ${EngineRegistry.MATCHES} matches, both fingerprints unchanged.")
        println("  result $resultHash")
        println("  shape  $shapeHash")
        return 0
    }

    println("GATE: FAIL — a fingerprint moved.")
    println()
    if (resultMoved) {
        println("RESULT fingerprint moved: $wasResult -> $resultHash")
        println("  The football changed. If the change was meant to be cosmetic,")
        println("  the cosmetic path is touching the football (§1.2).")
        println()
    }
    if (shapeMoved) {
        println("SHAPE fingerprint moved: $wasShape -> $shapeHash")
        printBreakdown(diff(digest, longs(baseline)))
        println()
        println("  Where eleven men stand is this game's subject, so this is never")
        println("  cosmetic — but it is often correct. If it is correct, say so with")
        println("  the breakdown above as the argument, and let the owner re-record.")
    }
    return MOVED
}

// --------------------------------------------------------------- record

private fun refuseToRecord(): Int {
    println(
        """
        |GATE: refusing --record.
        |
        |Re-recording a baseline is the owner's call and never the agent's
        |(GAME_BRIEF.md §1.2). A gate that gets re-recorded routinely is not a
        |gate, and that is how the predecessor's harness died.
        |
        |Run the gate without the flag. If a fingerprint moved it prints the old
        |hash, the new hash, and which statistic moved and by how much — paste
        |that, say why the new football is right, and let the owner decide.
        """.trimMargin()
    )
    return REFUSED
}

// -------------------------------------------------------------- selftest

/**
 * The breakdown printer is the one part of this file that can be proved
 * correct before an engine exists, so it is — with synthetic digests, rather
 * than by asserting in a comment that it works.
 */
private fun selfTest(): Int {
    var failures = 0

    fun check(what: String, ok: Boolean) {
        println("  ${if (ok) "ok  " else "FAIL"}  $what")
        if (!ok) failures++
    }

    println("gate selftest")

    // A digest identical to its baseline moves nothing.
    val a = ShapeDigest.declare()
    a.stat("home.block_depth_m", 0.5).add(32.0)
    a.stat("home.goal_side_in_box", 0.25).add(8.5)
    val same = a.asBaselineLines().associate {
        val (k, v) = it.split("="); k to v.toLong()
    }
    check("an unchanged digest reports no moved statistic", diff(a, same).isEmpty())

    // Move one statistic by a known amount and see it named.
    val b = ShapeDigest.declare()
    b.stat("home.block_depth_m", 0.5).add(34.5)      // +2.5 m = +5 quanta
    b.stat("home.goal_side_in_box", 0.25).add(8.5)   // unchanged
    val moved = diff(b, same)
    check("one moved statistic is reported", moved.size == 1)
    check("it is named", moved.firstOrNull()?.name == "home.block_depth_m")
    check("its delta is +5 quanta", moved.firstOrNull()?.delta == 5L)

    // A change smaller than the quantum is not a change.
    val c = ShapeDigest.declare()
    c.stat("home.block_depth_m", 0.5).add(32.2)      // +0.2 m, inside the quantum
    c.stat("home.goal_side_in_box", 0.25).add(8.5)
    check("a sub-quantum change is not reported", diff(c, same).isEmpty())

    // Worst offender first.
    val d = ShapeDigest.declare()
    d.stat("home.block_depth_m", 0.5).add(33.0)      // +2 quanta
    d.stat("home.goal_side_in_box", 0.25).add(6.0)   // -10 quanta
    val ordered = diff(d, same)
    check("the breakdown is sorted worst first",
        ordered.map { it.name } == listOf("home.goal_side_in_box", "home.block_depth_m"))

    // The digest hash is a function of the statistics, not of insertion order.
    val e = ShapeDigest.declare()
    e.stat("home.goal_side_in_box", 0.25).add(8.5)
    e.stat("home.block_depth_m", 0.5).add(32.0)
    check("the hash does not depend on insertion order", a.hex() == e.hex())
    check("the hash moves when a statistic moves", a.hex() != b.hex())

    val sb = StringBuilder()
    printBreakdown(ordered, sb)
    check("the printed breakdown names the statistic and its metres",
        sb.contains("goal_side_in_box") && sb.contains("-10 quanta"))
    print(sb)

    println(if (failures == 0) "selftest: PASS" else "selftest: FAIL ($failures)")
    return if (failures == 0) 0 else 1
}

// -------------------------------------------------------------- baseline

private fun readBaseline(): Map<String, String>? {
    val f = File(BASELINE)
    if (!f.exists()) return null
    return f.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
        .associate { val i = it.indexOf('='); it.take(i) to it.substring(i + 1) }
}

private fun longs(m: Map<String, String>): Map<String, Long> =
    m.mapNotNull { (k, v) -> v.toLongOrNull()?.let { k to it } }.toMap()
