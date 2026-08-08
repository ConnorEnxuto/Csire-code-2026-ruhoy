package com.liftright.repdetection.capture

import com.liftright.repdetection.ImuSample
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

/**
 * Writes a live IMU session to disk in the SAME layout as the offline datasets:
 * `accelerometer.csv` + `gyro.csv` (each `x,y,z,time,sample`), a `markers.csv`
 * (set boundaries / calibration poses / detected reps), and a small `meta.txt`.
 *
 * The whole point is that a captured session drops straight into the existing offline
 * harness and the app's own CSV dev-testing panel, turning every real workout into a
 * labelled, replayable regression fixture — which is exactly what the orientation-filter
 * work needs (multiple sessions with ground truth, instead of the single Oct20 recording).
 *
 * Units match the live source and the offline CSVs: accelerometer in g, gyroscope in
 * deg/s. `time` is epoch milliseconds at pairing time (enough to measure the effective
 * sample rate / BLE jitter); the offline loader ignores it and reads only x/y/z.
 *
 * Thread-safe: [logSample] runs on the BLE callback thread while [logMarker]/[close] may
 * run on the main thread, so writes are synchronized. Output is buffered; the sample
 * files are only guaranteed on disk after [close].
 */
class ImuSessionLogger(val directory: File, placement: String) {

    private val accel: BufferedWriter
    private val gyro: BufferedWriter
    private val head: BufferedWriter
    private val markers: BufferedWriter
    private var idx = 0
    private var headIdx = 0
    private var closed = false

    /** Number of samples written so far (also the next sample index a marker will reference). */
    val sampleCount: Int @Synchronized get() = idx

    /** Head readings written so far. Stays 0 on a device with no head IMU. */
    val headSampleCount: Int @Synchronized get() = headIdx

    init {
        directory.mkdirs()
        accel = BufferedWriter(FileWriter(File(directory, "accelerometer.csv")))
        gyro = BufferedWriter(FileWriter(File(directory, "gyro.csv")))
        head = BufferedWriter(FileWriter(File(directory, "head_gyro.csv")))
        markers = BufferedWriter(FileWriter(File(directory, "markers.csv")))
        accel.write("x,y,z,time,sample\n")
        gyro.write("x,y,z,time,sample\n")
        head.write("x,y,z,time,sample\n")
        markers.write("sample,time,type,note\n")
        File(directory, "meta.txt").writeText(
            buildString {
                append("startedAtMillis=").append(System.currentTimeMillis()).append('\n')
                append("placement=").append(placement).append('\n')
                append("accel=50Hz,+/-4g,units=g\n")
                append("gyro=50Hz,125dps,units=deg/s\n")
                append("head_gyro=deviceRate,units=deg/s,placement=glasses\n")
                append("note=accelerometer.csv and gyro.csv share the sample index; ")
                append("markers.csv references the same index. Replayable via the CSV dev panel.\n")
                append("note=head_gyro.csv is a SECOND sensor on its own clock; its `sample` ")
                append("column is the arm sample index current when the reading arrived, ")
                append("which is how the two streams are aligned. Rows are NOT 1:1 with the ")
                append("arm files and the file is empty on a device with no head IMU.\n")
            },
        )
        logMarker("session_start", placement)
    }

    /** One paired accel+gyro reading. Called at the sensor's native rate on the BLE thread. */
    @Synchronized
    fun logSample(s: ImuSample) {
        if (closed) return
        val t = System.currentTimeMillis()
        accel.write("${s.accX},${s.accY},${s.accZ},$t,$idx\n")
        gyro.write("${s.gyroX},${s.gyroY},${s.gyroZ},$t,$idx\n")
        idx++
    }

    /**
     * One reading from the glasses' head IMU (gyroscope, deg/s), stamped with [atArmSample]:
     * the arm stream's sample index at the moment it arrived.
     *
     * Written to its own file rather than added as columns to the arm files: it is a
     * separate sensor arriving on a separate thread at its own rate, so there is no row to
     * attach it to. Stamping it with the arm index is exactly what
     * [com.liftright.repdetection.head.HeadMotionTracker] does live, so a captured session
     * can be replayed through the same alignment offline — which is what the head-movement
     * thresholds need before they can be swept against real data.
     *
     * The index has to be **passed in** rather than read from this class's own [idx]. The two
     * are not the same number: [idx] counts what this logger has written and restarts at 0
     * with every capture, while the tracker indexes against the session's sample count, which
     * does not. Starting a capture part-way through a session would otherwise stamp head rows
     * against a zero-based counter and align the replay wrong by however many samples had
     * already gone by — and on the orientation-replay path, where no arm samples are logged
     * at all, [idx] never leaves 0 and every row would claim to be at the start of the set.
     */
    @Synchronized
    fun logHeadSample(gyroX: Double, gyroY: Double, gyroZ: Double, atArmSample: Int) {
        if (closed) return
        head.write("$gyroX,$gyroY,$gyroZ,${System.currentTimeMillis()},$atArmSample\n")
        headIdx++
    }

    /**
     * Record a labelled event at the current sample index. Types used: `session_start`,
     * `session_end`, `set_start`, `set_end`, `calibration`, `rep`. The marker file is
     * flushed on every write so a crash mid-session still leaves usable labels on disk.
     */
    @Synchronized
    fun logMarker(type: String, note: String = "") {
        if (closed) return
        markers.write("$idx,${System.currentTimeMillis()},$type,${note.replace(',', ';')}\n")
        markers.flush()
    }

    /** Flush and close all files. Safe to call more than once. */
    @Synchronized
    fun close() {
        if (closed) return
        markers.write("$idx,${System.currentTimeMillis()},session_end,head=$headIdx\n")
        accel.flush(); gyro.flush(); head.flush(); markers.flush()
        accel.close(); gyro.close(); head.close(); markers.close()
        closed = true
    }
}
