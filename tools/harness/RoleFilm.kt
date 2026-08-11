package harness

import com.dugout.career.sim.Formation
import com.dugout.career.sim.MatchSim
import com.dugout.career.sim.Pitch
import com.dugout.career.sim.RoleId
import com.dugout.career.ui.DebugRenderer
import java.io.File
import javax.imageio.ImageIO

/**
 * THE TWO FRAMES.
 *
 * §3 of the brief: every setting ships with a harness AND two rendered frames.
 * This is the second half of that for roles — the same fixture, the same seed,
 * the same clock second, one role changed, rendered twice.
 *
 * A separation matrix can be argued with. Two pictures of the same moment
 * cannot, and the man under test is ringed in both so nobody has to hunt.
 *
 *   java -cp build/gate.jar harness.RoleFilmKt [outDir]
 */
private const val SLOT = 7

private fun frame(role: RoleId, at: Float, seed: Long, dir: File) {
    val shape = Formation.withRole(Formation.preset("4-3-3"), SLOT, role)
    val sim = MatchSim(seed, homeShape = shape)
    val man = sim.men.first { it.side == 0 && it.slot.id == SLOT }
    var written = false
    sim.play { _, _ ->
        if (!written && sim.clock >= at) {
            written = true
            val cap = String.format(
                "slot %d = %s   %02d:%02d   his own-x %.1f m, lane y %.1f m   options %d",
                SLOT, role.name, (sim.clock / 60).toInt(), (sim.clock % 60).toInt(),
                Pitch.attX(0, man.x), Pitch.attY(0, man.y), sim.optionsFor(man)
            )
            val bmp = DebugRenderer.render(sim.men, sim.ball, caption = cap)
            val f = File(dir, "role_${role.name}_t${at.toInt()}.png")
            ImageIO.write(bmp.image, "png", f)
            println("  wrote ${f.name}")
            println("    $cap")
        }
    }
}

fun main(args: Array<String>) {
    val dir = File(if (args.isNotEmpty()) args[0] else "build/film")
    dir.mkdirs()

    // Two pairs, chosen because they are the pairs a manager would expect to
    // look different and the engine has no branch that knows their names.
    val pairs = listOf(
        RoleId.INVERTED_FB to RoleId.OVERLAPPING_FB,
        RoleId.POACHER to RoleId.BOX_TO_BOX
    )

    println("RoleFilm — same seed, same second, one role changed")
    for ((a, b) in pairs) {
        println("${a.name} vs ${b.name}:")
        frame(a, 600f, 1000L, dir)
        frame(b, 600f, 1000L, dir)
    }
}
