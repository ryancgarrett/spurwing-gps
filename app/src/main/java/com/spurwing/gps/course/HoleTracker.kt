package com.spurwing.gps.course

import kotlin.math.hypot
import kotlin.math.roundToInt

/** Yards to the front, middle and back of a green. Front is 0 when you're on the green. */
data class Yardages(val front: Int, val middle: Int, val back: Int, val onGreen: Boolean)

object Distances {
    fun toGreen(player: LatLon, hole: Hole): Yardages {
        val frame = LocalFrame(hole.greenCenter)
        val center = Xy(0.0, 0.0)
        val me = frame.toXy(player)
        val green = hole.green.map(frame::toXy)
        val middle = hypot(me.x, me.y)
        if (green.size < 3) {
            val y = middle.toYards().roundToInt()
            return Yardages(y, y, y, false)
        }
        val onGreen = Geometry.contains(green, me)
        val crossings = Geometry.rayCrossings(green, me, center)
        val front = when {
            onGreen -> 0.0
            crossings.isEmpty() -> middle
            else -> crossings.min()
        }
        val back = if (crossings.isEmpty()) middle else crossings.max()
        return Yardages(
            front.toYards().roundToInt(),
            middle.toYards().roundToInt(),
            back.toYards().roundToInt(),
            onGreen,
        )
    }

    fun yards(a: LatLon, b: LatLon): Int = distanceMeters(a, b).toYards().roundToInt()
}

/**
 * Keeps track of which hole you're playing and moves on automatically:
 *  - Pull up near a tee box and it switches to that hole, as long as you're not
 *    still out on the middle of the current hole (so driving past another hole's
 *    tee doesn't flip the screen).
 *  - If you've clearly left the current hole and stay on another one, it follows you.
 * Picking a hole by hand (say the GPS got confused) pauses the automatic switching
 * until the GPS actually sees you on that hole, then it picks up again from there.
 */
class HoleTracker(
    private val data: CourseData,
    startCourse: CourseId,
    startNumber: Int,
) {
    var course: CourseId = startCourse
        private set
    var number: Int = startNumber.coerceIn(1, maxOf(1, data.holes(startCourse).size))
        private set

    val hole: Hole get() = data.holes(course).first { it.number == number }

    private val frame: LocalFrame
    private val geo: Map<Hole, HoleGeo>

    private class HoleGeo(val corridor: List<Xy>, val length: Double, val tees: List<List<Xy>>, val teeStart: Xy, val green: Xy)

    private var manualUntilMs = 0L
    private var confirmCount = 0
    private var hasPlacedOnce = false

    /** True while a hole picked by hand is holding off the automatic switching. */
    fun isAutoPaused(nowMs: Long): Boolean = nowMs < manualUntilMs
    private var reachedGreen = false
    private var teeCandidate: Hole? = null
    private var teeCount = 0
    private var corridorCandidate: Hole? = null
    private var corridorCount = 0

    init {
        val all = data.allHoles.flatMap { it.line }
        frame = LocalFrame(LatLon(all.map { it.lat }.average(), all.map { it.lon }.average()))
        geo = data.allHoles.associateWith { h ->
            val corridor = (h.line + h.greenCenter).map(frame::toXy)
            HoleGeo(
                corridor = corridor,
                length = Geometry.polylineLength(corridor),
                tees = h.tees.map { t -> t.map(frame::toXy) },
                teeStart = frame.toXy(h.tee),
                green = frame.toXy(h.greenCenter),
            )
        }
    }

    fun select(course: CourseId, number: Int, nowMs: Long) {
        val holes = data.holes(course)
        if (holes.isEmpty()) return
        this.course = course
        this.number = number.coerceIn(holes.first().number, holes.last().number)
        manualUntilMs = nowMs + MANUAL_HOLD_MS
        confirmCount = 0
        hasPlacedOnce = true
        resetCandidates()
    }

    fun next(nowMs: Long) {
        val holes = data.holes(course)
        val i = holes.indexOfFirst { it.number == number }
        select(course, holes[(i + 1) % holes.size].number, nowMs)
    }

    fun previous(nowMs: Long) {
        val holes = data.holes(course)
        val i = holes.indexOfFirst { it.number == number }
        select(course, holes[(i - 1 + holes.size) % holes.size].number, nowMs)
    }

    fun switchCourse(nowMs: Long) {
        val other = if (course == CourseId.CHAMPIONSHIP) CourseId.CHALLENGE else CourseId.CHAMPIONSHIP
        select(other, 1, nowMs)
    }

    /** Feed a GPS fix. Returns true if the hole changed. */
    fun onLocation(p: LatLon, accuracyMeters: Float, nowMs: Long): Boolean {
        if (accuracyMeters > MAX_ACCURACY_M) return false
        val me = frame.toXy(p)

        // First good fix with nothing chosen yet: jump to whatever hole we're standing on.
        if (!hasPlacedOnce) {
            hasPlacedOnce = true
            val nearest = data.allHoles.minByOrNull { teeOrCorridorDistance(it, me) }
            if (nearest != null && teeOrCorridorDistance(nearest, me) < 60) return moveTo(nearest)
            return false
        }
        if (nowMs < manualUntilMs) {
            // You picked this hole by hand. Stay on it until the GPS sees you on it,
            // then go back to following you automatically.
            val g = geo.getValue(hole)
            val onPicked = teeDistance(hole, me) < TEE_RADIUS_M ||
                Geometry.distanceToPolyline(me, g.corridor) < 30
            confirmCount = if (onPicked) confirmCount + 1 else 0
            if (confirmCount < CONFIRM_FIXES) return false
            manualUntilMs = 0L
            confirmCount = 0
        }

        val cur = geo.getValue(hole)
        val (curDist, curAlong) = Geometry.projectOntoPolyline(me, cur.corridor)
        val toGreen = hypot(me.x - cur.green.x, me.y - cur.green.y)
        if (toGreen < GREEN_RADIUS_M) reachedGreen = true
        val nearCurrentGreen = toGreen < GREEN_RADIUS_M || curAlong > 0.85 * cur.length
        val outOnCurrentHole = curDist < 40 && !nearCurrentGreen

        // Tee rule. Some tees sit right beside another hole (hole 7's forward tees are
        // next to the 6th fairway), so while you're still playing a hole only the next
        // hole's tee counts, and only once you've been up to the green.
        val teeHole = data.allHoles
            .filter { it != hole }
            .map { it to teeDistance(it, me) }
            .filter { it.second < TEE_RADIUS_M }
            .minByOrNull { it.second }?.first
        if (teeHole != null && (!outOnCurrentHole || (reachedGreen && isNextHole(teeHole)))) {
            if (teeHole == teeCandidate) teeCount++ else { teeCandidate = teeHole; teeCount = 1 }
            if (teeCount >= TEE_FIXES) return moveTo(teeHole)
        } else {
            teeCandidate = null
            teeCount = 0
        }

        // Corridor rule: well away from the current hole and sitting on another one.
        if (curDist > 60) {
            val other = data.allHoles
                .filter { it != hole }
                .map { it to Geometry.distanceToPolyline(me, geo.getValue(it).corridor) }
                .filter { it.second < 20 }
                .minByOrNull { it.second }?.first
            if (other != null) {
                if (other == corridorCandidate) corridorCount++ else { corridorCandidate = other; corridorCount = 1 }
                if (corridorCount >= CORRIDOR_FIXES) return moveTo(other)
            } else {
                corridorCandidate = null
                corridorCount = 0
            }
        } else {
            corridorCandidate = null
            corridorCount = 0
        }
        return false
    }

    private fun isNextHole(h: Hole): Boolean {
        val holes = data.holes(course)
        val i = holes.indexOfFirst { it.number == number }
        return h.course == course && holes[(i + 1) % holes.size] == h
    }

    private fun teeDistance(h: Hole, me: Xy): Double {
        val g = geo.getValue(h)
        var best = hypot(me.x - g.teeStart.x, me.y - g.teeStart.y)
        for (t in g.tees) if (t.size >= 3) best = minOf(best, Geometry.distanceToPolygon(t, me))
        return best
    }

    private fun teeOrCorridorDistance(h: Hole, me: Xy): Double =
        minOf(teeDistance(h, me), Geometry.distanceToPolyline(me, geo.getValue(h).corridor))

    private fun moveTo(h: Hole): Boolean {
        val changed = h.course != course || h.number != number
        course = h.course
        number = h.number
        resetCandidates()
        return changed
    }

    private fun resetCandidates() {
        teeCandidate = null; teeCount = 0
        corridorCandidate = null; corridorCount = 0
        reachedGreen = false
    }

    companion object {
        /** A hand-picked hole holds at most this long if the GPS never confirms it. */
        const val MANUAL_HOLD_MS = 15 * 60 * 1000L
        private const val CONFIRM_FIXES = 3
        private const val MAX_ACCURACY_M = 30f
        private const val TEE_RADIUS_M = 25.0
        private const val GREEN_RADIUS_M = 35.0
        private const val TEE_FIXES = 2
        private const val CORRIDOR_FIXES = 10
    }
}
