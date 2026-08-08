package com.liftright.repdetection

/**
 * Supported exercises. The ordinal `code` matches the original LiftRight iOS app
 * (0 = bench press, 1 = shoulder press, 2 = lat pulldown) and selects the DTW
 * matching threshold used by the detector.
 */
enum class Exercise(val code: Int, val dtwThreshold: Double) {
    BENCH_PRESS(0, 5700.0),
    SHOULDER_PRESS(1, 5000.0),
    LAT_PULLDOWN(2, 10500.0);

    companion object {
        fun fromCode(code: Int): Exercise = values().first { it.code == code }
    }
}

/**
 * A single 6-axis IMU sample. Units match the original algorithm's expectations:
 *  - accelerometer axes in units of g (1 g == 9.81 m/s^2)
 *  - gyroscope axes in degrees / second
 *
 * These are exactly the units the MetaWear sensor produced in the iOS app, so if
 * you stream from the same type of IMU you can pass its readings through unchanged.
 */
data class ImuSample(
    val accX: Double,
    val accY: Double,
    val accZ: Double,
    val gyroX: Double,
    val gyroY: Double,
    val gyroZ: Double,
)

/**
 * Calibration offset captured while the sensor rests still before a set
 * (the "Calibrate" step in the original app). Accelerometer axes in g.
 * Use [ZERO] if you have no calibration step.
 */
data class CalibrationOffset(
    val x: Double,
    val y: Double,
    val z: Double,
) {
    companion object {
        val ZERO = CalibrationOffset(0.0, 0.0, 0.0)
    }
}

/**
 * Metrics for a single confirmed repetition. Mirrors `RepetitionRecord` in the
 * iOS app.
 *
 * @param index          1-based rep number within the current detector run.
 * @param rangeOfMotion  Orientation swing between lift start and lift stop, in degrees.
 * @param durationMs     Concentric (lifting) duration in milliseconds.
 * @param velocity       rangeOfMotion / duration, expressed in degrees per second.
 * @param liftStartIndex Sample index (into the accumulated orientation stream) of lift start.
 * @param liftStopIndex  Sample index of lift stop.
 */
data class Repetition(
    val index: Int,
    val rangeOfMotion: Double,
    val durationMs: Double,
    val velocity: Double,
    val liftStartIndex: Int,
    val liftStopIndex: Int,
)
