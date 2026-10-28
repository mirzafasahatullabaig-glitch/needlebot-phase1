package com.needlebot.app

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Phase 3 scripted-flow substrate. The only general way to operate other apps
 * on Android: read the on-screen node tree and inject clicks. Primitives here
 * (findClickableByText, clickFirstSwitch) back the Bluetooth-toggle flow and
 * any flow added later. The user must enable this in Settings > Accessibility;
 * until then [instance] is null and callers fall back.
 */
class NeedleAccessibilityService : AccessibilityService() {

    companion object {
        /** Non-null only while the service is enabled and connected. */
        @Volatile var instance: NeedleAccessibilityService? = null
    }

    override fun onServiceConnected() {
        instance = this
        // set in code because AAPT rejects canRetrieveWindowContents in the config XML here
        serviceInfo = serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
    }
    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onInterrupt() {}
    // We act on demand (toggleBluetooth), not on an event stream.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    /** Open Bluetooth settings, then after it renders click its on/off switch.
     *  [on] is advisory — the Settings switch is a toggle, so we flip whatever
     *  is there; opening the right screen is what makes it deterministic. */
    fun toggleBluetooth(ctx: Context, on: Boolean?) {
        ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        // Settings needs a beat to render before its switch exists in the tree.
        Handler(Looper.getMainLooper()).postDelayed({ clickFirstSwitch() }, 1200)
    }

    /** Click the first toggle switch on the current screen (the BT on/off switch).
     *  Settings skins use Switch/SwitchCompat/MaterialSwitch, so match any
     *  *checkable* node rather than one class name; fall back to an On/Off label. */
    private fun clickFirstSwitch(): Boolean {
        val root = rootInActiveWindow ?: return false
        val sw = findCheckable(root)
            ?: findClickableByText(root, "On") ?: findClickableByText(root, "Off")
            ?: return false
        return clickable(sw)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
    }

    /** Depth-first: first checkable node (any switch/toggle flavour). */
    private fun findCheckable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isCheckable) return node
        for (i in 0 until node.childCount) {
            findCheckable(node.getChild(i))?.let { return it }
        }
        return null
    }

    /** Depth-first: first node whose text equals [text] (case-insensitive). */
    private fun findClickableByText(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString().equals(text, ignoreCase = true)) return node
        for (i in 0 until node.childCount) {
            findClickableByText(node.getChild(i), text)?.let { return it }
        }
        return null
    }

    /** Walk up to the nearest clickable ancestor (a label often isn't itself clickable). */
    private fun clickable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var n = node
        while (n != null && !n.isClickable) n = n.parent
        return n
    }
}
