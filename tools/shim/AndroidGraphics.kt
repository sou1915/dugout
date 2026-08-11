@file:Suppress("unused", "UNUSED_PARAMETER")

package android.graphics

import java.awt.BasicStroke
import java.awt.Color as AwtColor
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage

/**
 * A deliberately small stand-in for the Android graphics classes the match
 * renderer touches, implemented over Java2D. It exists so MatchView.kt can be
 * compiled and run on a desktop JVM and the frames it produces measured with a
 * script. Nothing here ships in the APK.
 */

class Typeface private constructor(val awt: Font) {
    companion object {
        const val NORMAL = 0
        const val BOLD = 1
        const val ITALIC = 2
        const val BOLD_ITALIC = 3
        val SANS_SERIF = Typeface(Font(Font.SANS_SERIF, Font.PLAIN, 12))
        val SERIF = Typeface(Font(Font.SERIF, Font.PLAIN, 12))
        val DEFAULT = Typeface(Font(Font.SANS_SERIF, Font.PLAIN, 12))
        val DEFAULT_BOLD = Typeface(Font(Font.SANS_SERIF, Font.BOLD, 12))
        val MONOSPACE = Typeface(Font(Font.MONOSPACED, Font.PLAIN, 12))
        fun create(name: String, style: Int): Typeface =
            Typeface(Font(Font.SANS_SERIF, if (style == BOLD) Font.BOLD else Font.PLAIN, 12))
        fun create(base: Typeface?, style: Int): Typeface =
            Typeface(Font(Font.SANS_SERIF, if (style == BOLD) Font.BOLD else Font.PLAIN, 12))
    }
}

object Color {
    const val BLACK = -0x1000000
    const val WHITE = -0x1
    const val TRANSPARENT = 0

    fun argb(a: Int, r: Int, g: Int, b: Int) =
        (a shl 24) or (r shl 16) or (g shl 8) or b

    fun rgb(r: Int, g: Int, b: Int) = argb(255, r, g, b)

    fun alpha(c: Int) = (c ushr 24) and 0xFF
    fun red(c: Int) = (c shr 16) and 0xFF
    fun green(c: Int) = (c shr 8) and 0xFF
    fun blue(c: Int) = c and 0xFF
}

class Rect(var left: Int = 0, var top: Int = 0, var right: Int = 0, var bottom: Int = 0) {
    fun set(l: Int, t: Int, r: Int, b: Int) { left = l; top = t; right = r; bottom = b }
    fun width() = right - left
    fun height() = bottom - top
}

class RectF(
    @JvmField var left: Float = 0f,
    @JvmField var top: Float = 0f,
    @JvmField var right: Float = 0f,
    @JvmField var bottom: Float = 0f
) {
    fun set(l: Float, t: Float, r: Float, b: Float) { left = l; top = t; right = r; bottom = b }
    fun width() = right - left
    fun height() = bottom - top
    fun centerX() = (left + right) / 2f
    fun centerY() = (top + bottom) / 2f
}

class Paint(flags: Int = 0) {
    companion object {
        const val ANTI_ALIAS_FLAG = 1
        const val FILTER_BITMAP_FLAG = 2
        const val DITHER_FLAG = 4
    }

    enum class Style { FILL, STROKE, FILL_AND_STROKE }
    enum class Cap { BUTT, ROUND, SQUARE }
    enum class Join { MITER, ROUND, BEVEL }
    enum class Align { LEFT, CENTER, RIGHT }

    var style: Style = Style.FILL
    var strokeCap: Cap = Cap.BUTT
    var strokeJoin: Join = Join.MITER
    var strokeWidth: Float = 0f
    var color: Int = -0x1000000
    var alpha: Int = 255
    var shader: Any? = null
    var typeface: Typeface? = null
    var textAlign: Align = Align.LEFT
    var textSize: Float = 12f
    var isAntiAlias: Boolean = flags and ANTI_ALIAS_FLAG != 0
    var isFilterBitmap: Boolean = false
    var isDither: Boolean = false
    var isFakeBoldText: Boolean = false

    internal fun awtFont(): Font {
        val base = (typeface ?: Typeface.DEFAULT).awt
        return base.deriveFont(textSize)
    }

    /**
     * Measured with the same font machinery the shim draws with, so widths
     * reported to layout code match what actually lands on the canvas.
     */
    fun measureText(s: String): Float {
        val img = Bitmap.scratch()
        val g = img.createGraphics()
        g.font = awtFont()
        val w = g.fontMetrics.stringWidth(s).toFloat()
        g.dispose()
        return w
    }

    fun measureText(s: String, start: Int, end: Int): Float = measureText(s.substring(start, end))

    fun getTextBounds(s: String, start: Int, end: Int, r: Rect) {
        val w = measureText(s.substring(start, end)).toInt()
        val h = textSize.toInt()
        r.set(0, -h, w, 0)
    }
}

class Bitmap private constructor(val image: BufferedImage) {
    val width: Int get() = image.width
    val height: Int get() = image.height

    enum class Config { ARGB_8888, RGB_565, ALPHA_8 }

    fun recycle() {}
    fun getPixel(x: Int, y: Int): Int = image.getRGB(x, y)

    companion object {
        private val scratchImage = BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB)
        internal fun scratch(): BufferedImage = scratchImage

        fun createBitmap(w: Int, h: Int, config: Config): Bitmap =
            Bitmap(BufferedImage(maxOf(1, w), maxOf(1, h), BufferedImage.TYPE_INT_ARGB))
    }
}

class Path {
    internal val p = Path2D.Float()
    enum class Direction { CW, CCW }

    fun reset() = p.reset()
    fun moveTo(x: Float, y: Float) = p.moveTo(x, y)
    fun lineTo(x: Float, y: Float) = p.lineTo(x, y)
    fun quadTo(x1: Float, y1: Float, x2: Float, y2: Float) = p.quadTo(x1, y1, x2, y2)
    fun close() = p.closePath()

    fun addRoundRect(r: RectF, rx: Float, ry: Float, d: Direction) {
        p.append(RoundRectangle2D.Float(r.left, r.top, r.width(), r.height(), rx * 2, ry * 2), false)
    }

    fun addOval(r: RectF, d: Direction) {
        p.append(Ellipse2D.Float(r.left, r.top, r.width(), r.height()), false)
    }

    fun addCircle(cx: Float, cy: Float, rad: Float, d: Direction) {
        p.append(Ellipse2D.Float(cx - rad, cy - rad, rad * 2, rad * 2), false)
    }

    fun addRect(l: Float, t: Float, r: Float, b: Float, d: Direction) {
        p.append(Rectangle2D.Float(l, t, r - l, b - t), false)
    }
}

class Canvas(bitmap: Bitmap) {

    private val g: Graphics2D = bitmap.image.createGraphics().apply {
        setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        setRenderingHint(
            RenderingHints.KEY_TEXT_ANTIALIASING,
            RenderingHints.VALUE_TEXT_ANTIALIAS_ON
        )
        setRenderingHint(
            RenderingHints.KEY_INTERPOLATION,
            RenderingHints.VALUE_INTERPOLATION_BILINEAR
        )
    }

    private class State(val tx: AffineTransform, val clip: java.awt.Shape?)

    private val stack = ArrayList<State>()

    private fun argb(c: Int) = AwtColor(
        (c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF, (c ushr 24) and 0xFF
    )

    /** Applies the paint's colour and, for stroked paints, its pen. */
    private fun apply(paint: Paint, forceStroke: Boolean = false) {
        g.paint = argb(paint.color)
        if (forceStroke || paint.style == Paint.Style.STROKE) {
            val cap = when (paint.strokeCap) {
                Paint.Cap.ROUND -> BasicStroke.CAP_ROUND
                Paint.Cap.SQUARE -> BasicStroke.CAP_SQUARE
                else -> BasicStroke.CAP_BUTT
            }
            g.stroke = BasicStroke(maxOf(0.01f, paint.strokeWidth), cap, BasicStroke.JOIN_ROUND)
        }
    }

    private fun stroked(paint: Paint) = paint.style == Paint.Style.STROKE

    fun save(): Int {
        stack.add(State(AffineTransform(g.transform), g.clip))
        return stack.size
    }

    fun restore() {
        if (stack.isEmpty()) return
        val s = stack.removeAt(stack.size - 1)
        g.transform = s.tx
        g.clip = s.clip
    }

    fun restoreToCount(count: Int) {
        while (stack.size >= count && stack.isNotEmpty()) restore()
    }

    fun translate(dx: Float, dy: Float) = g.translate(dx.toDouble(), dy.toDouble())
    fun scale(sx: Float, sy: Float) = g.scale(sx.toDouble(), sy.toDouble())

    /** Scale about a pivot, as Android's four argument form does. */
    fun scale(sx: Float, sy: Float, px: Float, py: Float) {
        g.translate(px.toDouble(), py.toDouble())
        g.scale(sx.toDouble(), sy.toDouble())
        g.translate(-px.toDouble(), -py.toDouble())
    }
    fun rotate(deg: Float) = g.rotate(Math.toRadians(deg.toDouble()))

    fun rotate(deg: Float, px: Float, py: Float) {
        g.rotate(Math.toRadians(deg.toDouble()), px.toDouble(), py.toDouble())
    }

    fun clipPath(path: Path) = g.clip(path.p)

    fun clipRect(l: Float, t: Float, r: Float, b: Float) {
        g.clip(Rectangle2D.Float(l, t, r - l, b - t))
    }

    fun drawColor(c: Int) {
        g.paint = argb(c)
        g.fill(Rectangle2D.Float(-1e5f, -1e5f, 2e5f, 2e5f))
    }

    fun drawRect(l: Float, t: Float, r: Float, b: Float, paint: Paint) {
        apply(paint)
        val s = Rectangle2D.Float(minOf(l, r), minOf(t, b), Math.abs(r - l), Math.abs(b - t))
        if (stroked(paint)) g.draw(s) else g.fill(s)
    }

    fun drawRect(r: RectF, paint: Paint) = drawRect(r.left, r.top, r.right, r.bottom, paint)

    fun drawRoundRect(r: RectF, rx: Float, ry: Float, paint: Paint) {
        apply(paint)
        val s = RoundRectangle2D.Float(
            minOf(r.left, r.right), minOf(r.top, r.bottom),
            Math.abs(r.width()), Math.abs(r.height()), rx * 2, ry * 2
        )
        if (stroked(paint)) g.draw(s) else g.fill(s)
    }

    fun drawRoundRect(l: Float, t: Float, r: Float, b: Float,
                      rx: Float, ry: Float, paint: Paint) {
        val tmp = RectF(l, t, r, b)
        drawRoundRect(tmp, rx, ry, paint)
    }

    fun drawOval(l: Float, t: Float, r: Float, b: Float, paint: Paint) {
        drawOval(RectF(l, t, r, b), paint)
    }

    fun drawArc(l: Float, t: Float, r: Float, b: Float, startDeg: Float, sweepDeg: Float,
                useCenter: Boolean, paint: Paint) {
        drawArc(RectF(l, t, r, b), startDeg, sweepDeg, useCenter, paint)
    }

    fun drawOval(r: RectF, paint: Paint) {
        apply(paint)
        val s = Ellipse2D.Float(
            minOf(r.left, r.right), minOf(r.top, r.bottom),
            Math.abs(r.width()), Math.abs(r.height())
        )
        if (stroked(paint)) g.draw(s) else g.fill(s)
    }

    fun drawCircle(cx: Float, cy: Float, rad: Float, paint: Paint) {
        apply(paint)
        val s = Ellipse2D.Float(cx - rad, cy - rad, rad * 2, rad * 2)
        if (stroked(paint)) g.draw(s) else g.fill(s)
    }

    fun drawLine(x1: Float, y1: Float, x2: Float, y2: Float, paint: Paint) {
        apply(paint, forceStroke = true)
        g.draw(Line2D.Float(x1, y1, x2, y2))
    }

    fun drawPath(path: Path, paint: Paint) {
        apply(paint)
        if (stroked(paint)) g.draw(path.p) else g.fill(path.p)
    }

    /**
     * Android measures arc angles clockwise from three o'clock; Java2D measures
     * them anticlockwise, so both the start and the sweep are negated.
     */
    fun drawArc(r: RectF, startDeg: Float, sweepDeg: Float, useCenter: Boolean, paint: Paint) {
        apply(paint, forceStroke = stroked(paint))
        val type = if (useCenter) Arc2D.PIE else Arc2D.OPEN
        val s = Arc2D.Float(
            minOf(r.left, r.right), minOf(r.top, r.bottom),
            Math.abs(r.width()), Math.abs(r.height()),
            -startDeg, -sweepDeg, type
        )
        if (stroked(paint)) g.draw(s) else g.fill(s)
    }

    fun drawText(s: String, x: Float, y: Float, paint: Paint) {
        g.paint = argb(paint.color)
        g.font = paint.awtFont()
        val w = g.fontMetrics.stringWidth(s).toFloat()
        val dx = when (paint.textAlign) {
            Paint.Align.CENTER -> -w / 2f
            Paint.Align.RIGHT -> -w
            else -> 0f
        }
        g.drawString(s, x + dx, y)
    }

    fun drawBitmap(b: Bitmap, src: Rect?, dst: Rect, paint: Paint?) {
        g.drawImage(
            b.image,
            dst.left, dst.top, dst.right, dst.bottom,
            src?.left ?: 0, src?.top ?: 0,
            src?.right ?: b.width, src?.bottom ?: b.height,
            null
        )
    }

    fun drawBitmap(b: Bitmap, x: Float, y: Float, paint: Paint?) {
        g.drawImage(b.image, x.toInt(), y.toInt(), null)
    }
}
