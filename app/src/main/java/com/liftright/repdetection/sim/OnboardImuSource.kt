package com.liftright.repdetection.sim

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.liftright.repdetection.ImuSample

/**
 * Feeds the device's OWN built-in accelerometer + gyroscope into the pipeline, as a
 * stand-in for the upper-arm MetaWear when that sensor isn't to hand.
 *
 * Unlike [ReplayImuSource] this is genuinely live hardware: real sensor noise, real
 * callback threading, real timing jitter, real behaviour when the display sleeps. On the
 * glasses it is the head IMU, so nodding/tilting produces rep-like pitch swings — enough
 * to watch the counter respond and to sanity-check the whole live path end to end.
 *
 * What it does NOT cover: the BLE radio (scan/connect/dropouts) and, importantly, the
 * upper-arm mounting the detector's template expects — head motion has a different shape
 * and amplitude, so treat the resulting *counts* as a smoke test of the plumbing, not as
 * a measure of detection accuracy.
 *
 * Units are converted to match the MetaWear source and the offline CSVs: accelerometer in
 * g (Android reports m/s^2) and gyroscope in deg/s (Android reports rad/s).
 */
class OnboardImuSource(context: Context) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val accelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private var onSample: ((ImuSample) -> Unit)? = null

    // Latest gyro reading, paired with each incoming accelerometer sample. Both are
    // requested at the same rate, so this stays closely aligned (same approach the BLE
    // source uses for its two independent streams).
    private var gyroX = 0.0
    private var gyroY = 0.0
    private var gyroZ = 0.0

    var isRunning: Boolean = false
        private set

    /** True when this device actually exposes both sensors. */
    val isAvailable: Boolean get() = accelSensor != null && gyroSensor != null

    fun setOnSample(callback: (ImuSample) -> Unit) {
        onSample = callback
    }

    /** Begin delivering samples at ~[sampleRateHz] (the OS may honour this only approximately). */
    fun start(sampleRateHz: Double = 50.0) {
        val manager = sensorManager ?: return
        if (isRunning || !isAvailable) return
        val periodMicros = (1_000_000.0 / sampleRateHz).toInt()
        manager.registerListener(this, accelSensor, periodMicros)
        manager.registerListener(this, gyroSensor, periodMicros)
        isRunning = true
    }

    fun stop() {
        if (!isRunning) return
        sensorManager?.unregisterListener(this)
        isRunning = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                gyroX = event.values[0] * RAD2DEG
                gyroY = event.values[1] * RAD2DEG
                gyroZ = event.values[2] * RAD2DEG
            }
            Sensor.TYPE_ACCELEROMETER -> {
                onSample?.invoke(
                    ImuSample(
                        accX = event.values[0] / GRAVITY,
                        accY = event.values[1] / GRAVITY,
                        accZ = event.values[2] / GRAVITY,
                        gyroX = gyroX, gyroY = gyroY, gyroZ = gyroZ,
                    ),
                )
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val GRAVITY = 9.81
        const val RAD2DEG = 57.295779513082323
    }
}
