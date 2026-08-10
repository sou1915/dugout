package harness

import com.dugout.career.sim.Draw

/**
 * Proof that the supervisor keeps the fingerprint when the engine changes.
 *
 * The property that matters: adding a new random draw must leave every other
 * draw byte-identical. Under the old queue it never could. Measured here, not
 * asserted in a comment.
 *
 *   java -cp build/gate.jar harness.DrawCheckKt
 */
fun main() {
    var fail = 0
    fun check(what: String, ok: Boolean) {
        println("  ${if (ok) "ok  " else "FAIL"}  $what"); if (!ok) fail++
    }
    println("DrawCheck — the supervisor layer")

    // 1. same code, same seed, same value — anywhere, any run
    val a = Draw(1042L); val b = Draw(1042L)
    check("same code and seed give the same value",
        a.next("M1042.H1.T0207.P07.PASS") == b.next("M1042.H1.T0207.P07.PASS"))

    // 2. different seeds give different football
    check("a different seed gives a different value",
        Draw(1042L).next("X") != Draw(1043L).next("X"))

    // 3. THE PROPERTY: inserting a new draw disturbs nothing else
    val before = Draw(7L)
    val vPass = before.next("T0207.P07.PASS")
    val vShot = before.next("T0410.P09.SHOT")

    val after = Draw(7L)
    after.next("T0100.P03.NEW_TACKLE_MODEL")     // a whole new random decision
    after.next("T0150.P05.NEW_TACKLE_MODEL")
    val vPass2 = after.next("T0207.P07.PASS")
    val vShot2 = after.next("T0410.P09.SHOT")
    check("adding new draws leaves PASS untouched", vPass == vPass2)
    check("adding new draws leaves SHOT untouched", vShot == vShot2)

    // 4. a repeated code still gets fresh values
    val r = Draw(3L)
    check("the same code twice gives two values", r.next("Q") != r.next("Q"))

    // 5. freeze: hold one subsystem while another changes
    val f = Draw(9L)
    f.freeze("KEEPER.DIVE", 0.5f)
    check("a frozen code is pinned", f.next("KEEPER.DIVE") == 0.5f && f.next("KEEPER.DIVE") == 0.5f)

    // 6. divergence report names the first disagreement
    val t1 = Draw(5L).also { it.watching = true }
    val t2 = Draw(5L).also { it.watching = true }
    t1.next("A"); t1.next("B"); t1.next("C")
    t2.next("A"); t2.next("B"); t2.next("D")
    val d = Draw.firstDivergence(t1.trace(), t2.trace())
    check("divergence is located at the third draw", d != null && d.startsWith("draw #2"))
    println("  -> $d")

    // 7. census finds randomness that never fires
    val cen = Draw(1L).also { it.next("USED") }.census()
    check("the census counts what fired", cen["USED"] == 1 && cen["NEVER"] == null)

    println(if (fail == 0) "DrawCheck: PASS" else "DrawCheck: FAIL ($fail)")
    if (fail != 0) kotlin.system.exitProcess(1)
}
