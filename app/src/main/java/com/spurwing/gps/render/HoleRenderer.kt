package com.spurwing.gps.render

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.spurwing.gps.Fix
import com.spurwing.gps.course.CourseData
import com.spurwing.gps.course.Distances
import com.spurwing.gps.course.Hole
import com.spurwing.gps.course.Kind
import com.spurwing.gps.course.LatLon
import com.spurwing.gps.course.LocalFrame
import com.spurwing.gps.course.METERS_PER_YARD
import com.spurwing.gps.course.Scorecard
import com.spurwing.gps.course.Xy
import com.spurwing.gps.course.Yardages
import com.spurwing.gps.course.bearingDegrees
import com.spurwing.gps.course.distanceMeters
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Score and auto-switch state shown in the panel. `score` is 0 when not entered. */
class RoundInfo(val score: Int, val totals: Scorecard.Totals, val autoPaused: Boolean)

/** Turns a tap on the map back into a spot on the course. */
class MapProjection(
    val mapRect: RectF,
    private val frame: LocalFrame,
    private val cosB: Double,
    private val sinB: Double,
    private val scale: Double,
    private val midX: Double,
    private val midY: Double,
) {
    fun toScreen(p: LatLon): Pair<Float, Float> {
        val xy = frame.toXy(p)
        val rx = xy.x * cosB - xy.y * sinB
        val ry = xy.x * sinB + xy.y * cosB
        return (mapRect.centerX() + (rx - midX) * scale).toFloat() to
            (mapRect.centerY() - (ry - midY) * scale).toFloat()
    }

    fun toLatLon(sx: Float, sy: Float): LatLon? {
        if (!mapRect.contains(sx, sy)) return null
        val rx = (sx - mapRect.centerX()) / scale + midX
        val ry = (mapRect.centerY() - sy) / scale + midY
        val x = rx * cosB + ry * sinB
        val y = -rx * sinB + ry * cosB
        return frame.toLatLon(Xy(x, y))
    }
}

/**
 * Draws the hole overview: the hole turned so you're always looking from tee to
 * green, with your position, yardage markers and a side panel of front/middle/back
 * numbers. Used for both the cart screen and the phone screen.
 */
class HoleRenderer {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WHITE }
    private val bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    private val regular = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    private val path = Path()
    private val targets = ArrayList<TapTarget>()

    fun draw(
        c: Canvas,
        area: RectF,
        data: CourseData,
        hole: Hole,
        fix: Fix?,
        target: LatLon?,
        round: RoundInfo,
    ): DrawResult {
        targets.clear()
        fill.color = PANEL_BG
        c.drawRect(area, fill)

        val landscape = area.width() >= area.height() * 1.15f
        val u = min(area.width(), area.height()) / 100f
        val panel: RectF
        val map: RectF
        if (landscape) {
            val w = (area.width() * 0.30f).coerceIn(42 * u, 70 * u)
            panel = RectF(area.left, area.top, area.left + w, area.bottom)
            map = RectF(panel.right, area.top, area.right, area.bottom)
        } else {
            val h = 50 * u
            panel = RectF(area.left, area.bottom - h, area.right, area.bottom)
            map = RectF(area.left, area.top, area.right, panel.top)
        }

        val player = fix?.position
        val farAway = player == null || distanceMeters(player, hole.greenCenter) > FAR_AWAY_M
        val projection = drawMap(c, map, u, data, hole, if (farAway) null else player, target)
        val yardages = if (farAway || player == null) null else Distances.toGreen(player, hole)
        if (landscape) drawSidePanel(c, panel, u, hole, yardages, fix, farAway, round)
        else drawBottomPanel(c, panel, u, hole, yardages, fix, farAway, round)
        return DrawResult(targets.toList(), projection)
    }

    // --- Map ------------------------------------------------------------------

    private fun drawMap(
        c: Canvas,
        map: RectF,
        u: Float,
        data: CourseData,
        hole: Hole,
        player: LatLon?,
        target: LatLon?,
    ): MapProjection {
        val mid = LatLon(hole.line.map { it.lat }.average(), hole.line.map { it.lon }.average())
        val frame = LocalFrame(mid)
        val b = Math.toRadians(bearingDegrees(hole.tee, hole.greenCenter))
        val cosB = cos(b)
        val sinB = sin(b)
        fun rot(p: LatLon): Xy {
            val xy = frame.toXy(p)
            return Xy(xy.x * cosB - xy.y * sinB, xy.x * sinB + xy.y * cosB)
        }

        // Framing: the whole hole from the tee, or once you're out on the hole, just the
        // part from you to the green, so the view zooms in as you get closer.
        val keyPoints = ArrayList<Xy>()
        hole.green.forEach { keyPoints.add(rot(it)) }
        keyPoints.add(rot(hole.greenCenter))
        val onHole = player != null && (
            hole.line.zipWithNext().any { (a, b) -> nearSegment(player, a, b, 70.0) } ||
                distanceMeters(player, hole.greenCenter) < 120
            )
        if (onHole && player != null) {
            val me = rot(player)
            keyPoints.add(me)
            // Keep the parts of the hole still ahead of you.
            hole.line.map(::rot).filter { it.y > me.y }.forEach { keyPoints.add(it) }
        } else {
            hole.line.forEach { keyPoints.add(rot(it)) }
            hole.tees.flatten().forEach { keyPoints.add(rot(it)) }
        }
        var minX = keyPoints.minOf { it.x }
        var maxX = keyPoints.maxOf { it.x }
        var minY = keyPoints.minOf { it.y } - 25
        var maxY = keyPoints.maxOf { it.y } + 15
        val minWidth = 90.0
        if (maxX - minX < minWidth) {
            val cx = (minX + maxX) / 2
            minX = cx - minWidth / 2
            maxX = cx + minWidth / 2
        }
        val minHeight = 130.0
        if (maxY - minY < minHeight) {
            // Grow behind you rather than past the green.
            minY = maxY - minHeight
        }
        val pad = 5 * u
        val scale = min(
            (map.width() - 2 * pad) / (maxX - minX),
            (map.height() - 2 * pad) / (maxY - minY),
        )
        val proj = MapProjection(map, frame, cosB, sinB, scale, (minX + maxX) / 2, (minY + maxY) / 2)

        c.save()
        c.clipRect(map)
        fill.color = ROUGH
        c.drawRect(map, fill)

        // Course features, back to front.
        val order = listOf(Kind.DRIVING_RANGE, Kind.FAIRWAY, Kind.WATER, Kind.TEE, Kind.BUNKER, Kind.GREEN)
        stroke.strokeWidth = 0f // no outline unless a kind asks for one
        for (kind in order) {
            for (f in data.features) {
                if (f.kind != kind || f.inner || f.points.size < 3) continue
                buildPath(f.points, proj)
                when (kind) {
                    Kind.DRIVING_RANGE -> paintFill(RANGE)
                    Kind.FAIRWAY -> paintFill(FAIRWAY)
                    Kind.WATER -> { paintFill(WATER); paintStroke(WATER_EDGE, 0.35f * u) }
                    Kind.TEE -> { paintFill(TEE); paintStroke(TEE_EDGE, 0.25f * u) }
                    Kind.BUNKER -> paintFill(SAND)
                    Kind.GREEN -> { paintFill(GREEN); paintStroke(GREEN_EDGE, 0.3f * u) }
                    else -> {}
                }
                c.drawPath(path, fill)
                if (stroke.strokeWidth > 0f) c.drawPath(path, stroke)
                stroke.strokeWidth = 0f
            }
        }

        // Tee-to-green line.
        path.reset()
        (hole.line + hole.greenCenter).forEachIndexed { i, p ->
            val (x, y) = proj.toScreen(p)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        stroke.color = LINE
        stroke.strokeWidth = 0.45f * u
        stroke.pathEffect = DashPathEffect(floatArrayOf(1.8f * u, 1.4f * u), 0f)
        c.drawPath(path, stroke)
        stroke.pathEffect = null

        drawYardageMarkers(c, proj, u, hole)

        // Flag at the middle of the green.
        val (fx, fy) = proj.toScreen(hole.greenCenter)
        fill.color = WHITE
        c.drawCircle(fx, fy, 0.9f * u, fill)
        stroke.color = WHITE
        stroke.strokeWidth = 0.4f * u
        c.drawLine(fx, fy, fx, fy - 5 * u, stroke)
        path.reset()
        path.moveTo(fx, fy - 5 * u)
        path.lineTo(fx + 3.2f * u, fy - 4 * u)
        path.lineTo(fx, fy - 3 * u)
        path.close()
        fill.color = FLAG
        c.drawPath(path, fill)

        if (target != null) drawTarget(c, proj, u, hole, player, target)

        if (player != null) {
            val (px, py) = proj.toScreen(player)
            fill.color = PLAYER_HALO
            c.drawCircle(px, py, 3.2f * u, fill)
            fill.color = WHITE
            c.drawCircle(px, py, 1.9f * u, fill)
            fill.color = PLAYER
            c.drawCircle(px, py, 1.4f * u, fill)
        }

        c.restore()
        return proj
    }

    /** Dots on the line at 100/150/200/250 yards from the middle of the green. */
    private fun drawYardageMarkers(c: Canvas, proj: MapProjection, u: Float, hole: Hole) {
        val pts = hole.line + hole.greenCenter
        text.typeface = bold
        text.textSize = 2.8f * u
        text.textAlign = Paint.Align.LEFT
        for (yards in intArrayOf(100, 150, 200, 250)) {
            val r = yards * METERS_PER_YARD
            val p = pointAtDistanceFromGreen(pts, hole.greenCenter, r) ?: continue
            val (x, y) = proj.toScreen(p)
            fill.color = when (yards) { 100 -> MARK_100; 150 -> MARK_150; 200 -> MARK_200; else -> MARK_250 }
            c.drawCircle(x, y, 1.2f * u, fill)
            stroke.color = MARK_EDGE
            stroke.strokeWidth = 0.25f * u
            c.drawCircle(x, y, 1.2f * u, stroke)
            text.typeface = bold
            text.textSize = 2.8f * u
            val w = text.measureText(yards.toString())
            drawLabel(c, yards.toString(), x + 2.6f * u + w / 2, y + 1f * u, u, small = true)
        }
    }

    private fun nearSegment(p: LatLon, a: LatLon, b: LatLon, meters: Double): Boolean {
        val f = LocalFrame(a)
        return com.spurwing.gps.course.Geometry.segmentDistance(f.toXy(p), Xy(0.0, 0.0), f.toXy(b)).first < meters
    }

    /** Shrinks the text size so `s` fits within maxWidth. */
    private fun fitText(s: String, size: Float, maxWidth: Float) {
        text.textSize = size
        val w = text.measureText(s)
        if (w > maxWidth) text.textSize = size * maxWidth / w
    }

    /** Walks from the tee toward the green; returns the first point within r of the green. */
    private fun pointAtDistanceFromGreen(pts: List<LatLon>, green: LatLon, r: Double): LatLon? {
        val frame = LocalFrame(green)
        val xy = pts.map(frame::toXy)
        if (hypot(xy[0].x, xy[0].y) <= r) return null // hole is shorter than this
        for (i in 0 until xy.size - 1) {
            val a = xy[i]
            val b = xy[i + 1]
            val da = hypot(a.x, a.y)
            val db = hypot(b.x, b.y)
            if (da > r && db <= r) {
                // Binary search along the segment for the crossing.
                var lo = 0.0
                var hi = 1.0
                repeat(30) {
                    val m = (lo + hi) / 2
                    val d = hypot(a.x + (b.x - a.x) * m, a.y + (b.y - a.y) * m)
                    if (d > r) lo = m else hi = m
                }
                return frame.toLatLon(Xy(a.x + (b.x - a.x) * hi, a.y + (b.y - a.y) * hi))
            }
        }
        return null
    }

    private fun drawTarget(c: Canvas, proj: MapProjection, u: Float, hole: Hole, player: LatLon?, target: LatLon) {
        val (tx, ty) = proj.toScreen(target)
        val (gx, gy) = proj.toScreen(hole.greenCenter)
        stroke.color = TARGET
        stroke.strokeWidth = 0.5f * u
        if (player != null) {
            val (px, py) = proj.toScreen(player)
            c.drawLine(px, py, tx, ty, stroke)
            drawLabel(c, "${Distances.yards(player, target)}", (px + tx) / 2, (py + ty) / 2, u, small = false)
        }
        c.drawLine(tx, ty, gx, gy, stroke)
        drawLabel(c, "${Distances.yards(target, hole.greenCenter)}", (tx + gx) / 2, (ty + gy) / 2, u, small = false)
        fill.color = TARGET
        c.drawCircle(tx, ty, 1.5f * u, fill)
        stroke.color = PANEL_BG
        stroke.strokeWidth = 0.3f * u
        c.drawCircle(tx, ty, 1.5f * u, stroke)
    }

    /** White text on a dark pill so it reads over any background. */
    private fun drawLabel(c: Canvas, s: String, cx: Float, cy: Float, u: Float, small: Boolean) {
        text.typeface = bold
        text.textSize = (if (small) 2.8f else 4.2f) * u
        text.textAlign = Paint.Align.CENTER
        val w = text.measureText(s)
        val h = text.textSize
        val padX = 0.9f * u
        val box = RectF(cx - w / 2 - padX, cy - h * 0.75f, cx + w / 2 + padX, cy + h * 0.35f)
        fill.color = LABEL_BG
        c.drawRoundRect(box, h * 0.3f, h * 0.3f, fill)
        text.color = WHITE
        c.drawText(s, cx, cy, text)
    }

    private fun buildPath(points: List<LatLon>, proj: MapProjection) {
        path.reset()
        points.forEachIndexed { i, p ->
            val (x, y) = proj.toScreen(p)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
    }

    private fun paintFill(color: Int) { fill.color = color }
    private fun paintStroke(color: Int, width: Float) { stroke.color = color; stroke.strokeWidth = width }

    // --- Panels ---------------------------------------------------------------

    private fun drawSidePanel(c: Canvas, p: RectF, u: Float, hole: Hole, y: Yardages?, fix: Fix?, farAway: Boolean, round: RoundInfo) {
        val left = p.left + 5 * u
        val right = p.right - 5 * u
        fun at(k: Float) = p.top + k * u

        text.textAlign = Paint.Align.LEFT
        text.color = MUTED
        text.typeface = bold
        text.textSize = 3.4f * u
        c.drawText(hole.course.label.uppercase(), left, at(7f), text)

        text.color = WHITE
        fitText("Hole 18", 10f * u, right - left)
        c.drawText("Hole ${hole.number}", left, at(18.5f), text)

        text.color = MUTED
        text.typeface = regular
        fitText("Par 5  ·  627 yds", 4.2f * u, right - left)
        c.drawText("Par ${hole.par}  ·  ${hole.lengthYards} yds", left, at(24.5f), text)

        // Back / middle / front, stacked so it reads like looking at the green.
        drawRow(c, "BACK", y?.back, left, right, at(39f), 9f * u, u, big = false)
        drawRow(c, "MIDDLE", y?.middle, left, right, at(58f), 18f * u, u, big = true)
        drawRow(c, "FRONT", if (y?.onGreen == true) null else y?.front, left, right, at(70f), 9f * u, u, big = false)

        // Score for this hole.
        divider(c, left, right, at(74f), u)
        text.textAlign = Paint.Align.LEFT
        text.typeface = bold
        text.textSize = 3.4f * u
        text.color = MUTED
        c.drawText("SCORE", left, at(79f), text)
        text.textAlign = Paint.Align.RIGHT
        text.typeface = regular
        text.color = SOFT
        fitText(totalsShort(round.totals), 3.4f * u, (right - left) * 0.65f)
        c.drawText(totalsShort(round.totals), right, at(79f), text)
        drawScoreControl(c, RectF(left, at(81f), right, at(92f)), u, round.score, hole.par)

        text.textAlign = Paint.Align.LEFT
        text.typeface = regular
        text.color = MUTED
        fitText(statusText(fix, farAway, y, round), 3.3f * u, right - left)
        c.drawText(statusText(fix, farAway, y, round), left, at(97f), text)
    }

    private fun drawRow(
        c: Canvas, label: String, value: Int?, left: Float, right: Float, baseline: Float,
        size: Float, u: Float, big: Boolean,
    ) {
        text.textAlign = Paint.Align.LEFT
        text.typeface = bold
        text.textSize = 3.4f * u
        text.color = MUTED
        c.drawText(label, left, baseline - size * 0.72f - 1.2f * u, text)
        text.textAlign = Paint.Align.RIGHT
        text.typeface = bold
        // Size to fit three digits so the numbers don't jump around as they change.
        fitText("888", size, right - left)
        text.color = if (big) WHITE else SOFT
        c.drawText(value?.toString() ?: "—", right, baseline, text)
    }

    private fun drawBottomPanel(c: Canvas, p: RectF, u: Float, hole: Hole, y: Yardages?, fix: Fix?, farAway: Boolean, round: RoundInfo) {
        val left = p.left + 4 * u
        val right = p.right - 4 * u
        fun at(k: Float) = p.top + k * u
        val colW = (right - left) / 3f

        val cols = listOf(
            Triple("FRONT", if (y?.onGreen == true) null else y?.front, false),
            Triple("MIDDLE", y?.middle, true),
            Triple("BACK", y?.back, false),
        )
        cols.forEachIndexed { i, (label, value, big) ->
            val cx = left + colW * (i + 0.5f)
            text.textAlign = Paint.Align.CENTER
            text.typeface = bold
            text.color = MUTED
            text.textSize = 3.2f * u
            c.drawText(label, cx, at(6f), text)
            text.color = if (big) WHITE else SOFT
            fitText("888", if (big) 12f * u else 8.5f * u, colW * 0.9f)
            c.drawText(value?.toString() ?: "—", cx, at(19f), text)
        }

        divider(c, left, right, at(23f), u)
        text.textAlign = Paint.Align.LEFT
        text.typeface = bold
        text.color = WHITE
        text.textSize = 6.5f * u
        c.drawText("Hole ${hole.number}", left, at(32f), text)
        text.typeface = regular
        text.color = MUTED
        text.textSize = 3.4f * u
        c.drawText("Par ${hole.par} · ${hole.lengthYards} yds", left, at(37.5f), text)

        val ctrl = RectF(right - 44 * u, at(26f), right, at(36f))
        drawScoreControl(c, ctrl, u, round.score, hole.par)
        text.textAlign = Paint.Align.CENTER
        text.typeface = regular
        text.color = SOFT
        text.textSize = 3.2f * u
        c.drawText(totalsShort(round.totals), ctrl.centerX(), at(40.5f), text)

        text.textAlign = Paint.Align.LEFT
        text.typeface = regular
        text.color = MUTED
        fitText(statusText(fix, farAway, y, round), 3.2f * u, right - left)
        c.drawText(statusText(fix, farAway, y, round), left, at(47f), text)
    }

    /** [ − ]  score  [ + ]  in the given box. Tapping the score opens the full card. */
    private fun drawScoreControl(c: Canvas, box: RectF, u: Float, score: Int, par: Int) {
        val bw = box.height() * 1.15f
        val minus = RectF(box.left, box.top, box.left + bw, box.bottom)
        val plus = RectF(box.right - bw, box.top, box.right, box.bottom)
        val middle = RectF(minus.right, box.top, plus.left, box.bottom)
        drawButton(c, minus, "−", u)
        drawButton(c, plus, "+", u)
        targets.add(TapTarget(minus, TapAction.ScoreMinus))
        targets.add(TapTarget(plus, TapAction.ScorePlus))
        targets.add(TapTarget(middle, TapAction.OpenCard))

        text.textAlign = Paint.Align.CENTER
        text.typeface = bold
        text.textSize = box.height() * 0.78f
        text.color = when {
            score == 0 -> MUTED
            score < par -> UNDER
            score > par -> OVER
            else -> WHITE
        }
        c.drawText(if (score == 0) "—" else score.toString(), middle.centerX(), box.bottom - box.height() * 0.2f, text)
    }

    private fun drawButton(c: Canvas, r: RectF, label: String, u: Float) {
        fill.color = BUTTON
        c.drawRoundRect(r, 1.6f * u, 1.6f * u, fill)
        stroke.color = BUTTON_EDGE
        stroke.strokeWidth = 0.25f * u
        c.drawRoundRect(r, 1.6f * u, 1.6f * u, stroke)
        text.textAlign = Paint.Align.CENTER
        text.typeface = bold
        text.color = WHITE
        text.textSize = r.height() * 0.7f
        c.drawText(label, r.centerX(), r.centerY() + text.textSize * 0.36f, text)
    }

    private fun divider(c: Canvas, left: Float, right: Float, y: Float, u: Float) {
        stroke.color = DIVIDER
        stroke.strokeWidth = 0.2f * u
        c.drawLine(left, y, right, y, stroke)
    }

    private fun totalsShort(t: Scorecard.Totals): String =
        if (t.holesPlayed == 0) "No scores yet"
        else "${t.strokes} (${Scorecard.formatToPar(t.toPar)}) thru ${t.holesPlayed}"

    private fun statusText(fix: Fix?, farAway: Boolean, y: Yardages?, round: RoundInfo): String {
        val gps = when {
            fix == null -> "Waiting for GPS…"
            farAway -> "Not on the course"
            y?.onGreen == true -> "On the green · GPS ±${accuracyYards(fix)} yd"
            else -> "GPS ±${accuracyYards(fix)} yd"
        }
        return if (round.autoPaused) "$gps · Auto hole off" else gps
    }

    private fun accuracyYards(fix: Fix) = max(1, (fix.accuracyMeters / METERS_PER_YARD).toInt())

    companion object {
        private const val FAR_AWAY_M = 900.0

        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val SOFT = 0xFFD7E4D9.toInt()
        private const val MUTED = 0xFF8FA896.toInt()
        private const val PANEL_BG = 0xFF14221A.toInt()
        private const val ROUGH = 0xFF2C5532.toInt()
        private const val RANGE = 0xFF356A3B.toInt()
        private const val FAIRWAY = 0xFF4E9A4B.toInt()
        private const val GREEN = 0xFF7DD47A.toInt()
        private const val GREEN_EDGE = 0xFFB4F0AE.toInt()
        private const val TEE = 0xFF5DB35A.toInt()
        private const val TEE_EDGE = 0xAAFFFFFF.toInt()
        private const val SAND = 0xFFE9DBAE.toInt()
        private const val WATER = 0xFF3A7FC2.toInt()
        private const val WATER_EDGE = 0xFF6FA6DB.toInt()
        private const val LINE = 0xCCFFFFFF.toInt()
        private const val FLAG = 0xFFF2C14E.toInt()
        private const val PLAYER = 0xFF2F80ED.toInt()
        private const val PLAYER_HALO = 0x552F80ED
        private const val TARGET = 0xFFFFD84D.toInt()
        private const val LABEL_BG = 0xCC0E1712.toInt()
        private const val MARK_100 = 0xFFE5484D.toInt()
        private const val MARK_150 = 0xFFFFFFFF.toInt()
        private const val MARK_200 = 0xFF3B82F6.toInt()
        private const val MARK_250 = 0xFFF2C14E.toInt()
        private const val MARK_EDGE = 0xFF14221A.toInt()
        private const val BUTTON = 0xFF24382B.toInt()
        private const val BUTTON_EDGE = 0xFF3D5A45.toInt()
        private const val DIVIDER = 0xFF2A3F31.toInt()
        private const val UNDER = 0xFFFF7A7A.toInt()
        private const val OVER = 0xFF8FC1FF.toInt()
    }
}
