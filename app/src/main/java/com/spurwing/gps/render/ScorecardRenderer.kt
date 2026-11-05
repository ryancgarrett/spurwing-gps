package com.spurwing.gps.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.spurwing.gps.course.CourseData
import com.spurwing.gps.course.CourseId
import com.spurwing.gps.course.Scorecard
import kotlin.math.min

/**
 * The full scorecard, drawn over the whole screen: hole / par / score rows for each
 * nine, totals, and an edit strip for the selected hole. Tap a hole to select it.
 */
class ScorecardRenderer {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    private val regular = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    private val targets = ArrayList<TapTarget>()

    fun draw(
        c: Canvas,
        area: RectF,
        data: CourseData,
        card: Scorecard,
        course: CourseId,
        selected: Int,
        confirmingNewRound: Boolean,
    ): DrawResult {
        targets.clear()
        fill.color = BG
        c.drawRect(area, fill)

        val u = min(area.width(), area.height()) / 100f
        val pad = 4 * u
        val left = area.left + pad
        val right = area.right - pad
        val holes = data.holes(course)
        val nines = holes.chunked(9)
        val portrait = area.height() > area.width()
        val rowH = if (portrait) 7f * u else 8.2f * u
        // On the phone, leave room for the buttons along the top.
        var y = area.top + pad + if (portrait) 13f * u else 0f

        // Header: course and running total (side by side, or stacked on a narrow screen).
        val totals = card.totals(course)
        text.textAlign = Paint.Align.LEFT
        text.typeface = bold
        text.color = WHITE
        text.textSize = 5.2f * u
        c.drawText("${course.label} scorecard", left, y + 5f * u, text)
        val totalText = if (totals.holesPlayed == 0) "No scores yet"
        else "${totals.strokes}  (${Scorecard.formatToPar(totals.toPar)})  thru ${totals.holesPlayed}"
        text.color = SOFT
        text.typeface = regular
        text.textSize = 4.2f * u
        if (portrait) {
            text.textAlign = Paint.Align.LEFT
            c.drawText(totalText, left, y + 11f * u, text)
            y += 15f * u
        } else {
            text.textAlign = Paint.Align.RIGHT
            c.drawText(totalText, right, y + 5f * u, text)
            y += 9f * u
        }

        // One table per nine.
        val labelW = 13f * u
        val totalW = 12f * u
        val holeW = (right - left - labelW - totalW) / 9f
        nines.forEachIndexed { i, nine ->
            val first = nine.first().number
            val range = first..nine.last().number
            val sumLabel = when {
                nines.size == 1 -> "TOT"
                i == 0 -> "OUT"
                else -> "IN"
            }
            val sub = card.totals(course, range)
            val parSum = nine.sumOf { it.par }

            // Row backgrounds.
            fill.color = HEADER_ROW
            c.drawRect(RectF(left, y, right, y + rowH), fill)
            fill.color = ROW
            c.drawRect(RectF(left, y + rowH, right, y + 3 * rowH), fill)

            cell(c, "HOLE", left, y, labelW, rowH, MUTED, 0.38f, bold, alignLeft = true, u = u)
            cell(c, "PAR", left, y + rowH, labelW, rowH, MUTED, 0.38f, bold, alignLeft = true, u = u)
            cell(c, "SCORE", left, y + 2 * rowH, labelW, rowH, MUTED, 0.38f, bold, alignLeft = true, u = u)

            nine.forEachIndexed { j, h ->
                val x = left + labelW + j * holeW
                val col = RectF(x, y, x + holeW, y + 3 * rowH)
                if (h.number == selected) {
                    fill.color = SELECTED
                    c.drawRect(col, fill)
                }
                targets.add(TapTarget(col, TapAction.PickCardHole(course, h.number)))
                cell(c, h.number.toString(), x, y, holeW, rowH, WHITE, 0.5f, bold, u = u)
                cell(c, h.par.toString(), x, y + rowH, holeW, rowH, SOFT, 0.5f, regular, u = u)
                val s = card.get(course, h.number)
                drawScoreCell(c, s, h.par, RectF(x, y + 2 * rowH, x + holeW, y + 3 * rowH), u)
            }
            val tx = right - totalW
            cell(c, sumLabel, tx, y, totalW, rowH, MUTED, 0.42f, bold, u = u)
            cell(c, parSum.toString(), tx, y + rowH, totalW, rowH, SOFT, 0.5f, bold, u = u)
            cell(c, if (sub.holesPlayed == 0) "—" else sub.strokes.toString(), tx, y + 2 * rowH, totalW, rowH, WHITE, 0.55f, bold, u = u)

            // Grid lines.
            stroke.color = GRID
            stroke.strokeWidth = 0.15f * u
            for (k in 1..2) c.drawLine(left, y + k * rowH, right, y + k * rowH, stroke)
            c.drawLine(left + labelW, y, left + labelW, y + 3 * rowH, stroke)
            c.drawLine(tx, y, tx, y + 3 * rowH, stroke)
            y += 3 * rowH + 3f * u
        }

        // Edit strip for the selected hole.
        val sel = holes.firstOrNull { it.number == selected } ?: holes.first()
        val stripH = 9f * u
        text.textAlign = Paint.Align.LEFT
        text.typeface = bold
        text.color = WHITE
        text.textSize = 4.6f * u
        c.drawText("Hole ${sel.number} · Par ${sel.par}", left, y + stripH * 0.68f, text)
        val ctrl = RectF(right - 40f * u, y, right, y + stripH)
        val bw = stripH * 1.15f
        val minus = RectF(ctrl.left, ctrl.top, ctrl.left + bw, ctrl.bottom)
        val plus = RectF(ctrl.right - bw, ctrl.top, ctrl.right, ctrl.bottom)
        button(c, minus, "−", u, stripH * 0.7f)
        button(c, plus, "+", u, stripH * 0.7f)
        targets.add(TapTarget(minus, TapAction.ScoreMinus))
        targets.add(TapTarget(plus, TapAction.ScorePlus))
        val s = card.get(course, sel.number)
        text.textAlign = Paint.Align.CENTER
        text.typeface = bold
        text.textSize = stripH * 0.8f
        text.color = scoreColor(s, sel.par)
        c.drawText(if (s == 0) "—" else s.toString(), (minus.right + plus.left) / 2, ctrl.bottom - stripH * 0.18f, text)
        y += stripH + 3f * u

        // New round (tap twice) and back to the map.
        val btnH = 8f * u
        val newRound = RectF(left, y, left + 30f * u, y + btnH)
        button(c, newRound, if (confirmingNewRound) "Tap again to clear" else "New round", u, btnH * 0.42f,
            color = if (confirmingNewRound) DANGER else BUTTON)
        targets.add(TapTarget(newRound, TapAction.NewRound))
        val back = RectF(right - 24f * u, y, right, y + btnH)
        button(c, back, "Back to map", u, btnH * 0.42f)
        targets.add(TapTarget(back, TapAction.CloseCard))

        return DrawResult(targets.toList(), null)
    }

    private fun drawScoreCell(c: Canvas, s: Int, par: Int, r: RectF, u: Float) {
        if (s == 0) {
            cell(c, "·", r.left, r.top, r.width(), r.height(), MUTED, 0.5f, bold, u = u)
            return
        }
        val cx = r.centerX()
        val cy = r.centerY()
        val rad = min(r.width(), r.height()) * 0.36f
        stroke.strokeWidth = 0.3f * u
        stroke.color = scoreColor(s, par)
        // The usual scorecard marks: circles under par, squares over.
        when {
            s <= par - 2 -> { c.drawCircle(cx, cy, rad, stroke); c.drawCircle(cx, cy, rad * 0.8f, stroke) }
            s == par - 1 -> c.drawCircle(cx, cy, rad, stroke)
            s == par + 1 -> c.drawRect(RectF(cx - rad, cy - rad, cx + rad, cy + rad), stroke)
            s >= par + 2 -> {
                c.drawRect(RectF(cx - rad, cy - rad, cx + rad, cy + rad), stroke)
                val i = rad * 0.8f
                c.drawRect(RectF(cx - i, cy - i, cx + i, cy + i), stroke)
            }
        }
        cell(c, s.toString(), r.left, r.top, r.width(), r.height(), scoreColor(s, par), 0.48f, bold, u = u)
    }

    private fun scoreColor(s: Int, par: Int) = when {
        s == 0 -> MUTED
        s < par -> UNDER
        s > par -> OVER
        else -> WHITE
    }

    private fun cell(
        c: Canvas, s: String, x: Float, y: Float, w: Float, h: Float, color: Int, size: Float,
        face: Typeface, alignLeft: Boolean = false, u: Float,
    ) {
        text.color = color
        text.typeface = face
        text.textSize = h * size
        val maxW = w - 1.2f * u
        val tw = text.measureText(s)
        if (tw > maxW) text.textSize *= maxW / tw
        val baseline = y + h / 2 + text.textSize * 0.36f
        if (alignLeft) {
            text.textAlign = Paint.Align.LEFT
            c.drawText(s, x + 1.2f * u, baseline, text)
        } else {
            text.textAlign = Paint.Align.CENTER
            c.drawText(s, x + w / 2, baseline, text)
        }
    }

    private fun button(c: Canvas, r: RectF, label: String, u: Float, size: Float, color: Int = BUTTON) {
        fill.color = color
        c.drawRoundRect(r, 1.6f * u, 1.6f * u, fill)
        stroke.color = BUTTON_EDGE
        stroke.strokeWidth = 0.25f * u
        c.drawRoundRect(r, 1.6f * u, 1.6f * u, stroke)
        text.textAlign = Paint.Align.CENTER
        text.typeface = bold
        text.color = WHITE
        text.textSize = size
        val maxW = r.width() - 2 * u
        val tw = text.measureText(label)
        if (tw > maxW) text.textSize *= maxW / tw
        c.drawText(label, r.centerX(), r.centerY() + text.textSize * 0.36f, text)
    }

    companion object {
        private const val BG = 0xFF14221A.toInt()
        private const val HEADER_ROW = 0xFF1F3327.toInt()
        private const val ROW = 0xFF182A1F.toInt()
        private const val SELECTED = 0xFF2E5A3A.toInt()
        private const val GRID = 0xFF2A3F31.toInt()
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val SOFT = 0xFFD7E4D9.toInt()
        private const val MUTED = 0xFF8FA896.toInt()
        private const val BUTTON = 0xFF24382B.toInt()
        private const val BUTTON_EDGE = 0xFF3D5A45.toInt()
        private const val DANGER = 0xFF8A2E2E.toInt()
        private const val UNDER = 0xFFFF7A7A.toInt()
        private const val OVER = 0xFF8FC1FF.toInt()
    }
}
