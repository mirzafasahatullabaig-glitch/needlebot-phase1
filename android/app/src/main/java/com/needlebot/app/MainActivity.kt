package com.needlebot.app

import android.Manifest
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Chat UI over the BLE + needle pipeline. Messages arriving over BLE and
 * messages typed on the phone both go through handleText; only BLE-origin
 * messages get their reply sent back out over BLE.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var ble: BleManager
    private lateinit var intents: Intents
    private val needle = NeedleClient()
    private val scope = CoroutineScope(Dispatchers.Main)

    private lateinit var chat: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var status: TextView

    // palette
    private val bg = Color.parseColor("#0F1115")
    private val headerBg = Color.parseColor("#171A21")
    private val botBubble = Color.parseColor("#232834")
    private val youBubble = Color.parseColor("#2D6CF6")
    private val textLight = Color.parseColor("#E6E8EC")
    private val textMuted = Color.parseColor("#8A909C")
    private val accent = Color.parseColor("#2D6CF6")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())

        intents = Intents(this)
        ble = BleManager(this, onText = { handleText(it, fromBle = true) }, onStatus = ::setStatus)
        requestPermissions()
        requestOverlayPermission()
    }

    /** "Display over other apps" — without it Android blocks launching apps
     *  while NeedleBot is in the background, so a BLE "open X" is dropped once
     *  another app is in front. Sends the user to the grant screen once. */
    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !android.provider.Settings.canDrawOverlays(this)) {
            setStatus("Allow 'Display over other apps' so it can open apps in the background.")
            startActivity(android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")))
        }
    }

    private fun buildUi(): View {
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            fitsSystemWindows = true
        }

        // --- header: title + status dot ---
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(headerBg)
            setPadding(px(20), px(16), px(20), px(16))
        }
        header.addView(TextView(this).apply {
            text = "NeedleBot"
            setTextColor(textLight)
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        status = TextView(this).apply {
            text = "● starting…"
            setTextColor(textMuted)
            textSize = 13f
        }
        header.addView(status)
        root.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // --- scrolling chat area ---
        chat = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(12), px(12), px(12), px(12))
        }
        scroll = ScrollView(this).apply { addView(chat) }
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // --- quick-action buttons ---
        val quick = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(headerBg)
            setPadding(px(8), px(6), px(8), px(2))
        }
        fun chip(label: String, onTap: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(textLight)
            textSize = 13f
            background = GradientDrawable().apply {
                cornerRadius = px(16).toFloat(); setColor(botBubble)
            }
            setOnClickListener { onTap() }
        }
        val chipLp = { LinearLayout.LayoutParams(0, px(40), 1f).apply { leftMargin = px(4); rightMargin = px(4) } }
        var torchState = false
        quick.addView(chip("🔦 Light") {
            torchState = !torchState
            handleText(if (torchState) "flashlight on" else "flashlight off", fromBle = false)
        }, chipLp())
        quick.addView(chip("🎲 Dice") { handleText("roll a dice", fromBle = false) }, chipLp())
        quick.addView(chip("🔋 Battery") { handleText("battery", fromBle = false) }, chipLp())
        quick.addView(chip("Clear") { chat.removeAllViews() }, chipLp())
        root.addView(quick, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // --- input row ---
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(headerBg)
            setPadding(px(12), px(10), px(12), px(10))
        }
        val field = EditText(this).apply {
            hint = "Type a message…"
            setHintTextColor(textMuted)
            setTextColor(textLight)
            textSize = 16f
            setPadding(px(16), px(12), px(16), px(12))
            background = GradientDrawable().apply {
                cornerRadius = px(22).toFloat()
                setColor(botBubble)
            }
            maxLines = 4
            imeOptions = EditorInfo.IME_ACTION_SEND
        }
        inputRow.addView(field, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        val send = Button(this).apply {
            text = "Send"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = px(22).toFloat()
                setColor(accent)
            }
            setPadding(px(20), 0, px(20), 0)
        }
        inputRow.addView(send, LinearLayout.LayoutParams(WRAP_CONTENT, px(48)).apply {
            leftMargin = px(8)
        })
        root.addView(inputRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val submit = {
            val t = field.text.toString().trim()
            if (t.isNotEmpty()) { field.setText(""); handleText(t, fromBle = false) }
        }
        send.setOnClickListener { submit() }
        field.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { submit(); true } else false
        }
        return root
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
        else setStatus("Bluetooth permission denied.")
    }

    /** A message to classify, from BLE or the on-screen input. */
    private fun handleText(text: String, fromBle: Boolean) {
        bubble(text, mine = true)
        scope.launch {
            val intent = withContext(Dispatchers.IO) { needle.classify(text) }
            val reply = intents.respond(text, intent) { msg ->
                runOnUiThread { bubble(msg, mine = false); if (fromBle) ble.send(msg) }
            }
            bubble(reply, mine = false)
            if (fromBle) ble.send(reply)
        }
    }

    /** A chat bubble: mine (right, blue) or the bot's (left, grey). */
    private fun bubble(text: String, mine: Boolean) = runOnUiThread {
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(if (mine) Color.WHITE else textLight)
            textSize = 16f
            setPadding(px(14), px(10), px(14), px(10))
            background = GradientDrawable().apply {
                cornerRadius = px(18).toFloat()
                setColor(if (mine) youBubble else botBubble)
            }
        }
        val lp = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            topMargin = px(4); bottomMargin = px(4)
            gravity = if (mine) Gravity.END else Gravity.START
            marginStart = if (mine) px(48) else 0
            marginEnd = if (mine) 0 else px(48)
        }
        chat.addView(tv, lp)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    /** Connection status in the header (and a muted centered line in chat). */
    private fun setStatus(line: String) = runOnUiThread {
        val connected = line.startsWith("Ready") || line.startsWith("Connected")
        status.text = "● " + line
        status.setTextColor(if (connected) accent else textMuted)
    }

    override fun onDestroy() {
        super.onDestroy()
        ble.stop()
    }
}
