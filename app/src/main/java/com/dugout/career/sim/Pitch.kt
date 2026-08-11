package com.dugout.career.sim

/**
 * The pitch, in metres, and the 5 x 6 grid that is a VIEW of it.
 *
 * Coordinates are absolute: x from 0 (the home side's own goal line) to 105,
 * y from 0 to 68. Nothing in the engine reasons in this frame directly —
 * everything asks for a side's ATTACKING frame, where x is always "toward the
 * goal I am attacking". One mirror, here, and then a role or an instruction is
 * written once and works for both sides.
 *
 * The grid is a snap, not a cage: lane and band are read off continuous
 * metres, and a man is never held in a cell.
 */
object Pitch {

    const val LENGTH = 105f
    const val WIDTH = 68f

    const val BOX_DEPTH = 16.5f
    const val BOX_WIDTH = 40.32f
    const val SIX_DEPTH = 5.5f
    const val SIX_WIDTH = 18.32f
    const val GOAL_WIDTH = 7.32f
    const val CENTRE_CIRCLE = 9.15f

    const val LANES = 5
    const val BANDS = 6

    /**
     * Lane boundaries, split on the penalty-box lines rather than into fifths.
     *
     * That makes the wings the corridor OUTSIDE the box and the half-spaces
     * real half-spaces, which is the whole reason for having lanes. Fifths of a
     * rectangle would put the box edge in the middle of a lane and the grid
     * would stop meaning anything a coach would recognise.
     */
    val LANE_EDGES = floatArrayOf(0f, 13.84f, 27.28f, 40.72f, 54.16f, 68f)

    val LANE_NAMES = arrayOf("lw", "lhs", "c", "rhs", "rw")

    /** x in the attacking frame of [side]: 0 is his own goal line, 105 theirs. */
    fun attX(side: Int, x: Float): Float = if (side == 0) x else LENGTH - x

    /** y in the attacking frame of [side]: 0 is his left. */
    fun attY(side: Int, y: Float): Float = if (side == 0) y else WIDTH - y

    /** Back to absolute from a side's attacking frame. */
    fun absX(side: Int, ax: Float): Float = if (side == 0) ax else LENGTH - ax
    fun absY(side: Int, ay: Float): Float = if (side == 0) ay else WIDTH - ay

    /** Lane 0..4 from an attacking-frame y. */
    fun laneOf(ay: Float): Int {
        for (i in 0 until LANES) if (ay < LANE_EDGES[i + 1]) return i
        return LANES - 1
    }

    /** Band 0..5 from an attacking-frame x: 0 is his own box, 5 is theirs. */
    fun bandOf(ax: Float): Int =
        ((ax / LENGTH) * BANDS).toInt().coerceIn(0, BANDS - 1)

    /** Centre of a lane in attacking-frame metres. */
    fun laneCentre(lane: Float): Float {
        val i = lane.toInt().coerceIn(0, LANES - 1)
        val f = (lane - i).coerceIn(0f, 1f)
        val a = (LANE_EDGES[i] + LANE_EDGES[i + 1]) * 0.5f
        val b = if (i + 1 < LANES) (LANE_EDGES[i + 1] + LANE_EDGES[i + 2]) * 0.5f else a
        return a + (b - a) * f
    }

    /** Centre of a band in attacking-frame metres. */
    fun bandCentre(band: Float): Float = ((band + 0.5f) / BANDS) * LENGTH

    /** Is an absolute point inside [side]'s OWN penalty area? */
    fun inOwnBox(side: Int, x: Float, y: Float): Boolean {
        val ax = attX(side, x)
        return ax <= BOX_DEPTH &&
            y >= (WIDTH - BOX_WIDTH) * 0.5f &&
            y <= (WIDTH + BOX_WIDTH) * 0.5f
    }

    fun onPitch(x: Float, y: Float): Boolean =
        x >= 0f && x <= LENGTH && y >= 0f && y <= WIDTH
}
