package com.needlebot.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import org.json.JSONObject
import java.util.UUID

/**
 * BLE central: scans for NeedleBot-S3, connects, subscribes to the notify
 * characteristic, and writes responses to the write characteristic.
 * Replaces the bleak host loop (bleak has no Android backend). Same three UUIDs
 * as the ESP32 firmware.
 */
@SuppressLint("MissingPermission")  // caller requests runtime permissions first
class BleManager(
    private val ctx: Context,
    private val onText: (String) -> Unit,       // user text received from ESP32
    private val onStatus: (String) -> Unit,     // UI status updates
) {
    companion object {
        const val DEVICE_NAME = "NeedleBot-S3"
        val SERVICE_UUID: UUID = UUID.fromString("4fa86970-43ac-11ed-b878-0242ac120002")
        val NOTIFY_UUID: UUID = UUID.fromString("4fa86c40-43ac-11ed-b878-0242ac120002")
        val WRITE_UUID: UUID = UUID.fromString("4fa86dae-43ac-11ed-b878-0242ac120002")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val adapter: BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null

    fun start() {
        val scanner = adapter?.bluetoothLeScanner ?: run { onStatus("No BLE adapter"); return }
        onStatus("Scanning for $DEVICE_NAME...")
        scanner.startScan(scanCallback)
    }

    fun stop() {
        try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) {}
        gatt?.disconnect(); gatt?.close(); gatt = null
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (result.device.name == DEVICE_NAME) {
                adapter?.bluetoothLeScanner?.stopScan(this)
                onStatus("Connecting...")
                gatt = result.device.connectGatt(ctx, false, gattCallback)
            }
        }
        override fun onScanFailed(errorCode: Int) { onStatus("Scan failed: $errorCode") }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                onStatus("Connected, discovering...")
                g.requestMtu(185)   // match the ESP32 setMTU(185)
            } else {
                onStatus("Disconnected"); writeChar = null
            }
        }
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            g.discoverServices()
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(SERVICE_UUID) ?: run { onStatus("Service not found"); return }
            writeChar = svc.getCharacteristic(WRITE_UUID)
            val notify = svc.getCharacteristic(NOTIFY_UUID) ?: return
            g.setCharacteristicNotification(notify, true)
            notify.getDescriptor(CCCD_UUID)?.let {
                it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(it)
            }
            onStatus("Ready - send a message from the ESP32")
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            val raw = c.value?.toString(Charsets.UTF_8) ?: return
            val text = try { JSONObject(raw).optString("text", "") } catch (e: Exception) { "" }
            if (text.isNotEmpty()) onText(text)
        }
    }

    /** Send a response back to the ESP32 (reliable write, matches response=True). */
    fun send(text: String) {
        val g = gatt ?: return
        val c = writeChar ?: return
        c.value = text.toByteArray(Charsets.UTF_8)
        c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        g.writeCharacteristic(c)
    }
}
