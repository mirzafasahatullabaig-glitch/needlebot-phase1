package com.needlebot.app

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Intent routing + responses, ported from needle_host.py. Needle classifies via
 * HTTP; this maps the intent (or a keyword fallback) to a reply and, for
 * actions, performs them natively (Intents / CameraManager) instead of am/termux.
 */
class Intents(private val ctx: Context) {

    companion object {
        val ALL = setOf(
            "INTENT_GREETING", "INTENT_STATUS", "INTENT_JOKE", "INTENT_MATH",
            "INTENT_TIME", "INTENT_DATE", "INTENT_ROBOT_INFO", "INTENT_FACT",
            "INTENT_OPEN_APP", "INTENT_DEVICE", "INTENT_REMEMBER",
            "INTENT_TIMER", "INTENT_SEARCH", "INTENT_FUN", "INTENT_CALL",
            "INTENT_ALARM", "INTENT_CALENDAR", "INTENT_NOTE", "INTENT_BT_TOGGLE",
            "INTENT_MEDIA", "INTENT_VOLUME", "INTENT_SMS", "INTENT_WEATHER",
        )
        const val FALLBACK = "Sorry, I didn't catch that."
        const val ROBOT_NAME = "NeedleBot"
        const val ROBOT_VERSION = "0.1.0"
    }

    private val memory = mutableMapOf<String, String>()
    private var lastIntent = ""
    private var torchOn = false

    // curated facts (subset of facts.json; extend freely)
    private val facts = mapOf(
        "capital of france" to "Paris.",
        "capital of japan" to "Tokyo.",
        "capital of italy" to "Rome.",
        "largest planet" to "Jupiter is the largest planet.",
        "how many planets" to "There are eight planets in our solar system.",
    )

    private val greetings = listOf("Hey there!", "Hi! What's up?", "Hello! How can I help?")
    private val statuses = listOf("All good here.", "Doing great, thanks!", "Running smoothly.")
    private val jokes = listOf(
        "Why did the robot cross the road? It was programmed by a chicken.",
        "I'd tell you a UDP joke, but you might not get it.",
        "Why was the robot tired? It had a hard drive.",
    )

    /** Main entry: classify (needle intent passed in, "" if none) then respond. */
    fun respond(text: String, needleIntent: String, onAnnounce: (String) -> Unit): String {
        val lower = text.lowercase().trim()
        if (isFollowup(lower) && lastIntent.isNotEmpty()) {
            return dispatch(lastIntent, text, onAnnounce)
        }
        var intent = needleIntent.ifEmpty { keywordIntent(lower) }
        // "remind me to call/open X" is a note; override needle if it mis-read the
        // embedded verb (e.g. "call") as that action.
        if (Regex("^(remind me|add (a )?note|make a note|write down)\\b").containsMatchIn(lower))
            intent = "INTENT_NOTE"
        if (intent.isNotEmpty()) lastIntent = intent
        return dispatch(intent, text, onAnnounce)
    }

    private fun dispatch(intent: String, text: String, onAnnounce: (String) -> Unit): String =
        when (intent) {
            "INTENT_GREETING" -> greetings.random()
            "INTENT_STATUS" -> statuses.random()
            "INTENT_JOKE" -> jokes.random()
            "INTENT_MATH" -> handleMath(text)
            "INTENT_TIME" -> "It's " + SimpleDateFormat("H:mm", Locale.US).format(Date()) + "."
            "INTENT_DATE" -> "It's " + SimpleDateFormat("EEEE, MMMM d", Locale.US).format(Date()) + "."
            "INTENT_ROBOT_INFO" -> handleRobotInfo(text)
            "INTENT_FACT" -> handleFacts(text)
            "INTENT_OPEN_APP" -> handleOpenApp(text)
            "INTENT_DEVICE" -> handleDevice(text)
            "INTENT_REMEMBER" -> handleRemember(text)
            "INTENT_TIMER" -> handleTimer(text, onAnnounce)
            "INTENT_SEARCH" -> handleSearch(text)
            "INTENT_FUN" -> handleFun(text)
            "INTENT_CALL" -> handleCall(text)
            "INTENT_ALARM" -> handleAlarm(text)
            "INTENT_CALENDAR" -> handleCalendar(text)
            "INTENT_NOTE" -> handleNote(text)
            "INTENT_BT_TOGGLE" -> handleBluetooth(text)
            "INTENT_MEDIA" -> handleMedia(text)
            "INTENT_VOLUME" -> handleVolume(text)
            "INTENT_SMS" -> handleSms(text)
            "INTENT_WEATHER" -> handleWeather(text)
            else -> FALLBACK
        }

    // --- keyword fallback (port of _keyword_intent) ---
    private fun keywordIntent(t: String): String {
        val devicePhrases = listOf("flashlight on", "flashlight off", "torch on", "torch off", "battery")
        if (devicePhrases.any { it in t }) return "INTENT_DEVICE"
        if (Regex("\\bbluetooth\\b").containsMatchIn(t) &&
            listOf("on", "off", "toggle", "enable", "disable", "turn").any { it in t }) return "INTENT_BT_TOGGLE"
        if ("weather" in t || "forecast" in t || "temperature" in t) return "INTENT_WEATHER"
        if (listOf("play", "pause", "resume", "next track", "next song", "previous track",
                "previous song", "skip track", "skip song").any { it in t }) return "INTENT_MEDIA"
        if (("volume" in t || "mute" in t || "unmute" in t) &&
            "bluetooth" !in t) return "INTENT_VOLUME"
        // "text/sms mom ..." is an SMS; must not be caught by note/call below
        if (Regex("\\b(text|sms|message)\\b").containsMatchIn(t) &&
            Regex("\\b(to )?\\w+").containsMatchIn(t) && "remind" !in t) return "INTENT_SMS"
        // note/reminder must win before CALL: "remind me to call dad" is a note,
        // not a phone call, even though it contains "call"
        if (listOf("remind me", "add note", "note ", "make a note", "write down").any { it in t } &&
            "calendar" !in t) return "INTENT_NOTE"
        if (Regex("\\b(call|dial|phone)\\b").containsMatchIn(t)) return "INTENT_CALL"
        // "alarm at 7am" is a real clock alarm; "alarm in 5 min" stays the in-app timer
        if ("alarm" in t && hasClockTime(t)) return "INTENT_ALARM"
        if (listOf("calendar", "schedule", "meeting", "event", "appointment").any { it in t }) return "INTENT_CALENDAR"
        // "open X in google/browser/web" means search for X, not launch an app
        if (Regex("\\b(in|on) (the )?(google|browser|web|chrome|internet)\\b").containsMatchIn(t) ||
            listOf("search for", "google ", "look up").any { it in t }) return "INTENT_SEARCH"
        if (listOf("open ", "launch ", "start ").any { it in t }) return "INTENT_OPEN_APP"
        if ("timer" in t || (("remind" in t || "alarm" in t) && parseDuration(t) > 0)) return "INTENT_TIMER"
        if (Regex("\\bmy \\w+ is ").containsMatchIn(t) || "remember" in t ||
            Regex("what('?s| is) my \\w+").containsMatchIn(t)) return "INTENT_REMEMBER"
        if ("coin" in t || "dice" in t || "roll a" in t) return "INTENT_FUN"
        if ("joke" in t) return "INTENT_JOKE"
        if (listOf("plus", "minus", "times", "divided").any { it in t } ||
            Regex("\\d\\s*[-+*/x]\\s*\\d").containsMatchIn(t) || "calculate" in t) return "INTENT_MATH"
        if ("time" in t || "clock" in t) return "INTENT_TIME"
        if ("date" in t || "today" in t) return "INTENT_DATE"
        if ("status" in t || "how are you" in t) return "INTENT_STATUS"
        if (listOf("your name", "version", "can you do", "who are you", "are you", "how old", "age")
                .any { it in t }) return "INTENT_ROBOT_INFO"
        if (listOf("hi", "hello", "hey", "greet").any { it in t }) return "INTENT_GREETING"
        if (facts.keys.any { it in t }) return "INTENT_FACT"
        return ""
    }

    private fun handleMath(text: String): String {
        var e = text.lowercase()
        listOf("plus" to "+", "minus" to "-", "times" to "*", "divided by" to "/", "x" to "*")
            .forEach { (w, s) -> e = e.replace(w, s) }
        e = Regex("[^0-9+\\-*/.() ]").replace(e, "").trim()
        return try {
            val r = SimpleCalc.eval(e)
            val n = if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString()
            "That's $n."
        } catch (ex: Exception) { FALLBACK }
    }

    private fun handleRobotInfo(text: String): String {
        val t = text.lowercase()
        return when {
            "name" in t -> "I'm $ROBOT_NAME."
            "version" in t -> "$ROBOT_NAME, version $ROBOT_VERSION."
            "can you do" in t || "help" in t ->
                "I can chat, do math, open apps, make calls, send texts, set alarms, timers and calendar events, take notes, control music and volume, check the weather, toggle Bluetooth, and more."
            "how old" in t || "age" in t -> "Brand new - I was just built!"
            "are you" in t -> listOf(
                "Smart enough to tell a joke and do your math!",
                "I'm a small robot doing my best.", "I try to be helpful!").random()
            else -> "I'm $ROBOT_NAME, a little companion robot."
        }
    }

    private fun handleFacts(text: String): String {
        val t = text.lowercase()
        return facts.entries.firstOrNull { it.key in t }?.value ?: FALLBACK
    }

    private fun handleRemember(text: String): String {
        val t = text.lowercase().trim()
        Regex("my (\\w+) is (.+)", RegexOption.IGNORE_CASE).find(text)?.let {
            val key = it.groupValues[1].lowercase()
            val value = it.groupValues[2].trim(' ', '.', '!', '?')
            memory[key] = value
            return "Got it, your $key is $value."
        }
        Regex("my (\\w+)").find(t)?.let {
            if ("what" in t || "recall" in t) {
                val key = it.groupValues[1]
                return memory[key]?.let { v -> "Your $key is $v." } ?: "I don't know your $key yet."
            }
        }
        Regex("remember (?:that )?(.+)", RegexOption.IGNORE_CASE).find(text)?.let {
            memory["note"] = it.groupValues[1].trim(' ', '.', '!', '?')
            return "I'll remember that."
        }
        return FALLBACK
    }

    // --- native device actions (replaces am/termux-api) ---
    private fun handleOpenApp(text: String): String {
        val t = text.lowercase()
        // System actions that aren't reliably a launchable package label.
        val sys = when {
            "camera" in t -> Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            "maps" in t -> Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0"))
            "settings" in t -> Intent(android.provider.Settings.ACTION_SETTINGS)
            else -> null
        }
        if (sys != null) {
            return try { launchFresh(sys); "Opening it." }
            catch (e: Exception) { "I couldn't open that." }
        }
        // Otherwise: open any installed app by spoken name.
        val name = Regex("\\b(open|launch|start)\\b", RegexOption.IGNORE_CASE)
            .replace(text, "").trim(' ', '.', '!', '?', ',').lowercase()
        if (name.isEmpty()) return "What should I open?"
        val launch = findLaunchIntent(name) ?: return "I don't have an app called $name."
        return try { launchFresh(launch); "Opening $name." }
        catch (e: Exception) { "I couldn't open $name." }
    }

    /** Start an app from the background bringing it to front. NEW_TASK alone
     *  often just re-focuses the current foreground task; CLEAR_TOP forces the
     *  target app forward so a second "open Y" actually switches to Y. */
    private fun launchFresh(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        ctx.startActivity(intent)
    }

    /** Start an implicit/system intent (SET_ALARM, SET_TIMER, calendar insert),
     *  but only if something can handle it — these throw ActivityNotFoundException
     *  otherwise. CLEAR_TOP is wrong for a fresh system activity, so NEW_TASK only.
     *  Returns false when no handler exists, so callers can report it. */
    /** Try to launch; the try/catch is the real test. We do NOT gate on
     *  resolveActivity: under Android 11+ package-visibility it returns null for
     *  implicit actions like SET_ALARM even when a handler exists, which wrongly
     *  blocked the launch. startActivity throws only if nothing can handle it. */
    private fun startIfResolvable(intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { ctx.startActivity(intent); true }
        catch (e: Exception) { false }   // no handler -> report it; never crash / drop BLE
    }

    /** Match a spoken app name to an installed app's launch intent (label
     *  contains the name, shortest label wins to prefer the closest match). */
    private fun findLaunchIntent(name: String): Intent? {
        val pm = ctx.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val best = pm.queryIntentActivities(main, 0)
            .filter { it.loadLabel(pm).toString().lowercase().contains(name) }
            .minByOrNull { it.loadLabel(pm).length }
            ?: return null
        return pm.getLaunchIntentForPackage(best.activityInfo.packageName)
    }

    /** Call a spoken number, or look a name up in contacts and call it.
     *  Uses ACTION_CALL when CALL_PHONE is granted; otherwise ACTION_DIAL
     *  (opens the dialer pre-filled, user taps call — needs no permission). */
    private fun handleCall(text: String): String {
        val target = Regex("\\b(call|dial|phone)\\b", RegexOption.IGNORE_CASE)
            .replace(text, "").trim(' ', '.', '!', '?', ',').lowercase()
        if (target.isEmpty()) return "Who should I call?"

        val digits = target.filter { it.isDigit() || it == '+' }
        val (number, who) = if (digits.length >= 3) digits to target
                            else (lookupContact(target) ?: return "I don't have a contact called $target.") to target
        if (number.isEmpty()) return "I couldn't find a number for $who."

        val canCall = ctx.checkSelfPermission(android.Manifest.permission.CALL_PHONE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val action = if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL
        return try {
            launchFresh(Intent(action, Uri.parse("tel:" + Uri.encode(number))))
            if (canCall) "Calling $who." else "Dialing $who — tap call to connect."
        } catch (e: Exception) { "I couldn't place that call." }
    }

    /** First contact whose display name contains [name]; its primary number, or null. */
    private fun lookupContact(name: String): String? {
        val uri = android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val cols = arrayOf(
            android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER,
            android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
        ctx.contentResolver.query(
            uri, cols,
            "${android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"), null
        )?.use { c -> if (c.moveToFirst()) return c.getString(0) }
        return null
    }

    // --- Phase 3: offline deep-link tasks (no network, no accessibility) ---

    /** "set alarm 7am" / "wake me at 6:30" -> system Clock alarm (fully offline). */
    private fun handleAlarm(text: String): String {
        val (h, m) = parseClockTime(text) ?: return "What time should I set the alarm for?"
        val i = Intent(android.provider.AlarmClock.ACTION_SET_ALARM)
            .putExtra(android.provider.AlarmClock.EXTRA_HOUR, h)
            .putExtra(android.provider.AlarmClock.EXTRA_MINUTES, m)
        return if (startIfResolvable(i))
            "Alarm set for " + String.format(Locale.US, "%d:%02d.", if (h % 12 == 0) 12 else h % 12, m)
        else "No clock app here can set alarms."
    }

    /** "meeting at 3pm tomorrow" -> Calendar insert, pre-filled (saved offline). */
    private fun handleCalendar(text: String): String {
        val title = text
            .replace(Regex("\\b(add|create|new|schedule|set( up)?|a|an|to|my)\\b", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\b(calendar|event|meeting|appointment)\\b", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\bat\\s+\\d.*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+"), " ").trim().ifEmpty { "Event" }
        val i = Intent(Intent.ACTION_INSERT)
            .setData(android.provider.CalendarContract.Events.CONTENT_URI)
            .putExtra(android.provider.CalendarContract.Events.TITLE, title)
        parseClockTime(text)?.let { (h, m) ->
            val c = Calendar.getInstance().apply {
                if ("tomorrow" in text.lowercase()) add(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m)
            }
            i.putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, c.timeInMillis)
        }
        return try { launchFresh(i); "Opening your calendar for \"$title\"." }
        catch (e: Exception) { "I couldn't open the calendar." }
    }

    /** "note buy milk" -> share the text to a notes app (offline).
     *  shortcut: no universal "new note" intent exists, so this offers the text
     *  to any app that takes ACTION_SEND text; upgrade to a specific app's intent
     *  if the user settles on one notes app. */
    private fun handleNote(text: String): String {
        val note = text
            .replace(Regex("\\b(remind me( to)?|add( a)? note|make a note|write down|note|that)\\b",
                RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\s+"), " ").trim(' ', '.', '!', '?', ',')
        if (note.isEmpty()) return "What should I note down?"
        val i = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, note)
            .putExtra(Intent.EXTRA_SUBJECT, note)
        return try { launchFresh(Intent.createChooser(i, "Save note").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "Saving a note: $note." }
        catch (e: Exception) { "I couldn't save that note." }
    }

    /** Scripted (accessibility) flow: toggle Bluetooth by driving Settings.
     *  Falls back to opening Bluetooth settings if the service isn't enabled. */
    private fun handleBluetooth(text: String): String {
        val on = when {
            "off" in text || "disable" in text -> false
            "on" in text || "enable" in text -> true
            else -> null  // "toggle" / unspecified -> service flips current state
        }
        if (NeedleAccessibilityService.instance == null) {
            // service not enabled -> open the toggle screen so the user can grant/act
            return try {
                launchFresh(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
                "Enable NeedleBot in Accessibility settings so I can flip Bluetooth myself. For now I've opened Bluetooth settings."
            } catch (e: Exception) { "I can't reach Bluetooth settings." }
        }
        NeedleAccessibilityService.instance?.toggleBluetooth(ctx, on)
        return "Toggling Bluetooth" + (on?.let { if (it) " on." else " off." } ?: ".")
    }

    // --- Phase 3: media / volume / sms / weather ---

    /** Media transport via media-key events (works with whatever app holds the
     *  media session; no permission, offline). */
    private fun handleMedia(text: String): String {
        val t = text.lowercase()
        val (key, said) = when {
            "pause" in t -> android.view.KeyEvent.KEYCODE_MEDIA_PAUSE to "Paused."
            "next" in t || "skip" in t -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT to "Next track."
            "previous" in t || "back" in t -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS to "Previous track."
            "play" in t || "resume" in t -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY to "Playing."
            else -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to "Toggled playback."
        }
        return try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, key))
            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, key))
            said
        } catch (e: Exception) { "I couldn't control playback." }
    }

    /** Media-stream volume up/down/mute/set-percent (no permission, offline). */
    private fun handleVolume(text: String): String {
        val t = text.lowercase()
        return try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val stream = android.media.AudioManager.STREAM_MUSIC
            val max = am.getStreamMaxVolume(stream)
            when {
                "mute" in t && "un" !in t -> { am.setStreamVolume(stream, 0, 0); "Muted." }
                "unmute" in t || "max" in t -> { am.setStreamVolume(stream, max, 0); if ("un" in t) "Unmuted." else "Volume maxed." }
                "up" in t || "louder" in t || "increase" in t ->
                    { am.adjustStreamVolume(stream, android.media.AudioManager.ADJUST_RAISE, 0); "Volume up." }
                "down" in t || "lower" in t || "quieter" in t || "decrease" in t ->
                    { am.adjustStreamVolume(stream, android.media.AudioManager.ADJUST_LOWER, 0); "Volume down." }
                else -> Regex("(\\d{1,3})\\s*%?").find(t)?.let {
                    val pct = it.groupValues[1].toInt().coerceIn(0, 100)
                    am.setStreamVolume(stream, pct * max / 100, 0); "Volume set to $pct%."
                } ?: "Volume up, down, mute, or a percent?"
            }
        } catch (e: Exception) { "I couldn't change the volume." }
    }

    /** "text mom I'm on my way" -> SMS app pre-filled (no SEND_SMS; user taps send). */
    private fun handleSms(text: String): String {
        val m = Regex("\\b(?:text|sms|message)\\b\\s+(.+)", RegexOption.IGNORE_CASE).find(text)
            ?: return "Who should I text?"
        var rest = m.groupValues[1].trim()
        rest = rest.removePrefix("to ").trim()
        // Prefer an explicit separator ("saying"/"that"/":"/comma) to split
        // recipient from body; otherwise first token is the recipient, rest the
        // body. shortcut: a multi-word name needs an explicit "saying".
        val sep = Regex("\\b(saying|that|:)\\b|,", RegexOption.IGNORE_CASE)
        val who: String; val body: String
        if (sep.containsMatchIn(rest)) {
            val split = sep.split(rest, 2)
            who = split[0].trim(' ', '.', ','); body = split.getOrElse(1) { "" }.trim(' ', '.', ',')
        } else {
            val parts = rest.split(Regex("\\s+"), 2)
            who = parts[0].trim(' ', '.', ','); body = parts.getOrElse(1) { "" }.trim(' ', '.', ',')
        }
        if (who.isEmpty()) return "Who should I text?"
        val digits = who.filter { it.isDigit() || it == '+' }
        val number = if (digits.length >= 3) digits
                     else lookupContact(who.lowercase()) ?: return "I don't have a contact called $who."
        val uri = Uri.parse("smsto:" + Uri.encode(number))
        val i = Intent(Intent.ACTION_SENDTO, uri).putExtra("sms_body", body)
        return try { launchFresh(i); "Texting $who" + (if (body.isNotEmpty()) ": $body" else " — type your message.") }
        catch (e: Exception) { "I couldn't open a text to $who." }
    }

    /** "what's the weather" -> a web search for the forecast (needs data). */
    private fun handleWeather(text: String): String {
        val url = "https://www.google.com/search?q=" + Uri.encode("weather")
        return try { launchFresh(Intent(Intent.ACTION_VIEW, Uri.parse(url))); "Here's the weather." }
        catch (e: Exception) { "I couldn't open the weather." }
    }

    /** First "7am" / "7:30 pm" / "6:30" in the text as 24h (hour, minute), or null. */
    private fun parseClockTime(text: String): Pair<Int, Int>? {
        val m = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?", RegexOption.IGNORE_CASE).find(text) ?: return null
        var h = m.groupValues[1].toIntOrNull() ?: return null
        val min = m.groupValues[2].toIntOrNull() ?: 0
        val ap = m.groupValues[3].lowercase()
        if (h !in 0..23 || min !in 0..59) return null
        if (ap == "pm" && h < 12) h += 12
        if (ap == "am" && h == 12) h = 0
        return h to min
    }

    private fun hasClockTime(text: String): Boolean =
        Regex("\\b\\d{1,2}(:\\d{2})?\\s*(am|pm)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) ||
        Regex("\\b\\d{1,2}:\\d{2}\\b").containsMatchIn(text)

    private fun handleDevice(text: String): String {
        val t = text.lowercase()
        return try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val camId = cm.cameraIdList.first()
            when {
                "flashlight on" in t || "torch on" in t -> { cm.setTorchMode(camId, true); torchOn = true; "Flashlight on." }
                "flashlight off" in t || "torch off" in t -> { cm.setTorchMode(camId, false); torchOn = false; "Flashlight off." }
                "battery" in t -> batteryLevel()
                else -> "I can't do that one."
            }
        } catch (e: Exception) { "I couldn't do that." }
    }

    private fun batteryLevel(): String {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        val pct = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (pct in 0..100) "Battery is at $pct%." else "I couldn't read the battery."
    }

    private fun handleFun(text: String): String {
        val t = text.lowercase()
        return when {
            "coin" in t -> "It's " + listOf("heads", "tails").random() + "."
            "dice" in t || "die" in t || "roll" in t -> "You rolled a " + (1..6).random() + "."
            else -> FALLBACK
        }
    }

    private fun handleSearch(text: String): String {
        val q = text
            .replace(Regex("\\b(in|on) (the )?(google|browser|web|chrome|internet)\\b", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\b(open|launch|start|search( for)?|google|look up|find)\\b", RegexOption.IGNORE_CASE), "")
            .trim(' ', '?', '.')
        if (q.isEmpty()) return "What should I search for?"
        val url = "https://www.google.com/search?q=" + Uri.encode(q)
        return try {
            launchFresh(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            "Searching for $q."
        } catch (e: Exception) { "I couldn't open a search." }
    }

    /** System Clock timer: rings on its own and survives the app being
     *  backgrounded or BLE dropping (an in-app delayed message does not — it's
     *  lost on the frequent disconnects, which is why it "didn't set"). */
    private fun handleTimer(text: String, onAnnounce: (String) -> Unit): String {
        val secs = parseDuration(text)
        if (secs <= 0) return "How long should the timer be?"
        // Samsung Clock rejects headless (SKIP_UI) set, so let it open; the BLE
        // blip from the app switch is absorbed by auto-reconnect in BleManager.
        val i = Intent(android.provider.AlarmClock.ACTION_SET_TIMER)
            .putExtra(android.provider.AlarmClock.EXTRA_LENGTH, secs)
            .putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, "NeedleBot timer")
        val mins = secs / 60
        val human = if (mins > 0) "$mins minute${if (mins != 1) "s" else ""}" else "$secs seconds"
        return if (startIfResolvable(i)) "Timer set for $human."
        else "No clock app here can set timers."
    }

    private fun parseDuration(text: String): Int {
        val m = Regex("(\\d+)\\s*(h|hour|hr|m|min|minute|s|sec|second)", RegexOption.IGNORE_CASE)
            .find(text) ?: return 0
        val n = m.groupValues[1].toInt()
        return when (m.groupValues[2].lowercase().first()) {
            'h' -> n * 3600; 'm' -> n * 60; else -> n
        }
    }

    private fun isFollowup(t: String) =
        Regex("^\\s*(another( one)?|again|one more|more|and( that| it)?\\??|keep going|do it again)\\s*[.!?]*\\s*$",
            RegexOption.IGNORE_CASE).matches(t)
}
