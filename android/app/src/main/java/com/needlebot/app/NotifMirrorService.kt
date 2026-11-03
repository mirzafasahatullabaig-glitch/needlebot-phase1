package com.needlebot.app

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject

/**
 * Mirrors selected phone notifications onto the ESP bot's OLED.
 *
 * Android delivers every posted notification here (once the user grants
 * "Notification access" in Settings — a special one-time toggle, like the
 * Accessibility one). We keep only apps in ALLOW, format "App: title: text",
 * and push it to the ESP as {"notif":"..."} over the existing BLE write
 * characteristic (via BleManager.current). The ESP toasts it.
 *
 * No BLE of its own: if the app isn't connected to the bot, the notification
 * is simply dropped (BleManager.send is a no-op without a live gatt).
 */
class NotifMirrorService : NotificationListenerService() {

    companion object {
        // shortcut: hardcoded allowlist; move to a settings screen if the user
        // wants to pick apps at runtime. Package names, not display names.
        private val ALLOW = mapOf(
            "com.whatsapp" to "WhatsApp",
            "org.telegram.messenger" to "Telegram",
            "com.google.android.gm" to "Gmail",
            "com.google.android.apps.messaging" to "SMS",
            "com.android.dialer" to "Phone",
            "com.samsung.android.dialer" to "Phone",
            "com.android.server.telecom" to "Call",
        )
        private const val MAX = 40   // ESP truncates to ~20; keep the BLE write small
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val label = ALLOW[sbn.packageName] ?: return          // not an app we mirror
        val ble = BleManager.current ?: return                // bot not set up yet

        val ex = sbn.notification.extras
        val title = ex.getCharSequence("android.title")?.toString()?.trim().orEmpty()
        val text = ex.getCharSequence("android.text")?.toString()?.trim().orEmpty()
        val body = listOf(title, text).filter { it.isNotEmpty() }.joinToString(": ")
        if (body.isEmpty()) return                            // nothing readable (e.g. group summary)

        var line = "$label: $body"
        if (line.length > MAX) line = line.substring(0, MAX)

        val json = JSONObject().put("notif", line).toString()
        android.util.Log.i("NeedleVoice", "notif -> ESP: $line")
        ble.send(json)
    }
}
