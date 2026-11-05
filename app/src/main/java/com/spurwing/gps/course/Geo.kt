package com.spurwing.gps.course

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

/** A point in a flat local frame, in meters: x = east, y = north. */
data class Xy(val x: Double, val y: Double)

const val METERS_PER_YARD = 0.9144
private const val EARTH_RADIUS_M = 6371008.8

fun Double.toYards(): Double = this / METERS_PER_YARD

/** Great-circle distance in meters. */
fun distanceMeters(a: LatLon, b: LatLon): Double {
    val la1 = Math.toRadians(a.lat)
    val la2 = Math.toRadians(b.lat)
    val dLat = la2 - la1
    val dLon = Math.toRadians(b.lon - a.lon)
    val h = sin(dLat / 2).let { it * it } +
        cos(la1) * cos(la2) * sin(dLon / 2).let { it * it }
    return 2 * EARTH_RADIUS_M * asin(sqrt(h))
}

/** Initial bearing from a to b, degrees clockwise from north. */
fun bearingDegrees(a: LatLon, b: LatLon): Double {
    val la1 = Math.toRadians(a.lat)
    val la2 = Math.toRadians(b.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val y = sin(dLon) * cos(la2)
    val x = cos(la1) * sin(la2) - sin(la1) * cos(la2) * cos(dLon)
    return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
}

/**
 * Equirectangular projection around an origin. Over a golf course (a couple of km)
 * the error is a few centimeters, far below GPS noise.
 */
class LocalFrame(private val origin: LatLon) {
    private val kx = PI / 180.0 * EARTH_RADIUS_M * cos(Math.toRadians(origin.lat))
    private val ky = PI / 180.0 * EARTH_RADIUS_M

    fun toXy(p: LatLon) = Xy((p.lon - origin.lon) * kx, (p.lat - origin.lat) * ky)
    fun toLatLon(p: Xy) = LatLon(origin.lat + p.y / ky, origin.lon + p.x / kx)
}

object Geometry {
    /** Distance from p to segment a-b, plus how far along the segment (0..1) the closest point is. */
    fun segmentDistance(p: Xy, a: Xy, b: Xy): Pair<Double, Double> {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0.0, 1.0)
        return hypot(p.x - a.x - t * dx, p.y - a.y - t * dy) to t
    }

    fun distanceToPolyline(p: Xy, line: List<Xy>): Double {
        if (line.size == 1) return hypot(p.x - line[0].x, p.y - line[0].y)
        var best = Double.MAX_VALUE
        for (i in 0 until line.size - 1) {
            best = minOf(best, segmentDistance(p, line[i], line[i + 1]).first)
        }
        return best
    }

    /** Closest distance from p to the line, and the distance along the line where that happens. */
    fun projectOntoPolyline(p: Xy, line: List<Xy>): Pair<Double, Double> {
        var bestD = Double.MAX_VALUE
        var bestAlong = 0.0
        var acc = 0.0
        for (i in 0 until line.size - 1) {
            val (d, t) = segmentDistance(p, line[i], line[i + 1])
            val seg = hypot(line[i + 1].x - line[i].x, line[i + 1].y - line[i].y)
            if (d < bestD) {
                bestD = d
                bestAlong = acc + t * seg
            }
            acc += seg
        }
        return bestD to bestAlong
    }

    fun polylineLength(line: List<Xy>): Double {
        var acc = 0.0
        for (i in 0 until line.size - 1) acc += hypot(line[i + 1].x - line[i].x, line[i + 1].y - line[i].y)
        return acc
    }

    fun contains(poly: List<Xy>, p: Xy): Boolean {
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[j]
            if ((a.y > p.y) != (b.y > p.y) &&
                p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x
            ) inside = !inside
            j = i
        }
        return inside
    }

    /** Distance from p to a polygon: 0 inside, otherwise distance to its outline. */
    fun distanceToPolygon(poly: List<Xy>, p: Xy): Double {
        if (contains(poly, p)) return 0.0
        return distanceToPolyline(p, poly + poly.first())
    }

    /** Area centroid; falls back to the vertex average for degenerate shapes. */
    fun centroid(poly: List<Xy>): Xy {
        var a = 0.0
        var cx = 0.0
        var cy = 0.0
        var j = poly.size - 1
        for (i in poly.indices) {
            val p1 = poly[j]
            val p2 = poly[i]
            val c = p1.x * p2.y - p2.x * p1.y
            a += c
            cx += (p1.x + p2.x) * c
            cy += (p1.y + p2.y) * c
            j = i
        }
        if (abs(a) < 1e-9) {
            return Xy(poly.sumOf { it.x } / poly.size, poly.sumOf { it.y } / poly.size)
        }
        return Xy(cx / (3 * a), cy / (3 * a))
    }

    /**
     * Where the ray from `from` through `through` crosses the polygon outline,
     * as distances from `from`. Used to find the front and back edges of a green.
     */
    fun rayCrossings(poly: List<Xy>, from: Xy, through: Xy): List<Double> {
        val dx = through.x - from.x
        val dy = through.y - from.y
        val len = hypot(dx, dy)
        if (len < 1e-6) return emptyList()
        val ux = dx / len
        val uy = dy / len
        val out = ArrayList<Double>()
        var j = poly.size - 1
        for (i in poly.indices) {
            val a = poly[j]
            val b = poly[i]
            val ex = b.x - a.x
            val ey = b.y - a.y
            val denom = ux * ey - uy * ex
            if (abs(denom) > 1e-12) {
                val wx = a.x - from.x
                val wy = a.y - from.y
                val t = (wx * ey - wy * ex) / denom // distance along the ray
                val s = (wx * uy - wy * ux) / denom // position along the edge
                if (t >= 0 && s in 0.0..1.0) out.add(t)
            }
            j = i
        }
        return out
    }
}
