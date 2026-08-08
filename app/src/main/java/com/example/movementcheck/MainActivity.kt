package com.example.movementcheck

import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.liftright.repdetection.CalibrationOffset
import com.liftright.repdetection.CsvImuLoader
import com.liftright.repdetection.Exercise
import com.liftright.repdetection.example.RepDetectorViewModel
import com.liftright.repdetection.metawear.MetaWearConnectionState
import com.liftright.repdetection.metawear.MetaWearImuSource
import com.liftright.repdetection.sim.OnboardImuSource
import com.liftright.repdetection.sim.ReplayImuSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Binocular rep-count display for the RayNeo X3 Pro glasses.
 *
 * Fully operable from the temple touchpad: swipe forward/back to move the focus
 * highlight between controls, tap to activate the focused control (RayNeo's standard
 * swipe-to-move / tap-to-select gesture model). A settings toggle picks hands-free vs
 * manual set-marking detection (see [AppSettings]). CSV replay stays as a dev panel.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: RepDetectorViewModel by viewModels()
    private val nav = NavController()

    // Touchpad gesture tracking (displacement-based; see dispatchTouchEvent).
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Color.Black, surface = Color.Black)) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    RepCounterScreen(
                        viewModel = viewModel,
                        nav = nav,
                        // Dev hook for on-device testing without the touchpad/display:
                        //   adb shell am start -n .../.MainActivity --ez autoreplay true
                        autoReplay = intent?.getBooleanExtra("autoreplay", false) == true,
                        // Optional --ei speed N: replays faster than real time. Detection is
                        // sample-driven, so only the pacing changes, not the result.
                        replaySpeed = (intent?.getIntExtra("speed", 1) ?: 1).coerceAtLeast(1).toDouble(),
                        readCsv = { uri, onResult ->
                            lifecycleScope.launch {
                                val axes = withContext(Dispatchers.IO) {
                                    contentResolver.openInputStream(uri)?.use { CsvImuLoader.parse(it) }
                                        ?: emptyList()
                                }
                                onResult(axes)
                            }
                        },
                    )
                }
            }
        }
    }

    /**
     * The RayNeo X3 Pro temple touchpad reaches apps as a TOUCHSCREEN whose coordinates
     * span the display — verified on-device: single-touch DOWN/MOVE/UP MotionEvents with
     * source 0x5002 (a RayNeo-flagged touchscreen) and NO key events, contrary to the
     * vendor guide's claim that the temple emits button events.
     *
     * So temple gestures are recognised here from the touch stream (see [handleTouchpad]):
     * a swipe moves the focus highlight, a tap activates the focused control. Touchpad
     * events are consumed so they don't also fire whatever Compose control happens to sit
     * under the mapped screen coordinate; ordinary touchscreen input (e.g. the emulator)
     * passes through so on-screen controls stay tappable during development.
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if ((event.source and RAYNEO_TOUCHPAD_FLAG) != 0) {
            handleTouchpad(event)
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    /**
     * Displacement-based gesture recognition for the temple touchpad (more reliable than
     * velocity/fling for deliberate, slow swipes on a small pad): a short, near-stationary
     * touch is a tap (activate); a longer drag is a swipe whose dominant axis picks the
     * direction (move focus). Coordinates are in the touchpad's display-mapped space.
     */
    private fun handleTouchpad(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y; downTime = event.eventTime
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                val dy = event.y - downY
                val dt = event.eventTime - downTime
                val moved = abs(dx).coerceAtLeast(abs(dy))
                when {
                    moved < TAP_SLOP_PX && dt < TAP_MAX_MS -> {
                        android.util.Log.d("TouchpadGesture", "TAP -> '${nav.focusedId}'")
                        nav.activate()
                    }
                    moved >= SWIPE_MIN_PX -> {
                        // Verified on the X3 Pro: swipe forward (toward lenses) = +dx, backward = -dx.
                        // Forward/down advances to the next control; backward/up goes to previous.
                        if (abs(dx) >= abs(dy)) { if (dx > 0f) nav.next() else nav.prev() }
                        else { if (dy > 0f) nav.next() else nav.prev() }
                        android.util.Log.d("TouchpadGesture", "SWIPE dx=$dx dy=$dy")
                    }
                }
            }
        }
    }

    // Kept for the emulator and for any firmware/dock that does deliver D-pad keys; the
    // real glasses use the touch path above.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                in NEXT_KEYCODES -> { nav.next(); return true }
                in PREV_KEYCODES -> { nav.prev(); return true }
                in TAP_KEYCODES -> { nav.activate(); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        // The RayNeo temple touchpad reports source 0x5002 == SOURCE_TOUCHSCREEN | 0x4000
        // (verified on ARGF20/MercuryLiteXR). This flag distinguishes it from an ordinary
        // touchscreen (emulator = 0x1002) so we only gesture-route the glasses touchpad.
        private const val RAYNEO_TOUCHPAD_FLAG = 0x4000
        // Touchpad maps to the ~1280x480 display space; thresholds are in that space.
        private const val TAP_SLOP_PX = 60f    // max movement to still count as a tap
        private const val TAP_MAX_MS = 400L    // max duration to count as a tap
        private const val SWIPE_MIN_PX = 90f   // min movement to count as a swipe
        private val NEXT_KEYCODES = setOf(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN)
        private val PREV_KEYCODES = setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP)
        private val TAP_KEYCODES = setOf(
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_SPACE,
        )
    }
}

/**
 * Focus ids for touchpad navigation.
 *
 * Each control names itself twice — once where it is registered with [NavController] and
 * once where it draws its focus ring — and the two must agree or focus silently skips the
 * control. Naming them here makes that a compile error instead of a typo nobody notices.
 */
/**
 * How long a form cue stays on screen. Long enough to read and adjust on the next rep,
 * short enough that it is gone before the following rep would replace it anyway — reps in
 * these sessions run about 1.7 s with a few seconds between them.
 */
private const val FORM_CUE_MS = 5000L

/**
 * Stop showing rest after this long. Past ten minutes the lifter has finished, walked off, or
 * forgotten the app is running, and a number that sits lit forever is exactly what a
 * see-through display cannot afford.
 */
private const val REST_MAX_S = 600

/**
 * How long the end-of-set recap stays up. Long enough to read a graph and a fault line while
 * catching your breath; short enough that the pane is clear again well before the next set.
 */
private const val SET_RECAP_MS = 12_000L

/**
 * How wide content may be inside one 640 px eye pane.
 *
 * Only the middle of each pane is seen by both eyes; content out towards the pane edges is
 * monocular, so one eye sees an edge the other does not and the image ghosts — which reads as
 * doubling and leaves the eyes hunting for something to fuse. Measured on-device: the panes
 * are exactly 640 px apart and dead centred, so comfort is purely a question of how far from
 * the centre anything is drawn. A 400 px-wide graph reached to within 120 px of the pane
 * edge and was reported as eye-crossing; 320 keeps everything inside +/-160 px of centre.
 */
private val PANE_MAX_W = 320.dp

private object NavId {
    const val EXERCISE = "exercise"
    const val CONNECT = "connect"
    const val SETTINGS = "settings"
    const val OPT_CAPTURE = "opt_capture"
    const val OPT_DEV = "opt_dev"
    const val OPT_FORM = "opt_form"
    const val OPT_AUDIO = "opt_audio"
    const val OPT_REST = "opt_rest"
    const val OPT_DETAIL = "opt_detail"
    const val RECAP_CLOSE = "recap_close"
    const val WORKOUT = "workout"
    const val HISTORY = "history"
    const val HISTORY_CLOSE = "history_close"
    /** One id per listed workout; the index is appended. */
    const val HISTORY_ROW = "history_row_"
    const val SUMMARY_CLOSE = "summary_close"
    const val HANDS_FREE = "handsfree"
    const val SET = "set"
    const val CAPTURE = "capture"
    const val CALIBRATE = "calibrate"
    const val CSV_TOGGLE = "csvtoggle"
    const val ACCEL = "accel"
    const val GYRO = "gyro"
    const val RUN = "run"
    const val FOLDER = "folder"
    const val STREAM = "stream"
    const val ONBOARD = "onboard"
}

/** Short label for the compact exercise button — the enum names are too wide for it. */
private fun Exercise.shortLabel() = when (this) {
    Exercise.BENCH_PRESS -> "BENCH"
    Exercise.SHOULDER_PRESS -> "SHOULDER"
    Exercise.LAT_PULLDOWN -> "LAT"
}

/** Next exercise in the list, wrapping — the compact button cycles rather than expanding. */
private fun Exercise.next(): Exercise {
    val all = Exercise.values()
    return all[(ordinal + 1) % all.size]
}

/** Everything an [EyePane] needs to render; built once per composition, shown in both eyes. */
private data class PaneState(
    val exercise: Exercise,
    /** Advance to the next exercise; the compact button cycles rather than listing them. */
    val onCycleExercise: () -> Unit,
    val connectLabel: String,
    val isConnected: Boolean,
    val onConnectClick: () -> Unit,
    val handsFree: Boolean,
    val onToggleHandsFree: () -> Unit,
    val setActive: Boolean,
    val onToggleSet: () -> Unit,
    val showCsvPanel: Boolean,
    val onToggleCsvPanel: () -> Unit,
    /** Settings list is open; the workout controls give way to it. */
    val settingsOpen: Boolean,
    val onToggleSettings: () -> Unit,
    /** Whether each optional control group is allowed on the main screen at all. */
    val showCapture: Boolean,
    val onToggleShowCapture: () -> Unit,
    val showDevPanel: Boolean,
    val onToggleShowDevPanel: () -> Unit,
    val showFormFeedback: Boolean,
    val onToggleShowFormFeedback: () -> Unit,
    val audioCues: Boolean,
    val onToggleAudioCues: () -> Unit,
    val restTimer: Boolean,
    val onToggleRestTimer: () -> Unit,
    val showDetail: Boolean,
    val onToggleShowDetail: () -> Unit,
    /** Set just finished; its recap takes the pane briefly. */
    val setRecap: com.liftright.repdetection.example.RepDetectorViewModel.SetRecap?,
    val onDismissRecap: () -> Unit,
    /** Seconds of rest so far, or null when not resting / the timer is off. */
    val restSeconds: Int?,
    /** A workout is running; the control reads "end" rather than "start". */
    val workoutActive: Boolean,
    val onToggleWorkout: () -> Unit,
    /** Non-null once a workout ends: the summary takes over the pane until dismissed. */
    val summary: com.liftright.repdetection.workout.WorkoutSummary?,
    val onCloseSummary: () -> Unit,
    /** Saved workouts on disk, newest first, and whether their list is open. */
    val historyCount: Int,
    val historyOpen: Boolean,
    val history: List<com.liftright.repdetection.workout.WorkoutSummary>,
    val onOpenHistory: () -> Unit,
    val onCloseHistory: () -> Unit,
    val onPickHistory: (com.liftright.repdetection.workout.WorkoutSummary) -> Unit,
    /** The one thing to fix right now, or null when clean / expired / switched off. */
    val formHeadline: String?,
    /** Set-level trend, shown under the headline and only while there is one. */
    val formDetail: String?,
    val accelLabel: String,
    val gyroLabel: String,
    val onLoadAccel: () -> Unit,
    val onLoadGyro: () -> Unit,
    val onRunCsv: () -> Unit,
    val canRunCsv: Boolean,
    val isProcessingCsv: Boolean,
    val onStreamCsv: () -> Unit,
    val isStreaming: Boolean,
    val onLoadFromFolder: () -> Unit,
    val onboardLabel: String,
    val onToggleOnboard: () -> Unit,
    val repCount: Int,
    /** Set in progress, 0 before the first rep of the session. */
    val currentSet: Int,
    /** Reps in the current set — the big number. [repCount] is the session total. */
    val repsInCurrentSet: Int,
    val recording: Boolean,
    /** Mid-set: everything except the set and rep numbers is hidden. */
    val setInProgress: Boolean,
    /** Shown briefly when a counted rep is withdrawn, so the number never drops silently. */
    val retractionNote: String?,
    val capturing: Boolean,
    val captureLabel: String,
    val onToggleCapture: () -> Unit,
    val onCalibrate: () -> Unit,
    val focusedId: String?,
)

@Composable
fun RepCounterScreen(
    viewModel: RepDetectorViewModel,
    nav: NavController,
    readCsv: (Uri, (List<CsvImuLoader.Axes>) -> Unit) -> Unit,
    autoReplay: Boolean = false,
    replaySpeed: Double = 1.0,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val settings = remember { AppSettings(context) }
    var handsFree by remember { mutableStateOf(settings.handsFree) }
    LaunchedEffect(handsFree) {
        settings.handsFree = handsFree
        viewModel.setHandsFree(handsFree)
    }

    val imuSource = remember { MetaWearImuSource(context) }
    val connectionState by imuSource.state.collectAsStateWithLifecycle()

    var exercise by remember { mutableStateOf(Exercise.BENCH_PRESS) }

    // Sensor-less test sources: the glasses' own IMU (live hardware) and a real-time CSV
    // replay. Both feed the SAME onImuSample path as the BLE sensor, so the live pipeline
    // can be exercised end to end without the physical MetaWear.
    val onboardSource = remember { OnboardImuSource(context) }
    val replaySource = remember { ReplayImuSource() }
    var onboardActive by remember { mutableStateOf(false) }
    var isStreaming by remember { mutableStateOf(false) }

    // The glasses' own IMU, read purely as context for the summary: how still the head was
    // during each rep. A second, independent registration on the same hardware, because it
    // must not share a callback with the dev-panel source above — that one feeds the
    // detector, and head motion must never reach it. (With the dev toggle on, both roles do
    // end up on the same sensor, which makes head data meaningless; it is a dev path only.)
    val headSource = remember { OnboardImuSource(context) }

    DisposableEffect(imuSource, onboardSource, replaySource, headSource) {
        val forward: (com.liftright.repdetection.ImuSample) -> Unit = { sample ->
            viewModel.onImuSample(
                sample.accX, sample.accY, sample.accZ,
                sample.gyroX, sample.gyroY, sample.gyroZ,
            )
        }
        imuSource.bindService()
        imuSource.setOnSample(forward)
        onboardSource.setOnSample(forward)
        replaySource.setOnSample(forward)
        replaySource.setOnOrientation { pitch -> viewModel.onOrientationSample(pitch) }
        headSource.setOnSample { s -> viewModel.onHeadSample(s.gyroX, s.gyroY, s.gyroZ) }
        onDispose {
            replaySource.stop()
            onboardSource.stop()
            headSource.stop()
            imuSource.disconnect()
            imuSource.unbindService()
        }
    }

    // Head tracking runs only inside a workout: it is only ever read back through the
    // summary, so there is no reason to hold a sensor open the rest of the time.
    LaunchedEffect(uiState.workoutActive) {
        if (uiState.workoutActive) headSource.start(50.0) else headSource.stop()
    }

    LaunchedEffect(connectionState, exercise, handsFree) {
        if (connectionState is MetaWearConnectionState.Connected) {
            viewModel.startSession(exercise, CalibrationOffset.ZERO)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        if (results.values.all { it }) imuSource.startScan()
    }
    val onConnectClick = {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        permissionLauncher.launch(permissions)
    }

    // Raw-IMU capture: writes labelled Oct20-format CSVs to app external storage so live
    // upper-arm sessions become replayable regression fixtures. No permission needed for
    // getExternalFilesDir. Independent of the detector.
    val captureDir = remember { context.getExternalFilesDir("captures") }
    val onToggleCapture = {
        if (uiState.capturing) viewModel.stopCapture()
        else captureDir?.let { viewModel.startCapture(it, "upper_arm") }
        Unit
    }
    val onCalibrate = { viewModel.markCalibration() }

    var showCsvPanel by remember { mutableStateOf(false) }

    // Which non-workout controls are allowed on screen at all, and whether the settings
    // list is currently open. The first two persist; the third is a transient view state.
    var showCapture by remember { mutableStateOf(settings.showCapture) }
    var showDevPanel by remember { mutableStateOf(settings.showDevPanel) }
    var showFormFeedback by remember { mutableStateOf(settings.showFormFeedback) }
    var audioCues by remember { mutableStateOf(settings.audioCues) }
    var restTimer by remember { mutableStateOf(settings.restTimer) }
    var showDetail by remember { mutableStateOf(settings.showDetail) }
    var settingsOpen by remember { mutableStateOf(false) }

    // The recap clears itself: it appears between sets, and the lifter's next action should
    // be the next set, not dismissing a card.
    LaunchedEffect(uiState.setRecap) {
        if (uiState.setRecap != null) {
            kotlinx.coroutines.delay(SET_RECAP_MS)
            viewModel.clearSetRecap()
        }
    }

    // Audio cues. Held open only while switched on, so the app isn't claiming an audio
    // stream it never uses.
    val audio = remember { FormAudioCues() }
    DisposableEffect(audioCues) {
        if (audioCues) audio.start() else audio.stop()
        onDispose { audio.stop() }
    }
    // One sound per judged rep. Keyed on the report, which carries the rep index, so two
    // reps with the same fault still sound twice.
    LaunchedEffect(uiState.lastForm) {
        val report = uiState.lastForm
        if (audioCues && report != null) audio.play(report)
    }

    // Rest timer. Ticks only while it is actually on screen — no wake-ups to advance a
    // number nobody is looking at.
    var restSeconds by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(restTimer, uiState.setInProgress, uiState.lastRepAtMs) {
        if (!restTimer || uiState.setInProgress || uiState.lastRepAtMs == 0L) {
            restSeconds = null
            return@LaunchedEffect
        }
        // In hands-free mode the set only ends once the idle gap elapses, so the timer first
        // appears already reading ~20 s. That is the true rest so far, not a glitch.
        while (true) {
            val elapsed = ((System.currentTimeMillis() - uiState.lastRepAtMs) / 1000).toInt()
            if (elapsed > REST_MAX_S) {
                // Past this the lifter has moved on; stop lighting a number about it.
                restSeconds = null
                return@LaunchedEffect
            }
            restSeconds = elapsed.coerceAtLeast(0)
            kotlinx.coroutines.delay(1000)
        }
    }
    var accelAxes by remember { mutableStateOf<List<CsvImuLoader.Axes>?>(null) }
    var gyroAxes by remember { mutableStateOf<List<CsvImuLoader.Axes>?>(null) }
    var accelFileName by remember { mutableStateOf<String?>(null) }
    var gyroFileName by remember { mutableStateOf<String?>(null) }

    // A recorded orientation trace, when the folder holds one instead of raw IMU. Only one
    // session in the whole dataset ships raw accelerometer+gyro, so most can only be
    // replayed this way.
    var orientationTrace by remember { mutableStateOf<List<Double>?>(null) }

    val accelPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            accelFileName = uri.lastPathSegment
            readCsv(uri) { accelAxes = it }
        }
    }
    val gyroPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            gyroFileName = uri.lastPathSegment
            readCsv(uri) { gyroAxes = it }
        }
    }

    val accel = accelAxes
    val gyro = gyroAxes
    val accelLabel = accelFileName?.let { "${it.substringAfterLast('/')} (${accel?.size ?: 0})" } ?: "Load accelerometer CSV"
    val gyroLabel = gyroFileName?.let { "${it.substringAfterLast('/')} (${gyro?.size ?: 0})" } ?: "Load gyroscope CSV"
    val canRunCsv = accel != null && gyro != null && !uiState.isProcessing
    val onRunCsv = {
        if (accel != null && gyro != null) {
            viewModel.runFromSamples(exercise, CsvImuLoader.zipToImuSamples(accel, gyro))
        }
    }

    // Dev convenience: load accelerometer.csv + gyro.csv straight out of the app's
    // external files dir ("replay" folder), so a test session can be pushed with `adb push`
    // instead of driving the system file picker with the touchpad.
    // Parsing happens on the IO dispatcher, NOT inline. A session file is ~34k lines, and
    // reading it on the main thread blocked long enough to trip Android's 5 s input-dispatch
    // watchdog — an ANR dialog every time a replay was loaded, on the glasses as well as the
    // emulator. Only the state assignment comes back to the main thread.
    val loadScope = rememberCoroutineScope()
    val onLoadFromFolder = {
        loadScope.launch {
            val dir = context.getExternalFilesDir("replay")
            val a = java.io.File(dir, "accelerometer.csv")
            val g = java.io.File(dir, "gyro.csv")
            val o = java.io.File(dir, "orientations.csv")
            val loaded = withContext(Dispatchers.IO) {
                when {
                    a.exists() && g.exists() -> Triple(
                        a.inputStream().use { CsvImuLoader.parse(it) },
                        g.inputStream().use { CsvImuLoader.parse(it) },
                        null,
                    )
                    o.exists() -> Triple(
                        null, null,
                        o.readLines().filter { it.isNotBlank() }.mapNotNull { it.trim().toDoubleOrNull() },
                    )
                    else -> Triple(null, null, null)
                }
            }
            val (acc, gyr, trace) = loaded
            when {
                acc != null && gyr != null -> {
                    accelFileName = "replay/accelerometer.csv"
                    gyroFileName = "replay/gyro.csv"
                    accelAxes = acc; gyroAxes = gyr; orientationTrace = null
                }
                trace != null -> {
                    orientationTrace = trace
                    accelAxes = null; gyroAxes = null
                    accelFileName = "replay/orientations.csv (${trace.size} samples)"
                    gyroFileName = "orientation replay — our filter is bypassed"
                }
                else -> accelFileName = "no CSVs in ${dir?.absolutePath}"
            }
        }
        Unit
    }

    // Real-time replay of the loaded CSVs through the LIVE path (vs "Run", which batches
    // them as fast as possible). Exercises streaming, capture logging and the UI clock.
    val onStreamCsv = {
        val trace = orientationTrace
        when {
            isStreaming -> { replaySource.stop(); isStreaming = false }
            accel != null && gyro != null -> {
                viewModel.startSession(exercise, CalibrationOffset.ZERO)
                isStreaming = true
                replaySource.start(
                    samples = CsvImuLoader.zipToImuSamples(accel, gyro),
                    sampleRateHz = 50.0,
                    onFinished = { isStreaming = false },
                )
            }
            trace != null && trace.isNotEmpty() -> {
                viewModel.startSession(exercise, CalibrationOffset.ZERO)
                isStreaming = true
                replaySource.startOrientation(
                    values = trace,
                    sampleRateHz = 50.0,
                    onFinished = { isStreaming = false },
                )
            }
        }
        Unit
    }

    // Live sensor stand-in: the glasses' own head IMU. Real hardware, real timing — but a
    // different mounting than the upper-arm sensor, so it validates plumbing, not accuracy.
    val onToggleOnboard = {
        if (onboardActive) {
            onboardSource.stop()
            onboardActive = false
        } else if (onboardSource.isAvailable) {
            viewModel.startSession(exercise, CalibrationOffset.ZERO)
            onboardSource.start(50.0)
            onboardActive = true
        }
        Unit
    }

    // Dev: drive the real-time replay from adb (used to validate the live path on the
    // glasses when the display is off / the touchpad isn't reachable). Also turns on raw
    // capture so the run leaves a logged session behind as evidence.
    LaunchedEffect(autoReplay) {
        if (!autoReplay) return@LaunchedEffect
        android.util.Log.i("ReplayTest", "autoreplay: loading CSVs from /replay")
        onLoadFromFolder()
        // The load is asynchronous now, so poll for it rather than assuming a fixed wait is
        // long enough — a fixed sleep would silently start a replay with nothing loaded.
        var waited = 0
        while (accelAxes == null && orientationTrace == null && waited < 10_000) {
            kotlinx.coroutines.delay(200); waited += 200
        }
        // Read the state directly here: the onStreamCsv lambda belongs to an earlier
        // composition and would still see the CSVs as unloaded.
        val a = accelAxes
        val g = gyroAxes
        val trace = orientationTrace
        android.util.Log.i("ReplayTest",
            "autoreplay: loaded accel=${a?.size ?: 0} gyro=${g?.size ?: 0} orientation=${trace?.size ?: 0}")
        if ((a != null && g != null) || (trace != null && trace.isNotEmpty())) {
            captureDir?.let { viewModel.startCapture(it, "replay") }
            // Dev hook: a replay is a workout, so the summary can be exercised end to end.
            viewModel.startWorkout()
            viewModel.startSession(exercise, CalibrationOffset.ZERO)
            isStreaming = true
            val finished: () -> Unit = {
                isStreaming = false
                android.util.Log.i("ReplayTest", "autoreplay: replay finished")
            }
            if (a != null && g != null) {
                replaySource.start(CsvImuLoader.zipToImuSamples(a, g), 50.0, speed = replaySpeed, onFinished = finished)
                android.util.Log.i("ReplayTest", "autoreplay: streaming raw IMU at 50Hz")
            } else {
                replaySource.startOrientation(trace!!, 50.0, speed = replaySpeed, onFinished = finished)
                android.util.Log.i("ReplayTest", "autoreplay: streaming ORIENTATION at 50Hz (our filter bypassed)")
            }
            // Poll the ViewModel directly - lifecycle-aware collection pauses while the
            // glasses' display is off, so the composed uiState would stay stale.
            while (true) {
                kotlinx.coroutines.delay(5000)
                val s = viewModel.uiState.value
                android.util.Log.i(
                    "ReplayTest",
                    "t+${replaySource.emitted / 50}s emitted=${replaySource.emitted} " +
                        "delivered=${viewModel.deliveredSamples} reps=${s.repCount} " +
                        "set=${s.currentSet} inSet=${s.repsInCurrentSet} logged=${s.captureSamples}",
                )
            }
        }
    }

    // Let the withdrawal note show long enough to read, then clear it.
    LaunchedEffect(uiState.retractedCount) {
        if (uiState.justRetracted) {
            kotlinx.coroutines.delay(4000)
            viewModel.clearRetractionFlag()
        }
    }

    // Same for the form cue: long enough to read and act on within the set, then gone.
    // Keyed on the report itself, so a new finding restarts the countdown rather than
    // inheriting the tail of the previous one's.
    LaunchedEffect(uiState.lastForm) {
        if (uiState.lastForm != null) {
            kotlinx.coroutines.delay(FORM_CUE_MS)
            viewModel.clearFormCue()
        }
    }

    val connectLabel = when (val s = connectionState) {
        is MetaWearConnectionState.Idle -> "Connect Sensor"
        is MetaWearConnectionState.Scanning -> "Scanning…"
        is MetaWearConnectionState.Connecting -> "Connecting…"
        is MetaWearConnectionState.Connected -> "Connected: ${s.deviceLabel}"
        is MetaWearConnectionState.Disconnected -> "Disconnected — tap to retry"
        is MetaWearConnectionState.Error -> "Error: ${s.message}"
    }

    // Build the navigable control list (order = focus traversal). Actions are the same
    // ones the on-screen taps invoke, so touchpad and touch stay in sync.
    // Focus traversal must match what is actually on screen, so it is built from the same
    // conditions the UI uses — otherwise a swipe lands on a control nobody can see.
    // Workouts are written next to the captures, one JSON per workout.
    val workoutDir = remember { context.getExternalFilesDir("workouts") }
    val onToggleWorkout = {
        if (uiState.workoutActive) viewModel.endWorkout(workoutDir) else viewModel.startWorkout()
    }

    // Past workouts, read back off disk. Parsing happens on the IO dispatcher: a long history
    // is dozens of files with a traced shape per rep, and reading that on the main thread is
    // exactly what tripped the input-dispatch watchdog when CSVs were loaded inline.
    var historyOpen by remember { mutableStateOf(false) }
    var history by remember {
        mutableStateOf(emptyList<com.liftright.repdetection.workout.WorkoutSummary>())
    }
    // Whether the summary on screen was opened from the history list. Closing it should then
    // go back to that list rather than all the way out to the main screen — the lifter is
    // browsing past workouts, and dumping them to the start each time would make comparing
    // two of them a chore.
    var summaryFromHistory by remember { mutableStateOf(false) }
    val onCloseSummary = {
        viewModel.clearSummary()
        if (summaryFromHistory) {
            summaryFromHistory = false
            historyOpen = true
        }
    }
    val onPickHistory = { w: com.liftright.repdetection.workout.WorkoutSummary ->
        historyOpen = false
        summaryFromHistory = true
        viewModel.showSummary(w)
    }

    // Re-counted whenever a workout ends, so the control appears as soon as there is one.
    LaunchedEffect(uiState.lastSummaryPath, historyOpen) {
        history = withContext(Dispatchers.IO) {
            com.liftright.repdetection.workout.WorkoutHistory.load(workoutDir)
        }
    }

    val items = buildList {
        // The summary owns the pane, so nothing behind it should be reachable by a swipe.
        if (uiState.lastSummary != null) {
            add(NavController.Item(NavId.SUMMARY_CLOSE) { onCloseSummary() })
            return@buildList
        }
        // While the recap is up it is the only thing on screen, so it must also be the only
        // thing a tap can reach — otherwise a temple tap fires whichever control happens to
        // hold focus behind it, invisibly. One item, and it dismisses.
        if (uiState.setRecap != null) {
            add(NavController.Item(NavId.RECAP_CLOSE) { viewModel.clearSetRecap() })
            return@buildList
        }
        // Same for the history list: close first, then one entry per saved workout.
        if (historyOpen) {
            add(NavController.Item(NavId.HISTORY_CLOSE) { historyOpen = false })
            history.forEachIndexed { i, w ->
                add(NavController.Item(NavId.HISTORY_ROW + i) { onPickHistory(w) })
            }
            return@buildList
        }
        // Mid-set the screen shows only the numbers, so there is nothing to focus — except
        // the manual set marker, which is the one control the lifter still needs (it is how
        // the set ends). Leaving hidden controls in this list would let a blind swipe+tap
        // fire something invisible.
        if (uiState.setInProgress) {
            if (!handsFree) add(NavController.Item(NavId.SET) { viewModel.toggleSet() })
            return@buildList
        }

        add(NavController.Item(NavId.CONNECT) { if (connectionState !is MetaWearConnectionState.Connected) onConnectClick() })
        add(NavController.Item(NavId.EXERCISE) { exercise = exercise.next() })
        if (!handsFree) add(NavController.Item(NavId.SET) { viewModel.toggleSet() })
        add(NavController.Item(NavId.WORKOUT) { onToggleWorkout() })
        if (history.isNotEmpty()) add(NavController.Item(NavId.HISTORY) { historyOpen = true })
        add(NavController.Item(NavId.SETTINGS) { settingsOpen = !settingsOpen })

        if (settingsOpen) {
            // Close comes first, matching the on-screen order: it is the way back out, and
            // it is where the focus ring already sits after opening settings.
            add(NavController.Item(NavId.HANDS_FREE) { handsFree = !handsFree })
            add(NavController.Item(NavId.OPT_CAPTURE) {
                showCapture = !showCapture; settings.showCapture = showCapture
            })
            add(NavController.Item(NavId.OPT_DEV) {
                showDevPanel = !showDevPanel; settings.showDevPanel = showDevPanel
            })
            add(NavController.Item(NavId.OPT_FORM) {
                showFormFeedback = !showFormFeedback; settings.showFormFeedback = showFormFeedback
            })
            add(NavController.Item(NavId.OPT_AUDIO) {
                audioCues = !audioCues; settings.audioCues = audioCues
            })
            add(NavController.Item(NavId.OPT_REST) {
                restTimer = !restTimer; settings.restTimer = restTimer
            })
            add(NavController.Item(NavId.OPT_DETAIL) {
                showDetail = !showDetail; settings.showDetail = showDetail
            })
        } else {
            if (showCapture) {
                add(NavController.Item(NavId.CAPTURE) { onToggleCapture() })
                if (uiState.capturing) add(NavController.Item(NavId.CALIBRATE) { onCalibrate() })
            }
            if (showDevPanel) {
                add(NavController.Item(NavId.CSV_TOGGLE) { showCsvPanel = !showCsvPanel })
                if (showCsvPanel) {
                    add(NavController.Item(NavId.ACCEL) { accelPicker.launch("*/*") })
                    add(NavController.Item(NavId.GYRO) { gyroPicker.launch("*/*") })
                    add(NavController.Item(NavId.RUN) { if (canRunCsv) onRunCsv() })
                    add(NavController.Item(NavId.FOLDER) { onLoadFromFolder() })
                    add(NavController.Item(NavId.STREAM) { onStreamCsv() })
                    add(NavController.Item(NavId.ONBOARD) { onToggleOnboard() })
                }
            }
        }
    }
    nav.items = items

    // Keep focus on the set marker in manual mode — on entering it, and again each time a
    // set ends. Without the second case the control list grows back with focus at the top
    // (Connect Sensor), so starting the next set would need a swipe first; with it, a whole
    // workout is just tap-to-start, tap-to-end, repeat.
    LaunchedEffect(handsFree, uiState.setInProgress) {
        if (!handsFree && !uiState.setInProgress) nav.focusId(NavId.SET)
    }

    val paneState = PaneState(
        exercise = exercise,
        onCycleExercise = { exercise = exercise.next() },
        connectLabel = connectLabel,
        isConnected = connectionState is MetaWearConnectionState.Connected,
        onConnectClick = onConnectClick,
        handsFree = handsFree,
        onToggleHandsFree = { handsFree = !handsFree },
        setActive = uiState.setActive,
        onToggleSet = { viewModel.toggleSet() },
        showCsvPanel = showCsvPanel,
        onToggleCsvPanel = { showCsvPanel = !showCsvPanel },
        settingsOpen = settingsOpen,
        onToggleSettings = { settingsOpen = !settingsOpen },
        showCapture = showCapture,
        onToggleShowCapture = { showCapture = !showCapture; settings.showCapture = showCapture },
        showDevPanel = showDevPanel,
        onToggleShowDevPanel = { showDevPanel = !showDevPanel; settings.showDevPanel = showDevPanel },
        showFormFeedback = showFormFeedback,
        audioCues = audioCues,
        onToggleAudioCues = { audioCues = !audioCues; settings.audioCues = audioCues },
        restTimer = restTimer,
        onToggleRestTimer = { restTimer = !restTimer; settings.restTimer = restTimer },
        showDetail = showDetail,
        onToggleShowDetail = { showDetail = !showDetail; settings.showDetail = showDetail },
        setRecap = uiState.setRecap,
        onDismissRecap = { viewModel.clearSetRecap() },
        restSeconds = restSeconds,
        workoutActive = uiState.workoutActive,
        onToggleWorkout = onToggleWorkout,
        summary = uiState.lastSummary,
        historyCount = history.size,
        historyOpen = historyOpen,
        history = history,
        onOpenHistory = { historyOpen = true },
        onCloseHistory = { historyOpen = false },
        onPickHistory = onPickHistory,
        onCloseSummary = onCloseSummary,
        onToggleShowFormFeedback = {
            showFormFeedback = !showFormFeedback; settings.showFormFeedback = showFormFeedback
        },
        // Surface the single most severe finding: mid-set the lifter can act on one cue,
        // not a list. The rest is in the end-of-set summary.
        formHeadline = if (!showFormFeedback) null else uiState.lastForm?.findings
            ?.maxByOrNull { it.severity.ordinal * 10 + it.magnitude }?.message,
        // Set-level trend, in the same telegraphic form as the headline above it.
        formDetail = uiState.setSummary?.let { s ->
            if (s.reps < 3) null
            else buildString {
                append("SET")
                if (s.romFade > 0.12) append("  -${(s.romFade * 100).toInt()}%")
                if (s.slowdown > 1.25) append("  %.1fx".format(s.slowdown))
            }.takeIf { it != "SET" }
        },
        accelLabel = accelLabel,
        gyroLabel = gyroLabel,
        onLoadAccel = { accelPicker.launch("*/*") },
        onLoadGyro = { gyroPicker.launch("*/*") },
        onRunCsv = onRunCsv,
        canRunCsv = canRunCsv,
        isProcessingCsv = uiState.isProcessing,
        onStreamCsv = onStreamCsv,
        isStreaming = isStreaming,
        onLoadFromFolder = onLoadFromFolder,
        onboardLabel = when {
            !onboardSource.isAvailable -> "No built-in IMU on this device"
            onboardActive -> "■ Glasses IMU streaming — tap to stop"
            else -> "▶ Use glasses' own IMU (no sensor needed)"
        },
        onToggleOnboard = onToggleOnboard,
        repCount = uiState.repCount,
        currentSet = uiState.currentSet,
        repsInCurrentSet = uiState.repsInCurrentSet,
        recording = uiState.recording,
        setInProgress = uiState.setInProgress,
        retractionNote = if (uiState.justRetracted) "−1 · that was sitting up, not a rep" else null,
        capturing = uiState.capturing,
        captureLabel = if (uiState.capturing) "● REC ${uiState.captureSamples} samples — tap to stop"
                       else "○ Record raw IMU (upper arm)",
        onToggleCapture = onToggleCapture,
        onCalibrate = onCalibrate,
        focusedId = nav.focusedId,
    )

    // The X3 Pro display is SIDE-BY-SIDE stereo (verified on-device): the left 640px goes
    // to the left eye, the right 640px to the right eye. So the full UI is rendered into
    // EACH half; the two identical halves fuse into one binocular image.
    Row(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        EyePane(modifier = Modifier.weight(1f).fillMaxHeight(), state = paneState)
        EyePane(modifier = Modifier.weight(1f).fillMaxHeight(), state = paneState)
    }
}

/** Draws the focus ring when [focused], so the same control is highlighted in both eyes. */
private fun Modifier.focusRing(focused: Boolean): Modifier =
    if (focused) this.border(2.dp, Color(0xFF9C8CE0), RoundedCornerShape(6.dp)).padding(2.dp)
    else this.padding(2.dp)

@Composable
private fun EyePane(modifier: Modifier, state: PaneState) {
    val compactPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
    fun f(id: String) = state.focusedId == id

    // Keep content in a centered, narrower column so it stays within the binocular overlap
    // zone of the side-by-side stereo display and fuses cleanly (content spread to the
    // outer edges of each eye falls in the monocular periphery and appears doubled).
    Box(modifier = modifier.background(Color.Black), contentAlignment = Alignment.Center) {
    // The summary takes over the whole pane. It is read standing still, after the workout,
    // so it is the one screen here that can afford detail.
    if (state.summary != null) {
        WorkoutSummaryPane(state.summary, f(NavId.SUMMARY_CLOSE), state.onCloseSummary, state.showDetail)
        return@Box
    }
    if (state.historyOpen) {
        HistoryPane(state, ::f)
        return@Box
    }
    // The set recap owns the pane for its few seconds, the same way the summary does.
    if (state.setRecap != null) {
        SetRecapPane(state.setRecap, state.onDismissRecap)
        return@Box
    }
    Column(
        modifier = Modifier
            .widthIn(max = PANE_MAX_W)
            .fillMaxHeight()
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Status bar: set on the left, sensor in the middle, exercise on the right. Kept
        // inside the *content column* rather than the physical pane corners — anything
        // pushed to the outer edge of the side-by-side display falls outside the binocular
        // overlap and reads as a doubled image.
        // A Box, not a Row with SpaceBetween: SpaceBetween would centre the button only
        // *relative to its neighbours*, so it would drift sideways whenever the set number
        // gained a digit or the exercise label changed width. Aligning it to the box's
        // centre pins it to the true middle of the pane no matter what flanks it.
        Box(modifier = Modifier.fillMaxWidth()) {
            // Corner-anchored while there is a status bar to belong to; centred once the
            // rest of it clears, so mid-set the set and rep numbers read as one stacked
            // block instead of a label stranded off to one side.
            Text(
                text = if (state.currentSet > 0) "SET ${state.currentSet}" else "SET —",
                color = if (state.currentSet > 0) Color(0xFF1BAF7A) else Color.White.copy(alpha = 0.35f),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(
                    if (state.setInProgress) Alignment.Center else Alignment.CenterStart
                ),
            )

            // Mid-set the pane strips back to the set and rep numbers. Nothing here is
            // usable while the lifter is under the bar, and on a see-through display each
            // lit control is an object floating between them and it.
            if (!state.setInProgress) {
                Button(
                    onClick = state.onConnectClick,
                    enabled = !state.isConnected,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.align(Alignment.Center).focusRing(f(NavId.CONNECT)),
                ) {
                    Text(state.connectLabel, fontSize = 10.sp, maxLines = 1)
                }

                // One button that cycles, instead of three chips: with only three exercises
                // the cycle costs at most two extra taps and frees the top-right corner.
                Text(
                    text = state.exercise.shortLabel() + " ⟳",
                    color = Color(0xFF9C8CE0),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier.align(Alignment.CenterEnd)
                        .focusRing(f(NavId.EXERCISE)).clickable { state.onCycleExercise() },
                )
            }
        }

        // The prompt to start a set, manual mode only. Once the set is running it goes with
        // everything else — the tap that ends it still works, it just no longer needs a
        // label sitting in front of the bar to say so.
        if (!state.handsFree && !state.setInProgress) {
            Text(
                text = "○ tap to start set",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.focusRing(f(NavId.SET)).clickable { state.onToggleSet() },
            )
        }

        // ---- the count: high on the pane, above the setup controls rather than below ----

        // Tighter mid-set: the set number is directly above the count then, and the two
        // should read as one block rather than two separate readouts.
        Spacer(Modifier.height(if (state.setInProgress) 2.dp else 10.dp))

        // The form cue sits to the LEFT of the count inside a Box rather than in a Row, so
        // the count stays put: a Row would re-centre the number every time a cue appeared
        // or expired, and a rep counter that jumps sideways mid-set is worse than no cue.
        // This is the one overlay that survives the mid-set strip-back, because it is the
        // only thing here the lifter can act on while under the bar.
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                text = state.repsInCurrentSet.toString(),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 64.sp,
            )

            if (state.formHeadline != null) {
                Column(
                    modifier = Modifier.align(Alignment.CenterStart).fillMaxWidth(0.42f),
                    horizontalAlignment = Alignment.Start,
                ) {
                    Text(
                        text = state.formHeadline,
                        color = Color(0xFFE34948),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                    if (state.formDetail != null) {
                        Text(
                            text = state.formDetail,
                            color = Color(0xFFE34948).copy(alpha = 0.7f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        // Rest, counting up from the last rep. Sits directly under the count because that is
        // where the eye already is between sets, and it is never drawn during one.
        if (state.restSeconds != null) {
            Text(
                text = "REST  %d:%02d".format(state.restSeconds / 60, state.restSeconds % 60),
                color = Color(0xFF9C8CE0),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        // The big number is already the current set's count and the set number is in the
        // status bar, so the caption carries only what neither of those says: the session total.
        Text(
            text = "${state.repCount} TOTAL",
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )

        // A count that drops on its own is alarming unless it says why.
        if (state.retractionNote != null) {
            Text(
                text = state.retractionNote,
                color = Color(0xFFEDA100),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        // Being mid-set already says the detector is live, so the indicator is redundant
        // exactly when the screen is meant to be at its quietest.
        if (state.handsFree && state.recording && !state.setInProgress) {
            Text(
                text = "● RECORDING",
                color = Color(0xFFE34948),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        // Setup controls vanish entirely mid-set — see the note on the status bar above.
        if (state.setInProgress) return@Column

        Spacer(Modifier.height(10.dp))

        // Everything below is setup, not workout information, so it scrolls. The glasses
        // give each eye a short pane (480 px tall) and with the dev panel open this content
        // is taller than that; scrolling keeps it from pushing the count off the display.
        Column(
            modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {

        // The settings list replaces the controls rather than sitting above them: this pane
        // is 480 px tall and cannot show both without pushing the count off the display.
        if (state.settingsOpen) {
            // Close sits ABOVE the options, in the same place the ⚙ entry occupied when the
            // list was shut — so the control you just tapped doesn't move out from under you,
            // and the way out is the first thing you meet on the way in.
            Text(
                text = "✕ close settings",
                color = Color(0xFF9C8CE0),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.focusRing(f(NavId.SETTINGS)).clickable { state.onToggleSettings() },
            )
            Spacer(Modifier.height(8.dp))

            SettingToggle("Hands-free", state.handsFree,
                if (state.handsFree) "auto-counts reps" else "tap touchpad per set",
                f(NavId.HANDS_FREE), state.onToggleHandsFree)
            SettingToggle("Capture controls", state.showCapture,
                "record raw IMU to disk",
                f(NavId.OPT_CAPTURE), state.onToggleShowCapture)
            SettingToggle("Dev / CSV panel", state.showDevPanel,
                "replay recorded sessions",
                f(NavId.OPT_DEV), state.onToggleShowDevPanel)
            SettingToggle("Form feedback", state.showFormFeedback,
                "flag bad reps as you lift",
                f(NavId.OPT_FORM), state.onToggleShowFormFeedback)
            SettingToggle("Audio cues", state.audioCues,
                "beep on a bad rep",
                f(NavId.OPT_AUDIO), state.onToggleAudioCues)
            SettingToggle("Rest timer", state.restTimer,
                "time between sets",
                f(NavId.OPT_REST), state.onToggleRestTimer)
            SettingToggle("Detailed summary", state.showDetail,
                "rep graph + fault locations",
                f(NavId.OPT_DETAIL), state.onToggleShowDetail)
        } else {

        if (state.showCapture) {
            Text(
                text = state.captureLabel,
                color = if (state.capturing) Color(0xFFE34948) else Color(0xFF9C8CE0),
                fontSize = 10.sp,
                fontWeight = if (state.capturing) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                modifier = Modifier.focusRing(f(NavId.CAPTURE)).clickable { state.onToggleCapture() },
            )
            if (state.capturing) {
                Text(
                    text = "◎ mark calibration pose",
                    color = Color(0xFF1BAF7A),
                    fontSize = 9.sp,
                    modifier = Modifier.focusRing(f(NavId.CALIBRATE)).clickable { state.onCalibrate() },
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        if (state.showDevPanel) {
        Text(
            text = (if (state.showCsvPanel) "▴ " else "▾ ") + "Dev: CSV testing",
            color = Color(0xFF9C8CE0),
            fontSize = 10.sp,
            modifier = Modifier.focusRing(f(NavId.CSV_TOGGLE)).clickable { state.onToggleCsvPanel() },
        )

        if (state.showCsvPanel) {
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = state.onLoadAccel, contentPadding = compactPadding, modifier = Modifier.focusRing(f(NavId.ACCEL))) {
                    Text("Accel", fontSize = 11.sp)
                }
                Button(onClick = state.onLoadGyro, contentPadding = compactPadding, modifier = Modifier.focusRing(f(NavId.GYRO))) {
                    Text("Gyro", fontSize = 11.sp)
                }
                Button(onClick = state.onRunCsv, enabled = state.canRunCsv, contentPadding = compactPadding, modifier = Modifier.focusRing(f(NavId.RUN))) {
                    Text(if (state.isProcessingCsv) "Running…" else "Run", fontSize = 11.sp)
                }
            }
            Text(state.accelLabel, color = Color.White.copy(alpha = 0.5f), fontSize = 9.sp, maxLines = 1)
            Text(state.gyroLabel, color = Color.White.copy(alpha = 0.5f), fontSize = 9.sp, maxLines = 1)

            Spacer(Modifier.height(4.dp))

            Text(
                text = "⤓ Load CSVs from /replay folder",
                color = Color(0xFF9C8CE0),
                fontSize = 9.sp,
                maxLines = 1,
                modifier = Modifier.focusRing(f(NavId.FOLDER)).clickable { state.onLoadFromFolder() },
            )

            // Sensor-less testing: replay the loaded CSVs in real time through the live
            // path, or stream the device's own IMU. Both bypass the BLE sensor entirely.
            Text(
                text = if (state.isStreaming) "■ Streaming CSV live — tap to stop"
                       else "▶ Stream CSV in real time (live path)",
                color = if (state.isStreaming) Color(0xFFE34948) else Color(0xFF9C8CE0),
                fontSize = 9.sp,
                maxLines = 1,
                modifier = Modifier.focusRing(f(NavId.STREAM)).clickable { state.onStreamCsv() },
            )
            Text(
                text = state.onboardLabel,
                color = Color(0xFF9C8CE0),
                fontSize = 9.sp,
                maxLines = 1,
                modifier = Modifier.focusRing(f(NavId.ONBOARD)).clickable { state.onToggleOnboard() },
            )
        }
        }   // end showDevPanel

        // Start/end the workout. Above settings because it is the one control here that
        // gets used every session, and it reads as an action rather than configuration.
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (state.workoutActive) "■ end workout" else "▶ start workout",
            color = if (state.workoutActive) Color(0xFFE34948) else Color(0xFF1BAF7A),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.focusRing(f(NavId.WORKOUT)).clickable { state.onToggleWorkout() },
        )

        // Past workouts. Only offered when there are some, so a first-time screen stays bare.
        if (state.historyCount > 0) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "▤ history  ${state.historyCount}",
                color = Color(0xFF9C8CE0),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.focusRing(f(NavId.HISTORY)).clickable { state.onOpenHistory() },
            )
        }

        // The way into settings, below the controls it hides.
        Spacer(Modifier.height(6.dp))
        Text(
            text = "⚙ settings",
            color = Color(0xFF9C8CE0),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.focusRing(f(NavId.SETTINGS)).clickable { state.onToggleSettings() },
        )
        }   // end settingsOpen / else

        }   // end scrolling setup section
    }
    }
}

/**
 * The set you just finished, shown for a few seconds while you are still standing over the
 * bar and can still remember the reps.
 *
 * This is where per-rep shape actually earns its screen time: "the fourth one got shallow"
 * means something thirty seconds after the fourth one, and almost nothing at the end of the
 * workout. So the graph lives here by default, and the end-of-workout version of it is
 * behind a setting.
 *
 * Clears itself after a few seconds, and a tap on the glasses' temple dismisses it early —
 * for when you have seen it and want the barbell back. No focus ring: there is exactly one
 * thing a tap can do here, so drawing a target for it would be noise.
 */
@Composable
private fun SetRecapPane(
    recap: com.liftright.repdetection.example.RepDetectorViewModel.SetRecap,
    onDismiss: () -> Unit,
) {
    val faults = recap.reps.flatMap { it.faults }.groupingBy { it }.eachCount()
        .toList().sortedByDescending { it.second }
    val clean = recap.reps.count { it.judged && it.faults.isEmpty() }
    val judged = recap.reps.count { it.judged }

    Column(
        modifier = Modifier
            .widthIn(max = PANE_MAX_W)
            .fillMaxHeight()
            .clickable(
                // No ripple, no indication: this is a whole-pane dismiss target, and a flash
                // across the display is the last thing a see-through screen needs.
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onDismiss() }
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "SET ${recap.setNumber} DONE",
            color = Color(0xFF1BAF7A),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "${recap.reps.size} reps" + if (judged > 0) "   ·   $clean/$judged clean" else "",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        // Taller than the summary's version: there are only a handful of reps here, so each
        // one gets the width and height to actually be read as a shape.
        RepPathGraph(recap.reps, height = 76.dp)
        if (faults.isNotEmpty()) {
            Spacer(Modifier.height(5.dp))
            Text(
                text = faults.joinToString("   ") { "${it.second} ${it.first.name.replace('_', ' ')}" },
                color = Color(0xFFE34948),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * Past workouts, newest first. One line each: when, how much, how clean.
 *
 * A list, not a dashboard. Everything a single workout has to say is already in the summary
 * screen, so this only has to carry enough to pick one out — and picking one opens that same
 * screen rather than a second, thinner rendering of the same data.
 */
@Composable
private fun HistoryPane(state: PaneState, f: (String) -> Boolean) {
    val fmt = remember { java.text.SimpleDateFormat("d MMM  HH:mm", java.util.Locale.getDefault()) }
    Column(
        modifier = Modifier
            .widthIn(max = PANE_MAX_W)
            .fillMaxHeight()
            .padding(10.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "✕ close",
            color = Color(0xFF9C8CE0),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.focusRing(f(NavId.HISTORY_CLOSE)).clickable { state.onCloseHistory() },
        )
        Spacer(Modifier.height(6.dp))
        Text("HISTORY", color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))

        state.history.forEachIndexed { i, w ->
            val pct = w.perfectPct
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRing(f(NavId.HISTORY_ROW + i))
                    .clickable { state.onPickHistory(w) }
                    .padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = fmt.format(java.util.Date(w.startedAtMs)),
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 10.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${w.totalReps}×${w.totalSets}",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(0.5f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
                Text(
                    text = if (pct == null) "  —" else "  $pct%",
                    color = when {
                        pct == null -> Color.White.copy(alpha = 0.4f)
                        pct >= 80 -> Color(0xFF1BAF7A)
                        pct >= 50 -> Color(0xFFEDA100)
                        else -> Color(0xFFE34948)
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(0.5f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

/**
 * End-of-workout readout: headline numbers, the range-of-motion of every rep, and which
 * faults fired where.
 */
@Composable
private fun WorkoutSummaryPane(
    summary: com.liftright.repdetection.workout.WorkoutSummary,
    closeFocused: Boolean,
    onClose: () -> Unit,
    showDetail: Boolean,
) {
    Column(
        modifier = Modifier
            .widthIn(max = PANE_MAX_W)
            .fillMaxHeight()
            .padding(10.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "✕ close",
            color = Color(0xFF9C8CE0),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.focusRing(closeFocused).clickable { onClose() },
        )
        Spacer(Modifier.height(6.dp))

        Text("WORKOUT", color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp, fontWeight = FontWeight.Bold)

        // Headline: the share of judged reps with nothing wrong. Green only when it is
        // actually good, so the colour carries information rather than decoration.
        val pct = summary.perfectPct
        Text(
            text = if (pct == null) "—" else "$pct%",
            color = when {
                pct == null -> Color.White.copy(alpha = 0.5f)
                pct >= 80 -> Color(0xFF1BAF7A)
                pct >= 50 -> Color(0xFFEDA100)
                else -> Color(0xFFE34948)
            },
            fontSize = 44.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "CLEAN REPS  ·  ${summary.perfectReps}/${summary.judgedReps}",
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
        )

        Spacer(Modifier.height(6.dp))
        Text(
            text = "${summary.totalReps} reps  ·  ${summary.totalSets} sets  ·  " +
                "${summary.durationMs / 60000}m ${(summary.durationMs / 1000) % 60}s",
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
        if (summary.repsPerSet.isNotEmpty()) {
            Text(
                text = summary.repsPerSet.joinToString(" · ") { "$it" },
                color = Color.White.copy(alpha = 0.45f),
                fontSize = 10.sp,
            )
        }
        // Opening reps have no baseline yet, so they were never judged. Saying so keeps the
        // percentage above honest rather than quietly counting them as clean.
        if (summary.unjudgedReps > 0) {
            Text(
                text = "${summary.unjudgedReps} not judged (opening reps)",
                color = Color.White.copy(alpha = 0.35f),
                fontSize = 9.sp,
            )
        }

        // One graph, not two: the depth of each dip in the path IS that rep's range, so a
        // separate bar chart of the same numbers was spending scarce lit pixels twice.
        // Per-rep detail is opt-in: by the end of a workout it describes reps from twenty
        // minutes ago, and the end-of-set recap has already shown each set when it mattered.
        if (showDetail && summary.hasShapes) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "REP PATH  ·  ${summary.maxShapeDepth.toInt()}° deep",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(3.dp))
            RepPathGraph(summary.reps)
            SetAxis(summary)
        }

        // No fault breakdown here. Each set's faults were shown in its own recap, seconds
        // after it happened and while they could still be acted on; repeating them at the end
        // as a list of names is a wall of red the lifter can do nothing with. The clean-rep
        // percentage above already says how the session went. The full breakdown is still on
        // disk in the saved workout, and the per-set recaps carry it live.
        // Turn on "Detailed summary" to get the rep graph back on this screen.

        // Head movement, from the glasses' own IMU. Amber, not red: it is context about how
        // the rep was performed, not a barbell-form fault, and the two should not be read as
        // the same kind of thing.
        Spacer(Modifier.height(10.dp))
        Text("HEAD", color = Color.White.copy(alpha = 0.5f), fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(3.dp))
        when {
            summary.headTrackedReps == 0 -> Text(
                text = "not tracked",
                color = Color.White.copy(alpha = 0.35f),
                fontSize = 10.sp,
            )
            summary.headFlaggedReps == 0 -> Text(
                text = "Steady on all ${summary.headTrackedReps} reps",
                color = Color(0xFF1BAF7A),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
            else -> {
                Text(
                    text = "${summary.headFlaggedReps}/${summary.headTrackedReps} reps moved",
                    color = Color(0xFFEDA100),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
                for ((flag, count) in summary.headFlagCounts) {
                    Text(
                        text = "$count  ${flag.label}",
                        color = Color(0xFFEDA100),
                        fontSize = 10.sp,
                    )
                    if (showDetail) {
                        Text(
                            text = locationLine(summary.headLocationsOf(flag)),
                            color = Color(0xFFEDA100).copy(alpha = 0.55f),
                            fontSize = 9.sp,
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                }
            }
        }
        // The raw numbers behind the verdict, so a lifter who wants to know what "moved"
        // meant can see it rather than take the threshold on trust.
        val medianHead = summary.medianHeadExcursionDeg
        val peakHead = summary.peakHeadRateDegS
        if (medianHead != null && peakHead != null) {
            Text(
                text = "typical ${medianHead.toInt()}°  ·  peak ${peakHead.toInt()}°/s",
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 9.sp,
            )
        }

        Spacer(Modifier.height(10.dp))
    }
}

/**
 * Where something happened, as a short list of set/rep labels.
 *
 * Capped, because on a see-through display every lit character sits on top of the room. Past
 * a handful of labels the list stops being something you read and becomes a bright block —
 * the count above it already carries the scale, so the tail is spent as "+N more".
 */
private fun locationLine(labels: List<String>, max: Int = 8): String =
    if (labels.size <= max) labels.joinToString(" ")
    else labels.take(max).joinToString(" ") + "  +${labels.size - max} more"

/**
 * Set numbers under a graph, each centred on the reps it covers.
 *
 * The separator lines say *where* a set ended; this says *which* set, so a fault located at
 * "S2R4" can be found on the graph without counting bars from the left.
 */
@Composable
private fun SetAxis(summary: com.liftright.repdetection.workout.WorkoutSummary) {
    val perSet = summary.repsPerSet
    if (perSet.size < 2) return
    Row(modifier = Modifier.fillMaxWidth()) {
        for ((i, count) in perSet.withIndex()) {
            Text(
                text = "S${i + 1}",
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.weight(count.toFloat().coerceAtLeast(0.001f)),
            )
        }
    }
}

/**
 * The reps themselves: each one's path plotted as depth below the top it started from, laid
 * end to end so a set reads as a row of dips.
 *
 * This is the trace the detector actually works from, not a summary of it — a rep that was
 * lowered fast and hauled back slowly has a visibly lopsided dip, and depth falling away
 * across a set is a staircase rather than a number. Drawn downward from a top line because
 * that is the direction the bar moves.
 *
 * Every rep gets equal width regardless of how long it took. Real elapsed time would make a
 * slow grinding rep three times as wide as a fast one and squeeze the rest into nothing,
 * which buries the shape comparison this graph exists for; tempo is already reported as its
 * own fault.
 */
@Composable
private fun RepPathGraph(
    allReps: List<com.liftright.repdetection.workout.RepRecord>,
    height: androidx.compose.ui.unit.Dp = 60.dp,
) {
    val reps = allReps.filter { it.shape.size >= 3 }
    if (reps.isEmpty()) return
    val maxDepth = (reps.flatMap { it.shape }.maxOrNull() ?: 1.0).coerceAtLeast(1.0).toFloat()
    // Set dividers, derived from the reps actually being drawn — so a single-set recap gets
    // none and a whole workout gets one per boundary, from the same code.
    val boundaries = reps.indices.filter { i -> i > 0 && reps[i].setNumber != reps[i - 1].setNumber }

    androidx.compose.foundation.Canvas(
        modifier = Modifier.fillMaxWidth().height(60.dp),
    ) {
        val n = reps.size
        val setGap = 9f
        // Only boundaries that fall inside the filtered list can be drawn.
        val shown = boundaries.filter { it in 1 until n }
        val repW = (size.width - setGap * shown.size) / n

        fun xOf(i: Int): Float {
            var x = 0f
            for (k in 0 until i) {
                x += repW
                if (k + 1 in shown) x += setGap
            }
            return x
        }

        // The top line is the lockout height every rep is measured against.
        drawLine(
            color = Color.White.copy(alpha = 0.25f),
            start = androidx.compose.ui.geometry.Offset(0f, 1f),
            end = androidx.compose.ui.geometry.Offset(size.width, 1f),
            strokeWidth = 1f,
        )

        // Typical depth for this workout. Carried over from the bar chart this graph
        // replaced: the dips show depth fading, but a fixed line is what makes "shallower
        // than my own normal" readable instead of a judgement about slope.
        val depths = reps.map { r -> r.shape.maxOrNull() ?: 0.0 }.sorted()
        val medianY = (depths[depths.size / 2].toFloat() / maxDepth * size.height)
            .coerceIn(0f, size.height)
        drawLine(
            color = Color.White.copy(alpha = 0.18f),
            start = androidx.compose.ui.geometry.Offset(0f, medianY),
            end = androidx.compose.ui.geometry.Offset(size.width, medianY),
            strokeWidth = 1f,
        )

        reps.forEachIndexed { i, r ->
            val x0 = xOf(i)
            val path = androidx.compose.ui.graphics.Path()
            r.shape.forEachIndexed { k, depth ->
                val px = x0 + repW * k / (r.shape.size - 1).toFloat()
                val py = (depth.toFloat() / maxDepth * size.height).coerceIn(0f, size.height)
                if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            drawPath(
                path = path,
                color = when {
                    r.faults.isNotEmpty() -> Color(0xFFE34948)
                    !r.judged -> Color.White.copy(alpha = 0.35f)
                    else -> Color(0xFF1BAF7A)
                },
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.6f),
            )
            if (i in shown) {
                drawLine(
                    color = Color.White.copy(alpha = 0.35f),
                    start = androidx.compose.ui.geometry.Offset(x0 - setGap / 2, 0f),
                    end = androidx.compose.ui.geometry.Offset(x0 - setGap / 2, size.height),
                    strokeWidth = 1f,
                )
            }
        }
    }
}

/** One row of the settings list: name, state, and a line saying what turning it on does. */
@Composable
private fun SettingToggle(
    label: String,
    on: Boolean,
    subtitle: String,
    focused: Boolean,
    onToggle: () -> Unit,
) {
    Column(
        modifier = Modifier.focusRing(focused).clickable { onToggle() },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = (if (on) "☑ " else "☐ ") + label,
            color = if (on) Color(0xFF1BAF7A) else Color.White.copy(alpha = 0.6f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        // The explanation appears only for the row you are on. Four rows with a permanent
        // subtitle each is eight lines, which overflows the 480 px pane and pushed the last
        // toggle off the bottom where the touchpad could not reach it.
        if (focused) {
            Text(
                text = subtitle,
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 9.sp,
                maxLines = 1,
            )
        }
    }
    Spacer(Modifier.height(5.dp))
}
