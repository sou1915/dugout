package com.dugout.career.sim

enum class EvGroup { ON_BALL, PASSING, SHOOTING, DEFENDING, GOALKEEPING, FOULS, RESTARTS, MATCH }

/**
 * THE EVENT VOCABULARY — everything a football match contains.
 *
 * Most of these do not happen yet, and that is the entire point. This is not a
 * description of what the engine currently does; it is the LIST OF THE JOB, and
 * the census that walks it turns "there is nothing happening in the simulation"
 * into a column with sixty zeros in it, each one named and addressable.
 *
 * [wired] is the thing that keeps that column honest. It says whether the
 * engine has a site that can fire this event at all. Without it, "0.00 a match"
 * means either "not implemented" or "implemented and never happens", and the
 * predecessor lost months to exactly that ambiguity three separate times —
 * `v2Defend` written and never called, `CHANCE_QUALITY` swept while dead,
 * `Scene.TAKE_ON` reachable only from a path that no longer ran.
 *
 * A wired event reading zero is a bug. An unwired event reading zero is a
 * to-do. They must never look the same in a table.
 */
enum class Ev(val group: EvGroup, val wired: Boolean = false) {

    // ------------------------------------------------------------- on the ball
    CARRY(EvGroup.ON_BALL, wired = true),
    SHIELD_BALL(EvGroup.ON_BALL),
    TURN_AWAY(EvGroup.ON_BALL),
    TAKE_ON_ATTEMPT(EvGroup.ON_BALL),
    TAKE_ON_WON(EvGroup.ON_BALL),
    TAKE_ON_LOST(EvGroup.ON_BALL),
    NUTMEG(EvGroup.ON_BALL),
    BACKHEEL(EvGroup.ON_BALL),
    FLICK_ON(EvGroup.ON_BALL, wired = true),
    FIRST_TOUCH_GOOD(EvGroup.ON_BALL),
    FIRST_TOUCH_HEAVY(EvGroup.ON_BALL),
    /**
     * A contested first touch that belonged to nobody.
     *
     * The single largest hole in this engine until now: every struck ball was
     * claimed, instantly and completely, by whoever was nearest. So an
     * incomplete pass was a turnover BY CONSTRUCTION, a clearance retained
     * possession 1.6% of the time, and a press that got near the ball converted
     * every time. One missing idea, showing up in three broken rows.
     */
    LOOSE_BALL(EvGroup.ON_BALL, wired = true),
    DISPOSSESSED(EvGroup.ON_BALL, wired = true),

    // ---------------------------------------------------------------- passing
    /** A struck ball that travelled under 24 m. Length is a fact; intent is not. */
    PASS_SHORT(EvGroup.PASSING, wired = true),
    PASS_LONG(EvGroup.PASSING, wired = true),
    /** First touched by a team-mate of the man who struck it. */
    PASS_COMPLETED(EvGroup.PASSING, wired = true),
    PASS_SWITCH(EvGroup.PASSING, wired = true),
    THROUGH_BALL(EvGroup.PASSING, wired = true),
    ONE_TWO(EvGroup.PASSING),
    CROSS_EARLY(EvGroup.PASSING, wired = true),
    CROSS_BYLINE(EvGroup.PASSING, wired = true),
    CUT_BACK(EvGroup.PASSING, wired = true),
    PASS_CLIPPED(EvGroup.PASSING),
    PASS_DRIVEN(EvGroup.PASSING),
    PASS_MISPLACED(EvGroup.PASSING, wired = true),

    // --------------------------------------------------------------- shooting
    SHOT_PLACED(EvGroup.SHOOTING, wired = true),
    SHOT_DRIVEN(EvGroup.SHOOTING),
    SHOT_CHIPPED(EvGroup.SHOOTING),
    SHOT_VOLLEY(EvGroup.SHOOTING),
    SHOT_HEADER(EvGroup.SHOOTING, wired = true),
    SHOT_LONG_RANGE(EvGroup.SHOOTING, wired = true),
    SHOT_FIRST_TIME(EvGroup.SHOOTING),
    SHOT_ON_TARGET(EvGroup.SHOOTING),
    SHOT_OFF_TARGET(EvGroup.SHOOTING),
    SHOT_BLOCKED(EvGroup.SHOOTING, wired = true),
    SHOT_DEFLECTED(EvGroup.SHOOTING),
    WOODWORK(EvGroup.SHOOTING),
    REBOUND(EvGroup.SHOOTING),
    /** The ball crossed the line between the posts. Geometry, not intent. */
    GOAL(EvGroup.SHOOTING, wired = true),
    OWN_GOAL(EvGroup.SHOOTING),

    // -------------------------------------------------------------- defending
    /** An opponent of the striker got the first touch. */
    INTERCEPTION(EvGroup.DEFENDING, wired = true),
    BLOCK(EvGroup.DEFENDING),
    CLEARANCE_HOOFED(EvGroup.DEFENDING, wired = true),
    CLEARANCE_HEADED(EvGroup.DEFENDING, wired = true),
    TACKLE_STANDING(EvGroup.DEFENDING, wired = true),
    TACKLE_SLIDING(EvGroup.DEFENDING, wired = true),
    TACKLE_LAST_DITCH(EvGroup.DEFENDING),
    /** A loose ball picked up. Nobody passed it to him; he went and got it. */
    RECOVERY_RUN(EvGroup.DEFENDING, wired = true),
    /** The team mind committed several men at once. A collective act, not a man's. */
    PRESS_TRIGGERED(EvGroup.DEFENDING, wired = true),
    /** The ball changed hands while that press was live. */
    PRESS_WON(EvGroup.DEFENDING, wired = true),
    GOAL_LINE_CLEARANCE(EvGroup.DEFENDING),
    OFFSIDE_TRAP_SPRUNG(EvGroup.DEFENDING, wired = true),
    OFFSIDE(EvGroup.DEFENDING, wired = true),

    // ------------------------------------------------------------ goalkeeping
    SAVE_ROUTINE(EvGroup.GOALKEEPING, wired = true),
    SAVE_DIVING(EvGroup.GOALKEEPING, wired = true),
    SAVE_TIP_OVER(EvGroup.GOALKEEPING),
    SAVE_PARRY(EvGroup.GOALKEEPING),
    SAVE_PENALTY(EvGroup.GOALKEEPING),
    KEEPER_CLAIM_CROSS(EvGroup.GOALKEEPING, wired = true),
    KEEPER_PUNCH(EvGroup.GOALKEEPING),
    KEEPER_SWEEP(EvGroup.GOALKEEPING),
    KEEPER_DISTRIBUTION(EvGroup.GOALKEEPING),

    // ------------------------------------------------------ fouls, discipline
    FOUL(EvGroup.FOULS, wired = true),
    FOUL_ADVANTAGE(EvGroup.FOULS),
    FOUL_IN_BOX(EvGroup.FOULS, wired = true),
    HANDBALL(EvGroup.FOULS),
    PROFESSIONAL_FOUL(EvGroup.FOULS),
    CARD_YELLOW(EvGroup.FOULS, wired = true),
    CARD_SECOND_YELLOW(EvGroup.FOULS, wired = true),
    CARD_RED(EvGroup.FOULS, wired = true),

    // -------------------------------------------------- set pieces, restarts
    THROW_IN(EvGroup.RESTARTS, wired = true),
    THROW_LONG(EvGroup.RESTARTS),
    GOAL_KICK(EvGroup.RESTARTS, wired = true),
    CORNER_WON(EvGroup.RESTARTS, wired = true),
    CORNER_TAKEN(EvGroup.RESTARTS, wired = true),
    FREE_KICK_DIRECT(EvGroup.RESTARTS, wired = true),
    FREE_KICK_INDIRECT(EvGroup.RESTARTS, wired = true),
    PENALTY_AWARDED(EvGroup.RESTARTS, wired = true),
    PENALTY_SCORED(EvGroup.RESTARTS),
    PENALTY_MISSED(EvGroup.RESTARTS),
    DROP_BALL(EvGroup.RESTARTS),

    // ------------------------------------------------------ the match itself
    KICK_OFF(EvGroup.MATCH, wired = true),
    HALF_TIME(EvGroup.MATCH, wired = true),
    FULL_TIME(EvGroup.MATCH, wired = true),
    SUBSTITUTION(EvGroup.MATCH),
    INJURY_KNOCK(EvGroup.MATCH),
    INJURY_SERIOUS(EvGroup.MATCH);

    companion object {
        val ALL: List<Ev> = entries
        val WIRED: List<Ev> = entries.filter { it.wired }
    }
}

/**
 * Where every event goes.
 *
 * One recorder, and nothing outside it counts anything. That is the whole
 * defence against the predecessor's recurring failure, where a scene was wired
 * directly into one engine's decision path and then died in silence when the
 * caller moved: if the engine reports WHAT HAPPENED to one place, a census can
 * always ask that place what happened.
 */
class EventLog {
    @JvmField val total = IntArray(Ev.ALL.size)
    @JvmField val bySide = Array(2) { IntArray(Ev.ALL.size) }

    fun fire(e: Ev, side: Int) {
        total[e.ordinal]++
        if (side in 0..1) bySide[side][e.ordinal]++
    }

    operator fun get(e: Ev): Int = total[e.ordinal]
    fun side(e: Ev, s: Int): Int = bySide[s][e.ordinal]
}
