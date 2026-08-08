# Movement Check — Basic Framework

A minimal Kotlin/Jetpack Compose Android app skeleton for the RayNeo X3 Pro.
It displays a single indicator (● waiting / ✓ correct / ✕ incorrect) driven
by one state variable, so you have a working shell to wire your real
movement-detection pipeline into.

## What's here

- `MainActivity.kt` — the whole app. Key pieces:
  - `MovementResult` — the 3-state enum (`UNKNOWN`, `CORRECT`, `INCORRECT`)
    your detection logic should set.
  - `MovementStateHolder` — a `StateFlow` wrapper. Call `.update(result)`
    from anywhere (a camera callback, a coroutine, etc.) and the UI
    recomposes automatically.
  - `MovementIndicator` — the Compose UI that turns the enum into a
    symbol + color.
  - The `lifecycleScope.launch { ... }` block in `onCreate` is a
    **placeholder** that cycles through states every 2 seconds so you can
    confirm the UI works before any real detection is hooked up. Delete it
    once you have real data feeding `movementState.update(...)`.

## What's NOT here (on purpose)

- **RayNeo ARDK integration** — there's a `TODO` in `build.gradle.kts` and
  `AndroidManifest.xml` for where the ARDK dependency and any required
  permissions/manifest entries go. You'll need RayNeo's actual Maven repo
  URL / `.aar` and manifest requirements from their Open Platform docs to
  fill this in — that wasn't publicly available to fill in for you here.
- **Camera + pose detection wiring** — `camera-core`/`camera-camera2`/
  `mlkit:pose-detection` are added as dependencies so you're ready to go,
  but the actual camera capture → pose landmarks → partial-movement
  classifier pipeline isn't implemented yet. That's the next real piece
  of work: replace the placeholder loop with something like:

  ```
  CameraX frame -> PoseDetector.process(image) -> extract joint angles
      -> feed into your DTW/RNN classifier -> movementState.update(result)
  ```

## Running it

This needs Android Studio (or `./gradlew assembleDebug` from the command
line) with Android SDK + NDK installed, since it wasn't built/compiled in
this environment. Open the project root in Android Studio, let Gradle
sync, fill in the ARDK dependency, then build/deploy via `adb install` to
a Creator-Mode-enabled X3 Pro.
