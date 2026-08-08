package com.liftright.repdetection

import kotlin.math.asin
import kotlin.math.sqrt

/**
 * Streaming orientation estimator that collapses the 6-axis IMU stream into the
 * single "pitch" signal the rest of the pipeline works on.
 *
 * ## Porting note
 * The original engine used MATLAB's `imufilter`, an error-state Kalman AHRS. That
 * exact filter is not reasonably portable by hand, so this class uses a Madgwick
 * gradient-descent AHRS — a well-established, numerically stable equivalent that
 * tracks the same gravity-referenced orientation. The final pitch is extracted with
 * the *identical* formula the original used:
 *
 *     pitch = deg( asin( -2 * (qx*qz - qw*qy) ) ) + 90
 *
 * The result is functionally equivalent but not bit-identical to the Kalman output,
 * so the DTW / activity thresholds may benefit from light retuning against your own
 * sensor. Everything downstream of this class is an exact port.
 *
 * Inputs are the "sanitised" accelerometer (m/s^2-scale) and gyroscope (rad/s)
 * values; unit conversion and calibration offsets are applied by [RepDetector].
 */
internal class OrientationFilter(
    /** Filter gain. Higher = trusts the accelerometer more (faster, noisier). */
    private val beta: Double = 0.08,
) {
    private val rad2deg = 57.295779513082323

    // Orientation quaternion (w, x, y, z), initialised to identity.
    private var q0 = 1.0
    private var q1 = 0.0
    private var q2 = 0.0
    private var q3 = 0.0

    /** Reset the filter to the identity orientation (call before each new session). */
    fun reset() {
        q0 = 1.0; q1 = 0.0; q2 = 0.0; q3 = 0.0
    }

    /**
     * Seed the orientation from the first accelerometer reading (gravity direction) so a
     * session doesn't begin with a ~1 s settling transient from the identity pose — that
     * transient previously swallowed the first rep of the first set. Pre-converges using
     * the filter's own gradient step (zero gyro), keeping the seed consistent with this
     * filter's pitch convention. Validated against the reference orientation: raises the
     * (best-lag) correlation from 0.93 to 0.98 on the Oct20 data.
     */
    fun initFromAccel(ax: Double, ay: Double, az: Double) {
        reset()
        repeat(2000) { update(ax, ay, az, 0.0, 0.0, 0.0, 1.0 / 50.0) }
    }

    /**
     * Advance the filter by one sample and return the pitch angle in degrees.
     *
     * @param ax,ay,az accelerometer (any consistent scale; magnitude is normalised out)
     * @param gx,gy,gz gyroscope in radians / second
     * @param dt       sample period in seconds
     */
    fun update(
        ax: Double, ay: Double, az: Double,
        gx: Double, gy: Double, gz: Double,
        dt: Double,
    ): Double {
        // Rate of change of quaternion from the gyroscope.
        var qDot0 = 0.5 * (-q1 * gx - q2 * gy - q3 * gz)
        var qDot1 = 0.5 * (q0 * gx + q2 * gz - q3 * gy)
        var qDot2 = 0.5 * (q0 * gy - q1 * gz + q3 * gx)
        var qDot3 = 0.5 * (q0 * gz + q1 * gy - q2 * gx)

        // Only apply the accelerometer correction if it is a usable measurement.
        val aNorm = sqrt(ax * ax + ay * ay + az * az)
        if (aNorm > 1e-9) {
            val nx = ax / aNorm
            val ny = ay / aNorm
            val nz = az / aNorm

            // Auxiliary variables to avoid repeated arithmetic.
            val _2q0 = 2.0 * q0
            val _2q1 = 2.0 * q1
            val _2q2 = 2.0 * q2
            val _2q3 = 2.0 * q3
            val _4q0 = 4.0 * q0
            val _4q1 = 4.0 * q1
            val _4q2 = 4.0 * q2
            val _8q1 = 8.0 * q1
            val _8q2 = 8.0 * q2
            val q0q0 = q0 * q0
            val q1q1 = q1 * q1
            val q2q2 = q2 * q2
            val q3q3 = q3 * q3

            // Gradient descent step (objective = accel vs. gravity direction).
            var s0 = _4q0 * q2q2 + _2q2 * nx + _4q0 * q1q1 - _2q1 * ny
            var s1 = _4q1 * q3q3 - _2q3 * nx + 4.0 * q0q0 * q1 - _2q0 * ny - _4q1 +
                _8q1 * q1q1 + _8q1 * q2q2 + _4q1 * nz
            var s2 = 4.0 * q0q0 * q2 + _2q0 * nx + _4q2 * q3q3 - _2q3 * ny - _4q2 +
                _8q2 * q1q1 + _8q2 * q2q2 + _4q2 * nz
            var s3 = 4.0 * q1q1 * q3 - _2q1 * nx + 4.0 * q2q2 * q3 - _2q2 * ny

            val sNorm = sqrt(s0 * s0 + s1 * s1 + s2 * s2 + s3 * s3)
            if (sNorm > 1e-9) {
                s0 /= sNorm; s1 /= sNorm; s2 /= sNorm; s3 /= sNorm
                qDot0 -= beta * s0
                qDot1 -= beta * s1
                qDot2 -= beta * s2
                qDot3 -= beta * s3
            }
        }

        // Integrate to yield the new quaternion, then normalise.
        q0 += qDot0 * dt
        q1 += qDot1 * dt
        q2 += qDot2 * dt
        q3 += qDot3 * dt
        val qNorm = sqrt(q0 * q0 + q1 * q1 + q2 * q2 + q3 * q3)
        if (qNorm > 1e-9) {
            q0 /= qNorm; q1 /= qNorm; q2 /= qNorm; q3 /= qNorm
        }

        // Same pitch extraction as the original engine.
        var arg = -2.0 * (q1 * q3 - q0 * q2)
        if (arg > 1.0) arg = 1.0
        if (arg < -1.0) arg = -1.0
        return rad2deg * asin(arg) + 90.0
    }
}
