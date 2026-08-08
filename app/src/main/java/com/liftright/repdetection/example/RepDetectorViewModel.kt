package com.liftright.repdetection.example

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.liftright.repdetection.CalibrationOffset
import com.liftright.repdetection.Exercise
import com.liftright.repdetection.ImuSample
import com.liftright.repdetection.MultiValleyRepDetector
import com.liftright.repdetection.PostureVerifier
import com.liftright.repdetection.RepDetector
import com.liftright.repdetection.RepFilters
import com.liftright.repdetection.Repetition
import com.liftright.repdetection.SetGrouper
import com.liftright.repdetection.capture.ImuSessionLogger
import com.liftright.repdetection.form.DeferredFormMetrics
import com.liftright.repdetection.form.FormAnalyzer
import com.liftright.repdetection.head.HeadMotionTracker
import com.liftright.repdetection.workout.RepRecord
import com.liftright.repdetection.workout.WorkoutSummary
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Wires the rep-detection pipeline into an Android screen.
 *
 * Two detection modes, selected by [setHandsFree]:
 *  - Hands-free (default): the sliding [RepDetector] runs continuously and a
 *    [RepFilters.LiveIsolationGate] suppresses isolated between-set false positives. No
 *    user input per set. (~15 rep-count error on the 16-subject validation set.)
 *  - Manual set-marking: the [MultiValleyRepDetector] runs, and reps are only counted
 *    while the user has marked a set active (via [toggleSet], driven by a glasses
 *    touchpad tap). Multi-valley recovers under-counted fast reps while the active-set
 *    gate removes its excess. (~5 error, but two taps per set.)
 *
 * The BLE / sensor layer calls [onImuSample] for every reading (accel in g, gyro in
 * deg/s).
 */
class RepDetectorViewModel : ViewModel() {

    data class UiState(
        val repCount: Int = 0,
        val recording: Boolean = false,   // hands-free: activity gate
        val lastRep: Repetition? = null,
        val reps: List<Repetition> = emptyList(),
        val isProcessing: Boolean = false,
        val handsFree: Boolean = true,
        val setActive: Boolean = false,   // manual mode: a set is currently marked active
        val capturing: Boolean = false,   // raw-IMU logging to disk is active
        val capturePath: String? = null,  // directory of the current/last capture
        val captureSamples: Int = 0,      // samples written in the current capture
        /** 1-based number of the set in progress, 0 before the first rep. */
        val currentSet: Int = 0,
        /** Reps counted so far in the current set — the number the display leads with. */
        val repsInCurrentSet: Int = 0,
        /** Sets seen so far this session. */
        val setCount: Int = 0,
        /**
         * A set is under way — a rep landed recently enough that the lifter is still in it.
         * The screen strips back to the set and rep numbers while this holds.
         */
        val setInProgress: Boolean = false,
        /**
         * Wall-clock time of the most recent counted rep, 0 before the first one. The rest
         * timer counts up from here.
         *
         * Wall clock rather than the sample index the rest of the set logic uses: rest is
         * measured while the lifter is doing nothing, and if the sensor drops out mid-rest
         * the sample counter stops advancing while real time does not. A rest timer that
         * pauses when the sensor hiccups would be worse than none.
         */
        val lastRepAtMs: Long = 0L,
        // Form feedback for the most recent rep, plus a running tally for the set.
        val lastForm: FormAnalyzer.RepReport? = null,
        val setFaults: Map<FormAnalyzer.Fault, Int> = emptyMap(),
        val setSummary: FormAnalyzer.SetSummary? = null,
        /** Counts as reps are withdrawn after the fact (lying down / sitting up). */
        val retractedCount: Int = 0,
        /** Set briefly when a rep is withdrawn, so the UI can say why the number dropped. */
        val justRetracted: Boolean = false,
        /** A workout is running; reps are being filed into the summary. */
        val workoutActive: Boolean = false,
        /** Set when a workout ends, and cleared when the summary screen is dismissed. */
        val lastSummary: WorkoutSummary? = null,
        /** Where the summary was written, or null if the save failed. */
        val lastSummaryPath: String? = null,
        /** Set just finished — its recap owns the screen for a few seconds. */
        val setRecap: SetRecap? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    // Batch samples so the detector runs on chunks rather than every single reading.
    private val buffer = ArrayList<ImuSample>(16)

    private var handsFree = true
    private var setActive = false
    private var exercise = Exercise.BENCH_PRESS
    private var offset = CalibrationOffset.ZERO

    // Optional raw-IMU capture. Independent of the detector: it just tees off the sample
    // stream and the set/rep events so a live workout is saved as a replayable, labelled
    // dataset (see [ImuSessionLogger]). Null when not recording.
    private var logger: ImuSessionLogger? = null

    // Form analysis runs alongside detection: the detector reports each rep's landmarks,
    // the analyzer judges it against the lifter's own earlier reps.
    /**
     * Withdraws a rep that a few seconds of hindsight shows was the lifter lying down or
     * sitting up. Reps are shown the instant they are detected and taken back only if this
     * fires, so there is no waiting for normal reps.
     */
    private var verifier = buildVerifier()

    private fun buildVerifier() = PostureVerifier(sampleRateHz = 50.0) { rep ->
        _uiState.update { state ->
            val remaining = state.reps.filter { it.liftStartIndex != rep.liftStartIndex }
            if (remaining.size == state.reps.size) state else {
                // Re-derive the sets from what's left: if the withdrawn rep was the only
                // one in its set, that set disappears along with it.
                val g = setGrouper.group(remaining)
                state.copy(
                    reps = remaining,
                    repCount = remaining.size,
                    lastRep = remaining.lastOrNull(),
                    currentSet = g.currentSet,
                    repsInCurrentSet = g.repsInCurrentSet,
                    setCount = g.setCount,
                    retractedCount = state.retractedCount + 1,
                    justRetracted = true,
                )
            }
        }
        logger?.logMarker("rep_withdrawn", "liftStart=${rep.liftStartIndex}")
    }

    // Sets are inferred from the rest between counted reps rather than marked by the user,
    // so hands-free mode gets a set number too. See [SetGrouper] for why it re-derives.
    private val setGrouper = SetGrouper(sampleRateHz = 50.0)

    // "Still in a set" = a rep landed within the last [SET_IDLE_S] seconds. Deliberately
    // shorter than SetGrouper's 25 s rest threshold, so the display comes back before the
    // grouper would decide the next rep starts a new set. Driven off rep timing rather than
    // the detector's activity gate, which flickers between reps.
    private var lastRepSample = -1
    private val setIdleSamples = (SET_IDLE_S * 50.0).toInt()

    /**
     * Recompute the in-set flag; only touches state when it actually flips.
     *
     * The two modes decide this differently, because they decide a *set* differently:
     *  - manual: the user's mark is the set. It begins on the tap, before any rep exists,
     *    and ends on the next tap — rep timing must not override either edge.
     *  - hands-free: there is no mark, so a set is "a rep landed recently". It therefore
     *    begins at the first counted rep and ends on its own once the rest gap elapses,
     *    which is the automatic end-of-set.
     */
    private fun refreshSetInProgress() {
        val inSet =
            if (handsFree) {
                val since = _uiState.value.lastRepAtMs
                lastRepSample >= 0 &&
                    sampleCount - lastRepSample < setIdleSamples &&
                    // ...and the same gap in real time. The sample counter only advances
                    // while the sensor is delivering, so on a dropout — a dead battery, a
                    // BLE drop, the end of a replay — it freezes wherever it was. If it
                    // froze mid-set the screen stays stripped to the set and rep numbers
                    // with no controls on it at all, which leaves the workout impossible to
                    // end. Real time keeps running when samples do not.
                    since > 0L && System.currentTimeMillis() - since < SET_IDLE_MS
            } else setActive
        val was = _uiState.value.setInProgress
        if (inSet != was) {
            // A set just ended: show what it looked like while the lifter is still standing
            // there. Entering a set clears any recap still on screen.
            val recap = if (was && !inSet) buildSetRecap() else null
            _uiState.update { it.copy(setInProgress = inSet, setRecap = recap) }
        }
    }

    /**
     * Re-checks the in-set flag once a second while a set is running.
     *
     * Without this the wall-clock half of [refreshSetInProgress] would never be evaluated
     * during a dropout, because every other caller is driven by an arriving sample — the
     * exact thing that has stopped. Stops itself as soon as the set ends, so it costs
     * nothing between sets.
     */
    private var idleWatch: kotlinx.coroutines.Job? = null

    private fun startIdleWatch() {
        if (idleWatch?.isActive == true) return
        idleWatch = viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(1000)
                refreshSetInProgress()
                if (!_uiState.value.setInProgress) break
            }
        }
    }

    /**
     * Add newly-confirmed reps and re-derive the set grouping. When the reps open a new
     * set, the form baseline restarts with it — otherwise later sets are judged against
     * the first set's numbers and normal fatigue reads as form breakdown.
     *
     * Safe with respect to [DeferredFormMetrics]: it reports a rep once the lift tops out,
     * and at the very latest at its hard cap of 8 s after the bottom of the rep. A new set
     * is by definition [SetGrouper.restSeconds] (25 s) later, so the previous set's last rep
     * is always measured before the baseline resets.
     */
    private fun addReps(added: List<Repetition>) {
        if (added.isEmpty()) return
        val all = _uiState.value.reps + added
        lastRepSample = sampleCount
        val g = setGrouper.group(all)
        // Manual mode already restarts the set on the user's tap; don't do it twice.
        if (handsFree && g.currentSet > _uiState.value.currentSet) {
            form.startSet()
            setFaults.clear()
            logger?.logMarker("set_start", "auto,set=${g.currentSet}")
        }
        _uiState.update { state ->
            state.copy(
                reps = all,
                repCount = all.size,
                lastRep = all.last(),
                currentSet = g.currentSet,
                repsInCurrentSet = g.repsInCurrentSet,
                setCount = g.setCount,
                setFaults = LinkedHashMap(setFaults),
                lastRepAtMs = System.currentTimeMillis(),
            )
        }
        startIdleWatch()
    }

    /** Clear the "just withdrawn" flash once the UI has shown it. */
    fun clearRetractionFlag() {
        if (_uiState.value.justRetracted) _uiState.update { it.copy(justRetracted = false) }
    }

    /**
     * Drop the current form cue once it has had its few seconds on screen.
     *
     * Only [lastForm] is cleared: the set tallies and the running summary are the record of
     * what happened and are still wanted at the end of the set. This just stops the cue
     * from sitting in front of the bar indefinitely after the rep it described is over.
     */
    fun clearFormCue() {
        if (_uiState.value.lastForm != null) _uiState.update { it.copy(lastForm = null) }
    }

    private val form = FormAnalyzer()
    private val setFaults = LinkedHashMap<FormAnalyzer.Fault, Int>()

    /**
     * Measures each rep once the movement has finished, then reports immediately — a short
     * wait past the top, so the cue still lands during the same set.
     */
    private var formMetrics = buildFormMetrics()

    private fun buildFormMetrics() =
        DeferredFormMetrics(sampleRateHz = 50.0) { m, trace -> onFormMetrics(m, trace) }

    private fun onFormMetrics(m: FormAnalyzer.RepMetrics, trace: DeferredFormMetrics.RepTrace) {
        val report = form.analyse(m)
        for (f in report.findings) setFaults[f.fault] = (setFaults[f.fault] ?: 0) + 1
        logger?.logMarker("form", report.findings.joinToString(";") { "${it.fault}" })
        recordForWorkout(report, trace)
        _uiState.update {
            it.copy(lastForm = report, setFaults = LinkedHashMap(setFaults), setSummary = form.setSummary())
        }
    }

    // ---- workout session: an explicit start/end wrapper around any number of sets --------

    private var workoutStartedAt = 0L
    private val workoutReps = ArrayList<RepRecord>()

    /**
     * Every measured rep this session, workout or not.
     *
     * Kept separately from [workoutReps] because the end-of-set recap has to work whether or
     * not the lifter remembered to start a workout — it is the feedback they see between
     * sets, and gating it on a button press would make it arrive exactly when they had not
     * asked for it.
     */
    private val sessionRecords = ArrayList<RepRecord>()

    /**
     * Begin a workout. Clears the previous one's reps but does NOT restart detection — the
     * detector and its baselines keep running, so starting a workout mid-session cannot
     * corrupt a set that is already under way.
     */
    fun startWorkout() {
        workoutStartedAt = System.currentTimeMillis()
        workoutReps.clear()
        _uiState.update { it.copy(workoutActive = true, lastSummary = null) }
        logger?.logMarker("workout_start")
    }

    /**
     * End the workout, build the summary and persist it. The summary is kept in state even
     * if the write fails, because the numbers on screen are the useful part.
     */
    fun endWorkout(saveDir: File?) {
        if (!_uiState.value.workoutActive) return
        val summary = WorkoutSummary(workoutStartedAt, System.currentTimeMillis(), finalRecords())
        val file = saveDir?.let { summary.save(it) }
        logger?.logMarker("workout_end", "reps=${summary.totalReps},sets=${summary.totalSets}")
        _uiState.update {
            it.copy(workoutActive = false, lastSummary = summary, lastSummaryPath = file?.absolutePath)
        }
    }

    /** Dismiss the summary screen; the saved file stays on disk. */
    fun clearSummary() {
        if (_uiState.value.lastSummary != null) _uiState.update { it.copy(lastSummary = null) }
    }

    /**
     * Show a workout loaded from disk on the same screen the live one uses.
     *
     * Deliberately routed through the same state field rather than a parallel "viewing a past
     * workout" mode: the summary screen already knows how to render a [WorkoutSummary] and
     * how to be dismissed, and a second path would be a second chance for history and the
     * live summary to disagree. [lastSummaryPath] is cleared because the on-screen workout is
     * no longer the one this session saved.
     */
    fun showSummary(summary: WorkoutSummary) {
        _uiState.update { it.copy(lastSummary = summary, lastSummaryPath = null) }
    }

    /**
     * Re-derive each rep's set from the authoritative rep list, discarding whatever the UI
     * happened to be showing when its measurement completed.
     *
     * The live reading is racy and cannot be fixed in place: [RepFilters.LiveIsolationGate]
     * holds the first rep of every cluster until a second one arrives, so that rep's
     * deferred measurement can land while `currentSet` still names the *previous* set. On a
     * 6/7/6 session it produced 0/6/7/5 — every set's opening rep charged to the set before
     * it. Both lists are one entry per counted rep in the same order, so position is a
     * sound join; if a withdrawal has made them disagree, keep the live values rather than
     * silently pairing the wrong reps.
     */
    /**
     * The reps the summary should actually contain: those the lifter was shown.
     *
     * Every rep the detector confirms gets form-measured, but not every one of them is
     * *counted* — [RepFilters.LiveIsolationGate] suppresses isolated ones in hands-free mode,
     * the marked-set window does the same in manual mode, and [PostureVerifier] withdraws
     * reps after the fact. Filing straight from the measurement callback therefore put reps
     * in the summary that the counter had rejected: Subject27/Apr5 displayed 15 reps and its
     * summary claimed 17, and because the two lists disagreed [resolveSets] gave up and left
     * the racy live set numbers in place.
     *
     * Filtering here rather than at record time is deliberate: the isolation gate holds the
     * first rep of a cluster until a second arrives, so at measurement time a rep's fate is
     * not yet known. By the end of the workout it is, and [_uiState] holds the authority.
     */
    private fun finalRecords(): List<RepRecord> {
        val countedIndices = _uiState.value.reps.mapTo(HashSet()) { it.index }
        val kept = workoutReps
            .filter { it.detectorIndex in countedIndices }
            .mapIndexed { i, r -> r.copy(repIndex = i + 1) }
        return resolveSets(kept)
    }

    private fun resolveSets(records: List<RepRecord>): List<RepRecord> {
        val counted = _uiState.value.reps
        if (counted.size != records.size) return records
        val setOfRep = setGrouper.group(counted).setOfRep
        var lastSet = -1
        var inSet = 0
        return records.mapIndexed { i, r ->
            val s = setOfRep[i]
            if (s != lastSet) { lastSet = s; inSet = 0 }
            inSet++
            r.copy(setNumber = s, repInSet = inSet)
        }
    }

    /**
     * File one measured rep. It always lands in [sessionRecords], which feeds the end-of-set
     * recap; it additionally lands in [workoutReps] when a workout is running.
     *
     * The set position recorded here is only a fallback — [resolveSets] replaces it at the
     * end of the workout, and the recap re-derives it from the counted reps. See those notes.
     */
    private fun recordForWorkout(
        report: FormAnalyzer.RepReport,
        trace: DeferredFormMetrics.RepTrace,
    ) {
        val s = _uiState.value
        val record = RepRecord(
            repIndex = sessionRecords.size + 1,
            detectorIndex = report.metrics.index,
            setNumber = s.currentSet,
            repInSet = s.repsInCurrentSet,
            romDown = report.metrics.romDown,
            faults = report.findings.map { it.fault },
            judged = report.hasBaseline,
            // Ask the head sensor what it saw over the same span of samples. Null when it
            // saw nothing — no glasses IMU, or the rep fell outside its history.
            head = headTracker.summarise(trace.window.first, trace.window.last),
            shape = trace.depthShape,
        )
        sessionRecords.add(record)
        if (s.workoutActive) workoutReps.add(record.copy(repIndex = workoutReps.size + 1))
    }

    /**
     * The set that has just finished, for the recap shown while the lifter is still standing
     * over the bar. Null except in the seconds after a set ends.
     *
     * Built from the *counted* reps and their authoritative grouping, then joined back to the
     * measured records — so a rep the isolation gate rejected never appears in the recap, the
     * same rule the saved summary follows.
     */
    private fun buildSetRecap(): SetRecap? {
        val counted = _uiState.value.reps
        if (counted.isEmpty()) return null
        val g = setGrouper.group(counted)
        val setNo = g.currentSet
        val wanted = counted.filterIndexed { i, _ -> g.setOfRep.getOrNull(i) == setNo }
            .mapTo(HashSet()) { it.index }
        if (wanted.isEmpty()) return null
        val reps = sessionRecords.filter { it.detectorIndex in wanted }
            .mapIndexed { i, r -> r.copy(repInSet = i + 1, setNumber = setNo) }
        if (reps.isEmpty()) return null
        return SetRecap(setNo, reps)
    }

    /** Dismiss the end-of-set recap once it has had its time on screen. */
    fun clearSetRecap() {
        if (_uiState.value.setRecap != null) _uiState.update { it.copy(setRecap = null) }
    }

    /** One finished set, as the recap screen shows it. */
    data class SetRecap(val setNumber: Int, val reps: List<RepRecord>)

    // ---- head movement, from the glasses' own IMU ----------------------------------------

    /**
     * What the head was doing, tracked independently of the arm sensor that counts reps.
     *
     * This stream never reaches the detector: head motion has nothing to do with whether a
     * rep happened, and feeding it in would corrupt counting. It is read back per rep at the
     * end, and reported only in the workout summary.
     */
    private val headTracker = HeadMotionTracker()

    /**
     * Feed one reading from the glasses' IMU (gyroscope in deg/s).
     *
     * Stamped with the arm stream's current sample index, which is how the two sensors are
     * lined up — see [HeadMotionTracker].
     */
    fun onHeadSample(gyroX: Double, gyroY: Double, gyroZ: Double) {
        headTracker.onSample(gyroX, gyroY, gyroZ, sampleCount, System.currentTimeMillis().toDouble())
        // Tee into the capture, so a recorded session carries the head stream too and the
        // head thresholds can eventually be swept the way the form ones were. Stamped with
        // the same sampleCount the tracker just used, so the file replays through the same
        // alignment it was recorded under.
        logger?.logHeadSample(gyroX, gyroY, gyroZ, sampleCount)
    }

    // Manual-mode set-marking, tracked in sample-index space (aligned with the detector's
    // orientation buffer). A rep counts if its valley falls inside a marked set window,
    // with a small grace margin so a rep whose confirmation lands just after the user
    // taps "end set" (the detector emits a rep slightly after it physically completes)
    // is still counted.
    private var sampleCount = 0

    /**
     * Samples this view model has actually received, for diagnosing a count anomaly seen only
     * on the glasses: two runs in seven produced a wrong rep total from an identical replay
     * (once 0, once 13 where every other run gave 19), which cannot happen if the detector is
     * a pure function of its input. Comparing this against the replay source's own emitted
     * counter separates the two possible causes — samples lost between the source and here,
     * versus detector state being reset mid-run.
     */
    val deliveredSamples: Int get() = sampleCount

    private var currentSetStart = -1
    private val activeIntervals = ArrayList<IntRange>()
    private val setGraceSamples = 100   // ~2 s at 50 Hz

    private fun repInActiveSet(valleyIndex: Int): Boolean {
        if (setActive && currentSetStart >= 0 && valleyIndex >= currentSetStart - setGraceSamples) return true
        return activeIntervals.any { valleyIndex >= it.first - setGraceSamples && valleyIndex <= it.last + setGraceSamples }
    }

    // Hands-free: sliding detector + isolation gate. Manual: multi-valley detector.
    private var liveGate = RepFilters.LiveIsolationGate(sampleRateHz = 50.0)
    private var slidingDetector: RepDetector? = buildLive()
    private var mvDetector: MultiValleyRepDetector? = null

    /** Switch pipelines. Restarts the session so the change takes effect immediately. */
    fun setHandsFree(enabled: Boolean) {
        if (handsFree == enabled) return
        handsFree = enabled
        startSession(exercise, offset)
    }

    /** Call after the calibration step, or when the user picks a different exercise. */
    fun startSession(exercise: Exercise, offset: CalibrationOffset = CalibrationOffset.ZERO) {
        this.exercise = exercise
        this.offset = offset
        buffer.clear()
        setActive = false
        sampleCount = 0
        // Head samples are stamped with the arm index, and that index restarts here — so
        // anything already buffered now points at the wrong place and has to go. The measured
        // records go with them: they are keyed by detector rep index, which also restarts.
        headTracker.reset()
        sessionRecords.clear()
        currentSetStart = -1
        lastRepSample = -1
        activeIntervals.clear()
        form.startSet()
        setFaults.clear()
        verifier = buildVerifier()
        formMetrics = buildFormMetrics()
        if (handsFree) {
            liveGate = RepFilters.LiveIsolationGate(sampleRateHz = 50.0)
            slidingDetector = buildLive()
            mvDetector = null
        } else {
            slidingDetector = null
            mvDetector = buildManual()
        }
        // A workout outlives a detection session: the sensor reconnecting, or the user
        // switching modes, restarts detection but must not silently end the workout the
        // lifter is in the middle of. Carry those fields across the reset.
        val prev = _uiState.value
        _uiState.value = UiState(
            handsFree = handsFree,
            workoutActive = prev.workoutActive,
            lastSummary = prev.lastSummary,
            lastSummaryPath = prev.lastSummaryPath,
        )
    }

    /**
     * Manual mode only: the user tapped the glasses touchpad. First tap marks the start
     * of a set (reps now count), second tap ends it (reps stop counting). No-op in
     * hands-free mode.
     */
    fun toggleSet() {
        if (handsFree) return
        if (!setActive) {
            currentSetStart = sampleCount
            setActive = true
            form.startSet()          // form is judged within a set
            setFaults.clear()
            logger?.logMarker("set_start")
        } else {
            if (currentSetStart >= 0) activeIntervals.add(currentSetStart..sampleCount)
            currentSetStart = -1
            setActive = false
            logger?.logMarker("set_end")
        }
        _uiState.update { it.copy(setActive = setActive) }
        // Drive the display change off the tap itself. Waiting for the next sample would
        // leave the controls on screen until the sensor happened to deliver one — and with
        // no sensor connected, forever.
        refreshSetInProgress()
    }

    /**
     * Start logging the raw IMU stream (plus set/rep/calibration markers) to a new session
     * folder under [baseDir]. No-op if already capturing. Independent of detection — you can
     * record in either mode. Start this BEFORE the workout so the logged sample index lines
     * up with the detector's, then the marker file's indices are directly comparable.
     */
    fun startCapture(baseDir: File, placement: String = "upper_arm") {
        if (logger != null) return
        val dir = File(baseDir, "session_${System.currentTimeMillis()}")
        logger = ImuSessionLogger(dir, placement)
        _uiState.update { it.copy(capturing = true, capturePath = dir.absolutePath, captureSamples = 0) }
    }

    /** Stop and finalise the current capture, flushing the files to disk. No-op if idle. */
    fun stopCapture() {
        val l = logger ?: return
        l.close()
        logger = null
        _uiState.update { it.copy(capturing = false, captureSamples = l.sampleCount) }
    }

    /** Mark a calibration pose (e.g. arm relaxed at side, or locked out at the top) at the current sample. */
    fun markCalibration() {
        logger?.logMarker("calibration")
    }

    override fun onCleared() {
        // Finalise any in-progress capture so the files aren't left unflushed.
        logger?.close()
        logger = null
        super.onCleared()
    }

    /** Feed one IMU reading. Safe to call at the sensor's native rate. */
    fun onImuSample(
        accX: Double, accY: Double, accZ: Double,
        gyroX: Double, gyroY: Double, gyroZ: Double,
    ) {
        val sample = ImuSample(accX, accY, accZ, gyroX, gyroY, gyroZ)
        logger?.let { l ->
            l.logSample(sample)
            if (sampleCount % 25 == 0) _uiState.update { it.copy(captureSamples = l.sampleCount) }
        }
        buffer.add(sample)
        sampleCount++
        if (buffer.size >= 5) flush()
    }

    /**
     * Feed one already-fused orientation value, for replaying a recorded orientation
     * trace. Bypasses [com.liftright.repdetection.OrientationFilter] — detection, posture
     * checking and form measurement are otherwise identical.
     */
    fun onOrientationSample(pitch: Double) {
        sampleCount++
        slidingDetector?.feedOrientation(pitch)
        mvDetector?.feedOrientation(pitch)
        slidingDetector?.let { d -> _uiState.update { it.copy(recording = d.isRecording) } }
        refreshSetInProgress()
    }

    /** Push any buffered samples through the active detector immediately. */
    fun flush() {
        if (buffer.isEmpty()) return
        val chunk = buffer.toList()
        buffer.clear()
        slidingDetector?.let { d ->
            d.process(chunk)
            _uiState.update { it.copy(recording = d.isRecording) }
        }
        mvDetector?.process(chunk)
        refreshSetInProgress()
    }

    /**
     * Replays a pre-recorded session (e.g. loaded from CSV) through a fresh hands-free
     * detector and applies the batch isolation filter. Used by the CSV dev panel.
     */
    fun runFromSamples(
        exercise: Exercise,
        samples: List<ImuSample>,
        offset: CalibrationOffset = CalibrationOffset.ZERO,
    ) {
        this.exercise = exercise
        this.offset = offset
        buffer.clear()
        // Replay must apply the same posture check as the live path, or the dev panel
        // reports a different number from the one the glasses would show.
        val batchReps = ArrayList<Repetition>()
        val batchVerifier = PostureVerifier(sampleRateHz = 50.0) { rep ->
            batchReps.removeAll { it.liftStartIndex == rep.liftStartIndex }
        }
        val batch = RepDetector(
            exercise = exercise, sampleRateHz = 50.0, offset = offset, verifier = batchVerifier,
        ) { rep -> batchReps.add(rep) }
        slidingDetector = null
        mvDetector = null
        _uiState.value = UiState(isProcessing = true, handsFree = handsFree)
        viewModelScope.launch {
            val filtered = withContext(Dispatchers.Default) {
                for (sample in samples) batch.feed(sample)
                RepFilters.filterIsolated(batchReps, sampleRateHz = 50.0)
            }
            val g = setGrouper.group(filtered)
            _uiState.update {
                it.copy(
                    recording = batch.isRecording, repCount = filtered.size, reps = filtered,
                    currentSet = g.currentSet, repsInCurrentSet = g.repsInCurrentSet,
                    setCount = g.setCount,
                    retractedCount = batchVerifier.retracted, isProcessing = false,
                )
            }
        }
    }

    /** Hands-free live detector: reps pass through the isolation gate before display. */
    private fun buildLive() =
        RepDetector(
            exercise = exercise, sampleRateHz = 50.0, offset = offset, verifier = verifier,
            onRepetition = { rep ->
                logger?.logMarker("rep", "handsfree,liftStart=${rep.liftStartIndex}")
                addReps(liveGate.offer(rep))
            },
            formMetrics = formMetrics,
        )

    /**
     * Manual live detector: multi-valley detection, gated by the marked set windows.
     * A rep is counted only if its valley falls inside a set the user marked active
     * (with a grace margin for the detector's post-rep confirmation delay).
     */
    private fun buildManual() =
        MultiValleyRepDetector(
            exercise = exercise, sampleRateHz = 50.0, offset = offset,
            formMetrics = formMetrics, verifier = verifier,
        ) { rep ->
            logger?.logMarker("rep", "manual,liftStart=${rep.liftStartIndex},inSet=${repInActiveSet(rep.liftStartIndex)}")
            if (repInActiveSet(rep.liftStartIndex)) addReps(listOf(rep))
        }

    private companion object {
        /**
         * How long after a rep the lifter is still considered mid-set. Sits between the
         * longest gap measured *inside* a real set (8 s across the 16-session set) and
         * SetGrouper's 25 s rest threshold, so it neither drops out between reps nor
         * outlives the set it belongs to.
         */
        const val SET_IDLE_S = 20.0

        /** The same gap in wall-clock milliseconds, for the sensor-dropout guard. */
        const val SET_IDLE_MS = (SET_IDLE_S * 1000).toLong()
    }
}
