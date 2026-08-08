package com.liftright.repdetection.metawear

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.liftright.repdetection.ImuSample
import com.mbientlab.metawear.MetaWearBoard
import com.mbientlab.metawear.android.BtleService
import com.mbientlab.metawear.data.Acceleration
import com.mbientlab.metawear.data.AngularVelocity
import com.mbientlab.metawear.module.Accelerometer
import com.mbientlab.metawear.module.Gyro
import com.mbientlab.metawear.module.GyroBmi160
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** MetaWear devices advertise under this name (matches the original iOS app's filter). */
private const val DEVICE_NAME = "MetaWear"

sealed interface MetaWearConnectionState {
    data object Idle : MetaWearConnectionState
    data object Scanning : MetaWearConnectionState
    data class Connecting(val deviceLabel: String) : MetaWearConnectionState
    data class Connected(val deviceLabel: String) : MetaWearConnectionState
    data class Disconnected(val reason: String? = null) : MetaWearConnectionState
    data class Error(val message: String) : MetaWearConnectionState
}

/**
 * Live IMU source for the [com.liftright.repdetection.RepDetector] pipeline, backed by a
 * MetaWear wrist/arm-worn sensor over BLE — the same class of device the original
 * LiftRight iOS app used (via MbientLab's MetaWear SDK, `mbl_mw_acc_set_odr(50Hz)` /
 * `mbl_mw_gyro_bmi160_set_odr(50Hz)` / range +/-4g accel, 125dps gyro).
 *
 * Usage: call [bindService] once (e.g. in `onCreate`/`onStart`), [startScan] to find and
 * auto-connect to the first advertising MetaWear device, and [setOnSample] to receive
 * paired accelerometer+gyroscope readings as they arrive. Call [disconnect] and
 * [unbindService] when done (e.g. `onStop`/`onDestroy`).
 *
 * Not thread-safe beyond what the MetaWear SDK itself guarantees; all callbacks land on
 * whatever thread the SDK invokes them on (its Bluetooth GATT callback thread), so
 * consumers touching UI state from [setOnSample] must dispatch to the main thread.
 */
class MetaWearImuSource(private val context: Context) : ServiceConnection {

    private val _state = MutableStateFlow<MetaWearConnectionState>(MetaWearConnectionState.Idle)
    val state: StateFlow<MetaWearConnectionState> = _state

    private var serviceBinder: BtleService.LocalBinder? = null
    private var board: MetaWearBoard? = null
    private var scanning = false

    private var onSample: ((ImuSample) -> Unit)? = null

    // Accel and gyro arrive as independent async callbacks (not one combined sample), so
    // pair them off in arrival order as each side has data - both stream at the same
    // configured ODR (50Hz) so they stay closely aligned.
    private val pendingAccel = ArrayDeque<Acceleration>()
    private val pendingGyro = ArrayDeque<AngularVelocity>()

    /** Register the callback invoked once per paired accel+gyro sample. */
    fun setOnSample(callback: (ImuSample) -> Unit) {
        onSample = callback
    }

    fun bindService() {
        context.applicationContext.bindService(
            Intent(context, BtleService::class.java), this, Context.BIND_AUTO_CREATE,
        )
    }

    fun unbindService() {
        try {
            context.applicationContext.unbindService(this)
        } catch (_: IllegalArgumentException) {
            // Already unbound.
        }
    }

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        serviceBinder = service as BtleService.LocalBinder
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        serviceBinder = null
    }

    /** Scan for a MetaWear device and connect to the first one found. */
    @SuppressLint("MissingPermission")
    fun startScan() {
        if (scanning) return
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            _state.value = MetaWearConnectionState.Error("Bluetooth is off or unsupported")
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            _state.value = MetaWearConnectionState.Error("BLE scanner unavailable")
            return
        }
        try {
            _state.value = MetaWearConnectionState.Scanning
            scanning = true
            scanner.startScan(scanCallback)
        } catch (e: SecurityException) {
            scanning = false
            _state.value = MetaWearConnectionState.Error("Missing Bluetooth scan permission")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!scanning) return
        scanning = false
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {
            // Nothing to do - permission was already missing when we started the scan.
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (result.device.name == DEVICE_NAME) {
                stopScan()
                connect(result.device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            _state.value = MetaWearConnectionState.Error("BLE scan failed (code $errorCode)")
        }
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        val binder = serviceBinder
        if (binder == null) {
            _state.value = MetaWearConnectionState.Error("BLE service not bound yet")
            return
        }
        val label = device.name ?: device.address
        _state.value = MetaWearConnectionState.Connecting(label)

        val newBoard = binder.getMetaWearBoard(device)
        board = newBoard
        newBoard.onUnexpectedDisconnect { status ->
            _state.value = MetaWearConnectionState.Disconnected("BLE status $status")
        }

        newBoard.connectAsync().continueWith { task ->
            if (task.isFaulted) {
                _state.value = MetaWearConnectionState.Error(task.error?.message ?: "Connect failed")
            } else {
                _state.value = MetaWearConnectionState.Connected(label)
                configureAndStream(newBoard)
            }
            null
        }
    }

    private fun configureAndStream(board: MetaWearBoard) {
        val accelerometer = board.getModule(Accelerometer::class.java)
        val gyro = board.getModule(GyroBmi160::class.java)
        if (accelerometer == null || gyro == null) {
            _state.value = MetaWearConnectionState.Error("Board is missing accelerometer/gyro module")
            return
        }

        // Matches the original app's sensor config: 50Hz / +-4g accel, 50Hz / 125dps gyro.
        accelerometer.configure().odr(50f).range(4f).commit()
        gyro.configure().odr(Gyro.OutputDataRate.ODR_50_HZ).range(Gyro.Range.FSR_125).commit()

        accelerometer.acceleration().addRouteAsync { source ->
            source.stream { data, _ -> onAccel(data.value(Acceleration::class.java)) }
        }.continueWith {
            accelerometer.acceleration().start()
            accelerometer.start()
            null
        }

        gyro.angularVelocity().addRouteAsync { source ->
            source.stream { data, _ -> onGyro(data.value(AngularVelocity::class.java)) }
        }.continueWith {
            gyro.angularVelocity().start()
            gyro.start()
            null
        }
    }

    private fun onAccel(a: Acceleration) {
        pendingAccel.addLast(a)
        drainPairs()
    }

    private fun onGyro(g: AngularVelocity) {
        pendingGyro.addLast(g)
        drainPairs()
    }

    private fun drainPairs() {
        while (pendingAccel.isNotEmpty() && pendingGyro.isNotEmpty()) {
            val a = pendingAccel.removeFirst()
            val g = pendingGyro.removeFirst()
            onSample?.invoke(
                ImuSample(
                    accX = a.x().toDouble(), accY = a.y().toDouble(), accZ = a.z().toDouble(),
                    gyroX = g.x().toDouble(), gyroY = g.y().toDouble(), gyroZ = g.z().toDouble(),
                ),
            )
        }
    }

    /** Disconnect from the board, if connected. Safe to call even if never connected. */
    @SuppressLint("MissingPermission")
    fun disconnect() {
        stopScan()
        val current = board ?: run {
            _state.value = MetaWearConnectionState.Idle
            return
        }
        pendingAccel.clear()
        pendingGyro.clear()
        current.disconnectAsync().continueWith {
            current.tearDown()
            board = null
            _state.value = MetaWearConnectionState.Idle
            null
        }
    }
}
