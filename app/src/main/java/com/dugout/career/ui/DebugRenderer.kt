package com.dugout.career.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.dugout.career.sim.Ball
import com.dugout.career.sim.Man
import com.dugout.career.sim.Pitch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * THE DEBUG RENDERER — build-order step 2, and deliberately ugly.
 *
 * Top-down, twenty-two dots, the ball, the lane lines, and an arrow from each
 * man to where he is trying to be. It is not presentation and it is not on the
 * road to being presentation: the broadcast renderer is step 10, on top of
 * football that is already varied.
 *
 * It exists because the two largest defects in the predecessor were invisible
 * to every aggregate and obvious in one picture, and because "presentation
 * last" must not become an argument for building blind. An arrow to a target
 * is the single most useful thing here — a man in the right place for the
 * wrong reason and a man in the right place for the right reason look
 * identical until you draw what he was aiming at.
 */
object DebugRenderer {

    private const val MARGIN = 26f

    fun render(
        men: List<Man>, ball: Ball,
        width: Int = 1050, height: Int = 720,
        caption: String = ""
    ): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        val sx = (width - MARGIN * 2) / Pitch.LENGTH
        val sy = (height - MARGIN * 2) / Pitch.WIDTH
        val s = minOf(sx, sy)
        val ox = (width - Pitch.LENGTH * s) * 0.5f
        val oy = (height - Pitch.WIDTH * s) * 0.5f

        fun px(x: Float) = ox + x * s
        fun py(y: Float) = oy + y * s

        // grass
        p.style = Paint.Style.FILL
        p.color = Color.rgb(16, 22, 18)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
        p.color = Color.rgb(24, 62, 34)
        c.drawRect(px(0f), py(0f), px(Pitch.LENGTH), py(Pitch.WIDTH), p)

        // lane boundaries — the grid the engine actually reads, drawn so a
        // wrong lane assignment is visible rather than inferred
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1f
        p.color = Color.argb(90, 255, 235, 140)
        for (i in 1 until Pitch.LANE_EDGES.size - 1) {
            val y = py(Pitch.LANE_EDGES[i])
            c.drawLine(px(0f), y, px(Pitch.LENGTH), y, p)
        }
        // band boundaries
        p.color = Color.argb(60, 160, 220, 255)
        for (b in 1 until Pitch.BANDS) {
            val x = px(Pitch.LENGTH * b / Pitch.BANDS)
            c.drawLine(x, py(0f), x, py(Pitch.WIDTH), p)
        }

        // markings
        p.color = Color.argb(210, 235, 245, 235)
        p.strokeWidth = 2f
        c.drawRect(px(0f), py(0f), px(Pitch.LENGTH), py(Pitch.WIDTH), p)
        c.drawLine(px(Pitch.LENGTH / 2), py(0f), px(Pitch.LENGTH / 2), py(Pitch.WIDTH), p)
        c.drawCircle(px(Pitch.LENGTH / 2), py(Pitch.WIDTH / 2), Pitch.CENTRE_CIRCLE * s, p)
        val boxTop = (Pitch.WIDTH - Pitch.BOX_WIDTH) * 0.5f
        val boxBot = (Pitch.WIDTH + Pitch.BOX_WIDTH) * 0.5f
        c.drawRect(px(0f), py(boxTop), px(Pitch.BOX_DEPTH), py(boxBot), p)
        c.drawRect(px(Pitch.LENGTH - Pitch.BOX_DEPTH), py(boxTop), px(Pitch.LENGTH), py(boxBot), p)
        val sixTop = (Pitch.WIDTH - Pitch.SIX_WIDTH) * 0.5f
        val sixBot = (Pitch.WIDTH + Pitch.SIX_WIDTH) * 0.5f
        c.drawRect(px(0f), py(sixTop), px(Pitch.SIX_DEPTH), py(sixBot), p)
        c.drawRect(px(Pitch.LENGTH - Pitch.SIX_DEPTH), py(sixTop), px(Pitch.LENGTH), py(sixBot), p)

        // arrows to targets, drawn UNDER the men so a dot is never hidden
        for (m in men) {
            val dx = m.targetX - m.x
            val dy = m.targetY - m.y
            if (dx * dx + dy * dy < 0.36f) continue
            p.color = if (m.side == 0) Color.argb(150, 120, 190, 255) else Color.argb(150, 255, 150, 120)
            p.strokeWidth = 1.6f
            val x1 = px(m.x); val y1 = py(m.y)
            val x2 = px(m.targetX); val y2 = py(m.targetY)
            c.drawLine(x1, y1, x2, y2, p)
            val a = atan2((y2 - y1).toDouble(), (x2 - x1).toDouble()).toFloat()
            val head = 6f
            for (k in intArrayOf(-1, 1)) {
                val b = a + k * 2.6f
                c.drawLine(x2, y2, x2 + cos(b.toDouble()).toFloat() * head,
                    y2 + sin(b.toDouble()).toFloat() * head, p)
            }
            // the target itself, so an unreached target reads as a gap
            p.style = Paint.Style.STROKE
            c.drawCircle(x2, y2, 3f, p)
        }

        // the men
        p.style = Paint.Style.FILL
        for (m in men) {
            p.color = when {
                m.isKeeper && m.side == 0 -> Color.rgb(210, 240, 120)
                m.isKeeper -> Color.rgb(240, 200, 120)
                m.side == 0 -> Color.rgb(90, 160, 255)
                else -> Color.rgb(255, 110, 80)
            }
            c.drawCircle(px(m.x), py(m.y), 6.5f, p)
            p.color = Color.argb(220, 12, 14, 16)
            p.textSize = 9f
            p.textAlign = Paint.Align.CENTER
            c.drawText(m.slot.id.toString(), px(m.x), py(m.y) + 3.2f, p)
        }

        // the ball, with its height shown as a ring so a lofted ball is not a
        // rolling one that happens to be somewhere odd
        p.color = Color.rgb(255, 255, 255)
        c.drawCircle(px(ball.x), py(ball.y), 4.5f, p)
        if (ball.height > 0.15f) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1.4f
            p.color = Color.argb(170, 255, 255, 255)
            c.drawCircle(px(ball.x), py(ball.y), 4.5f + ball.height * 2.2f, p)
            p.style = Paint.Style.FILL
        }

        if (caption.isNotEmpty()) {
            p.color = Color.rgb(230, 235, 240)
            p.textSize = 14f
            p.textAlign = Paint.Align.LEFT
            c.drawText(caption, 8f, 18f, p)
        }
        return bmp
    }
}
