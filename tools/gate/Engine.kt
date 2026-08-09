package gate

/**
 * The contract the engine must satisfy to be gateable, and the registry that
 * says whether one exists yet.
 *
 * This is deliberately the whole coupling between the gate and the football:
 * play a match at a fixed seed, hand back the result record, and write the
 * positional statistics into the digest as you go. Anything the gate needs
 * beyond this is a sign the gate has started to know things about football.
 */
interface Gateable {
    /**
     * Play one match at [seed], deterministically, and return its result.
     *
     * [digest] is fed during the match — the five-minute buckets are the
     * engine's to walk, since only it knows when a minute has passed.
     */
    fun play(seed: Long, digest: ShapeDigest): MatchResult
}

/**
 * Step 2 wired the simulation in. The gate now hashes 200 fixed-seed matches
 * and will refuse to pass until a baseline exists — which the owner records,
 * not the agent.
 */
object EngineRegistry {
    val engine: Gateable? = SimGate

    const val MATCHES = 200
    const val FIRST_SEED = 1_000L
}
