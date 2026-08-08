package com.liftright.repdetection.workout

import com.liftright.repdetection.form.FormAnalyzer
import com.liftright.repdetection.head.HeadFlag
import com.liftright.repdetection.head.HeadMotion
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Reads back the workouts [WorkoutSummary.save] has written.
 *
 * The saved JSON is the whole record — every rep, its faults, its traced shape and what the
 * head was doing — so a past workout parses back into a full [WorkoutSummary] and can be
 * shown through exactly the same screen as the one that just finished. No second, thinner
 * model of a workout, and no chance of the history disagreeing with the live summary.
 *
 * Parsing is deliberately forgiving. These files are written by whatever version of the app
 * was installed at the time, so a field that has since been added is simply absent from older
 * ones, and a fault or head flag that has since been renamed no longer resolves. Neither is a
 * reason to lose the workout: unknown enum names are dropped and missing fields fall back to
 * their defaults, so an old file still shows its counts and its percentage.
 */
object WorkoutHistory {

    /**
     * Every saved workout, newest first. Never throws — a directory that does not exist yet,
     * or a file that cannot be read, yields no entry rather than an error, because a corrupt
     * file must not take the whole history down with it.
     *
     * Reads and parses files, so call this off the main thread.
     */
    fun load(baseDir: File?, limit: Int = 30): List<WorkoutSummary> {
        val dir = baseDir ?: return emptyList()
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        return files
            // Sort by filename first — it carries the start time, so the newest `limit`
            // files can be picked without parsing every one of them.
            .sortedByDescending { it.name }
            .take(limit)
            .mapNotNull { f -> runCatching { parse(f.readText()) }.getOrNull() }
            .sortedByDescending { it.startedAtMs }
    }

    /** Parse one saved workout. Returns null if the text is not a workout at all. */
    fun parse(text: String): WorkoutSummary? {
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (!o.has("startedAtMs")) return null
        val repsJson = o.optJSONArray("reps") ?: JSONArray()
        val reps = ArrayList<RepRecord>(repsJson.length())
        for (i in 0 until repsJson.length()) {
            val r = repsJson.optJSONObject(i) ?: continue
            reps.add(
                RepRecord(
                    repIndex = r.optInt("rep", i + 1),
                    detectorIndex = r.optInt("det", r.optInt("rep", i + 1)),
                    setNumber = r.optInt("set", 1),
                    repInSet = r.optInt("repInSet", i + 1),
                    romDown = r.optDouble("romDown", 0.0),
                    faults = faults(r.optJSONArray("faults")),
                    judged = r.optBoolean("judged", false),
                    head = head(r.optJSONObject("head")),
                    shape = doubles(r.optJSONArray("shape")),
                )
            )
        }
        return WorkoutSummary(
            startedAtMs = o.optLong("startedAtMs", 0L),
            endedAtMs = o.optLong("endedAtMs", 0L),
            reps = reps,
        )
    }

    private fun faults(a: JSONArray?): List<FormAnalyzer.Fault> {
        if (a == null) return emptyList()
        val out = ArrayList<FormAnalyzer.Fault>(a.length())
        for (i in 0 until a.length()) {
            val name = a.optString(i) ?: continue
            runCatching { FormAnalyzer.Fault.valueOf(name) }.getOrNull()?.let(out::add)
        }
        return out
    }

    private fun head(o: JSONObject?): HeadMotion? {
        if (o == null) return null
        val flags = ArrayList<HeadFlag>()
        val a = o.optJSONArray("flags")
        if (a != null) for (i in 0 until a.length()) {
            runCatching { HeadFlag.valueOf(a.optString(i)) }.getOrNull()?.let(flags::add)
        }
        return HeadMotion(
            excursionDeg = o.optDouble("excursionDeg", 0.0),
            peakRateDegS = o.optDouble("peakRateDegS", 0.0),
            jerkDegS2 = o.optDouble("jerkDegS2", 0.0),
            samples = o.optInt("samples", 0),
            flags = flags,
        )
    }

    private fun doubles(a: JSONArray?): List<Double> {
        if (a == null) return emptyList()
        return List(a.length()) { a.optDouble(it, 0.0) }
    }
}
