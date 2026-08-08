package com.liftright.repdetection

import kotlin.math.abs

/**
 * Second-opinion check that runs a few seconds *after* a rep has already been counted, and
 * takes it back if it turns out to have been the lifter lying down or sitting up rather
 * than a repetition.
 *
 * ## Why it has to be retrospective
 * The thing that separates a rep from a posture change is whether the arm **returns to
 * where it started**. A rep begins and ends with the arm extended above the chest; sitting
 * up ends somewhere else entirely and stays there. That cannot be known at the moment the
 * rep completes — only by watching what happens next. So the rep is shown immediately and
 * withdrawn later if it fails, rather than making every rep wait.
 *
 * ## The rule
 * A rep is withdrawn only when BOTH hold:
 *  - the arm's resting level shifted by more than [wideShiftDeg] across the movement
 *    (measured 2-6 s before vs 2-6 s after), and
 *  - the arm did NOT come back to where it started — the top reached after the lift
 *    differs from the top before the descent by more than [selfShiftDeg].
 *
 * The second condition is the important one: it is what stops the last rep of a set being
 * withdrawn just because the lifter sat up straight afterwards. Validated across 16
 * sessions — false positives 41 -> 26, with **zero** real reps withdrawn.
 *
 * Not thread-safe; drive it from the same thread as the detector.
 */
class PostureVerifier(
    private val sampleRateHz: Double = 50.0,
    private val wideShiftDeg: Double = 40.0,
    private val selfShiftDeg: Double = 35.0,
    /** Invoked when a previously-counted rep is withdrawn. */
    private val onRetract: (Repetition) -> Unit,
) {
    private companion object {
        /** How long to keep watching after a rep before judging it. */
        const val VERIFY_AFTER_S = 6.0
        /** How much history to retain: enough for the "before" window plus the rep itself. */
        const val BUFFER_S = 16.0
        /** Cap on how far forward the lift's true top is tracked. */
        const val TOP_SEARCH_S = 4.0
    }

    private val verifyAfter = (VERIFY_AFTER_S * sampleRateHz).toInt()
    private val bufferLen = (BUFFER_S * sampleRateHz).toInt()
    private val topSearch = (TOP_SEARCH_S * sampleRateHz).toInt()

    // Rolling window of recent orientation. `base` is the absolute index of buffer[0], so
    // absolute sample indices from the detector stay meaningful as the window slides.
    private val buffer = ArrayList<Double>(bufferLen + 64)
    private var base = 0

    private class Pending(val rep: Repetition, val startPeak: Int, val valley: Int)
    private val pending = ArrayList<Pending>()

    /** Total reps withdrawn this session — useful for diagnostics. */
    var retracted: Int = 0
        private set

    fun reset() {
        buffer.clear(); base = 0; pending.clear(); retracted = 0
    }

    /** Feed every fused orientation sample, in order, with its absolute index. */
    fun onSample(index: Int, pitch: Double) {
        buffer.add(pitch)
        if (buffer.size > bufferLen) {
            val drop = buffer.size - bufferLen
            repeat(drop) { buffer.removeAt(0) }
            base += drop
        }
        // Judge anything that has now had enough time to play out.
        var i = 0
        while (i < pending.size) {
            val p = pending[i]
            if (index - p.valley >= verifyAfter) {
                if (isPostureChange(p)) {
                    retracted++
                    onRetract(p.rep)
                }
                pending.removeAt(i)
            } else i++
        }
    }

    /** Register a rep that has just been counted and displayed. */
    fun onRep(rep: Repetition, startPeak: Int, valley: Int) {
        pending.add(Pending(rep, startPeak, valley))
    }

    private fun at(absIndex: Int): Double? {
        val i = absIndex - base
        return if (i in buffer.indices) buffer[i] else null
    }

    private fun median(from: Int, to: Int): Double? {
        val vals = ArrayList<Double>(maxOf(0, to - from))
        for (a in from until to) at(a)?.let { vals.add(it) }
        if (vals.size < 3) return null
        vals.sort()
        return vals[vals.size / 2]
    }

    private fun isPostureChange(p: Pending): Boolean {
        val s = sampleRateHz.toInt()
        // Did the resting level shift across the movement?
        val before = median(p.valley - 6 * s, p.valley - 2 * s)
        val after = median(p.valley + 2 * s, p.valley + 6 * s)
        if (before == null || after == null) return false          // can't tell - keep the rep
        if (abs(after - before) <= wideShiftDeg) return false

        // Did the arm come back to where it started? Track the lift to its true top: the
        // detector confirms a rep only partway up the ascent, so the stop peak it reports
        // is not the top of the movement.
        val startLevel = at(p.startPeak) ?: return false
        var top = at(p.valley) ?: return false
        for (a in p.valley..(p.valley + topSearch)) {
            val v = at(a) ?: break
            if (v > top) top = v
            if (v < top - 25.0) break                              // turned over into the next movement
        }
        return abs(top - startLevel) > selfShiftDeg                // did NOT return -> posture change
    }
}
