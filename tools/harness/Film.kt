package harness

import com.dugout.career.sim.MatchSim
import com.dugout.career.ui.DebugRenderer
import java.io.File
import javax.imageio.ImageIO

/**
 * Render frames and look at them.
 *
 * Two of the predecessor's largest defects were invisible to every aggregate
 * and obvious in one picture, so this exists from step 2 rather than from step
 * 10. It writes PNGs; a number is not a substitute for one.
 *
 *   java -cp build/gate.jar harness.FilmKt [outDir] [seed]
 */
fun main(args: Array<String>) {
    val dir = File(if (args.isNotEmpty()) args[0] else "build/film")
    dir.mkdirs()
    val seed = if (args.size > 1) args[1].toLong() else 1000L

    val sim = MatchSim(seed)
    val at = intArrayOf(2, 20, 45, 120, 400, 1200, 2700, 4500)
    var next = 0

    val snap = com.dugout.career.sim.ShapeSnapshot()
    println("film: seed $seed -> ${dir.path}")
    sim.play { _, _ ->
        if (next < at.size && sim.clock >= at[next]) {
            sim.sample(snap)
            val mm = (sim.clock / 60).toInt()
            val ss = (sim.clock % 60).toInt()
            val cap = String.format(
                "seed %d   %02d:%02d   depth home %.1fm away %.1fm   ball %.1f,%.1f h=%.2f",
                seed, mm, ss, snap.blockDepth[0], snap.blockDepth[1],
                sim.ball.x, sim.ball.y, sim.ball.height
            )
            val bmp = DebugRenderer.render(sim.men, sim.ball, caption = cap)
            val f = File(dir, String.format("t%04d.png", at[next]))
            ImageIO.write(bmp.image, "png", f)
            println("  wrote ${f.name}  $cap")
            next++
        }
    }
    println("film: ${at.size} frames")
}
