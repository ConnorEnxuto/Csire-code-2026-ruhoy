package com.liftright.repdetection.head

import kotlin.math.abs
import kotlin.math.sqrt

/** Why a rep's head movement was called unusual. */
enum class HeadFlag(val label: String) {
    /** The head travelled a long way — looking around, or coming up off the bench. */
    HEAD_SWING("head moved a lot"),
    /** A fast rotation — a snap or a whip of the head. */
    HEAD_SNAP("fast head turn"),
    /** Abrupt starts and stops rather than one smooth movement. */
    HEAD_JERK("jerky head motion"),
}

/**
 * How much the head moved during one rep, and whether that counts as unusual.
 *
 * [excursionDeg] is the total angular *path* the head travelled, not net displacement, so
 * looking away and back again adds up rather than cancelling out.
 */
data class HeadMotion(
    val excursionDeg: Double,
    val peakRateDegS: Double,
    val jerkDegS2: Double,
    val samples: Int,
    val flags: List<HeadFlag>,
) {
    val isUnusual: Boolean get() = flags.isNotEmpty()
}

/**
 * When head movement counts as unusual.
 *
 * These are first-pass numbers reasoned from the physics, NOT fitted to recorded data the
 * way the [com.liftright.repdetection.form.FormAnalyzer] thresholds were — there is no
 * labelled head-motion set to sweep against yet. They are constructor parameters so a sweep
 * can replace them once real sessions have been captured.
 *
 * @param stillnessGateDegS below this the head is treated as still and contributes no
 *   excursion at all. Without a deadband, sensor noise alone integrates to several degrees
 *   over a four-second rep, and body wobble transmitted through the bench adds more. Set
 *   above both, so a head resting on the bench scores a clean zero.
 * @param excursionDeg a glance at the bar and back is roughly 40 deg of total path; holding
 *   still is near zero. 30 sits between them.
 * @param peakRateDegS a deliberate, controlled turn peaks well under 100 deg/s; a snap of
 *   the head goes past 150.
 * @param jerkDegS2 peak angular acceleration. A smooth turn builds up over a few tenths of
 *   a second (a few hundred deg/s^2); an abrupt start or stop is several times that.
 */
data class HeadMotionThresholds(
    val stillnessGateDegS: Double = 15.0,
    val excursionDeg: Double = 30.0,
    val peakRateDegS: Double = 120.0,
    val jerkDegS2: Double = 900.0,
)

/**
 * Records what the glasses' own IMU saw, so each rep can be asked how still the head was
 * while it happened.
 *
 * ## Why it is indexed by arm sample
 * The head IMU and the upper-arm IMU are separate sensors on separate clocks, and reps are
 * reported in the arm stream's sample-index space. Rather than trying to align two wall
 * clocks, every head reading is stamped with the arm-sample index that was current when it
 * arrived; a rep spanning arm samples `[a, b]` then reads back the head samples stamped
 * within that range. This is the same index space the detector and the set-marking code
 * already share, so no new notion of time is introduced.
 *
 * Real elapsed time is still kept per sample, because integrating a rate and differentiating
 * it both need it, and the Android sensor stack delivers at an approximate rate with jitter.
 *
 * The buffer is a fixed-size ring: head data is only ever read back a second or two after
 * the rep, and a workout must not grow memory without bound.
 */
class HeadMotionTracker(
    historySeconds: Double = 120.0,
    nominalRateHz: Double = 60.0,
    private val thresholds: HeadMotionThresholds = HeadMotionThresholds(),
) {
    private val capacity = (historySeconds * nominalRateHz).toInt().coerceAtLeast(256)

    private val armIndex = IntArray(capacity)
    private val rate = DoubleArray(capacity)      // |angular velocity|, deg/s
    private val timeMs = DoubleArray(capacity)

    private var head = 0      // next write position
    private var size = 0

    /** True once any head data at all has arrived — the UI says so rather than showing zeroes. */
    val hasData: Boolean get() = size > 0

    fun reset() {
        head = 0
        size = 0
    }

    /**
     * Record one head reading: gyroscope in deg/s, stamped with the arm stream's current
     * sample index and the reading's own arrival time in milliseconds.
     */
    fun onSample(gyroX: Double, gyroY: Double, gyroZ: Double, atArmSample: Int, atMs: Double) {
        armIndex[head] = atArmSample
        rate[head] = sqrt(gyroX * gyroX + gyroY * gyroY + gyroZ * gyroZ)
        timeMs[head] = atMs
        head = (head + 1) % capacity
        if (size < capacity) size++
    }

    /**
     * Summarise the head over the arm-sample window `[from, to]`, or null if too little head
     * data covers it.
     *
     * Null is the honest answer in several real cases — the glasses' IMU was unavailable, a
     * workout started mid-rep, or a CSV was replayed faster than real time so the arm index
     * outran the live head stream. The summary reports those reps as untracked rather than
     * as perfectly still.
     */
    fun summarise(from: Int, to: Int): HeadMotion? {
        if (size == 0 || to <= from) return null

        // Pull the window out in arrival order. The ring is written in order, so walking from
        // the oldest entry forward gives a time-ordered slice.
        val rates = ArrayList<Double>()
        val times = ArrayList<Double>()
        val oldest = if (size < capacity) 0 else head
        for (k in 0 until size) {
            val i = (oldest + k) % capacity
            val a = armIndex[i]
            if (a in from..to) {
                rates.add(rate[i])
                times.add(timeMs[i])
            }
        }
        if (rates.size < MIN_SAMPLES) return null

        // Smooth before differentiating: a single noisy sample would otherwise read as a
        // large jerk. Three samples is enough to kill single-sample spikes without blunting
        // a real snap, which lasts a tenth of a second or more.
        val smooth = movingAverage(rates, 3)

        var excursion = 0.0
        var peak = 0.0
        var jerk = 0.0
        for (i in smooth.indices) {
            val r = smooth[i]
            if (r > peak) peak = r
            if (i == 0) continue
            val dt = (times[i] - times[i - 1]) / 1000.0
            if (dt <= 0.0 || dt > MAX_GAP_S) continue   // dropped samples: no path, no jerk
            // Only movement above the deadband counts as the head actually going somewhere.
            if (r > thresholds.stillnessGateDegS) excursion += r * dt
            val j = abs(r - smooth[i - 1]) / dt
            if (j > jerk) jerk = j
        }

        val flags = buildList {
            if (excursion >= thresholds.excursionDeg) add(HeadFlag.HEAD_SWING)
            if (peak >= thresholds.peakRateDegS) add(HeadFlag.HEAD_SNAP)
            if (jerk >= thresholds.jerkDegS2) add(HeadFlag.HEAD_JERK)
        }
        return HeadMotion(excursion, peak, jerk, rates.size, flags)
    }

    private fun movingAverage(xs: List<Double>, window: Int): DoubleArray {
        val out = DoubleArray(xs.size)
        val half = window / 2
        for (i in xs.indices) {
            var sum = 0.0
            var n = 0
            for (j in maxOf(0, i - half)..minOf(xs.size - 1, i + half)) {
                sum += xs[j]; n++
            }
            out[i] = sum / n
        }
        return out
    }

    private companion object {
        /** Below this the window is too sparsely covered to say anything about it. */
        const val MIN_SAMPLES = 8

        /** A gap longer than this means samples were dropped; don't invent motion across it. */
        const val MAX_GAP_S = 0.5
    }
}
