package com.liftright.repdetection.form

import kotlin.math.abs

/**
 * Measures a rep's form **after** the movement finishes, then reports it straight away.
 *
 * ## Why this exists
 * Taking the measurements at the moment the rep is detected gives badly wrong numbers,
 * because neither end of the movement is fully in view yet:
 *  - the detector confirms a rep only ~38% of the way back up, so the ascent is cut short
 *    (measured range-up came out at 0.33x range-down, when a real rep returns to where it
 *    started);
 *  - the descent's starting peak is found inside the detector's 1.8 s search window, but a
 *    real lowering phase takes 2.4-3.3 s, so the true top sits outside it. Measured
 *    lowering time topped out at 1.3 s against a real range of 2.4-3.3 s.
 *
 * Only range-of-motion on the way down survived that (the arm dwells near the top, so the
 * window still catches the right height). Everything derived from timing or from the
 * ascent was unusable, which made GRINDING, INCOMPLETE_LOCKOUT and DROPPED_ECCENTRIC
 * unreliable.
 *
 * ## How it works
 * The rep is parked when detected. Samples keep arriving; once the lift has clearly topped
 * out — the post-valley maximum stops advancing for [plateauSamples] — the real landmarks
 * are known and the metrics are computed from them. If the signal never settles, the rep is
 * measured anyway at [maxWaitSamples] rather than being dropped.
 *
 * The wait is short by design (typically well under a second past the top) so the lifter
 * still gets the cue **during the same set**, in time to change the next rep.
 */
class DeferredFormMetrics(
    private val sampleRateHz: Double = 50.0,
    /**
     * How long the top must stop rising before the lift counts as finished.
     *
     * This is the single control over whether the rep is measured at its top or part-way up,
     * and 0.30 s was too short. A grinding rep stalls on the way up — stalling is what
     * grinding *is* — and any stall longer than the window ended the measurement at the stall
     * instead of the top. The rep then read as short at the top and INCOMPLETE_LOCKOUT fired,
     * which is why that fault never responded to threshold tightening: it was reporting a
     * measurement artefact, not the lifter.
     *
     * Swept over the 36-session set (890 reps), holding everything else fixed:
     *
     * ```
     *   plateau   measured/true romUp p10   lockout findings on a truncated rep
     *   0.30 s              0.91                          64%
     *   1.00 s              1.00                          44%
     *   1.50 s              1.00                          38%
     * ```
     *
     * 1.00 s is where truncation disappears at p10. Going further keeps chipping at the
     * artefact share but buys no measurement accuracy and delays every cue, so the remaining
     * 44% wants a better test for the top rather than a longer wait for one.
     *
     * Confirming the top by watching the trace fall back off its maximum was tried and does
     * nothing: the fall takes longer than the plateau, so the plateau always fires first.
     * A 20 deg confirmation left every number identical at 0.30 s and made lockout slightly
     * worse at 1.00 s (4.3% -> 4.6% of judged reps).
     */
    plateauSeconds: Double = 1.0,
    /**
     * Absolute backstop, timed from the bottom of the rep: measure anyway rather than let a
     * rep sit unreported forever.
     *
     * This is deliberately far longer than any real ascent, because it is a safety net and
     * not a schedule. It used to be 2.0 s, which was **shorter than the lift itself** — real
     * ascents run to 3.2 s at p90 across the 16-session set, so 22% of reps hit the cap and
     * were measured part-way up. That truncated [FormAnalyzer.RepMetrics.romUp] on 18% of
     * reps (to 0.68 of its true value at p10) and was the reason INCOMPLETE_LOCKOUT fired on
     * reps that had in fact locked out: measured romUp/romDown had a p10 of 0.54 where the
     * true value is 0.89. The fault was reading a measurement artefact.
     *
     * A rep whose trace is still climbing is not stuck, it is mid-lift, so the plateau test
     * below — not a stopwatch started at the valley — is what decides when the lift is over.
     */
    hardCapSeconds: Double = 8.0,
    /** How far back to hunt for the true start of the descent. */
    lookBackSeconds: Double = 5.0,
    /**
     * Reports the rep's metrics along with everything else that is only knowable here, at
     * the point the true top-to-top landmarks are finally resolved: see [RepTrace].
     */
    private val onMetrics: (FormAnalyzer.RepMetrics, RepTrace) -> Unit,
) {
    /**
     * What the rep looked like, beyond the numbers measured from it.
     *
     * @param window the span of sample indices the rep occupied. This is what lets a second
     *   sensor — the glasses' head IMU — be asked what it saw *during this rep*.
     * @param depthShape the rep's own orientation trace, resampled to a fixed number of
     *   points and expressed as **degrees below the top it started from**: 0 at the start,
     *   rising to the bottom of the rep, returning toward 0 at lockout.
     *
     *   Fixed-length and top-relative so reps are directly comparable no matter how long
     *   they took or where the strap sat — plotting raw pitch would make a slow rep wider
     *   than a fast one and put every lifter on a different baseline. Degrees are kept
     *   rather than normalised to 0..1, because a rep getting shallower through a set is
     *   exactly what the shape is for and scaling that away would hide it.
     */
    data class RepTrace(val window: IntRange, val depthShape: List<Double>)

    private val plateauSamples = (plateauSeconds * sampleRateHz).toInt()
    private val hardCapSamples = (hardCapSeconds * sampleRateHz).toInt()
    private val lookBack = (lookBackSeconds * sampleRateHz).toInt()

    /** A drop this far below the running max means we've left the plateau at the top. */
    private val topDropDeg = 20.0

    private class Pending(val repIndex: Int, val valley: Int) {
        var maxIdx = valley
        var maxVal = Double.NEGATIVE_INFINITY
    }

    private val pending = ArrayList<Pending>()

    fun reset() = pending.clear()

    /** Register a rep the detector has just confirmed. */
    fun onRep(repIndex: Int, valley: Int) {
        pending.add(Pending(repIndex, valley))
    }

    /**
     * Call once per new sample, passing the detector's own orientation buffer (no second
     * copy is kept here). Emits metrics for any rep whose movement has now played out.
     */
    fun onSample(orientation: List<Double>) {
        if (pending.isEmpty()) return
        val last = orientation.size - 1
        if (last < 0) return
        val v = orientation[last]

        var i = 0
        while (i < pending.size) {
            val p = pending[i]
            if (v > p.maxVal) { p.maxVal = v; p.maxIdx = last }
            val settled = (last - p.maxIdx) >= plateauSamples
            val gaveUp = (last - p.valley) >= hardCapSamples
            if (settled || gaveUp) {
                emit(orientation, p)
                pending.removeAt(i)
            } else i++
        }
    }

    private fun emit(orientation: List<Double>, p: Pending) {
        val startPeak = findDescentStart(orientation, p.valley)
        val top = p.maxIdx
        FormMetrics.extract(orientation, startPeak, p.valley, top, sampleRateHz, p.repIndex)
            ?.let { onMetrics(it, RepTrace(startPeak..top, depthShape(orientation, startPeak, top))) }
    }

    /**
     * The rep's trace as depth below the top it began from, resampled to [SHAPE_POINTS].
     *
     * Linear interpolation onto a fixed grid, then a light 3-point smooth. The orientation
     * buffer is already filtered, so this is only to keep the drawn line from picking up
     * single-sample steps once a two-second rep is squeezed into twenty pixels.
     */
    private fun depthShape(orientation: List<Double>, from: Int, to: Int): List<Double> {
        val span = to - from
        if (span < 2) return emptyList()
        val start = orientation[from]
        val raw = DoubleArray(SHAPE_POINTS) { k ->
            val pos = from + span * k.toDouble() / (SHAPE_POINTS - 1)
            val i = pos.toInt().coerceIn(from, to)
            val j = (i + 1).coerceAtMost(to)
            val f = pos - i
            // Positive = below the top, which is the direction the eye reads as "down".
            start - (orientation[i] * (1 - f) + orientation[j] * f)
        }
        return List(SHAPE_POINTS) { k ->
            val a = raw[(k - 1).coerceAtLeast(0)]
            val b = raw[k]
            val c = raw[(k + 1).coerceAtMost(SHAPE_POINTS - 1)]
            (a + b + c) / 3.0
        }
    }

    /**
     * Walk back from the bottom of the rep to where the descent actually began.
     *
     * Going backwards the trace climbs to the top and then falls away again into the
     * previous rep's ascent, so the highest point found before it starts dropping is the
     * top this rep was lowered from. In a continuous set that is the same peak the previous
     * rep finished on, which is correct — consecutive reps share it.
     */
    private companion object {
        /**
         * Points kept per rep for the summary's shape graph. Enough to show where in the rep
         * a stall or a fast drop happened; small enough that a long workout's shapes stay a
         * few kB in the saved JSON.
         */
        const val SHAPE_POINTS = 28
    }

    private fun findDescentStart(orientation: List<Double>, valley: Int): Int {
        val limit = maxOf(0, valley - lookBack)
        var bestIdx = valley
        var bestVal = orientation[valley]
        for (i in valley downTo limit) {
            val x = orientation[i]
            if (x > bestVal) { bestVal = x; bestIdx = i }
            else if (x < bestVal - topDropDeg) break   // past the top, into the prior rep
        }
        return bestIdx
    }
}
