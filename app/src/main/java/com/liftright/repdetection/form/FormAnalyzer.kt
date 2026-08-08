package com.liftright.repdetection.form

import kotlin.math.abs

/**
 * Per-rep form analysis from the single arm-worn IMU.
 *
 * ## What this can and cannot see
 * One sensor on the upper arm measures how the arm rotates through the lift. That supports
 * judgements about **depth, tempo, speed and steadiness** — it does NOT see elbow flare,
 * grip width, wrist angle, scapular position, leg drive, or left/right bar tilt, because
 * none of those change this sensor's rotation in a way that can be told apart from the
 * rest of the movement. Faults are limited to what the signal actually supports.
 *
 * ## Why the baseline is the lifter's own early reps
 * Thresholds are relative to the first [BASELINE_REPS] good reps of the current set, not
 * absolute angles. A person's range of motion depends on their limb lengths, the exercise,
 * and exactly where the strap sits — all unknown and variable. Comparing a rep against the
 * same lifter's opening reps in the same set removes every one of those unknowns, and it
 * is what makes "your depth is dropping" meaningful rather than a guess about anatomy.
 *
 * Findings are emitted only once the baseline exists, so the opening reps are never judged.
 */
class FormAnalyzer(
    private val baselineReps: Int = BASELINE_REPS,
    // Every threshold is injectable so the sweep harness can vary one at a time against the
    // 16-session set. The defaults below are the swept values and are what ships.
    private val shallowWarn: Double = SHALLOW_WARN,
    private val shallowBad: Double = SHALLOW_BAD,
    private val lockoutWarn: Double = LOCKOUT_WARN,
    private val lockoutBad: Double = LOCKOUT_BAD,
    private val grindWarn: Double = GRIND_WARN,
    private val grindBad: Double = GRIND_BAD,
    private val dropWarn: Double = DROP_WARN,
    private val dropBad: Double = DROP_BAD,
    private val jerkWarn: Double = JERK_WARN,
    private val jerkBad: Double = JERK_BAD,
    private val ratioWarn: Double = RATIO_WARN,
    private val ratioBad: Double = RATIO_BAD,
) {
    companion object {
        const val BASELINE_REPS = 3

        // ---------------------------------------------------------------------------
        // All swept across the 16-session set (281 judged reps), harness
        // `scratchpad/formx/src/FormSweep.kt`. The originals faulted 58% of all reps,
        // which is noise whatever the ground truth is; these bring it to 28% with no
        // single fault dominating. Sweeping HARDER does not help — a much tighter set
        // reached only 26% while dropping outlier recall 56% -> 51%, because the floor
        // is the union of six roughly-independent checks, not any one of them.
        //
        // CAVEAT on everything below: that sweep ran against a censored measurement of the
        // top of the rep. Fixing it (see [DeferredFormMetrics]) changed what these ratios
        // mean. Re-measured over 36 sessions / 782 judged reps, before vs after the fix:
        //
        //   SHALLOW_DEPTH        9.1% -> 9.1%   unchanged, romDown never depended on the top
        //   DROPPED_ECCENTRIC    3.5% -> 3.5%   unchanged, eccentric ends at the valley
        //   INCOMPLETE_LOCKOUT   7.5% -> 4.3%   the fix was aimed at this one
        //   GRINDING            10.9% -> 20.7%  re-fitted, 2.00 -> 2.80
        //   UNSTEADY             5.8% ->  7.4%  re-fitted
        //   VELOCITY_IMBALANCE   4.9% ->  7.8%  re-fitted
        //
        // The last three all read the ascent, so moving where the rep is judged to end moved
        // them; the first two do not, and their swept values still stand untouched.
        //
        // After re-fitting GRINDING, UNSTEADY and VELOCITY_IMBALANCE the six sit at 25.6% of
        // judged reps in total — GRINDING 10.5, SHALLOW_DEPTH 9.1, UNSTEADY 6.3,
        // VELOCITY_IMBALANCE 4.5, INCOMPLETE_LOCKOUT 4.3, DROPPED_ECCENTRIC 3.5 — which is
        // the original sweep's shape (~28%, nothing dominating) rebuilt on a measurement that
        // is no longer censored. Each was fitted with the others held fixed; a joint sweep
        // over all six at once would supersede this.
        // ---------------------------------------------------------------------------

        // A rep must lose this much of the baseline range before depth is called short.
        private const val SHALLOW_WARN = 0.72    // 8.5% -> 5.7% of reps
        private const val SHALLOW_BAD = 0.58
        // Lockout gets its own, much lower bar. It responded badly to tightening — still
        // 7.8% of reps even at 0.42 — which said the top-of-rep landmark was systematically
        // short rather than that lifters miss lockout this often. That diagnosis was right:
        // the measurement was fixed in [DeferredFormMetrics] (hard cap 2 s -> 8 s, plateau
        // 0.3 s -> 1.0 s) and the rate fell from 7.5% to 4.3% of judged reps at this same
        // threshold, with measured/true romUp at p10 going 0.68 -> 1.00.
        //
        // STILL NOT CLEAN, and still not a threshold problem: 44% of the findings that
        // remain land on a rep whose measured romUp is below its true value, down from 64%.
        // Closing that needs a better test for "the lift is over" than a timeout of any
        // length. Confirming the top by watching the trace fall back off its maximum was
        // tried and does nothing — the plateau always fires first. See [DeferredFormMetrics].
        private const val LOCKOUT_WARN = 0.50    // 7.5% -> 4.3% of reps (via the measurement fix)
        private const val LOCKOUT_BAD = 0.40
        // Concentric slower than this multiple of baseline = grinding.
        //
        // RE-SWEPT after the top-of-rep measurement was fixed (see [DeferredFormMetrics]),
        // because the old numbers were fitted to a censored metric. A 2.0 s cap timed from
        // the bottom of the rep put a hard ceiling of 2000 ms on measured concentric time,
        // which squeezed this rep's time AND the baseline's toward the same ceiling and so
        // compressed their ratio toward 1. The old note here — "2.25 collapses to 2.1% of
        // reps, 3.00 never fires at all" — described that compression, not lifters, and is
        // false against the corrected measurement: 3.00 now fires on 8.8% of reps.
        //
        // Re-swept over the same 36-session set, everything else held fixed:
        //   2.00 -> 20.7% of reps (33.9% flagged overall)
        //   2.60 -> 12.5%         (27.4%)
        //   2.80 -> 10.5%         (26.3%)
        //   3.00 ->  8.8%         (25.2%)
        // 2.80 restores the operating point the original sweep was aiming at — it picked
        // 2.00 to reach 10.3% of reps — and keeps GRINDING from dominating the fault list.
        private const val GRIND_WARN = 2.80      // 20.7% -> 10.5% of reps
        private const val GRIND_BAD = 3.40
        // Eccentric faster than this fraction of baseline = dropping the weight.
        private const val DROP_WARN = 0.55       // 5.3% -> 3.9% of reps
        private const val DROP_BAD = 0.42
        // Smoothness (gradient kurtosis) above this multiple of baseline = unsteady.
        //
        // RE-SWEPT after the top-of-rep fix. Kurtosis is taken across the *whole* rep, so
        // extending the rep to its true top changed the distribution it is measured over and
        // pushed the rate from 5.8% to 7.4% of judged reps at the old 2.60. Swept over 36
        // sessions: 2.60 -> 7.4%, 2.75 -> 6.3%, 2.90 -> 4.3%, 3.20 -> 3.2%.
        private const val JERK_WARN = 2.75       // 7.4% -> 6.3% of reps
        private const val JERK_BAD = 3.55
        // Lift:lower speed ratio below this fraction of baseline = the two phases have gone
        // out of balance. Relative, not absolute: measured ratios vary widely between
        // lifters (textbook tempo implies ~2-3, this dataset's reps sit near 1), so only a
        // change against the lifter's own opening reps means anything.
        //
        // The ratio is a NOISY quantity — its rep-to-rep spread is about as large as its
        // median — so the threshold has to sit well out in the tail or it fires constantly.
        // Swept with the rest over 16 sessions: 0.55 was still 16.4% of reps.
        //
        // RE-SWEPT after the top-of-rep fix. The ratio divides by the ascent's mean velocity,
        // so a rep measured short at the top had an inflated one; correcting it moved the
        // rate from 4.9% to 7.8% of judged reps at the old 0.36. Swept over 36 sessions:
        // 0.36 -> 7.8%, 0.32 -> 5.8%, 0.30 -> 4.5%, 0.29 -> 4.1%, 0.26 -> 2.6%.
        private const val RATIO_WARN = 0.30      // 7.8% -> 4.5% of reps
        private const val RATIO_BAD = 0.21
    }

    /** Raw measurements for one rep, all derived from the fused orientation trace. */
    data class RepMetrics(
        val index: Int,
        val eccentricMs: Double,   // top -> bottom (lowering)
        val concentricMs: Double,  // bottom -> top (lifting)
        val romDown: Double,       // degrees travelled on the way down
        val romUp: Double,         // degrees recovered on the way up
        val peakVelocityUp: Double,// deg/s, fastest point of the lift
        val peakVelocityDown: Double, // deg/s, fastest point of the lowering
        val smoothness: Double,    // kurtosis of the gradient; higher = jerkier
    ) {
        /** Mean angular speed of the lift, deg/s. */
        val meanVelocityUp: Double get() = if (concentricMs > 0) romUp / (concentricMs / 1000.0) else 0.0

        /** Mean angular speed of the lowering, deg/s. */
        val meanVelocityDown: Double get() = if (eccentricMs > 0) romDown / (eccentricMs / 1000.0) else 0.0

        /**
         * Lift speed as a multiple of lowering speed. Above 1 means the bar goes up faster
         * than it comes down, which is what a controlled rep looks like.
         *
         * Uses mean speed rather than the peak samples: peaks sit on a single differentiated
         * sample and are noisy, while the means are range over time and use both phases'
         * measured travel, so the ratio stays meaningful when the two phases cover different
         * distances (a rep cut short at the top has a smaller [romUp] than [romDown]).
         */
        val velocityRatio: Double get() =
            if (meanVelocityDown > 0.0) meanVelocityUp / meanVelocityDown else 0.0
    }

    enum class Fault {
        SHALLOW_DEPTH,        // not coming down as far as your good reps
        INCOMPLETE_LOCKOUT,   // not returning to the top
        GRINDING,             // lift phase slowing markedly - approaching failure
        DROPPED_ECCENTRIC,    // lowering much faster than usual, i.e. not controlled
        UNSTEADY,             // jerky / uneven path through the rep
        VELOCITY_IMBALANCE,   // lift:lower speed ratio collapsed against this lifter's own
    }

    enum class Severity { NOTE, WARN }

    data class Finding(
        val fault: Fault,
        val severity: Severity,
        /**
         * Read mid-set, mid-rep, by someone under a loaded barbell — so it is a glyph, one
         * word, and at most one number, never a sentence. The word says which thing is
         * wrong; the number says how far off. Anything longer does not get read at all.
         */
        val message: String,
        /** How far past the threshold, 0..1, for UI emphasis. */
        val magnitude: Double,
    )

    data class RepReport(
        val metrics: RepMetrics,
        val findings: List<Finding>,
        val hasBaseline: Boolean,
    ) {
        val isClean: Boolean get() = hasBaseline && findings.isEmpty()
    }

    private val setReps = ArrayList<RepMetrics>()
    private val sessionReps = ArrayList<RepMetrics>()
    private var baseRomDown = 0.0
    private var baseRomUp = 0.0
    private var baseConcentric = 0.0
    private var baseEccentric = 0.0
    private var baseSmoothness = 0.0
    private var baseVelocityRatio = 0.0
    private var haveBaseline = false

    /** Call when the user starts a new set; form is judged within one set where possible. */
    fun startSet() {
        setReps.clear()
        // Carry a session-level baseline into the new set so its opening reps - and short
        // sets that never reach [baselineReps] at all - still get judged. Superseded as
        // soon as this set has enough reps of its own.
        haveBaseline = seedFromSession()
    }

    /** Feed one completed rep; returns what (if anything) looked wrong with it. */
    fun analyse(m: RepMetrics): RepReport {
        val findings = ArrayList<Finding>()
        // Snapshot BEFORE this rep can contribute to the baseline: a rep must never be
        // judged against a baseline it helped build, and the report has to say honestly
        // whether this rep was judged at all.
        val judged = haveBaseline

        if (judged) {
            checkDepth(m, findings)
            checkLockout(m, findings)
            checkGrinding(m, findings)
            checkEccentric(m, findings)
            checkSteadiness(m, findings)
            checkVelocityRatio(m, findings)
        }

        setReps.add(m)
        sessionReps.add(m)
        if (setReps.size >= baselineReps) buildBaseline(setReps.take(baselineReps))

        return RepReport(m, findings, judged)
    }

    /** Median of the opening reps - median, not mean, so one odd opening rep can't skew it. */
    private fun buildBaseline(from: List<RepMetrics>) {
        baseRomDown = med(from.map { it.romDown })
        baseRomUp = med(from.map { it.romUp })
        baseConcentric = med(from.map { it.concentricMs })
        baseEccentric = med(from.map { it.eccentricMs })
        baseSmoothness = med(from.map { it.smoothness })
        // Only reps with a usable ratio contribute, so one degenerate rep can't zero it out.
        baseVelocityRatio = med(from.map { it.velocityRatio }.filter { it > 0.0 })
        haveBaseline = baseRomDown > 1.0 && baseConcentric > 0.0
    }

    /**
     * Provisional baseline for a new set, from everything seen this session. Weaker than a
     * within-set baseline (load and fatigue shift between sets), so it only fills the gap
     * until the current set has [baselineReps] of its own.
     */
    private fun seedFromSession(): Boolean {
        if (sessionReps.size < baselineReps) return false
        buildBaseline(sessionReps)
        return haveBaseline
    }

    private fun checkDepth(m: RepMetrics, out: MutableList<Finding>) {
        if (baseRomDown <= 1.0) return
        val frac = m.romDown / baseRomDown
        if (frac >= shallowWarn) return
        val pct = ((1 - frac) * 100).toInt()
        out.add(Finding(
            Fault.SHALLOW_DEPTH,
            if (frac < shallowBad) Severity.WARN else Severity.NOTE,
            "▼ DEPTH  -$pct%",
            ((shallowWarn - frac) / shallowWarn).coerceIn(0.0, 1.0),
        ))
    }

    private fun checkLockout(m: RepMetrics, out: MutableList<Finding>) {
        if (baseRomUp <= 1.0) return
        val frac = m.romUp / baseRomUp
        if (frac >= lockoutWarn) return
        // Only call it a lockout problem when the descent was fine - otherwise the rep was
        // simply short at both ends and SHALLOW_DEPTH already says so.
        if (m.romDown < baseRomDown * shallowWarn) return
        val pct = ((1 - frac) * 100).toInt()
        out.add(Finding(
            Fault.INCOMPLETE_LOCKOUT,
            if (frac < lockoutBad) Severity.WARN else Severity.NOTE,
            "▲ LOCKOUT  -$pct%",
            ((lockoutWarn - frac) / lockoutWarn).coerceIn(0.0, 1.0),
        ))
    }

    private fun checkGrinding(m: RepMetrics, out: MutableList<Finding>) {
        if (baseConcentric <= 0.0) return
        val ratio = m.concentricMs / baseConcentric
        if (ratio <= grindWarn) return
        out.add(Finding(
            Fault.GRINDING,
            if (ratio > grindBad) Severity.WARN else Severity.NOTE,
            "◷ SLOW  %.1fx".format(ratio),
            ((ratio - grindWarn) / grindWarn).coerceIn(0.0, 1.0),
        ))
    }

    private fun checkEccentric(m: RepMetrics, out: MutableList<Finding>) {
        if (baseEccentric <= 0.0) return
        val ratio = m.eccentricMs / baseEccentric
        if (ratio >= dropWarn) return
        out.add(Finding(
            Fault.DROPPED_ECCENTRIC,
            if (ratio < dropBad) Severity.WARN else Severity.NOTE,
            "⇊ DROP",
            ((dropWarn - ratio) / dropWarn).coerceIn(0.0, 1.0),
        ))
    }

    private fun checkSteadiness(m: RepMetrics, out: MutableList<Finding>) {
        if (baseSmoothness <= 0.0) return
        val ratio = m.smoothness / baseSmoothness
        if (ratio <= jerkWarn) return
        out.add(Finding(
            Fault.UNSTEADY,
            if (ratio > jerkBad) Severity.WARN else Severity.NOTE,
            "∿ SHAKY",
            ((ratio - jerkWarn) / jerkWarn).coerceIn(0.0, 1.0),
        ))
    }

    /**
     * Lift:lower speed balance. [Fault.DROPPED_ECCENTRIC] asks a different question — it
     * compares this rep's lowering against the *baseline lowering time*, so a rep where
     * both phases slow together passes it. This compares the two phases of the same rep
     * against each other, which catches the descent getting away from the lifter even as
     * the whole rep drags.
     */
    private fun checkVelocityRatio(m: RepMetrics, out: MutableList<Finding>) {
        if (baseVelocityRatio <= 0.0) return
        val ratio = m.velocityRatio
        if (ratio <= 0.0) return                    // a phase with no travel or no time
        val frac = ratio / baseVelocityRatio
        if (frac >= ratioWarn) return
        out.add(Finding(
            Fault.VELOCITY_IMBALANCE,
            if (frac < ratioBad) Severity.WARN else Severity.NOTE,
            // Shown as "how far off your usual", like DEPTH and LOCKOUT. The raw ratio
            // means nothing without the baseline beside it, and two numbers on one line is
            // already too much to take in mid-rep.
            "⇅ TEMPO  -${((1 - frac) * 100).toInt()}%",
            ((ratioWarn - frac) / ratioWarn).coerceIn(0.0, 1.0),
        ))
    }

    private fun med(xs: List<Double>): Double {
        if (xs.isEmpty()) return 0.0
        val s = xs.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
    }

    /** Whole-set summary, for the end-of-set readout. */
    fun setSummary(): SetSummary {
        if (setReps.isEmpty()) return SetSummary(0, 0.0, 0.0, 0.0)
        val first = setReps.take(baselineReps)
        val last = setReps.takeLast(minOf(3, setReps.size))
        val romFade = if (first.isNotEmpty() && med(first.map { it.romDown }) > 1.0)
            1.0 - med(last.map { it.romDown }) / med(first.map { it.romDown }) else 0.0
        val slowdown = if (first.isNotEmpty() && med(first.map { it.concentricMs }) > 0.0)
            med(last.map { it.concentricMs }) / med(first.map { it.concentricMs }) else 1.0
        return SetSummary(setReps.size, romFade, slowdown, med(setReps.map { it.romDown }))
    }

    data class SetSummary(
        val reps: Int,
        /** Fraction of range lost by the end of the set (0 = held depth, 0.3 = lost 30%). */
        val romFade: Double,
        /** Final reps' lift time as a multiple of the opening reps'. */
        val slowdown: Double,
        val medianRom: Double,
    )
}
