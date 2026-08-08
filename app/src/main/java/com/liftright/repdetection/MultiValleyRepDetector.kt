package com.liftright.repdetection

import kotlin.math.abs

/**
 * Multi-valley variant of [RepDetector], used for the manual (non-hands-free) mode.
 *
 * The default [RepDetector] takes a single global-argmin valley per sliding window, so
 * two rep valleys that fall inside one ~1.8 s window get merged and one rep is lost
 * (fast-cadence under-count). This detector keeps the same front-end (IMU -> fused
 * pitch via the Madgwick [OrientationFilter]) and the same DTW gate + [ExerciseHmm]
 * confirmation, but within each DTW-passing window it scans for ALL local minima and
 * validates each, so closely-spaced reps stay distinct.
 *
 * On its own this over-detects (it also fires between sets and on non-rep motion), so it
 * is only used together with an external "active set" gate: in manual mode the user taps
 * the glasses touchpad to mark when a set is in progress, and [RepDetectorViewModel]
 * only counts reps emitted while a set is active. That pairing was the configuration
 * that reached ~5 rep-count error across the 16-subject validation set (vs ~15 for the
 * hands-free sliding detector), because multi-valley recovers the under-counted fast
 * reps while the active-set gate removes its excess detections.
 *
 * Not thread-safe; feed from a single thread.
 */
class MultiValleyRepDetector(
    private val exercise: Exercise,
    private val sampleRateHz: Double = 50.0,
    private val offset: CalibrationOffset = CalibrationOffset.ZERO,
    private val minValleySeparation: Int = 30,   // ~0.6 s at 50 Hz
    /** Deferred form measurement; see the note in [RepDetector]. */
    private val formMetrics: com.liftright.repdetection.form.DeferredFormMetrics? = null,
    /** Retrospective posture check; see [PostureVerifier]. */
    private val verifier: PostureVerifier? = null,
    private val onRepetition: (Repetition) -> Unit,
) {
    companion object {
        const val WINDOW = 90
        private const val DEG2RAD = 0.017453292519943295
        private const val G = 9.81
        private const val MAX_ORIENTATION = 3_000_000
    }

    private val dt = 1.0 / sampleRateHz
    private val orientationFilter = OrientationFilter()
    private val hmm = ExerciseHmm()
    private val orientation = ArrayList<Double>(4096)

    private val offsetX: Double = offset.x * -G
    private val offsetY: Double = offset.y * -G
    private val offsetZ: Double = offset.z * -G

    private val refractorySamples: Int = (0.5 * sampleRateHz).toInt()
    private var lastConfirmedStopPeak = -1
    private var repCount = 0

    private val fallingSlopes: DoubleArray
    private val risingSlopes: DoubleArray

    init {
        val t = RepTemplate.TEMPLATE
        val valleyIdx = SignalUtils.argMin(t, 0, t.size)
        fallingSlopes = SignalUtils.gradient(normalise(t.copyOfRange(0, valleyIdx + 1)))
        risingSlopes = SignalUtils.gradient(normalise(t.copyOfRange(valleyIdx, t.size)))
    }

    var totalReps: Int = 0
        private set

    private var seeded = false

    fun reset() {
        orientationFilter.reset()
        hmm.reset()
        orientation.clear()
        lastConfirmedStopPeak = -1
        repCount = 0
        totalReps = 0
        seeded = false
    }

    fun feed(sample: ImuSample) = process(listOf(sample))

    fun process(chunk: List<ImuSample>) {
        if (chunk.isEmpty()) return
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
        if (orientation.size >= WINDOW) findRepetitions()
    }

    /** Feed an already-fused orientation value; see [RepDetector.feedOrientation]. */
    fun feedOrientation(pitch: Double) {
        pushPitch(pitch)
        if (orientation.size >= WINDOW) findRepetitions()
    }

    private fun pushPitch(pitch: Double) {
        if (orientation.size < MAX_ORIENTATION) orientation.add(pitch)
        verifier?.onSample(orientation.size - 1, pitch)
        formMetrics?.onSample(orientation)
    }

    private fun findRepetitions() {
        val n = orientation.size
        val start = maxOf(0, n - WINDOW)
        val window = DoubleArray(n - start) { orientation[start + it] }
        if (Dtw.distance(RepTemplate.TEMPLATE, window) > exercise.dtwThreshold) return

        // All local minima in the window (strictly lower than +/-k neighbours).
        val k = 5
        val valleys = ArrayList<Int>()
        for (i in k until window.size - k) {
            var isMin = true
            for (j in 1..k) if (window[i] > window[i - j] || window[i] > window[i + j]) { isMin = false; break }
            if (isMin) {
                val gIdx = start + i
                if (valleys.isEmpty() || gIdx - valleys.last() >= minValleySeparation) valleys.add(gIdx)
                else if (window[i] < orientation[valleys.last() - start]) valleys[valleys.size - 1] = gIdx
            }
        }

        for (valley in valleys) {
            if (valley <= lastConfirmedStopPeak + refractorySamples) continue
            val wStart = maxOf(start, lastConfirmedStopPeak + 1)
            if (valley - wStart < 5) continue
            val startPeak = argMaxAbs(wStart, valley)
            val stopPeak = argMaxAbs(valley, n - 1)
            if (!(startPeak < valley && valley < stopPeak)) continue
            if (stopPeak - valley < 5 || valley - startPeak < 5) continue

            val dropSeg = orientationSlice(startPeak, valley + 1)
            val riseSeg = orientationSlice(valley, stopPeak + 1)
            if (dropSeg.size < 2 || riseSeg.size < 2) continue
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
            if (!hmm.isRepetition(features)) continue

            repCount++
            totalReps = repCount
            val rom = orientation[stopPeak] - orientation[valley]
            val durationMs = (stopPeak - valley) / sampleRateHz * 1000.0
            val velocity = if (durationMs != 0.0) rom / durationMs * 1000.0 else 0.0
            val rep = Repetition(repCount, rom, durationMs, velocity, valley, stopPeak)
            onRepetition(rep)
            verifier?.onRep(rep, startPeak, valley)
            formMetrics?.onRep(repCount, valley)
            lastConfirmedStopPeak = stopPeak
        }
    }

    private fun argMaxAbs(from: Int, to: Int): Int {
        var idx = from
        var best = orientation[from]
        for (i in from + 1..to) if (orientation[i] > best) { best = orientation[i]; idx = i }
        return idx
    }

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
