package com.liftright.repdetection

import kotlin.math.abs
import kotlin.math.floor

/**
 * Streaming weightlifting rep detector.
 *
 * Kotlin port of the LiftRight rep-detection pipeline. Feed it IMU samples as they
 * arrive from the sensor and it will invoke [onRepetition] each time a repetition is
 * confirmed. It is fully online: state accumulates across calls, exactly like the
 * original engine.
 *
 * The pipeline, per new chunk of samples:
 *   1. Sanitise + apply calibration offset, fuse to a 1-D orientation ("pitch") signal.
 *   2. Activity gate: median-filtered trend vs. a dynamic threshold decides whether a
 *      set is currently in progress (exposed via [isRecording]).
 *   3. Once >= [WINDOW] samples exist, scan the most-recent window for a valley-between-
 *      two-peaks (a rep's characteristic shape) whose DTW distance to the trained
 *      template is under the exercise threshold.
 *   4. Deduplicate overlapping candidates, then confirm with the HMM classifier.
 *   5. On confirmation, compute range-of-motion / duration / velocity and emit a [Repetition].
 *
 * Not thread-safe; call [process]/[feed] from a single thread (e.g. the sensor thread).
 *
 * @param exercise      which exercise (sets the DTW threshold)
 * @param sampleRateHz  expected IMU rate; the engine assumes uniform spacing. Defaults
 *                      to 50 Hz to match the original app.
 * @param offset        resting calibration offset (accelerometer, in g)
 * @param onRepetition  callback invoked once per confirmed rep
 */
class RepDetector(
    private val exercise: Exercise,
    private val sampleRateHz: Double = 50.0,
    private val offset: CalibrationOffset = CalibrationOffset.ZERO,
    /**
     * Optional form measurement. Deferred: the collector is handed the orientation buffer
     * and each rep's bottom, then measures once the movement has played out — the numbers
     * are wrong if taken at detection time. See [com.liftright.repdetection.form.DeferredFormMetrics].
     * Declared before [onRepetition] so that stays the trailing lambda.
     */
    private val formMetrics: com.liftright.repdetection.form.DeferredFormMetrics? = null,
    /** Retrospective posture check; see [PostureVerifier]. */
    private val verifier: PostureVerifier? = null,
    private val onRepetition: (Repetition) -> Unit,
) {
    companion object {
        /** Look-back window used to search for a rep (~1.8 s at 50 Hz). */
        const val WINDOW = 90

        private const val DEG2RAD = 0.017453292519943295
        private const val G = 9.81

        // Safety bound on the orientation buffer, not a session-length assumption: at
        // 50Hz this is ~16.7 hours, far beyond any real workout. The previous value
        // (30_000 = 10 minutes) silently froze detection for the rest of any longer
        // session once hit - process() kept running but stopped recording new
        // orientation samples, so the analysis window never advanced past that point.
        private const val MAX_ORIENTATION = 3_000_000
    }

    private val dt = 1.0 / sampleRateHz
    private val orientationFilter = OrientationFilter()
    private val hmm = ExerciseHmm()

    // Rolling buffer of fused orientation. Grows up to MAX_ORIENTATION samples.
    private val orientation = ArrayList<Double>(4096)

    // Pre-sanitised offset terms, mirroring the original engine.
    private val offsetX: Double
    private val offsetY: Double = offset.y * -G
    private val offsetZ: Double = offset.z * -G

    // Activity-gate running state.
    private val trendMeans = ArrayList<Double>()
    private val bins = ArrayList<Double>()
    private var recording = false
    private var generalCounter = 0

    // Rep bookkeeping.
    private var repCount = 0
    private var lastConfirmedStopPeak = -1

    // Refractory gap: a new rep's VALLEY must fall at least this many samples after the
    // previous rep's stop peak. Prevents a single movement being counted multiple times
    // as the window slides. ~1.1 s, scaled to the sample rate.
    //
    // The anchor matters: this check used to compare the candidate's START PEAK against
    // the previous rep's stop peak. But consecutive reps in a set share a peak - the
    // trace runs peak -> valley -> peak -> valley - so a rep that immediately followed
    // another had startPeak == lastConfirmedStopPeak and was rejected outright, losing
    // roughly one rep per set. Anchoring on the valley (which is what
    // MultiValleyRepDetector already did, and why it counted those reps correctly) fixes
    // that while still deduping re-detections of the same movement.
    //
    // The margin was re-tuned after the anchor change (the anchor sits ~half a rep later,
    // so the old 0.5 s no longer means the same thing). Swept against the 16-subject
    // validation set: valley@1.1s gives the best total rep-count error (14, 5/16 exact)
    // vs the old startPeak@0.5s (15, 4/16), and the valley anchor stays stable across
    // margins (err 14-17 for 0.2-1.6 s) where the start-peak anchor degrades sharply.
    //
    // Note: the original engine uses a more sophisticated stateful "candidate merge"
    // mechanism (captured_reps) instead of a fixed refractory gap. A faithful Kotlin port
    // of that mechanism was tried and validated against the same 16 sessions; it
    // empirically performed *worse* than this simpler heuristic, likely due to a subtle
    // bug in translating its stateful refinement logic.
    private val refractorySamples: Int = (1.1 * sampleRateHz).toInt()

    // Template rising/falling slope profiles (gradients of the normalised template halves).
    private val fallingSlopes: DoubleArray
    private val risingSlopes: DoubleArray

    init {
        offsetX = offset.x * -G

        // Split the template at its minimum into falling / rising halves, normalise each
        // and take the gradient — the reference slope profiles used for correlation.
        val t = RepTemplate.TEMPLATE
        val valleyIdx = SignalUtils.argMin(t, 0, t.size)
        val fallingRaw = t.copyOfRange(0, valleyIdx + 1)
        val risingRaw = t.copyOfRange(valleyIdx, t.size)
        fallingSlopes = SignalUtils.gradient(normalise(fallingRaw))
        risingSlopes = SignalUtils.gradient(normalise(risingRaw))
    }

    /** Number of reps confirmed so far in this run. */
    var totalReps: Int = 0
        private set

    /** Whether the activity gate currently believes a set is in progress. */
    val isRecording: Boolean get() = recording

    /** Reset all state to begin a fresh session. */
    fun reset() {
        orientationFilter.reset()
        hmm.reset()
        orientation.clear()
        trendMeans.clear()
        bins.clear()
        recording = false
        generalCounter = 0
        repCount = 0
        totalReps = 0
        lastConfirmedStopPeak = -1
        seeded = false
    }

    // Set false until the orientation filter is seeded from the first accelerometer sample.
    private var seeded = false

    /** Convenience: feed a single sample. */
    fun feed(sample: ImuSample) = process(listOf(sample))

    /**
     * Feed a chunk of samples collected since the last call. Any non-empty chunk works;
     * the original app delivered them in small batches from the BLE sensor.
     */
    fun process(chunk: List<ImuSample>) {
        if (chunk.isEmpty()) return

        // 1. Sanitise + fuse each sample to an orientation value.
        for (s in chunk) {
            val ax = s.accX * G + offsetX
            val ay = s.accY * G + offsetY
            val az = s.accZ * G + offsetZ
            val gx = s.gyroX * DEG2RAD
            val gy = s.gyroY * DEG2RAD
            val gz = s.gyroZ * DEG2RAD
            if (!seeded) { orientationFilter.initFromAccel(ax, ay, az); seeded = true }
            pushPitch(orientationFilter.update(ax, ay, az, gx, gy, gz, dt))
        }
        analyse()
    }

    /**
     * Feed an already-fused orientation value, bypassing the IMU -> pitch step.
     *
     * Recorded sessions usually ship only the fused orientation trace, not raw 6-axis IMU
     * (across the whole validation set exactly one session has raw accelerometer+gyro), so
     * without this they cannot be replayed on-device at all. Detection is otherwise
     * identical — the same template match, classifier, dedup, posture check and form
     * measurement run on the values fed in.
     *
     * Note this deliberately skips [OrientationFilter], so a replay driven this way
     * exercises the detector but NOT our own orientation estimation.
     */
    fun feedOrientation(pitch: Double) {
        pushPitch(pitch)
        analyse()
    }

    /** Record one orientation value and let the per-sample consumers see it. */
    private fun pushPitch(pitch: Double) {
        if (orientation.size < MAX_ORIENTATION) orientation.add(pitch)
        verifier?.onSample(orientation.size - 1, pitch)
        formMetrics?.onSample(orientation)
    }

    /** Activity gate + repetition search, once per delivered chunk. */
    private fun analyse() {
        updateActivityGate()
        if (orientation.size >= WINDOW) findRepetition()
    }

    // ---- Stage 2: recording on/off via median trend + dynamic threshold ----

    private fun updateActivityGate() {
        val n = orientation.size
        // Recent slice (last ~100 samples) for the trend estimate.
        val from = maxOf(0, n - 100)
        val recent = DoubleArray(n - from) { orientation[from + it] }
        val trend = SignalUtils.movingMedian(recent, 100)
        val trendMean = SignalUtils.mean(trend)
        trendMeans.add(trendMean)

        // "Bin" = difference of this trend mean vs. the one 7 steps ago.
        val prior = if (trendMeans.size > 7) trendMeans[trendMeans.size - 8] else 0.0
        bins.add(trendMean - prior)

        // Dynamic threshold = half the mean orientation so far.
        val threshold = SignalUtils.mean(DoubleArray(n) { orientation[it] }) / 2.0

        if (generalCounter > 1) {
            val bin = bins.last()
            if (bin >= threshold && trendMean > threshold) {
                recording = true
            } else if (bin <= 0.0 && trendMean < threshold) {
                recording = false
            }
        }
        generalCounter++
    }

    // ---- Stages 3-5: candidate detection, dedupe, HMM confirmation ----

    private fun findRepetition() {
        val n = orientation.size
        val start = maxOf(0, n - WINDOW)      // window global start index
        val window = DoubleArray(n - start) { orientation[start + it] }

        // 3a. Template match (coarse gate).
        if (Dtw.distance(RepTemplate.TEMPLATE, window) > exercise.dtwThreshold) return

        // 3b. Locate valley and the two surrounding peaks (a V/U shape).
        val valleyLocal = SignalUtils.argMin(window, 0, window.size)
        val startPeakLocal = SignalUtils.argMax(window, 0, valleyLocal + 1)
        val stopPeakLocal = SignalUtils.argMax(window, valleyLocal, window.size)

        val startPeak = start + startPeakLocal
        val valley = start + valleyLocal
        val stopPeak = start + stopPeakLocal

        // Require a genuine V shape (peak -> valley -> peak).
        if (!(startPeak < valley && valley < stopPeak)) return

        // 4. Refractory dedupe: the rep's valley must fall after the previous rep's stop
        //    so a single movement is not counted repeatedly as the window slides. Anchored
        //    on the valley, not the start peak, which consecutive reps share - see the
        //    [refractorySamples] note.
        if (valley <= lastConfirmedStopPeak + refractorySamples) return

        // 5. HMM confirmation.
        val dropSeg = orientationSlice(startPeak, valley + 1)   // falling phase
        val riseSeg = orientationSlice(valley, stopPeak + 1)    // rising phase
        if (dropSeg.size < 2 || riseSeg.size < 2) return

        val total = (dropSeg.size + riseSeg.size).toDouble()
        val features = ExerciseHmm.Features(
            dropPopulation = dropSeg.size / total * 100.0,
            risePopulation = riseSeg.size / total * 100.0,
            dropKurtosis = SignalUtils.kurtosis(SignalUtils.gradient(dropSeg)),
            riseKurtosis = SignalUtils.kurtosis(SignalUtils.gradient(riseSeg)),
            dropCorrelation = abs(SignalUtils.correlation(SignalUtils.gradient(dropSeg), fallingSlopes)),
            riseCorrelation = abs(SignalUtils.correlation(SignalUtils.gradient(riseSeg), risingSlopes)),
            dropRom = abs(orientation[valley] - orientation[startPeak]),
            riseRom = abs(orientation[stopPeak] - orientation[valley]),
            dropStd = SignalUtils.std(dropSeg),
            riseStd = SignalUtils.std(riseSeg),
        )
        if (!hmm.isRepetition(features)) return

        // Confirmed. The concentric ("lifting") phase runs valley -> stop peak.
        val rep = emitRep(valley, stopPeak)
        verifier?.onRep(rep, startPeak, valley)
        formMetrics?.onRep(repCount, valley)
        lastConfirmedStopPeak = stopPeak
    }

    private fun emitRep(liftStartIdx: Int, liftStopIdx: Int): Repetition {
        val rom = orientation[liftStopIdx] - orientation[liftStartIdx]
        val durationMs = (liftStopIdx - liftStartIdx) / sampleRateHz * 1000.0
        val velocity = if (durationMs != 0.0) rom / durationMs * 1000.0 else 0.0

        repCount++
        totalReps = repCount
        val rep = Repetition(
                index = repCount,
                rangeOfMotion = rom,
                durationMs = durationMs,
                velocity = velocity,
                liftStartIndex = liftStartIdx,
                liftStopIndex = liftStopIdx,
        )
        onRepetition(rep)
        return rep
    }

    // ---- helpers ----

    private fun orientationSlice(from: Int, to: Int): DoubleArray {
        val a = maxOf(0, from)
        val b = minOf(orientation.size, to)
        return DoubleArray(maxOf(0, b - a)) { orientation[a + it] }
    }

    private fun normalise(x: DoubleArray): DoubleArray {
        val norm = SignalUtils.norm(x)
        return if (norm == 0.0) x.copyOf() else DoubleArray(x.size) { x[it] / norm }
    }
}
