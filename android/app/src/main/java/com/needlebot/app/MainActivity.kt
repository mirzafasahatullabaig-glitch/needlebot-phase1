package com.needlebot.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Wires the pieces: BLE text in -> needle HTTP classify -> intent response ->
 * BLE out. A simple scrolling log is the whole UI for v1.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var ble: BleManager
    private lateinit var intents: Intents
    private val needle = NeedleClient()
    private val scope = CoroutineScope(Dispatchers.Main)
    private lateinit var log: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        log = TextView(this).apply { textSize = 14f; setPadding(24, 24, 24, 24) }
        setContentView(ScrollView(this).apply { addView(log) })

        intents = Intents(this)
        ble = BleManager(this, onText = ::handleText, onStatus = ::append)

        requestPermissions()
    }

    private fun requestPermissions() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        ActivityCompat.requestPermissions(this, perms, 1)
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        if (r.isNotEmpty() && r.all { it == 0 }) ble.start()
        else append("Bluetooth permission denied.")
    }

    /** A user message arrived over BLE: classify with needle, respond, send back. */
    private fun handleText(text: String) {
        append("RX: $text")
        scope.launch {
            // needle HTTP call off the main thread
            val intent = withContext(Dispatchers.IO) { needle.classify(text) }
            val reply = intents.respond(text, intent) { msg ->
                // timer announcements fire later, on the main thread
                runOnUiThread { append("TX (timer): $msg"); ble.send(msg) }
            }
            append("TX: $reply")
            ble.send(reply)
        }
    }

    private fun append(line: String) = runOnUiThread {
        log.append("$line\n")
    }

    override fun onDestroy() {
        super.onDestroy()
        ble.stop()
    }
}
