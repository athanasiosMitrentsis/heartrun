package de.mitre.heartrun.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import android.view.Display
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

enum class SensorState { Idle, Searching, Connecting, Connected, Disconnected }

data class FoundDevice(val name: String?, val address: String)

/**
 * Spike client for ADR 0001: GATT connection to the Heart Rate Service of the H10.
 * All log lines use tag [TAG], so the test run can be followed with `adb logcat -s HR`.
 * Callers must hold BLUETOOTH_SCAN and BLUETOOTH_CONNECT before calling [startScan]/[connect].
 */
@SuppressLint("MissingPermission")
class HeartRateBleClient(context: Context) {

    companion object {
        const val TAG = "HR"

        // Not covered by an allowed source (polar-ble.md, 1b). The spike logs service discovery
        // to confirm them on the H10.
        val HEART_RATE_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HEART_RATE_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val appContext = context.applicationContext
    private val adapter = appContext.getSystemService(BluetoothManager::class.java).adapter
    private val displayManager = appContext.getSystemService(DisplayManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(SensorState.Idle)
    val state: StateFlow<SensorState> = _state.asStateFlow()

    private val _sample = MutableStateFlow<HeartRateSample?>(null)
    val sample: StateFlow<HeartRateSample?> = _sample.asStateFlow()

    private val _devices = MutableStateFlow<List<FoundDevice>>(emptyList())
    val devices: StateFlow<List<FoundDevice>> = _devices.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private var target: BluetoothDevice? = null
    private var closed = false
    private var lastSampleNanos = 0L

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = FoundDevice(result.device.name, result.device.address)
            _devices.update { list ->
                if (list.any { it.address == device.address }) list
                else list + device.also { Log.d(TAG, "scan: found ${it.name} ${it.address} rssi=${result.rssi}") }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "scan: failed errorCode=$errorCode")
            _state.value = SensorState.Idle
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "gatt: connectionState=$newState status=$status bond=${bondName(g.device.bondState)}")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _state.value = SensorState.Connecting
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _state.value = SensorState.Disconnected
                    g.close()
                    if (gatt == g) gatt = null
                    if (!closed) reconnect()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            Log.d(TAG, "gatt: servicesDiscovered status=$status")
            g.services.forEach { service ->
                Log.d(TAG, "  service ${service.uuid}")
                service.characteristics.forEach { c ->
                    Log.d(TAG, "    characteristic ${c.uuid} properties=0x${c.properties.toString(16)}")
                }
            }
            val characteristic = g.getService(HEART_RATE_SERVICE)?.getCharacteristic(HEART_RATE_MEASUREMENT)
            val descriptor = characteristic?.getDescriptor(CCCD)
            if (characteristic == null || descriptor == null) {
                Log.w(TAG, "gatt: heart rate measurement or CCCD not found")
                return
            }
            g.setCharacteristicNotification(characteristic, true)
            val result = g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            Log.d(TAG, "gatt: writeDescriptor(CCCD=0x01) result=$result")
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.d(TAG, "gatt: descriptorWrite ${descriptor.uuid} status=$status")
            if (descriptor.uuid == CCCD && status == BluetoothGatt.GATT_SUCCESS) {
                _state.value = SensorState.Connected
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            val now = SystemClock.elapsedRealtimeNanos()
            if (characteristic.uuid != HEART_RATE_MEASUREMENT) return
            val parsed = HeartRateParser.parse(value)
            if (parsed == null) {
                Log.w(TAG, "hr: unparseable ${value.toHex()}")
                return
            }
            val deltaMs = if (lastSampleNanos == 0L) 0 else (now - lastSampleNanos) / 1_000_000
            lastSampleNanos = now
            Log.d(
                TAG,
                "hr: t=$now dt=${deltaMs}ms bpm=${parsed.bpm} rr=${parsed.rrMs} " +
                    "contact=${parsed.sensorContact} raw=${value.toHex()}",
            )
            _sample.value = parsed.copy(elapsedRealtimeNanos = now)
        }
    }

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            val bond = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
            Log.d(TAG, "bond: ${device?.address} -> ${bondName(bond)}")
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            val state = displayManager.getDisplay(displayId)?.state
            Log.d(TAG, "display: t=${SystemClock.elapsedRealtimeNanos()} state=${displayStateName(state)}")
        }

        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
    }

    init {
        appContext.registerReceiver(
            bondReceiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            Context.RECEIVER_NOT_EXPORTED,
        )
        displayManager.registerDisplayListener(displayListener, mainHandler)
    }

    fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: run {
            Log.w(TAG, "scan: bluetooth unavailable")
            return
        }
        _devices.value = emptyList()
        _state.value = SensorState.Searching
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(HEART_RATE_SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, scanCallback)
        Log.d(TAG, "scan: started")
    }

    fun stopScan() {
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        Log.d(TAG, "scan: stopped")
    }

    fun connect(address: String) {
        stopScan()
        val device = adapter?.getRemoteDevice(address) ?: return
        target = device
        closed = false
        _state.value = SensorState.Connecting
        Log.d(TAG, "gatt: connect $address bond=${bondName(device.bondState)}")
        gatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    // autoConnect=true lets the stack reconnect as soon as the H10 advertises again.
    private fun reconnect() {
        val device = target ?: return
        Log.d(TAG, "gatt: reconnect ${device.address}")
        _state.value = SensorState.Searching
        gatt = device.connectGatt(appContext, true, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun close() {
        closed = true
        stopScan()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        _state.value = SensorState.Idle
        appContext.unregisterReceiver(bondReceiver)
        displayManager.unregisterDisplayListener(displayListener)
    }

    private fun bondName(state: Int) = when (state) {
        BluetoothDevice.BOND_NONE -> "NONE"
        BluetoothDevice.BOND_BONDING -> "BONDING"
        BluetoothDevice.BOND_BONDED -> "BONDED"
        else -> state.toString()
    }

    private fun displayStateName(state: Int?) = when (state) {
        Display.STATE_ON -> "ON"
        Display.STATE_OFF -> "OFF"
        Display.STATE_DOZE -> "DOZE(ambient)"
        Display.STATE_DOZE_SUSPEND -> "DOZE_SUSPEND(ambient)"
        Display.STATE_ON_SUSPEND -> "ON_SUSPEND"
        else -> state.toString()
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
