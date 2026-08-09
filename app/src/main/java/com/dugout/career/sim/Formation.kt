package com.dugout.career.sim

/** How far a man goes to do his job. At step 2 it is carried but not yet read. */
enum class Duty { DEFEND, SUPPORT, ATTACK }

/**
 * One of the eleven, placed on the grid.
 *
 * [lane] and [band] are CONTINUOUS, not indices. The grid is a snap, not a
 * cage: a preset writes eleven anchors and dragging a man puts him at 3.4 /
 * 2.7 if that is where he was dropped. Asymmetry therefore costs nothing and
 * needs no feature — a left-back at band 2.6 and a right-back at band 1.2 is
 * two numbers, and nothing downstream knows they differ.
 */
data class Slot(
    val id: Int,
    val lane: Float,
    val band: Float,
    val duty: Duty = Duty.SUPPORT
) {
    val isKeeper: Boolean get() = id == 0
}

/**
 * The block, in metres, separately for in and out of possession — four
 * numbers, not one slider, plus where the last line sits.
 */
data class Block(
    val widthInPoss: Float = 50f,
    val widthOutPoss: Float = 34f,
    val depthInPoss: Float = 40f,
    val depthOutPoss: Float = 30f,
    val lineHeight: Float = 36f
)

class Formation(val name: String, val slots: List<Slot>, val block: Block = Block()) {

    init { require(slots.size == 11) { "a formation is eleven men, got ${slots.size}" } }

    val outfield: List<Slot> get() = slots.filter { !it.isKeeper }

    /**
     * Compile the grid to anchors in a side's ATTACKING frame, in metres,
     * shaped by the block.
     *
     * Done on change rather than per tick — this is the compile step the
     * design calls for, and the per-tick cost of a man is then reading two
     * floats out of [outAx] / [outAy].
     */
    fun compile(inPossession: Boolean, outAx: FloatArray, outAy: FloatArray) {
        for (s in slots) {
            outAx[s.id] = Pitch.bandCentre(s.band)
            outAy[s.id] = Pitch.laneCentre(s.lane)
        }

        val wantWidth = if (inPossession) block.widthInPoss else block.widthOutPoss
        val wantDepth = if (inPossession) block.depthInPoss else block.depthOutPoss

        // Width and depth scale the SHAPE about its own centroid. Applied here
        // once, not clamped per man per tick, because a per-tick clamp fights
        // whatever the man is trying to do and you get rubber-banding.
        var cx = 0f; var cy = 0f
        for (s in outfield) { cx += outAx[s.id]; cy += outAy[s.id] }
        cx /= 10f; cy /= 10f

        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (s in outfield) {
            if (outAx[s.id] < minX) minX = outAx[s.id]
            if (outAx[s.id] > maxX) maxX = outAx[s.id]
            if (outAy[s.id] < minY) minY = outAy[s.id]
            if (outAy[s.id] > maxY) maxY = outAy[s.id]
        }
        val kx = if (maxX - minX > 0.5f) wantDepth / (maxX - minX) else 1f
        val ky = if (maxY - minY > 0.5f) wantWidth / (maxY - minY) else 1f

        for (s in outfield) {
            outAx[s.id] = cx + (outAx[s.id] - cx) * kx
            outAy[s.id] = cy + (outAy[s.id] - cy) * ky
        }

        // Slide the whole block so the last line sits at the stated height.
        var lowest = Float.MAX_VALUE
        for (s in outfield) if (outAx[s.id] < lowest) lowest = outAx[s.id]
        val slide = block.lineHeight - lowest
        for (s in outfield) outAx[s.id] += slide

        // The keeper is not part of the block and does not scale with it.
        val gk = slots.first { it.isKeeper }
        outAx[gk.id] = 5.5f
        outAy[gk.id] = Pitch.WIDTH * 0.5f
    }

    companion object {
        /**
         * Presets fill the grid; they are not names the engine reads. The
         * engine reads lane occupancy, and these are just eleven pairs of
         * numbers that produce it.
         */
        fun preset(name: String): Formation = when (name) {
            "4-3-3" -> Formation("4-3-3", listOf(
                Slot(0, 2.0f, 0.2f, Duty.DEFEND),
                Slot(1, 0.4f, 1.5f, Duty.SUPPORT),   // left back
                Slot(2, 1.4f, 1.0f, Duty.DEFEND),
                Slot(3, 2.6f, 1.0f, Duty.DEFEND),
                Slot(4, 3.6f, 1.5f, Duty.SUPPORT),   // right back
                Slot(5, 2.0f, 2.0f, Duty.DEFEND),    // holding
                Slot(6, 1.3f, 2.8f, Duty.SUPPORT),
                Slot(7, 2.7f, 2.8f, Duty.SUPPORT),
                Slot(8, 0.2f, 3.9f, Duty.ATTACK),    // left wing
                Slot(9, 2.0f, 4.3f, Duty.ATTACK),    // centre forward
                Slot(10, 3.8f, 3.9f, Duty.ATTACK)    // right wing
            ))
            "4-4-2" -> Formation("4-4-2", listOf(
                Slot(0, 2.0f, 0.2f, Duty.DEFEND),
                Slot(1, 0.5f, 1.4f, Duty.SUPPORT),
                Slot(2, 1.4f, 1.0f, Duty.DEFEND),
                Slot(3, 2.6f, 1.0f, Duty.DEFEND),
                Slot(4, 3.5f, 1.4f, Duty.SUPPORT),
                Slot(5, 0.4f, 2.7f, Duty.SUPPORT),
                Slot(6, 1.5f, 2.5f, Duty.SUPPORT),
                Slot(7, 2.5f, 2.5f, Duty.SUPPORT),
                Slot(8, 3.6f, 2.7f, Duty.SUPPORT),
                Slot(9, 1.6f, 4.1f, Duty.ATTACK),
                Slot(10, 2.4f, 4.1f, Duty.ATTACK)
            ))
            "3-5-2" -> Formation("3-5-2", listOf(
                Slot(0, 2.0f, 0.2f, Duty.DEFEND),
                Slot(1, 1.1f, 1.0f, Duty.DEFEND),
                Slot(2, 2.0f, 0.9f, Duty.DEFEND),
                Slot(3, 2.9f, 1.0f, Duty.DEFEND),
                Slot(4, 0.2f, 2.6f, Duty.ATTACK),    // wing back
                Slot(5, 3.8f, 2.6f, Duty.ATTACK),    // wing back
                Slot(6, 2.0f, 2.1f, Duty.DEFEND),
                Slot(7, 1.3f, 3.0f, Duty.SUPPORT),
                Slot(8, 2.7f, 3.0f, Duty.SUPPORT),
                Slot(9, 1.6f, 4.2f, Duty.ATTACK),
                Slot(10, 2.4f, 4.2f, Duty.ATTACK)
            ))
            else -> throw IllegalArgumentException("no preset '$name'")
        }
    }
}
