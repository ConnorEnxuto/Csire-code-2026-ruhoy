package com.liftright.repdetection.form

import com.liftright.repdetection.SignalUtils
import kotlin.math.abs

/**
 * Turns the three landmarks the detector already finds — the top before the descent, the
 * bottom, and the top after the ascent — into the per-rep measurements [FormAnalyzer]
 * judges. Nothing here is exercise-specific; it is all read off the orientation trace.
 */
object FormMetrics {

    /**
     * @param orientation the detector's fused pitch buffer
     * @param startPeak   index of the top of the rep (before lowering)
     * @param valley      index of the bottom
     * @param stopPeak    index of the top after lifting
     */
    fun extract(
        orientation: List<Double>,
        startPeak: Int,
        valley: Int,
        stopPeak: Int,
        sampleRateHz: Double,
        repIndex: Int,
    ): FormAnalyzer.RepMetrics? {
        if (startPeak < 0 || valley <= startPeak || stopPeak <= valley) return null
        if (stopPeak >= orientation.size) return null

        val down = slice(orientation, startPeak, valley)
        val up = slice(orientation, valley, stopPeak)
        if (down.size < 3 || up.size < 3) return null

        val msPerSample = 1000.0 / sampleRateHz
        val romDown = orientation[startPeak] - orientation[valley]
        val romUp = orientation[stopPeak] - orientation[valley]

        // Fastest point of each phase, in degrees/second. The descent runs the other way, so
        // its gradient is negative and the fastest point is the most negative sample.
        val upGrad = SignalUtils.gradient(up)
        val peakUp = (upGrad.maxOrNull() ?: 0.0) * sampleRateHz
        val downGrad = SignalUtils.gradient(down)
        val peakDown = abs(downGrad.minOrNull() ?: 0.0) * sampleRateHz

        // Steadiness: kurtosis of the gradient across the whole rep. A smooth, evenly-paced
        // rep has a broad gradient distribution; a rep with a stall or a jerk concentrates
        // the movement into a few samples and spikes the kurtosis.
        val whole = slice(orientation, startPeak, stopPeak)
        val smoothness = if (whole.size >= 4) abs(SignalUtils.kurtosis(SignalUtils.gradient(whole))) else 0.0

        return FormAnalyzer.RepMetrics(
            index = repIndex,
            eccentricMs = (valley - startPeak) * msPerSample,
            concentricMs = (stopPeak - valley) * msPerSample,
            romDown = abs(romDown),
            romUp = abs(romUp),
            peakVelocityUp = peakUp,
            peakVelocityDown = peakDown,
            smoothness = smoothness,
        )
    }

    private fun slice(o: List<Double>, from: Int, to: Int): DoubleArray {
        val a = maxOf(0, from); val b = minOf(o.size - 1, to)
        if (b <= a) return DoubleArray(0)
        return DoubleArray(b - a + 1) { o[a + it] }
    }
}
