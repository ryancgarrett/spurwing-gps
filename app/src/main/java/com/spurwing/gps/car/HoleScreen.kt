package com.spurwing.gps.car

import androidx.annotation.DrawableRes
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import com.spurwing.gps.GolfState
import com.spurwing.gps.R

/**
 * The cart screen. The hole map and scorecard are drawn straight onto the screen by
 * [SurfaceRenderer]; this template adds the buttons along the edge.
 */
class HoleScreen(carContext: CarContext) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val strip = ActionStrip.Builder()
            .addAction(iconAction(R.drawable.ic_prev) { GolfState.previousHole() })
            .addAction(iconAction(R.drawable.ic_next) { GolfState.nextHole() })
            .addAction(iconAction(R.drawable.ic_card) { GolfState.toggleCard() })
            .addAction(iconAction(R.drawable.ic_flag) { GolfState.switchCourse() })
            .build()
        // Android Auto only passes taps on the map to the app when the Pan action is
        // present. On a touchscreen the Pan button itself isn't shown.
        val mapStrip = ActionStrip.Builder()
            .addAction(Action.PAN)
            .build()
        return NavigationTemplate.Builder()
            .setActionStrip(strip)
            .setMapActionStrip(mapStrip)
            .build()
    }

    private fun iconAction(@DrawableRes icon: Int, onClick: () -> Unit): Action =
        Action.Builder()
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, icon)).build())
            .setOnClickListener { onClick() }
            .build()
}
