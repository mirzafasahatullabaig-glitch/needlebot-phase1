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
    private lateinit var espScope: ScopeView    // CRO-style ESP32 link indicator
    private var espPulse: android.animation.ValueAnimator? = null

    // palette — validated dark design system (dataviz skill): warm near-black
    // surface #1a1a19, one calm blue accent, warm off-white ink. Cohesive, not neon.
    private val bg = Color.parseColor("#1a1a19")            // surface
    private val headerBg = Color.parseColor("#1a1a19")
    private val botBubble = Color.parseColor("#2b2b29")     // raised surface
    private val youBubble = Color.parseColor("#3987e5")     // accent blue (dark-validated)
    private val textLight = Color.parseColor("#f0efec")     // primary ink
    private val textMuted = Color.parseColor("#c3c2b7")     // secondary ink
    private val accent = Color.parseColor("#3987e5")
    private val good = Color.parseColor("#0ca30c")          // connected status
    private val bgTop = Color.parseColor("#222220")         // subtle top-lit gradient
    private val bgBottom = Color.parseColor("#131312")
    private val botTop = Color.parseColor("#323230")
    private val botBottom = Color.parseColor("#232321")
    private val youTop = Color.parseColor("#4f97ee")
    private val youBottom = Color.parseColor("#2a6fc9")
    private val glassFill = Color.parseColor("#14FFFFFF")   // faint light panel over the surface

    /** A glossy top-to-bottom gradient pill/box — the core of the look. */
    private fun glossy(top: Int, bottom: Int, radius: Float) = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom)
    ).apply { cornerRadius = radius }

    /** Translucent "glass" panel over the dark gradient (reads as frosted). */
    private fun glass(radius: Float) = GradientDrawable().apply {
        cornerRadius = radius
        setColor(glassFill)
        setStroke((resources.displayMetrics.density).toInt().coerceAtLeast(1),
            Color.parseColor("#22FFFFFF"))   // faint top-light edge
    }

    /** Neon outer glow around a view (shadow tinted with the accent). */
    private fun glow(v: View, color: Int, radius: Int) {
        v.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        v.elevation = radius.toFloat()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            v.outlineAmbientShadowColor = color
            v.outlineSpotShadowColor = color
        }
    }

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
        val gutter = px(16)        // one side gutter used on every row
        val vpad = px(14)          // one vertical padding used on every bar

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(bgTop, bgBottom))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            fitsSystemWindows = true
        }

        // --- header: bot orb + title + pulsing ESP status (raised) ---
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = glass(0f)
            elevation = px(8).toFloat()
            setPadding(gutter, vpad, gutter, vpad)
        }
        val orb = OrbView(this, Color.parseColor("#9BC8F7"), youBottom)
        glow(orb, accent, px(10))
        header.addView(orb, LinearLayout.LayoutParams(px(40), px(40)).apply { marginEnd = px(12) })
        startOrbAnimation(orb)

        // title block: "NeedleBot" with the status line beneath it, takes the slack
        val titleBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleBlock.addView(TextView(this).apply {
            text = "NeedleBot"
            setTextColor(textLight)
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        })
        status = TextView(this).apply {
            text = "starting…"
            setTextColor(textMuted)
            textSize = 12f
        }
        titleBlock.addView(status)
        header.addView(titleBlock, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        // CRO-style ESP32 link: a scope trace that beats when connected, on the right
        espScope = ScopeView(this, good, textMuted)
        header.addView(espScope, LinearLayout.LayoutParams(px(60), px(26)))
        startEspPulse()   // scope scrolls continuously; `live` gates the waveform
        root.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // --- scrolling chat area (same gutter as the bars) ---
        chat = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(gutter, px(12), gutter, px(12))
        }
        scroll = ScrollView(this).apply { addView(chat); isFillViewport = true }
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // --- quick-action buttons (aligned to the gutter, even gaps) ---
        val quick = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = glass(0f)
            setPadding(gutter, px(10), gutter, px(6))
        }
        fun chip(label: String, onTap: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(textLight)
            textSize = 13f
            background = glossy(botTop, botBottom, px(16).toFloat())
            elevation = px(4).toFloat()
            stateListAnimator = null  // keep our elevation, not the default button one
            setOnClickListener { onTap() }
        }
        val chipGap = px(4)
        val chipLp = { LinearLayout.LayoutParams(0, px(40), 1f).apply { leftMargin = chipGap; rightMargin = chipGap } }
        var torchState = false
        quick.addView(chip("🔦 Light") {
            torchState = !torchState
            handleText(if (torchState) "flashlight on" else "flashlight off", fromBle = false)
        }, chipLp())
        quick.addView(chip("🎲 Dice") { handleText("roll a dice", fromBle = false) }, chipLp())
        quick.addView(chip("🔋 Battery") { handleText("battery", fromBle = false) }, chipLp())
        quick.addView(chip("Clear") { chat.removeAllViews() }, chipLp())
        root.addView(quick, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // --- input row (raised bar) ---
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = glass(0f)
            elevation = px(8).toFloat()
            setPadding(gutter, px(10), gutter, px(10))
        }
        val field = EditText(this).apply {
            hint = "Type a message…"
            setHintTextColor(textMuted)
            setTextColor(textLight)
            textSize = 16f
            setPadding(px(16), px(12), px(16), px(12))
            // inset look: darker fill with a subtle lighter stroke
            background = glossy(botBottom, botTop, px(22).toFloat()).apply {
                setStroke(px(1), botTop)
            }
            maxLines = 4
            imeOptions = EditorInfo.IME_ACTION_SEND
        }
        inputRow.addView(field, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        val send = Button(this).apply {
            text = "Send"
            isAllCaps = false
            setTextColor(Color.parseColor("#06121A"))
            background = glossy(youTop, youBottom, px(22).toFloat())
            stateListAnimator = null
            setPadding(px(20), 0, px(20), 0)
        }
        glow(send, accent, px(8))   // neon cyan glow
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
        // call-a-contact perms — requested separately so a denial here never
        // blocks BLE startup (rc 1); without them INTENT_CALL falls back to the dialer
        ActivityCompat.requestPermissions(this,
            arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_CONTACTS), 2)
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        if (rc != 1) return
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
            // glossy gradient bubble, lifted off the background with a shadow
            background = glossy(
                if (mine) youTop else botTop,
                if (mine) youBottom else botBottom,
                px(18).toFloat())
            if (mine) glow(this, accent, px(6)) else elevation = px(3).toFloat()
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
        status.text = line
        status.setTextColor(if (connected) good else textMuted)
        espScope.live = connected   // scope beats when linked, flatlines otherwise
    }

    private var orbAnim: android.animation.ValueAnimator? = null

    /** Spin the sphere (highlight orbits) with a gentle breathe. Honors reduced-motion. */
    private fun startOrbAnimation(orb: OrbView) {
        val reduced = try {
            android.provider.Settings.Global.getFloat(
                contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        } catch (e: Exception) { false }
        if (reduced) { orb.phase = 0.15f; return }
        orbAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3000                       // one bob/blink cycle per 3s
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { orb.phase = it.animatedValue as Float }
            start()
        }
    }

    /** Scroll the scope trace continuously (the waveform only shows when live). */
    private fun startEspPulse() {
        if (espPulse?.isRunning == true) return
        espPulse = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1400                 // one heartbeat sweep per 1.4s
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { espScope.t = it.animatedValue as Float }
            start()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        orbAnim?.cancel()
        espPulse?.cancel()
        ble.stop()
    }
}
