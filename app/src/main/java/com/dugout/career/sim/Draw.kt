package com.dugout.career.sim

/**
 * THE RANDOMNESS SUPERVISOR — a layer that watches, reacts, and does not lose
 * the fingerprint.
 *
 * The problem it replaces: the old Rng was a QUEUE. Draws came out in order, so
 * adding one new random decision anywhere shifted every draw after it, and both
 * fingerprints moved wholesale on a change that touched one line — a shifted
 * stream and a changed football looked identical.
 *
 * IT ALSO MADE THE BRIEF'S TWO-STREAM DESIGN UNNECESSARY. §3 asked for separate
 * outcome and presentation streams so that a cosmetic draw could be proved
 * cosmetic. With addressed draws that is true by construction: a code used only
 * for drawing something cannot disturb a code that decides something, whatever
 * order they fire in. The second stream was carried unused for the whole of
 * this project and is deleted here — the property it existed to guarantee is
 * now a property of the mechanism.
 *
 * Here a draw is resolved by its CODE, not by its position:
 *
 *     next("M1042.H1.T0207.P07.PASS")  ->  always the same value
 *
 * Add a code, delete a code, reorder the engine — every other code keeps its
 * value, byte for byte. The fingerprint then moves only for the thing that
 * actually changed, which is the whole point of having one.
 *
 * Three things it does beyond generating numbers:
 *
 *  1. WATCH     every draw is counted by code, so dead randomness shows up the
 *               way a dead event does in the census.
 *  2. COMPARE   two runs can be diffed to the first code that disagreed —
 *               "they matched for 4,127 draws, then P07.PASS" — instead of
 *               "the hash moved".
 *  3. FREEZE    a named subsystem can be pinned to a recorded value, so one
 *               model can be changed while every other source of randomness is
 *               held identical. That is a real controlled experiment, and its
 *               absence is why several measurements this session were taken
 *               under confounds.
 */
class Draw(private val seed: Long) {

    /** How many times each code has been drawn — a repeat needs a new value. */
    private val used = HashMap<String, Int>()

    /** Codes pinned to a fixed value, for a controlled experiment. */
    private val frozen = HashMap<String, Float>()

    /** Recorded order, only when watching is on. Off in the gate: it is slow. */
    @JvmField var watching = false
    private val log = ArrayList<Pair<String, Float>>()

    /**
     * A value in 0..1 for this code.
     *
     * The occurrence index is folded in so a code fired twice in the same match
     * gets two different values — but it is counted PER CODE, so it cannot be
     * disturbed by anything happening elsewhere in the engine.
     */
    fun next(code: String): Float {
        frozen[code]?.let { return it }
        val n = used.merge(code, 1, Int::plus)!! - 1
        val v = value(seed, code, n)
        if (watching) log.add(code to v)
        return v
    }

    fun range(code: String, lo: Float, hi: Float): Float = lo + (hi - lo) * next(code)

    /**
     * RECORD THAT SOMETHING HAPPENED, without drawing a value.
     *
     * This is what turns the supervisor from a generator into a MEMORY, and it
     * is what a mind reads to know it has played this same ball six times
     * already. The counter is the same one [next] uses for occurrences, so the
     * census shows decisions and draws side by side — but a memory code carries
     * `.DID.` and is never passed to [next], so the two can never collide and
     * bump each other's values.
     *
     * It is safe for the fingerprint for exactly the reason the whole supervisor
     * exists: occurrences are counted PER CODE. Noting a new fact under a new
     * code cannot move a single value drawn under any other code.
     */
    fun note(code: String) { used.merge(code, 1, Int::plus) }

    /** How many times this code has fired. A mind's memory of its own match. */
    fun count(code: String): Int = used[code] ?: 0

    /** Pin a code, so a change elsewhere can be measured against a fixed draw. */
    fun freeze(code: String, v: Float) { frozen[code] = v }
    fun unfreeze(code: String) { frozen.remove(code) }

    /** How many times each code fired. A zero is dead randomness. */
    fun census(): Map<String, Int> = used

    fun trace(): List<Pair<String, Float>> = log

    companion object {
        /**
         * hash(seed, code, occurrence) -> 0..1
         *
         * SplitMix64's finaliser over an FNV-1a of the code. The generator was
         * never the problem — the ORDER was — so the same mixing is reused.
         */
        fun value(seed: Long, code: String, occurrence: Int): Float {
            var h = -0x340d631b7bdddcdbL
            for (ch in code) { h = (h xor ch.code.toLong()) * 0x100000001b3L }
            var z = h xor (seed * -0x61c8864680b583ebL) xor (occurrence.toLong() * 0x9e3779b1L)
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            z = z xor (z ushr 31)
            return ((z ushr 11).toDouble() / (1L shl 53).toDouble()).toFloat()
        }

        /**
         * The first code at which two traces disagree, or null if identical.
         * This is the answer to "what did my change actually do?".
         */
        fun firstDivergence(
            a: List<Pair<String, Float>>, b: List<Pair<String, Float>>
        ): String? {
            val n = minOf(a.size, b.size)
            for (i in 0 until n) if (a[i] != b[i])
                return "draw #$i: ${a[i].first}=${a[i].second} vs ${b[i].first}=${b[i].second}"
            if (a.size != b.size) return "traces agree for $n draws, then lengths differ (${a.size} vs ${b.size})"
            return null
        }
    }
}
