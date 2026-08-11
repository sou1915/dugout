package gate

import com.dugout.career.sim.MatchSim
import com.dugout.career.sim.ShapeSnapshot

/**
 * The adapter between the football and the gate.
 *
 * This is the whole coupling: play a seed, feed the digest, hand back the
 * result. The simulation does not import anything from `gate`, and the gate
 * does not know what a lane is beyond a string in a component name.
 */
object SimGate : Gateable {

    /**
     * Resolved once, not per match — the digest outlives a match, and so
     * should the references into it.
     */
    private var index: ShapeIndex? = null
    private var indexFor: ShapeDigest? = null

    private fun indexOf(d: ShapeDigest): ShapeIndex {
        val cached = index
        if (cached != null && indexFor === d) return cached
        val fresh = ShapeIndex(d)
        index = fresh
        indexFor = d
        return fresh
    }

    override fun play(seed: Long, digest: ShapeDigest): MatchResult {
        val ix = indexOf(digest)
        val sim = MatchSim(seed)

        sim.play { bucket, snap -> feed(ix, bucket, snap) }

        // Score, shots, cards and corners: the same four facts the predecessor
        // hashed, so a number from either project means the same thing. This
        // note used to say they were all zero because step 2 had no events.
        return MatchResult(
            homeGoals = sim.goals[0], awayGoals = sim.goals[1],
            homeShots = sim.shots[0], awayShots = sim.shots[1],
            homeCards = sim.cards[0], awayCards = sim.cards[1],
            homeCorners = sim.corners[0], awayCorners = sim.corners[1]
        )
    }

    private fun feed(ix: ShapeIndex, bucket: Int, snap: ShapeSnapshot) {
        for (side in 0..1) {
            ix.depth[side][bucket].add(snap.blockDepth[side].toDouble())
            // Only when the ball is actually in that side's own box. Adding a
            // zero the rest of the time would average the statistic into
            // meaninglessness and then read as a change when possession moved.
            if (snap.goalSideValid[side])
                ix.goalSide[side][bucket].add(snap.goalSide[side].toDouble())
            val occ = snap.occupancy[side]
            val into = ix.occ[side][bucket]
            for (i in occ.indices) into[i].add(occ[i].toDouble())
        }
    }
}
