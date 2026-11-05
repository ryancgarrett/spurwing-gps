package com.spurwing.gps.car

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.spurwing.gps.GolfState
import com.spurwing.gps.render.DrawResult

/** Draws the hole map (or the scorecard) onto the cart screen and handles taps on it. */
class SurfaceRenderer(private val carContext: CarContext) : DefaultLifecycleObserver {
    private var container: SurfaceContainer? = null
    private var visibleArea: Rect? = null
    private var lastDraw: DrawResult? = null
    private val listener: () -> Unit = { render() }

    private val callback = object : SurfaceCallback {
        override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
            container = surfaceContainer
            render()
        }

        override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
            container = null
        }

        override fun onVisibleAreaChanged(visibleArea: Rect) {
            this@SurfaceRenderer.visibleArea = visibleArea
            render()
        }

        override fun onStableAreaChanged(stableArea: Rect) {
            render()
        }

        // Taps: score buttons, scorecard cells, or a spot on the map to measure to.
        // Android Auto only sends these because HoleScreen adds the Pan action.
        override fun onClick(x: Float, y: Float) {
            GolfState.tap(lastDraw, x, y, slopPx())
        }
    }

    /** About a sixth of an inch of extra margin around buttons. */
    private fun slopPx(): Float {
        val dpi = container?.dpi ?: 160
        return (if (dpi > 0) dpi else 160) / 6f
    }

    override fun onCreate(owner: LifecycleOwner) {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(callback)
        GolfState.addListener(listener)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        GolfState.removeListener(listener)
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(null)
    }

    fun render() {
        val c = container ?: return
        val surface = c.surface ?: return
        if (!surface.isValid || c.width <= 0 || c.height <= 0) return
        val canvas = try {
            surface.lockCanvas(null)
        } catch (e: Exception) {
            return
        } ?: return
        try {
            canvas.drawColor(0xFF14221A.toInt())
            val area = visibleArea?.takeIf { it.width() > 0 && it.height() > 0 }
                ?.let { RectF(it) }
                ?: RectF(0f, 0f, c.width.toFloat(), c.height.toFloat())
            lastDraw = GolfState.drawScreen(canvas, area)
            if (!GolfState.hasLocationPermission(carContext)) {
                drawMessage(canvas, area, "Open SpurWing GPS on your phone and allow location.")
            }
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
    }

    private fun drawMessage(canvas: Canvas, area: RectF, msg: String) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = area.height() / 18f
            textAlign = Paint.Align.CENTER
        }
        val bg = Paint().apply { color = 0xDD14221A.toInt() }
        val y = area.centerY()
        canvas.drawRect(area.left, y - p.textSize * 1.4f, area.right, y + p.textSize * 0.9f, bg)
        canvas.drawText(msg, area.centerX(), y, p)
    }
}
