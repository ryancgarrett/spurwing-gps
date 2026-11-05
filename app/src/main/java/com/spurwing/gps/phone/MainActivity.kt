package com.spurwing.gps.phone

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.spurwing.gps.GolfState
import com.spurwing.gps.render.DrawResult

/**
 * The phone screen: asks for location permission and shows the same hole map as the
 * cart, so you can try the app out walking the course before it's in the cart.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var holeView: HoleView
    private val listener: () -> Unit = { holeView.invalidate() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GolfState.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        holeView = HoleView(this)
        val dp = resources.displayMetrics.density
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), 0)
            addView(button("◀") { GolfState.previousHole() })
            addView(button("▶") { GolfState.nextHole() })
            addView(button("Card") { GolfState.toggleCard() })
            addView(button("Course") { GolfState.switchCourse() })
        }
        val root = FrameLayout(this).apply {
            fitsSystemWindows = true
            addView(holeView, FrameLayout.LayoutParams(-1, -1))
            addView(buttons, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        }
        setContentView(root)

        if (!GolfState.hasLocationPermission(this)) {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                REQ_LOCATION,
            )
        }
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        alpha = 0.92f
        setOnClickListener { onClick() }
    }

    override fun onStart() {
        super.onStart()
        GolfState.addListener(listener)
        GolfState.startGps()
        holeView.invalidate()
    }

    override fun onStop() {
        GolfState.removeListener(listener)
        GolfState.stopGps()
        super.onStop()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
            GolfState.tryStartGps()
        }
    }

    companion object {
        private const val REQ_LOCATION = 1
    }
}

/** Draws the hole map or scorecard with the shared renderers; handles taps on them. */
class HoleView(context: Context) : View(context) {
    private var lastDraw: DrawResult? = null

    override fun onDraw(canvas: Canvas) {
        lastDraw = GolfState.drawScreen(canvas, RectF(0f, 0f, width.toFloat(), height.toFloat()))
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            GolfState.tap(lastDraw, event.x, event.y, 12 * resources.displayMetrics.density)
        }
        return true
    }
}
