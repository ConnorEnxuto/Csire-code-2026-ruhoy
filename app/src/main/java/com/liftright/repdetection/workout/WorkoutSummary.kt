package com.liftright.repdetection.workout

import com.liftright.repdetection.form.FormAnalyzer
import com.liftright.repdetection.head.HeadFlag
import com.liftright.repdetection.head.HeadMotion
import java.io.File

/**
 * One rep as it will appear in the workout summary.
 *
 * [judged] matters: the opening reps of a set have no baseline to be compared against yet,
 * so they carry no faults *because nothing looked at them*, not because they were clean.
 * Counting them as perfect would inflate the score, so the summary excludes them from the
 * percentage and says how many were left out.
 */
data class RepRecord(
    val repIndex: Int,
    /**
     * The detector's own number for this rep, shared with [com.liftright.repdetection.Repetition.index].
     * Only used to match a measured rep against the reps that were actually counted; see
     * the note on the workout's record-keeping in the view model.
     */
    val detectorIndex: Int,
    val setNumber: Int,
    val repInSet: Int,
    val romDown: Double,
    val faults: List<FormAnalyzer.Fault>,
    val judged: Boolean,
    /**
     * What the glasses' own IMU saw while this rep happened, or null when head data did not
     * cover it (no head sensor, or the rep fell outside what the tracker held).
     *
     * Deliberately kept apart from [faults]: head movement is context about how the rep was
     * performed, not a barbell-form fault, and it is reported only in the summary rather
     * than cued mid-set.
     */
    val head: HeadMotion? = null,
    /**
     * The rep's own path, as degrees below the top it started from — 0 at the start, deepest
     * at the bottom, back toward 0 at lockout. Fixed length, so reps line up side by side
     * regardless of how long each took. Empty when the rep was too short to shape.
     */
    val shape: List<Double> = emptyList(),
)

/** Everything the end-of-workout screen shows, derived from the reps it was given. */
class WorkoutSummary(
    val startedAtMs: Long,
    val endedAtMs: Long,
    val reps: List<RepRecord>,
) {
    val durationMs: Long get() = (endedAtMs - startedAtMs).coerceAtLeast(0)
    val totalReps: Int get() = reps.size
    val totalSets: Int get() = reps.maxOfOrNull { it.setNumber } ?: 0
    val repsPerSet: List<Int>
        get() = (1..totalSets).map { s -> reps.count { it.setNumber == s } }

    private val judged get() = reps.filter { it.judged }
    val judgedReps: Int get() = judged.size
    val unjudgedReps: Int get() = reps.size - judged.size
    val perfectReps: Int get() = judged.count { it.faults.isEmpty() }

    /** Share of *judged* reps with no fault at all. Null when nothing could be judged. */
    val perfectPct: Int?
        get() = if (judged.isEmpty()) null else (perfectReps * 100.0 / judged.size).toInt()

    /** How many times each fault fired, most frequent first. */
    val faultCounts: List<Pair<FormAnalyzer.Fault, Int>>
        get() = reps.flatMap { it.faults }.groupingBy { it }.eachCount()
            .toList().sortedByDescending { it.second }

    /** Where each fault fired, as set/rep labels — "S2R4" reads at a glance. */
    fun locationsOf(fault: FormAnalyzer.Fault): List<String> =
        reps.filter { fault in it.faults }.map { "S${it.setNumber}R${it.repInSet}" }

    /** Descent range per rep, in order — the series the summary graph plots. */
    val romSeries: List<Double> get() = reps.map { it.romDown }

    /** True once at least one rep carries a traced shape worth drawing. */
    val hasShapes: Boolean get() = reps.any { it.shape.size >= 3 }

    /** Deepest point reached anywhere in the workout, in degrees — the shape graph's scale. */
    val maxShapeDepth: Double
        get() = reps.flatMap { it.shape }.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0

    /**
     * Index of the first rep of each set after the first, i.e. where a separator belongs.
     * Derived from the reps themselves so it stays right after a retroactive withdrawal.
     */
    val setBoundaries: List<Int>
        get() = reps.indices.filter { i -> i > 0 && reps[i].setNumber != reps[i - 1].setNumber }

    // ---- head movement -------------------------------------------------------------
    // Reported separately from faults. The head IMU is a second sensor that can be absent
    // or drop out independently of the arm sensor, so "how many reps could be checked" is
    // part of the answer rather than an assumption.

    private val headTracked get() = reps.mapNotNull { r -> r.head?.let { r to it } }

    /** Reps the head sensor actually covered. */
    val headTrackedReps: Int get() = headTracked.size

    /** Reps whose head movement tripped at least one threshold. */
    val headFlaggedReps: Int get() = headTracked.count { it.second.isUnusual }

    /** How many times each kind of head movement fired, most frequent first. */
    val headFlagCounts: List<Pair<HeadFlag, Int>>
        get() = headTracked.flatMap { it.second.flags }.groupingBy { it }.eachCount()
            .toList().sortedByDescending { it.second }

    /** Where a given kind of head movement fired, as set/rep labels. */
    fun headLocationsOf(flag: HeadFlag): List<String> =
        headTracked.filter { flag in it.second.flags }.map { "S${it.first.setNumber}R${it.first.repInSet}" }

    /**
     * Typical head travel per rep, in degrees — the median, so one wild rep doesn't set the
     * lifter's normal. Null when nothing was tracked.
     */
    val medianHeadExcursionDeg: Double?
        get() {
            val xs = headTracked.map { it.second.excursionDeg }.sorted()
            return if (xs.isEmpty()) null else xs[xs.size / 2]
        }

    /** Fastest head rotation seen in the whole workout, in deg/s. Null when nothing was tracked. */
    val peakHeadRateDegS: Double?
        get() = headTracked.maxOfOrNull { it.second.peakRateDegS }

    fun toJson(): String = buildString {
        append("{\n")
        append("  \"startedAtMs\": $startedAtMs,\n")
        append("  \"endedAtMs\": $endedAtMs,\n")
        append("  \"durationMs\": $durationMs,\n")
        append("  \"totalReps\": $totalReps,\n")
        append("  \"totalSets\": $totalSets,\n")
        append("  \"repsPerSet\": [${repsPerSet.joinToString(",")}],\n")
        append("  \"judgedReps\": $judgedReps,\n")
        append("  \"unjudgedReps\": $unjudgedReps,\n")
        append("  \"perfectReps\": $perfectReps,\n")
        append("  \"perfectPct\": ${perfectPct ?: -1},\n")
        append("  \"faultCounts\": {")
        append(faultCounts.joinToString(",") { "\"${it.first}\": ${it.second}" })
        append("},\n")
        append("  \"headTrackedReps\": $headTrackedReps,\n")
        append("  \"headFlaggedReps\": $headFlaggedReps,\n")
        append("  \"medianHeadExcursionDeg\": ${fmt(medianHeadExcursionDeg)},\n")
        append("  \"peakHeadRateDegS\": ${fmt(peakHeadRateDegS)},\n")
        append("  \"headFlagCounts\": {")
        append(headFlagCounts.joinToString(",") { "\"${it.first}\": ${it.second}" })
        append("},\n")
        append("  \"reps\": [\n")
        append(reps.joinToString(",\n") { r ->
            "    {\"rep\": ${r.repIndex}, \"det\": ${r.detectorIndex}, " +
                "\"set\": ${r.setNumber}, \"repInSet\": ${r.repInSet}, " +
                "\"romDown\": ${"%.1f".format(r.romDown)}, \"judged\": ${r.judged}, " +
                "\"faults\": [${r.faults.joinToString(",") { "\"$it\"" }}], " +
                "\"head\": ${headJson(r.head)}, " +
                "\"shape\": [${r.shape.joinToString(",") { "%.1f".format(it) }}]}"
        })
        append("\n  ]\n}\n")
    }

    /** null reads as JSON null, so "not tracked" stays distinguishable from zero movement. */
    private fun fmt(v: Double?): String = if (v == null) "null" else "%.1f".format(v)

    private fun headJson(h: HeadMotion?): String = if (h == null) "null" else
        "{\"excursionDeg\": ${"%.1f".format(h.excursionDeg)}, " +
            "\"peakRateDegS\": ${"%.1f".format(h.peakRateDegS)}, " +
            "\"jerkDegS2\": ${"%.0f".format(h.jerkDegS2)}, " +
            "\"samples\": ${h.samples}, " +
            "\"flags\": [${h.flags.joinToString(",") { "\"$it\"" }}]}"

    /**
     * Write the summary to [baseDir] as one JSON file per workout. Returns the file, or null
     * if it could not be written — a failed save must not take the summary screen down with
     * it, since the numbers on screen are still the useful part.
     */
    fun save(baseDir: File): File? = try {
        if (!baseDir.exists()) baseDir.mkdirs()
        File(baseDir, "workout_$startedAtMs.json").apply { writeText(toJson()) }
    } catch (e: Exception) {
        null
    }
}
