package com.example.meshmash

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.nio.charset.StandardCharsets
import java.util.UUID

/** The same small sender/listener transport used by [BleMeshTestActivity]. */
@SuppressLint("MissingPermission") // The Activity checks runtime permissions before every action.
class BleMeshNode(
    context: Context,
    private val onStatus: (String) -> Unit,
    private val onMessage: (String) -> Unit,
    private val onSendFinished: (Boolean) -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter?
        get() = bluetoothManager.adapter

    private var gattServer: BluetoothGattServer? = null
    private var clientGatt: BluetoothGatt? = null
    private var pendingPayload: ByteArray? = null
    private var listening = false
    private var sending = false
    private var negotiatedMtu = DEFAULT_MTU
    private var scanResultsSeen = 0
    private val incompatiblePeerAddresses = mutableSetOf<String>()
    private val scanSettings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .setReportDelay(0)
        .build()

    private val sendTimeout: Runnable = Runnable {
        if (!sending) return@Runnable
        val message = when {
            clientGatt != null -> "Listener was found, but the BLE connection timed out"
            scanResultsSeen == 0 ->
                "No BLE devices found. Check Nearby Devices permission and Bluetooth"
            else -> "No MeshMash listener found; saw $scanResultsSeen other BLE devices"
        }
        failSend(message)
    }

    val isBluetoothEnabled: Boolean
        get() = adapter?.isEnabled == true
    val isListening: Boolean
        get() = listening

    fun listen(): Boolean {
        val bluetoothAdapter = adapter
        if (bluetoothAdapter == null) {
            onStatus("Bluetooth is not supported on this phone")
            return false
        }
        if (!bluetoothAdapter.isEnabled) {
            onStatus("Turn Bluetooth on, then try again")
            return false
        }
        if (listening) return true
        if (bluetoothAdapter.bluetoothLeAdvertiser == null) {
            onStatus("This phone does not support BLE advertising")
            return false
        }

        val characteristic = BluetoothGattCharacteristic(
            MESSAGE_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val service = BluetoothGattService(
            MESH_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        ).apply { addCharacteristic(characteristic) }
        val server = bluetoothManager.openGattServer(appContext, serverCallback)
        if (server == null) {
            onStatus("Could not open the BLE listener")
            return false
        }
        gattServer = server
        listening = true
        onStatus("Starting listener…")
        if (!server.addService(service)) {
            onStatus("Could not register the BLE service")
            stopListening()
            return false
        }
        return true
    }

    fun send(message: String): Boolean {
        val bluetoothAdapter = adapter
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            onStatus("Turn Bluetooth on, then tap Send again")
            return false
        }
        if (sending) {
            onStatus("A send is already in progress")
            return false
        }
        val payload = message.toByteArray(StandardCharsets.UTF_8)
        if (payload.isEmpty()) return false
        if (payload.size > MAX_MESSAGE_BYTES) {
            onStatus("Data is too large (maximum $MAX_MESSAGE_BYTES UTF-8 bytes)")
            return false
        }
        val scanner = bluetoothAdapter.bluetoothLeScanner
        if (scanner == null) {
            onStatus("BLE scanning is unavailable")
            return false
        }
        pendingPayload = payload
        sending = true
        negotiatedMtu = DEFAULT_MTU
        scanResultsSeen = 0
        incompatiblePeerAddresses.clear()
        onStatus("Looking for a listening phone…")
        scanner.startScan(null, scanSettings, scanCallback)
        mainHandler.removeCallbacks(sendTimeout)
        mainHandler.postDelayed(sendTimeout, SEND_TIMEOUT_MS)
        return true
    }

    fun stop() {
        stopSending()
        stopListening()
    }

    /** Stops a background attempt so a user-initiated packet can start immediately. */
    fun cancelCurrentSend() {
        stopSending()
    }

    private fun startAdvertising() {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(MESH_SERVICE_UUID))
            .addManufacturerData(MANUFACTURER_ID, MESH_MARKER)
            .setIncludeDeviceName(false)
            .build()
        adapter?.bluetoothLeAdvertiser?.startAdvertising(settings, data, advertiseCallback)
    }

    private fun writePendingData(gatt: BluetoothGatt) {
        val payload = pendingPayload ?: return failSend("Nothing to send")
        val characteristic = gatt.getService(MESH_SERVICE_UUID)
            ?.getCharacteristic(MESSAGE_CHARACTERISTIC_UUID)
            ?: return failSend("Listener service was not found")
        if (payload.size > negotiatedMtu - ATT_HEADER_BYTES) {
            return failSend("Phone negotiated only $negotiatedMtu-byte MTU; packet is too large")
        }
        val result = gatt.writeCharacteristic(
            characteristic,
            payload,
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
        )
        if (result == BluetoothGatt.GATT_SUCCESS) onStatus("Sending data…")
        else failSend("Could not send data (Bluetooth error $result)")
    }

    private fun failSend(message: String) {
        val notify = sending
        onStatus(message)
        clientGatt?.disconnect()
        stopSending()
        if (notify) onSendFinished(false)
    }

    private fun ignoreIncompatiblePeerAndContinue(gatt: BluetoothGatt) {
        incompatiblePeerAddresses += gatt.device.address
        if (clientGatt === gatt) clientGatt = null
        gatt.disconnect()
        gatt.close()
        negotiatedMtu = DEFAULT_MTU
        if (!sending) return
        onStatus("Incompatible listener skipped; still looking…")
        val scanner = adapter?.bluetoothLeScanner
            ?: return failSend("BLE scanning is unavailable")
        scanner.startScan(null, scanSettings, scanCallback)
    }

    private fun stopSending() {
        mainHandler.removeCallbacks(sendTimeout)
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        clientGatt?.disconnect()
        clientGatt?.close()
        clientGatt = null
        pendingPayload = null
        sending = false
        negotiatedMtu = DEFAULT_MTU
        incompatiblePeerAddresses.clear()
    }

    private fun stopListening() {
        if (!listening) return
        adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        gattServer?.clearServices()
        gattServer?.close()
        gattServer = null
        listening = false
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            onStatus("Listening for data…")
        }

        override fun onStartFailure(errorCode: Int) {
            onStatus("Could not listen: advertising error $errorCode")
            stopListening()
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!sending || clientGatt != null) return
            if (result.device.address in incompatiblePeerAddresses) return
            scanResultsSeen++
            val scanRecord = result.scanRecord
            val serviceMatches = scanRecord?.serviceUuids?.any {
                it.uuid == MESH_SERVICE_UUID
            } == true
            val markerMatches = scanRecord
                ?.getManufacturerSpecificData(MANUFACTURER_ID)
                ?.contentEquals(MESH_MARKER) == true
            if (!serviceMatches && !markerMatches) return
            adapter?.bluetoothLeScanner?.stopScan(this)
            onStatus("Listener found; connecting…")
            clientGatt = result.device.connectGatt(
                appContext,
                false,
                clientCallback,
                BluetoothDevice.TRANSPORT_LE,
            )
        }

        override fun onScanFailed(errorCode: Int) {
            failSend("Could not scan for listener (error $errorCode)")
        }
    }

    private val clientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                clientGatt = gatt
                onStatus("Connected; preparing data…")
                if (!gatt.requestMtu(REQUESTED_MTU)) gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val wasActiveConnection = clientGatt === gatt
                gatt.close()
                if (wasActiveConnection) clientGatt = null
                if (sending && wasActiveConnection) {
                    failSend("Connection ended before data was sent")
                }
            } else if (status != BluetoothGatt.GATT_SUCCESS) {
                failSend("Connection failed (Bluetooth error $status)")
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            negotiatedMtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else DEFAULT_MTU
            onStatus("Finding listener service…")
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failSend("Could not discover listener service (error $status)")
                return
            }
            val characteristic = gatt.getService(MESH_SERVICE_UUID)
                ?.getCharacteristic(MESSAGE_CHARACTERISTIC_UUID)
            if (characteristic == null) ignoreIncompatiblePeerAndContinue(gatt)
            else writePendingData(gatt)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            // A callback from a connection that was preempted must not complete the new send.
            if (!sending || clientGatt !== gatt) {
                gatt.close()
                return
            }
            if (status == BluetoothGatt.GATT_SUCCESS) {
                onStatus("Data sent successfully")
                pendingPayload = null
                sending = false
                mainHandler.removeCallbacks(sendTimeout)
                gatt.disconnect()
                onSendFinished(true)
            } else {
                failSend("Send failed (Bluetooth error $status)")
            }
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(
            device: BluetoothDevice,
            status: Int,
            newState: Int,
        ) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                onStatus("Sender connected; waiting for data…")
            } else if (listening) {
                onStatus("Listening for data…")
            }
        }

        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (status == BluetoothGatt.GATT_SUCCESS && service.uuid == MESH_SERVICE_UUID) {
                startAdvertising()
            } else {
                onStatus("Could not register listener service (error $status)")
                stopListening()
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            val valid = characteristic.uuid == MESSAGE_CHARACTERISTIC_UUID &&
                !preparedWrite && offset == 0 && value.size <= MAX_MESSAGE_BYTES
            if (valid) {
                onMessage(value.toString(StandardCharsets.UTF_8))
                onStatus("Data received — still listening…")
            }
            if (responseNeeded) {
                gattServer?.sendResponse(
                    device,
                    requestId,
                    if (valid) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE,
                    0,
                    null,
                )
            }
        }
    }

    companion object {
        private val MESH_SERVICE_UUID =
            UUID.fromString("00002222-0000-1000-8000-00805f9b34fb")
        private val MESSAGE_CHARACTERISTIC_UUID =
            UUID.fromString("00001111-0000-1000-8000-00805f9b34fb")
        private const val DEFAULT_MTU = 23
        private const val REQUESTED_MTU = 517
        private const val ATT_HEADER_BYTES = 3
        private const val MANUFACTURER_ID = 0x1234
        private val MESH_MARKER = byteArrayOf(0x4D, 0x4D)
        private const val SEND_TIMEOUT_MS = 25_000L
        const val MAX_MESSAGE_BYTES = 500
    }
}
