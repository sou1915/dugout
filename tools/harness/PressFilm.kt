package harness

import com.dugout.career.sim.MatchSim
import com.dugout.career.ui.DebugRenderer
import java.io.File
import javax.imageio.ImageIO

/**
 * WATCH ONE PRESS.
 *
 * §1 of the brief: a tactic must be visible on the pitch, or it is not
 * implemented. Every other collective number in TeamCheck could be produced by
 * a bug that happens to move a counter. Three men leaving their stations at the
 * same instant and converging on the ball cannot — you either see it in five
 * consecutive frames or you do not.
 *
 * Frames are written from the tick the trigger fires, half a second apart, with
 * the committed men RINGED so the picture says which movement is the play and
 * which is the shape doing what it always does.
 *
 *   java -cp build/gate.jar harness.PressFilmKt [outDir] [seed]
 */
fun main(args: Array<String>) {
    val dir = File(if (args.isNotEmpty()) args[0] else "build/film")
    dir.mkdirs()
    val seed = if (args.size > 1) args[1].toLong() else 1000L

    val sim = MatchSim(seed)
    var wroteAt = -100f
    var frame = 0
    val EVERY = 0.5f
    val FRAMES = 5

    println("pressfilm: seed $seed -> ${dir.path}")
    sim.play { _, _ ->
        val pressing = sim.teams.firstOrNull { it.pressLive }
        if (pressing != null && frame == 0 && sim.clock - wroteAt > 20f) {
            // A new press, well clear of the last one we filmed.
            wroteAt = sim.clock
            frame = 1
        }
        if (frame in 1..FRAMES && sim.clock >= wroteAt + (frame - 1) * EVERY) {
            val live = sim.teams.flatMap { it.pressers }
            val mm = (sim.clock / 60).toInt()
            val ss = (sim.clock % 60).toInt()
            val cap = String.format(
                "PRESS  seed %d  %02d:%02d  +%.1fs   committed %d   ball %.1f,%.1f",
                seed, mm, ss, (frame - 1) * EVERY, live.size, sim.ball.x, sim.ball.y
            )
            val bmp = DebugRenderer.render(sim.men, sim.ball, caption = cap, committed = live)
            val f = File(dir, String.format("press%d.png", frame))
            ImageIO.write(bmp.image, "png", f)
            println("  wrote ${f.name}  $cap")
            frame++
            if (frame > FRAMES) frame = 0
        }
    }
    if (wroteAt < 0f) {
        println("  NO PRESS EVER FIRED in this match. That is a dead collective act.")
        System.exit(1)
    }
}
