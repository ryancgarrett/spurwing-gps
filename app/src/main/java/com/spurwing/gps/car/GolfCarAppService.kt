package com.spurwing.gps.car

import android.Manifest
import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.spurwing.gps.GolfState

/** Entry point Android Auto binds to when the app is opened on the cart screen. */
class GolfCarAppService : CarAppService() {
    // A personal, sideloaded app: accept any Android Auto host.
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = GolfSession()
}

class GolfSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        GolfState.init(carContext)
        val renderer = SurfaceRenderer(carContext)
        lifecycle.addObserver(renderer)

        // GPS runs while the cart screen is showing the app.
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = GolfState.startGps()
            override fun onStop(owner: LifecycleOwner) = GolfState.stopGps()
        })

        if (!GolfState.hasLocationPermission(carContext)) {
            // Shows the permission prompt on the phone.
            carContext.requestPermissions(listOf(Manifest.permission.ACCESS_FINE_LOCATION)) { granted, _ ->
                if (granted.isNotEmpty()) GolfState.tryStartGps()
                renderer.render()
            }
        }
        return HoleScreen(carContext)
    }
}
