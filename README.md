# SpurWing GPS

A personal golf-cart GPS for The Club at SpurWing (Meridian, ID). It shows an overhead
map of the hole you're on, turned so you're looking from tee to green, with yards to the
front, middle and back of the green. It runs on an Android phone that stays in the cart
and shows up on the cart's screen through Android Auto.

Both courses are built in: the 18-hole Championship course and the 9-hole Challenge course.

## What it does

- **Knows which hole you're on.** Pull up to a tee and it switches to that hole. It won't
  flip to another hole just because you drive past that hole's tee mid-round.
- **Front / middle / back yardages** to the green, updated every second. Front reads 0 once
  you're on the green.
- **Zooms in as you go.** From the tee you see the whole hole; out on the fairway the view
  tightens to the stretch between you and the green.
- **100 / 150 / 200 / 250 markers** along the hole (red, white, blue, yellow).
- **Tap to measure.** Tap anywhere on the map to see yards from the cart to that spot and
  from that spot to the middle of the green. Tap the yellow dot again to clear it (it also
  clears after 30 seconds).
- **Scorecard.** The panel has **− / +** buttons for the hole you're on: the first **+**
  puts down par, the first **−** a birdie, then each tap moves one stroke. Your running
  total (strokes, to par, holes played) shows above the buttons. Tap the score itself, or
  the grid button on the cart screen, to open the full scorecard: tap any hole to fix its
  score, and the arrows step through holes. **New round** clears the card (tap it twice).
  Scores are saved, so they survive the app or phone restarting mid-round.
- **Buttons on the cart screen:** previous hole, next hole, scorecard, and the flag
  switches between the Championship and Challenge courses.
- **If the GPS gets it wrong**, use the arrows to pick the right hole. The app stays on
  your pick (the panel shows "Auto hole off") until the GPS actually sees you on that hole,
  then goes back to following you automatically.
- Works with no cell signal. The course map is stored in the app.

## One-time setup

You need a Windows/Mac computer with Android Studio, and the Android phone that will live
in the cart.

### 1. Build and install the app

1. Install [Android Studio](https://developer.android.com/studio) if you don't have it.
2. Unzip this project and choose **File → Open** in Android Studio, then pick the
   `SpurwingGPS` folder. Let it finish "Gradle sync" (the first time it downloads a few
   hundred MB of build tools). If it offers to upgrade the Android Gradle Plugin, it's
   fine to say yes.
3. On the phone, turn on developer options: **Settings → About phone → tap "Build number"
   7 times**. Then in **Settings → System → Developer options**, turn on **USB debugging**.
4. Plug the phone into the computer, accept the "Allow USB debugging?" prompt on the phone,
   pick the phone in Android Studio's device list, and press the green **Run ▶** button.
5. The app opens on the phone. Allow location, and choose **Precise** and **While using the
   app**.

(Alternative to steps 3–4: **Build → Build App Bundle(s) / APK(s) → Build APK(s)**, copy the
APK from `app/build/outputs/apk/debug/` to the phone, and open it to install.)

### 2. Let Android Auto show it

Android Auto only lists Play Store apps unless you allow others:

1. On the phone, open Android Auto's settings (**Settings → Connected devices →
   Connection preferences → Android Auto**, or search Settings for "Android Auto").
2. Scroll to the bottom and tap **Version** about 10 times until it asks to allow
   developer settings. Tap **OK**.
3. Tap the **⋮ menu → Developer settings** and check **Unknown sources**.

### 3. In the cart

Connect the phone to the cart the way you normally use Android Auto. Open the app
launcher on the cart screen and tap **SpurWing GPS**.

If an Android Auto update ever hides the app, repeat step 2.

## Trying it before it's in the cart

Open the app on the phone: it shows the same map and yardages, so you can test it walking or
driving the course. You can also run it in Google's Desktop Head Unit emulator on your
computer ([instructions](https://developer.android.com/training/cars/testing/dhu)).

## Notes and limits

- **"Middle" is the center of the green**, not today's pin position.
- **Hole yardage** in the side panel is the Championship scorecard's back-tee number. The
  live yardages come from your actual position.
- **Accuracy** is whatever the phone's GPS gives, usually within 3–5 yards in the open.
  The current accuracy shows at the bottom of the panel.
- **Phone in the cart:** a phone left in a hot cart can overheat and its battery can swell.
  Keep it out of direct sun and take it inside on hot days.

## Course data

The course map (tees, fairways, greens, bunkers, water and hole routings) comes from
OpenStreetMap, © OpenStreetMap contributors, available under the Open Database License
(https://www.openstreetmap.org/copyright). It's stored in `app/src/main/assets/spurwing.txt`.
If the course changes, the map can be edited on openstreetmap.org and re-exported into
that file.

## Project layout

- `course/` – course data parsing, distance math, hole tracking and scorecard (plain Kotlin, unit tested)
- `render/HoleRenderer.kt` – draws the hole map and yardage/score panel
- `render/ScorecardRenderer.kt` – draws the full scorecard
- `car/` – the Android Auto screen
- `phone/MainActivity.kt` – the phone screen
- `GolfState.kt` – shared state and GPS
