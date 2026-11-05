package com.spurwing.gps.course

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CourseTest {
    private val data = CourseData.parse(File("src/main/assets/spurwing.txt").readText())

    @Test
    fun allHolesLoad() {
        assertEquals(18, data.holes(CourseId.CHAMPIONSHIP).size)
        assertEquals(9, data.holes(CourseId.CHALLENGE).size)
        assertEquals(72, data.holes(CourseId.CHAMPIONSHIP).sumOf { it.par })
        for (h in data.allHoles) {
            assertTrue("hole ${h.course} ${h.number} has a green", h.green.size >= 3)
            assertTrue("hole ${h.course} ${h.number} has tees", h.tees.isNotEmpty())
        }
    }

    @Test
    fun yardagesFromTeeLookRight() {
        val h1 = data.holes(CourseId.CHAMPIONSHIP).first { it.number == 1 }
        val y = Distances.toGreen(h1.tee, h1)
        // Scorecard says 387 from the tips.
        assertTrue("middle ${y.middle}", y.middle in 370..405)
        assertTrue(y.front < y.middle && y.middle < y.back)
        assertTrue(!y.onGreen)
    }

    @Test
    fun onTheGreenFrontIsZero() {
        val h = data.holes(CourseId.CHAMPIONSHIP).first { it.number == 9 }
        val y = Distances.toGreen(h.greenCenter, h)
        assertTrue(y.onGreen)
        assertEquals(0, y.front)
        assertEquals(0, y.middle)
    }

    @Test
    fun trackerFollowsToNextTee() {
        val t = HoleTracker(data, CourseId.CHAMPIONSHIP, 1)
        val h1 = data.holes(CourseId.CHAMPIONSHIP).first { it.number == 1 }
        val h2 = data.holes(CourseId.CHAMPIONSHIP).first { it.number == 2 }
        var now = 0L
        // Standing on the 1st tee places us on hole 1.
        t.onLocation(h1.tee, 5f, now)
        assertEquals(1, t.number)
        // Finish on the 1st green, then pull up to the 2nd tee.
        repeat(3) { now += 1000; t.onLocation(h1.greenCenter, 5f, now) }
        assertEquals(1, t.number)
        repeat(3) { now += 1000; t.onLocation(h2.tee, 5f, now) }
        assertEquals(2, t.number)
    }

    @Test
    fun scorecardButtons() {
        val card = Scorecard(data)
        val c = CourseId.CHAMPIONSHIP
        card.adjust(c, 1, +1)
        assertEquals("first + puts down par", 4, card.get(c, 1))
        card.adjust(c, 1, +1)
        assertEquals(5, card.get(c, 1))
        card.adjust(c, 2, -1)
        assertEquals("first − puts down a birdie", 4, card.get(c, 2))
        val t = card.totals(c)
        assertEquals(9, t.strokes)
        assertEquals(0, t.toPar)
        val copy = Scorecard(data).apply { decode(card.encode()) }
        assertEquals(5, copy.get(c, 1))
        card.clear(c)
        assertEquals(0, card.totals(c).holesPlayed)
    }

    @Test
    fun manualPickResumesOnceGpsAgrees() {
        val c = CourseId.CHAMPIONSHIP
        val h = data.holes(c).associateBy { it.number }
        val t = HoleTracker(data, c, 1)
        var now = 0L
        t.onLocation(h.getValue(3).tee, 5f, now)
        t.select(c, 5, now)
        repeat(20) { now += 1000; t.onLocation(h.getValue(3).greenCenter, 5f, now) }
        assertEquals("stays on the hole you picked", 5, t.number)
        repeat(3) { now += 1000; t.onLocation(h.getValue(5).tee, 5f, now) }
        assertTrue("automatic again", !t.isAutoPaused(now))
    }

    @Test
    fun manualPickHoldsForAWhile() {
        val t = HoleTracker(data, CourseId.CHAMPIONSHIP, 1)
        val h2 = data.holes(CourseId.CHAMPIONSHIP).first { it.number == 2 }
        t.select(CourseId.CHAMPIONSHIP, 5, 0L)
        repeat(3) { t.onLocation(h2.tee, 5f, 1000L * (it + 1)) }
        assertEquals(5, t.number)
    }
}
