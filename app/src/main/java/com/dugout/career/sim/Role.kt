package com.dugout.career.sim

/**
 * ROLES AND DUTIES — build-order step 4.
 *
 * A role is a bundle of intents with weights, never a stat bonus. Everything
 * here changes WHERE A MAN GOES and WHAT HE CHASES; nothing here adds to a
 * dice roll, and there is no `if (role == INVERTED_FB)` anywhere in the engine.
 * An inverted full-back and an overlapping full-back are the same seven fields
 * with different numbers, and that is the test of whether the role system is
 * real rather than decorative.
 *
 * The acceptance, from the brief: two sides identical except for one role
 * change must produce visibly different heat maps and different event rates.
 * `RoleCheck` measures exactly that and prints the pairs that fail to separate.
 */
enum class RoleId {
    GK,
    BALL_PLAYING_DEFENDER, STOPPER, COVER,
    OVERLAPPING_FB, INVERTED_FB,
    HOLDING_MID, BOX_TO_BOX, DEEP_PLAYMAKER, ADVANCED_PLAYMAKER,
    TOUCHLINE_WINGER, INSIDE_FORWARD,
    TARGET_MAN, POACHER, FALSE_NINE
}

/**
 * @param bandIn      band delta with the ball — how far up he goes
 * @param bandOut     band delta without it — how far he drops
 * @param laneIn      lane delta with the ball, POSITIVE meaning toward the
 *                    centre. The sign against the touchline is derived from
 *                    his anchor, so one number works on either flank.
 * @param laneOut     lane delta without it
 * @param leashM      how far from his anchor the block's slide may carry him.
 *                    This is what stops the shape being a rigid lattice that
 *                    translates: a poacher barely moves, a box-to-box roams.
 * @param chase       0..1, how readily he goes to the ball. Enters as seconds
 *                    added to his claim time, never as a probability.
 * @param maxAttX     a hard ceiling in his attacking frame, metres. The
 *                    holding midfielder's "does not cross the halfway line" is
 *                    this and nothing else.
 */
data class Role(
    val id: RoleId,
    val bandIn: Float,
    val bandOut: Float,
    val laneIn: Float = 0f,
    val laneOut: Float = 0f,
    val leashM: Float = 12f,
    val chase: Float = 0.5f,
    val maxAttX: Float = 999f,
    /**
     * What he TRIES, as a multiplicative prior on each option's utility.
     *
     * This is the only channel by which a role reaches the decision layer, and
     * it is a prior rather than a bonus on a dice roll — the design principle
     * of §2 written as a type. A deep playmaker weighting the switch does not
     * become better at switching; he attempts it more often, and whether it
     * comes off is the execution model's business.
     */
    val intent: Map<OptKind, Float> = emptyMap()
) {
    fun intentFor(k: OptKind): Float = intent[k] ?: 1f
}

/**
 * Duty is a TRANSFORM, not a fourth table.
 *
 * Fourteen roles times three duties is forty-two hand-tuned bundles that drift
 * apart within a month. Four scalars applied to the role keeps one source of
 * truth, and a duty change is then legible as the same role pushed further.
 */
data class DutyMod(
    val bandPush: Float,
    val leashScale: Float,
    val chaseScale: Float
)

object Roles {

    fun mod(d: Duty): DutyMod = when (d) {
        Duty.DEFEND -> DutyMod(-0.35f, 0.78f, 1.15f)
        Duty.SUPPORT -> DutyMod(0f, 1f, 1f)
        Duty.ATTACK -> DutyMod(0.45f, 1.28f, 0.88f)
    }

    private val table = HashMap<RoleId, Role>()

    operator fun get(id: RoleId): Role = table.getValue(id)

    val ALL: List<Role> get() = RoleId.entries.map { table.getValue(it) }

    private fun add(r: Role) { table[r.id] = r }

    init {
        add(Role(RoleId.GK, 0f, 0f, leashM = 5f, chase = 0.05f))

        // --- centre backs: three answers to the same question
        add(Role(RoleId.BALL_PLAYING_DEFENDER, bandIn = 0.55f, bandOut = 0.05f,
            leashM = 11f, chase = 0.35f,
            intent = mapOf(OptKind.THROUGH_BALL to 1.5f, OptKind.PASS_SPACE to 1.3f,
                OptKind.CLEAR to 0.45f, OptKind.SHOT to 0.3f)))
        // follows the striker out of the line
        add(Role(RoleId.STOPPER, bandIn = 0.15f, bandOut = 0.40f,
            leashM = 15f, chase = 0.85f,
            intent = mapOf(OptKind.CLEAR to 2.2f, OptKind.PASS_FEET to 1.2f,
                OptKind.THROUGH_BALL to 0.35f, OptKind.SHOT to 0.2f,
                OptKind.CARRY to 0.4f)))
        // drops behind it and sweeps
        add(Role(RoleId.COVER, bandIn = 0.05f, bandOut = -0.40f,
            leashM = 9f, chase = 0.25f,
            intent = mapOf(OptKind.CLEAR to 1.8f, OptKind.THROUGH_BALL to 0.5f,
                OptKind.CARRY to 0.6f, OptKind.SHOT to 0.2f)))

        // --- full backs: the lane change is the whole point
        add(Role(RoleId.OVERLAPPING_FB, bandIn = 1.35f, bandOut = -0.05f,
            laneIn = -0.45f, laneOut = -0.15f, leashM = 18f, chase = 0.5f,
            intent = mapOf(OptKind.CROSS to 2.0f, OptKind.CUT_BACK to 1.5f,
                OptKind.SHOT to 0.4f)))
        add(Role(RoleId.INVERTED_FB, bandIn = 0.70f, bandOut = 0.10f,
            laneIn = 1.15f, laneOut = 0.30f, leashM = 12f, chase = 0.5f,
            intent = mapOf(OptKind.CROSS to 0.35f, OptKind.PASS_FEET to 1.3f,
                OptKind.SWITCH to 1.4f)))

        // --- midfield
        add(Role(RoleId.HOLDING_MID, bandIn = 0.15f, bandOut = -0.15f,
            leashM = 9f, chase = 0.65f, maxAttX = 54f,
            intent = mapOf(OptKind.PASS_FEET to 1.5f, OptKind.SHOT to 0.15f,
                OptKind.THROUGH_BALL to 0.6f, OptKind.CARRY to 0.5f)))
        add(Role(RoleId.BOX_TO_BOX, bandIn = 1.25f, bandOut = -0.35f,
            leashM = 21f, chase = 0.85f,
            intent = mapOf(OptKind.CARRY to 1.9f, OptKind.PASS_SPACE to 1.3f,
                OptKind.SHOT to 1.2f, OptKind.CLEAR to 0.5f)))
        add(Role(RoleId.DEEP_PLAYMAKER, bandIn = 0.30f, bandOut = -0.25f,
            leashM = 13f, chase = 0.40f,
            intent = mapOf(OptKind.SWITCH to 2.2f, OptKind.THROUGH_BALL to 1.4f,
                OptKind.CLEAR to 0.4f)))
        add(Role(RoleId.ADVANCED_PLAYMAKER, bandIn = 0.85f, bandOut = -0.55f,
            laneIn = 0.45f, leashM = 15f, chase = 0.40f,
            intent = mapOf(OptKind.THROUGH_BALL to 2.2f, OptKind.PASS_SPACE to 1.5f,
                OptKind.CLEAR to 0.3f)))

        // --- wide forwards
        add(Role(RoleId.TOUCHLINE_WINGER, bandIn = 0.75f, bandOut = -0.60f,
            laneIn = -0.55f, laneOut = -0.25f, leashM = 13f, chase = 0.35f,
            intent = mapOf(OptKind.CROSS to 2.4f, OptKind.CUT_BACK to 1.6f,
                OptKind.SWITCH to 0.5f, OptKind.SHOT to 0.6f)))
        add(Role(RoleId.INSIDE_FORWARD, bandIn = 0.85f, bandOut = -0.55f,
            laneIn = 1.25f, laneOut = 0.45f, leashM = 15f, chase = 0.40f,
            intent = mapOf(OptKind.SHOT to 2.1f, OptKind.CARRY to 1.4f,
                OptKind.CROSS to 0.4f)))

        // --- centre forwards
        add(Role(RoleId.TARGET_MAN, bandIn = 0.45f, bandOut = -0.70f,
            leashM = 10f, chase = 0.30f,
            intent = mapOf(OptKind.PASS_FEET to 1.6f, OptKind.SHOT to 1.3f,
                OptKind.THROUGH_BALL to 0.5f)))
        add(Role(RoleId.POACHER, bandIn = 0.90f, bandOut = -0.95f,
            leashM = 8f, chase = 0.15f,
            intent = mapOf(OptKind.SHOT to 2.6f, OptKind.PASS_FEET to 0.6f,
                OptKind.SWITCH to 0.2f, OptKind.CLEAR to 0.2f)))
        // drops OUT of the line and leaves the centre empty
        add(Role(RoleId.FALSE_NINE, bandIn = -0.65f, bandOut = -0.85f,
            leashM = 17f, chase = 0.45f,
            intent = mapOf(OptKind.PASS_FEET to 1.8f, OptKind.THROUGH_BALL to 1.6f,
                OptKind.SHOT to 0.5f, OptKind.CROSS to 0.3f, OptKind.CLEAR to 0.25f)))
    }
}
