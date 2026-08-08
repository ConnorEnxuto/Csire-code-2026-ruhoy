package com.liftright.repdetection.sim

import com.liftright.repdetection.ImuSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Streams a pre-recorded session through the *live* sample path at real time, so the whole
 * pipeline downstream of the radio can be exercised without a physical sensor.
 *
 * The CSV dev panel's "Run" button replays a session as fast as possible in one batch,
 * which only tests the detector's maths. This instead emits one sample every 1/rate second
 * on a background thread — exactly how the BLE source delivers them — which additionally
 * exercises the streaming chunking, the live detector's incremental behaviour, the capture
 * logger writing under sustained load, set-marking timing, and the UI updating in real time.
 *
 * What it does NOT cover: the BLE radio itself (scan/connect/GATT/dropouts) and the real
 * sensor's noise and mounting. Use [OnboardImuSource] for live-sensor realism.
 *
 * Pacing uses an absolute schedule off [System.nanoTime] rather than a fixed `delay(20)`,
 * so timing does not drift over a long session.
 */
class ReplayImuSource {

    private var onSample: ((ImuSample) -> Unit)? = null
    private var onOrientation: ((Double) -> Unit)? = null

    /** Register the callback for orientation-only replay; see [startOrientation]. */
    fun setOnOrientation(callback: (Double) -> Unit) {
        onOrientation = callback
    }

    /**
     * Replay a recorded orientation trace instead of raw IMU, at the same real-time pace.
     *
     * Most recorded sessions ship only the fused orientation; raw 6-axis IMU exists for
     * just one. This is what makes the rest of them replayable on-device.
     */
    fun startOrientation(
        values: List<Double>,
        sampleRateHz: Double = 50.0,
        speed: Double = 1.0,
        onFinished: () -> Unit = {},
    ) {
        stop()
        emitted = 0
        val periodNanos = (1_000_000_000.0 / (sampleRateHz * speed)).toLong()
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = newScope
        job = newScope.launch {
            val started = System.nanoTime()
            for ((i, v) in values.withIndex()) {
                if (!isActive) return@launch
                val waitMillis = (started + i * periodNanos - System.nanoTime()) / 1_000_000
                if (waitMillis > 0) delay(waitMillis)
                onOrientation?.invoke(v)
                emitted = i + 1
            }
            onFinished()
        }
    }
    private var scope: CoroutineScope? = null
    private var job: Job? = null

    /** Samples emitted so far in the current replay. */
    @Volatile
    var emitted: Int = 0
        private set

    val isRunning: Boolean get() = job?.isActive == true

    /** Register the callback invoked once per replayed sample. */
    fun setOnSample(callback: (ImuSample) -> Unit) {
        onSample = callback
    }

    /**
     * Begin replaying [samples] at [sampleRateHz]. [speed] > 1 replays faster than real
     * time (handy for long sessions); 1.0 is true real time. Restarts if already running.
     * [onFinished] is invoked on the replay thread once the last sample is emitted.
     */
    fun start(
        samples: List<ImuSample>,
        sampleRateHz: Double = 50.0,
        speed: Double = 1.0,
        onFinished: () -> Unit = {},
    ) {
        stop()
        emitted = 0
        val periodNanos = (1_000_000_000.0 / (sampleRateHz * speed)).toLong()
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = newScope
        job = newScope.launch {
            val started = System.nanoTime()
            for ((i, sample) in samples.withIndex()) {
                if (!isActive) return@launch
                // Absolute schedule: sample i is due at start + i*period (no cumulative drift).
                val dueAt = started + i * periodNanos
                val waitMillis = (dueAt - System.nanoTime()) / 1_000_000
                if (waitMillis > 0) delay(waitMillis)
                onSample?.invoke(sample)
                emitted = i + 1
            }
            onFinished()
        }
    }

    /** Stop any in-progress replay. Safe to call when idle. */
    fun stop() {
        job?.cancel()
        job = null
        scope?.cancel()
        scope = null
    }
}
