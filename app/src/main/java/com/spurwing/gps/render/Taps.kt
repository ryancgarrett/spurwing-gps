package com.spurwing.gps.render

import android.graphics.RectF
import com.spurwing.gps.course.CourseId
import com.spurwing.gps.course.LatLon

/** Something on screen you can tap. */
sealed interface TapAction {
    data object ScoreMinus : TapAction
    data object ScorePlus : TapAction
    data object OpenCard : TapAction
    data object CloseCard : TapAction
    data object NewRound : TapAction
    data class PickCardHole(val course: CourseId, val number: Int) : TapAction
    data class MapSpot(val position: LatLon) : TapAction
}

class TapTarget(val rect: RectF, val action: TapAction)

/** What a draw produced: where the buttons ended up, and how to read taps on the map. */
class DrawResult(val targets: List<TapTarget>, val projection: MapProjection?) {
    /**
     * Turns a tap at (x, y) into an action. Buttons get a little extra margin so a
     * slightly-off finger on a bumpy cart still lands.
     */
    fun hit(x: Float, y: Float, slop: Float): TapAction? {
        targets.firstOrNull { it.rect.contains(x, y) }?.let { return it.action }
        targets.firstOrNull {
            RectF(it.rect.left - slop, it.rect.top - slop, it.rect.right + slop, it.rect.bottom + slop)
                .contains(x, y)
        }?.let { return it.action }
        return projection?.toLatLon(x, y)?.let { TapAction.MapSpot(it) }
    }
}
