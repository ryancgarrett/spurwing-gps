package com.spurwing.gps.course

import kotlin.math.hypot

enum class Kind { FAIRWAY, GREEN, TEE, BUNKER, WATER, DRIVING_RANGE, HOLE, OTHER }

class Feature(
    val kind: Kind,
    val ref: String,
    val points: List<LatLon>,
    /** An inner ring (a hole cut out of a shape). We don't fill these. */
    val inner: Boolean,
)

enum class CourseId(val label: String) {
    CHAMPIONSHIP("Championship"),
    CHALLENGE("Challenge"),
}

class Hole(
    val course: CourseId,
    val number: Int,
    val par: Int,
    /** Tee-to-green line from OpenStreetMap, tee end first. */
    val line: List<LatLon>,
    val green: List<LatLon>,
    val greenCenter: LatLon,
    val tees: List<List<LatLon>>,
    /** Scorecard yardage when we have it, otherwise measured from the map. */
    scorecardYards: Int?,
) {
    val tee: LatLon get() = line.first()
    val lengthYards: Int = scorecardYards
        ?: line.zipWithNext { a, b -> distanceMeters(a, b) }.sum().toYards().toInt()
}

class CourseData(val features: List<Feature>, holes: List<Hole>) {
    val holesByCourse: Map<CourseId, List<Hole>> =
        holes.groupBy { it.course }.mapValues { (_, v) -> v.sortedBy { it.number } }

    val allHoles: List<Hole> = holes

    fun holes(course: CourseId): List<Hole> = holesByCourse[course].orEmpty()

    companion object {
        /** Scorecard pars for the 18-hole course. The 9-hole Challenge course is all par 3s. */
        private val CHAMPIONSHIP_PARS = intArrayOf(4, 5, 4, 4, 5, 3, 4, 4, 3, 4, 4, 3, 5, 4, 3, 5, 4, 4)
        /** Scorecard yardages for the 18-hole course (back tees). */
        private val CHAMPIONSHIP_YARDS = intArrayOf(
            387, 551, 406, 451, 540, 195, 465, 406, 178,
            465, 423, 201, 574, 442, 194, 627, 343, 424,
        )

        // The data file stores coordinates as millionths of a degree from this origin.
        private const val ORIGIN_LON = -116.45
        private const val ORIGIN_LAT = 43.65

        /**
         * Parses the course file. One feature per line: `kind|ref|x0,y0,dx1,dy1,...`
         * x/y are millionths of a degree (lon/lat) from the origin; after the first
         * point each pair is a delta from the previous one. A kind ending in `!` is an
         * inner ring. Hole refs are `M7` (Championship 7) or `C3` (Challenge 3).
         */
        fun parse(text: String): CourseData {
            val features = ArrayList<Feature>()
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                val parts = line.split('|')
                if (parts.size != 3) continue
                val kindText = parts[0]
                val inner = kindText.endsWith("!")
                val kind = when (kindText.removeSuffix("!")) {
                    "fairway" -> Kind.FAIRWAY
                    "green" -> Kind.GREEN
                    "tee" -> Kind.TEE
                    "bunker" -> Kind.BUNKER
                    "water" -> Kind.WATER
                    "driving_range" -> Kind.DRIVING_RANGE
                    "hole" -> Kind.HOLE
                    else -> Kind.OTHER
                }
                val nums = parts[2].split(',').map { it.trim().toInt() }
                val pts = ArrayList<LatLon>(nums.size / 2)
                var x = 0
                var y = 0
                var i = 0
                while (i + 1 < nums.size) {
                    if (i == 0) {
                        x = nums[0]; y = nums[1]
                    } else {
                        x += nums[i]; y += nums[i + 1]
                    }
                    pts.add(LatLon(ORIGIN_LAT + y / 1e6, ORIGIN_LON + x / 1e6))
                    i += 2
                }
                features.add(Feature(kind, parts[1], pts, inner))
            }
            return CourseData(features, buildHoles(features))
        }

        private fun buildHoles(features: List<Feature>): List<Hole> {
            val all = features.flatMap { it.points }
            val frame = LocalFrame(LatLon(all.map { it.lat }.average(), all.map { it.lon }.average()))
            val greens = features.filter { it.kind == Kind.GREEN && !it.inner }
                .map { f -> f.points to f.points.map(frame::toXy) }
            val teeShapes = features.filter { it.kind == Kind.TEE && !it.inner }
                .map { f -> f.points to f.points.map(frame::toXy) }

            data class Raw(val course: CourseId, val number: Int, val line: List<LatLon>, val xy: List<Xy>)

            val raws = features.filter { it.kind == Kind.HOLE && it.ref.length >= 2 }.mapNotNull { f ->
                val course = when (f.ref[0]) {
                    'M' -> CourseId.CHAMPIONSHIP
                    'C' -> CourseId.CHALLENGE
                    else -> return@mapNotNull null
                }
                val number = f.ref.substring(1).toIntOrNull() ?: return@mapNotNull null
                Raw(course, number, f.points, f.points.map(frame::toXy))
            }

            // Give each tee box to the hole it belongs to: close to the start of a hole's
            // line (tee boxes often sit behind where the line starts), or beside the first
            // part of the line (forward tees).
            val teesFor = HashMap<Raw, MutableList<List<LatLon>>>()
            for ((teePts, teeXy) in teeShapes) {
                val c = Geometry.centroid(teeXy)
                var best: Raw? = null
                var bestD = Double.MAX_VALUE
                for (h in raws) {
                    val (d, along) = Geometry.projectOntoPolyline(c, h.xy)
                    val total = Geometry.polylineLength(h.xy)
                    val fromStart = hypot(c.x - h.xy[0].x, c.y - h.xy[0].y)
                    val eligible = (d < 45 && along <= 0.6 * total) || fromStart < 60
                    if (eligible && d < bestD) {
                        bestD = d
                        best = h
                    }
                }
                if (best != null) teesFor.getOrPut(best) { ArrayList() }.add(teePts)
            }

            return raws.map { h ->
                val end = h.xy.last()
                // The green is the one the hole line ends in, or failing that the nearest.
                val green = greens.firstOrNull { Geometry.contains(it.second, end) }
                    ?: greens.minByOrNull { Geometry.distanceToPolygon(it.second, end) }
                val greenPts = green?.first ?: listOf(h.line.last())
                val center = if (green != null) frame.toLatLon(Geometry.centroid(green.second)) else h.line.last()
                val par = when (h.course) {
                    CourseId.CHAMPIONSHIP -> CHAMPIONSHIP_PARS.getOrElse(h.number - 1) { 4 }
                    CourseId.CHALLENGE -> 3
                }
                val yards = if (h.course == CourseId.CHAMPIONSHIP) CHAMPIONSHIP_YARDS.getOrNull(h.number - 1) else null
                Hole(h.course, h.number, par, h.line, greenPts, center, teesFor[h].orEmpty(), yards)
            }
        }
    }
}
