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
            "INTENT_TIMER", "INTENT_SEARCH",
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
            else -> FALLBACK
        }

    // --- keyword fallback (port of _keyword_intent) ---
    private fun keywordIntent(t: String): String {
        val devicePhrases = listOf("flashlight on", "flashlight off", "torch on", "torch off", "battery")
        if (devicePhrases.any { it in t }) return "INTENT_DEVICE"
        // "open X in google/browser/web" means search for X, not launch an app
        if (Regex("\\b(in|on) (the )?(google|browser|web|chrome|internet)\\b").containsMatchIn(t) ||
            listOf("search for", "google ", "look up").any { it in t }) return "INTENT_SEARCH"
        if (listOf("open ", "launch ", "start ").any { it in t }) return "INTENT_OPEN_APP"
        if ("timer" in t || (("remind" in t || "alarm" in t) && parseDuration(t) > 0)) return "INTENT_TIMER"
        if (Regex("\\bmy \\w+ is ").containsMatchIn(t) || "remember" in t ||
            Regex("what('?s| is) my \\w+").containsMatchIn(t)) return "INTENT_REMEMBER"
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
                "I can chat, tell jokes, do math, open apps, set timers, and more."
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

    private fun handleDevice(text: String): String {
        val t = text.lowercase()
        return try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val camId = cm.cameraIdList.first()
            when {
                "flashlight on" in t || "torch on" in t -> { cm.setTorchMode(camId, true); torchOn = true; "Flashlight on." }
                "flashlight off" in t || "torch off" in t -> { cm.setTorchMode(camId, false); torchOn = false; "Flashlight off." }
                else -> "I can't do that one."
            }
        } catch (e: Exception) { "I couldn't do that." }
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

    private fun handleTimer(text: String, onAnnounce: (String) -> Unit): String {
        val secs = parseDuration(text)
        if (secs <= 0) return "How long should the timer be?"
        android.os.Handler(android.os.Looper.getMainLooper())
            .postDelayed({ onAnnounce("Time's up!") }, secs * 1000L)
        val mins = secs / 60
        val human = if (mins > 0) "$mins minute${if (mins != 1) "s" else ""}" else "$secs seconds"
        return "Timer set for $human."
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
