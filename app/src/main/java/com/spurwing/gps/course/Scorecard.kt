package com.spurwing.gps.course

/**
 * Strokes per hole for one round on each course. 0 means not entered yet.
 * Saved as a short text string so it survives the app closing mid-round.
 */
class Scorecard(private val data: CourseData) {
    private val scores: Map<CourseId, IntArray> =
        CourseId.entries.associateWith { IntArray(data.holes(it).size) }

    fun get(course: CourseId, number: Int): Int = scores.getValue(course).getOrElse(number - 1) { 0 }

    fun set(course: CourseId, number: Int, strokes: Int) {
        val arr = scores.getValue(course)
        if (number in 1..arr.size) arr[number - 1] = strokes.coerceIn(0, MAX_STROKES)
    }

    /**
     * The − and + buttons. On a hole with no score yet, + puts down par and − puts
     * down one under, since that's what you'd usually be entering anyway.
     */
    fun adjust(course: CourseId, number: Int, delta: Int) {
        val par = par(course, number)
        val current = get(course, number)
        val next = when {
            current == 0 && delta > 0 -> par
            current == 0 && delta < 0 -> par - 1
            current + delta < 1 -> 0 // stepping below 1 clears the hole
            else -> current + delta
        }
        set(course, number, next)
    }

    fun clear(course: CourseId) = scores.getValue(course).fill(0)

    fun par(course: CourseId, number: Int): Int =
        data.holes(course).firstOrNull { it.number == number }?.par ?: 4

    /** Total strokes, par for the holes played, and how many holes have a score. */
    data class Totals(val strokes: Int, val parPlayed: Int, val holesPlayed: Int) {
        val toPar: Int get() = strokes - parPlayed
    }

    fun totals(course: CourseId, holes: IntRange = 1..scores.getValue(course).size): Totals {
        var strokes = 0
        var par = 0
        var played = 0
        for (n in holes) {
            val s = get(course, n)
            if (s > 0) {
                strokes += s
                par += par(course, n)
                played++
            }
        }
        return Totals(strokes, par, played)
    }

    fun encode(): String = CourseId.entries.joinToString(";") { c ->
        c.name + ":" + scores.getValue(c).joinToString(",")
    }

    fun decode(text: String?) {
        if (text.isNullOrBlank()) return
        for (part in text.split(';')) {
            val name = part.substringBefore(':', "")
            val course = CourseId.entries.firstOrNull { it.name == name } ?: continue
            part.substringAfter(':', "").split(',').forEachIndexed { i, v ->
                v.trim().toIntOrNull()?.let { set(course, i + 1, it) }
            }
        }
    }

    companion object {
        const val MAX_STROKES = 15

        /** "E", "+3", "−2" */
        fun formatToPar(toPar: Int): String = when {
            toPar == 0 -> "E"
            toPar > 0 -> "+$toPar"
            else -> "−${-toPar}"
        }
    }
}
