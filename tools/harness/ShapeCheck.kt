package harness

import com.dugout.career.sim.Formation
import com.dugout.career.sim.MatchSim
import com.dugout.career.sim.Pitch
import com.dugout.career.sim.ShapeSnapshot
import kotlin.math.sqrt

/**
 * The first measurement with a control row.
 *
 * It answers the only question step 2 can be asked: do eleven men hold a
 * shape, does that shape move when a setting says it should, and do they
 * actually ARRIVE where they were sent? A setting that cannot be seen in a
 * number here is not implemented, whatever the code says.
 *
 *   java -cp build/gate.jar harness.ShapeCheckKt [matches]
 */

private class Acc {
    private var sum = 0.0
    private var sumSq = 0.0
    private var n = 0
    fun add(v: Double) { sum += v; sumSq += v * v; n++ }
    val mean: Double get() = if (n == 0) 0.0 else sum / n
    /** Standard error of the mean — a difference smaller than this is nothing. */
    val se: Double get() {
        if (n < 2) return 0.0
        val varr = (sumSq - sum * sum / n) / (n - 1)
        return sqrt(varr / n)
    }
}

private class Row(val label: String) {
    val depth = Acc()
    val meanX = Acc()
    val gap = Acc()
    val arrive = Acc()
    val band = Array(Pitch.BANDS) { Acc() }
    val lane = Array(Pitch.LANES) { Acc() }
}

private fun run(label: String, lineHeight: Float, width: Float, matches: Int): Row {
    val row = Row(label)
    val base = Formation.preset("4-3-3")
    val shape = Formation(
        base.name, base.slots,
        base.block.copy(lineHeight = lineHeight, widthOutPoss = width)
    )
    val snap = ShapeSnapshot()

    for (i in 0 until matches) {
        val sim = MatchSim(1000L + i, homeShape = shape)
        var depth = 0.0; var mx = 0.0; var gap = 0.0; var n = 0
        val bandC = DoubleArray(Pitch.BANDS)
        val laneC = DoubleArray(Pitch.LANES)

        sim.play { _, _ ->
            sim.sample(snap)
            depth += snap.blockDepth[0]
            for (j in 0 until Pitch.LANES) for (b in 0 until Pitch.BANDS) {
                val v = snap.occupancy[0][j * Pitch.BANDS + b]
                bandC[b] += v
                laneC[j] += v
            }
            var sx = 0.0
            var sg = 0.0
            var men = 0
            for (m in sim.men) {
                if (m.side != 0 || m.isKeeper) continue
                sx += Pitch.attX(0, m.x)
                sg += com.dugout.career.sim.Physics.dist(m.x, m.y, m.targetX, m.targetY)
                men++
            }
            mx += sx / men
            gap += sg / men
            n++
        }

        row.depth.add(depth / n)
        row.meanX.add(mx / n)
        row.gap.add(gap / n)
        if (sim.arriveGapCount > 0)
            row.arrive.add(sim.arriveGapSum / sim.arriveGapCount)
        for (b in 0 until Pitch.BANDS) row.band[b].add(bandC[b] / n)
        for (j in 0 until Pitch.LANES) row.lane[j].add(laneC[j] / n)
    }
    return row
}

private fun header() {
    println(String.format("%-22s %14s %14s %12s %14s",
        "row", "block depth m", "mean own-x m", "gap to tgt m", "gap on arrival"))
    println("-".repeat(82))
}

private fun print(r: Row) {
    println(String.format(
        "%-22s %8.2f±%-5.2f %8.2f±%-5.2f %7.2f±%-4.2f %9.2f±%-4.2f",
        r.label, r.depth.mean, r.depth.se, r.meanX.mean, r.meanX.se,
        r.gap.mean, r.gap.se, r.arrive.mean, r.arrive.se
    ))
}

fun main(args: Array<String>) {
    val matches = if (args.isNotEmpty()) args[0].toInt() else 12

    println("ShapeCheck — home side only, $matches matches per row, 4-3-3")
    println("Away side is identical in every row, so it is the within-run control.")
    println()
    header()

    val control = run("control (line 36m)", 36f, 34f, matches)
    val low = run("line 26m", 26f, 34f, matches)
    val high = run("line 46m", 46f, 34f, matches)
    val wide = run("width 50m", 36f, 50f, matches)

    print(control); print(low); print(high); print(wide)

    println()
    println("men per band, home attacking frame (b0 own box .. b5 their box)")
    println(String.format("%-22s %6s %6s %6s %6s %6s %6s", "row", "b0", "b1", "b2", "b3", "b4", "b5"))
    for (r in listOf(control, low, high, wide)) {
        val sb = StringBuilder(String.format("%-22s", r.label))
        for (b in 0 until Pitch.BANDS) sb.append(String.format(" %6.2f", r.band[b].mean))
        println(sb)
    }

    println()
    println("men per lane (lw, lhs, c, rhs, rw)")
    for (r in listOf(control, wide)) {
        val sb = StringBuilder(String.format("%-22s", r.label))
        for (j in 0 until Pitch.LANES) sb.append(String.format(" %6.2f", r.lane[j].mean))
        println(sb)
    }

    println()
    val dHigh = high.meanX.mean - control.meanX.mean
    val dLow = low.meanX.mean - control.meanX.mean
    val noise = 2 * sqrt(control.meanX.se * control.meanX.se + high.meanX.se * high.meanX.se)
    println(String.format("line 46 vs control: mean own-x %+.2f m   (2 SE = %.2f)", dHigh, noise))
    println(String.format("line 26 vs control: mean own-x %+.2f m", dLow))
    println(String.format("width 50 vs control: lane spread %+.2f",
        (wide.lane[0].mean + wide.lane[4].mean) - (control.lane[0].mean + control.lane[4].mean)))
    println()
    println(if (dHigh > noise && dLow < -noise)
        "VERDICT: line height moves the block, and by more than the noise."
    else
        "VERDICT: line height did NOT move the block beyond noise — the lever is dead.")
}
