package com.spurwing.gps

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.spurwing.gps.course.CourseData
import com.spurwing.gps.course.CourseId
import com.spurwing.gps.course.HoleTracker
import com.spurwing.gps.course.LatLon
import com.spurwing.gps.course.Scorecard
import com.spurwing.gps.render.DrawResult
import com.spurwing.gps.render.HoleRenderer
import com.spurwing.gps.render.RoundInfo
import com.spurwing.gps.render.ScorecardRenderer
import com.spurwing.gps.render.TapAction
import android.graphics.Canvas
import android.graphics.RectF
import kotlin.math.hypot

/** A GPS fix as the screens need it. */
data class Fix(val position: LatLon, val accuracyMeters: Float, val elapsedMs: Long)

/**
 * The one shared picture of the round: course data, current hole, latest GPS fix and
 * any spot tapped on the map. The cart screen and the phone screen both draw from it.
 */
object GolfState {
    lateinit var course: CourseData
        private set
    lateinit var tracker: HoleTracker
        private set

    var fix: Fix? = null
        private set

    /** A spot tapped on the map, shown with its distances until it expires. */
    var target: LatLon? = null
        private set
    private var targetExpiresMs = 0L

    private lateinit var appContext: Context
    private val listeners = LinkedHashSet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private var gpsClients = 0
    private var gpsRunning = false

    private const val PREFS = "golf"
    private const val TARGET_MS = 30_000L

    fun init(context: Context) {
        if (::course.isInitialized) return
        appContext = context.applicationContext
        val text = appContext.assets.open("spurwing.txt").bufferedReader().use { it.readText() }
        course = CourseData.parse(text)
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val savedCourse = runCatching { CourseId.valueOf(prefs.getString("course", null) ?: "") }
            .getOrDefault(CourseId.CHAMPIONSHIP)
        tracker = HoleTracker(course, savedCourse, prefs.getInt("hole", 1))
        scorecard = Scorecard(course).apply { decode(prefs.getString("scores", null)) }
    }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    private var savedKey = ""

    private fun changed() {
        val key = "${tracker.course.name}:${tracker.number}"
        if (key != savedKey) {
            savedKey = key
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("course", tracker.course.name)
                .putInt("hole", tracker.number)
                .apply()
        }
        listeners.toList().forEach { it() }
    }

    // --- Hole selection -------------------------------------------------------

    // On the scorecard, the arrows move the hole being edited instead.
    fun nextHole() {
        if (showCard) { cardHole = stepCardHole(+1); changed(); return }
        tracker.next(SystemClock.elapsedRealtime()); clearTarget(); changed()
    }

    fun previousHole() {
        if (showCard) { cardHole = stepCardHole(-1); changed(); return }
        tracker.previous(SystemClock.elapsedRealtime()); clearTarget(); changed()
    }

    fun switchCourse() {
        tracker.switchCourse(SystemClock.elapsedRealtime()); clearTarget()
        cardHole = 1
        changed()
    }

    private fun stepCardHole(delta: Int): Int {
        val n = course.holes(tracker.course).size
        return ((cardHole - 1 + delta + n) % n) + 1
    }

    // --- Tap to measure -------------------------------------------------------

    fun setTarget(p: LatLon?) {
        target = p
        targetExpiresMs = SystemClock.elapsedRealtime() + TARGET_MS
        main.removeCallbacks(expireTarget)
        if (p != null) main.postDelayed(expireTarget, TARGET_MS)
        changed()
    }

    private fun clearTarget() {
        target = null
        main.removeCallbacks(expireTarget)
    }

    private val expireTarget = Runnable {
        if (SystemClock.elapsedRealtime() >= targetExpiresMs) { target = null; changed() }
    }

    // --- Scorecard ------------------------------------------------------------

    lateinit var scorecard: Scorecard
        private set

    /** Showing the full scorecard instead of the hole map. */
    var showCard = false
        private set

    /** The hole being edited on the full scorecard. */
    var cardHole = 1
        private set

    /** "New round" was tapped once; a second tap within a few seconds clears the card. */
    var confirmingNewRound = false
        private set

    fun toggleCard() {
        showCard = !showCard
        cardHole = tracker.number
        confirmingNewRound = false
        changed()
    }

    /** The −/+ buttons: the hole being edited on the card, otherwise the hole you're on. */
    fun adjustScore(delta: Int) {
        val number = if (showCard) cardHole else tracker.number
        scorecard.adjust(tracker.course, number, delta)
        saveScores()
        changed()
    }

    private fun newRoundTapped() {
        if (confirmingNewRound) {
            scorecard.clear(tracker.course)
            saveScores()
            confirmingNewRound = false
        } else {
            confirmingNewRound = true
            main.postDelayed({ if (confirmingNewRound) { confirmingNewRound = false; changed() } }, 4000)
        }
        changed()
    }

    private fun saveScores() {
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("scores", scorecard.encode())
            .apply()
    }

    fun roundInfo(): RoundInfo = RoundInfo(
        score = scorecard.get(tracker.course, tracker.number),
        totals = scorecard.totals(tracker.course),
        autoPaused = tracker.isAutoPaused(SystemClock.elapsedRealtime()),
    )

    // --- Drawing and taps, shared by the cart and phone screens ---------------

    private val holeRenderer = HoleRenderer()
    private val cardRenderer = ScorecardRenderer()

    fun drawScreen(canvas: Canvas, area: RectF): DrawResult =
        if (showCard) {
            cardRenderer.draw(canvas, area, course, scorecard, tracker.course, cardHole, confirmingNewRound)
        } else {
            holeRenderer.draw(canvas, area, course, tracker.hole, fix, target, roundInfo())
        }

    /**
     * Handles a tap at (x, y) on whatever was last drawn. `slop` is the extra margin
     * around buttons, in pixels.
     */
    fun tap(result: DrawResult?, x: Float, y: Float, slop: Float) {
        val r = result ?: return
        when (val action = r.hit(x, y, slop)) {
            TapAction.ScoreMinus -> adjustScore(-1)
            TapAction.ScorePlus -> adjustScore(+1)
            TapAction.OpenCard -> if (!showCard) toggleCard()
            TapAction.CloseCard -> if (showCard) toggleCard()
            TapAction.NewRound -> newRoundTapped()
            is TapAction.PickCardHole -> { cardHole = action.number; confirmingNewRound = false; changed() }
            is TapAction.MapSpot -> {
                // Tapping the yellow spot again clears it.
                val current = target
                val proj = r.projection
                if (current != null && proj != null) {
                    val (tx, ty) = proj.toScreen(current)
                    if (hypot(tx - x, ty - y) < slop * 2) { setTarget(null); return }
                }
                setTarget(action.position)
            }
            null -> {}
        }
    }

    // --- GPS ------------------------------------------------------------------

    fun hasLocationPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Each screen that's showing calls this; GPS runs while at least one is. */
    fun startGps() {
        gpsClients++
        tryStartGps()
    }

    fun stopGps() {
        gpsClients = maxOf(0, gpsClients - 1)
        if (gpsClients == 0 && gpsRunning) {
            locationManager().removeUpdates(gpsListener)
            gpsRunning = false
        }
    }

    /** Call again after the permission is granted. */
    @SuppressLint("MissingPermission")
    fun tryStartGps() {
        if (gpsRunning || gpsClients == 0 || !hasLocationPermission(appContext)) return
        val lm = locationManager()
        val provider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> LocationManager.GPS_PROVIDER
        }
        runCatching {
            lm.requestLocationUpdates(provider, 1000L, 0f, gpsListener, Looper.getMainLooper())
            gpsRunning = true
            lm.getLastKnownLocation(provider)?.let { onLocation(it, stale = true) }
        }
    }

    private fun locationManager() =
        appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onLocation(location, stale = false)
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Required on older Android versions")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private fun onLocation(loc: Location, stale: Boolean) {
        val p = LatLon(loc.latitude, loc.longitude)
        val acc = if (loc.hasAccuracy()) loc.accuracy else 50f
        fix = Fix(p, acc, SystemClock.elapsedRealtime())
        // An old cached position shouldn't move us to a different hole.
        if (!stale) tracker.onLocation(p, acc, SystemClock.elapsedRealtime())
        changed()
    }
}
