package com.bajaj.ns200.telemetry.data.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import com.bajaj.ns200.telemetry.config.Ns200BluetoothConfig
import java.util.UUID

@SuppressLint("MissingPermission")
class BluetoothConnectionManager(
    private val context: Context,
    private val bluetoothAdapter: BluetoothAdapter?
) {
    companion object {
        private const val TAG = "BTConnMgr"
        private val CCC_DESCRIPTOR_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        fun bytesToHex(bytes: ByteArray): String =
            bytes.joinToString("") { "%02X".format(it) }
    }

    private var bluetoothGatt: BluetoothGatt? = null

    interface ConnectionCallback {
        fun onConnected()
        fun onDisconnected()
        fun onServiceDiscovered(services: List<BluetoothGattService>)
        fun onCharacteristicRead(characteristic: BluetoothGattCharacteristic, value: ByteArray)
        fun onCharacteristicChanged(characteristic: BluetoothGattCharacteristic, value: ByteArray)
        fun onConnectionError(errorCode: Int)
    }

    private var callback: ConnectionCallback? = null

    var connectedDeviceAddress: String? = null
        private set

    val isConnected: Boolean
        get() = bluetoothGatt != null

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Connected to ${gatt.device.address}")
                    connectedDeviceAddress = gatt.device.address
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Disconnected from ${gatt.device.address}")
                    bluetoothGatt?.close()
                    bluetoothGatt = null
                    connectedDeviceAddress = null
                    callback?.onDisconnected()
                }
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Connection error: $status")
                callback?.onConnectionError(status)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "=== SERVICES DISCOVERED ===")
                gatt.services.forEach { svc ->
                    Log.i(TAG, "Service: ${svc.uuid} (${serviceName(svc.uuid)})")
                    svc.characteristics.forEach { char ->
                        val props = buildString {
                            if (char.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) append("R")
                            if (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) append("W")
                            if (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) append("Wn")
                            if (char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) append(" N")
                            if (char.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) append(" I")
                        }
                        Log.i(TAG, "  Char: ${char.uuid} [$props]")
                        char.descriptors.forEach { desc ->
                            Log.i(TAG, "    Desc: ${desc.uuid}")
                        }
                    }
                }
                callback?.onServiceDiscovered(gatt.services)
                callback?.onConnected()
            } else {
                Log.e(TAG, "Service discovery failed: $status")
                callback?.onConnectionError(status)
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val value = characteristic.value ?: ByteArray(0)
                Log.d(TAG, "Read ${characteristic.uuid}: ${bytesToHex(value)}")
                callback?.onCharacteristicRead(characteristic, value)
            } else {
                Log.e(TAG, "Read failed ${characteristic.uuid}: $status")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val value = characteristic.value ?: ByteArray(0)
            Log.d(TAG, "Notify ${characteristic.uuid}: ${bytesToHex(value)}")
            callback?.onCharacteristicChanged(characteristic, value)
        }
    }

    fun setCallback(listener: ConnectionCallback) {
        this.callback = listener
    }

    fun connect(deviceAddress: String) {
        disconnect()
        val device = bluetoothAdapter?.getRemoteDevice(deviceAddress)
        if (device == null) {
            callback?.onConnectionError(-1)
            return
        }
        Log.i(TAG, "Connecting to $deviceAddress...")
        bluetoothGatt = device.connectGatt(context, false, gattCallback)
    }

    fun disconnect() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        connectedDeviceAddress = null
    }

    fun readCharacteristic(serviceUuid: UUID, charUuid: UUID): Boolean {
        val gatt = bluetoothGatt ?: return false
        val service = gatt.getService(serviceUuid) ?: return false
        val characteristic = service.getCharacteristic(charUuid) ?: return false
        return gatt.readCharacteristic(characteristic)
    }

    fun enableNotifications(serviceUuid: UUID, charUuid: UUID): Boolean {
        val gatt = bluetoothGatt ?: return false
        val service = gatt.getService(serviceUuid) ?: return false
        val characteristic = service.getCharacteristic(charUuid) ?: return false
        val success = gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(CCC_DESCRIPTOR_UUID)
        if (descriptor != null) {
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
        }
        Log.i(TAG, "Notifications ${if (success) "enabled" else "failed"} for $charUuid")
        return success
    }

    private fun serviceName(uuid: UUID): String = when (uuid.toString().uppercase()) {
        "0000180F-0000-1000-8000-00805F9B34FB" -> "Battery Service"
        "0000180A-0000-1000-8000-00805F9B34FB" -> "Device Information"
        "00001800-0000-1000-8000-00805F9B34FB" -> "Generic Access"
        "00001801-0000-1000-8000-00805F9B34FB" -> "Generic Attribute"
        else -> "Custom"
    }
}
