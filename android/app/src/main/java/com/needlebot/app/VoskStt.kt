package com.needlebot.app

import android.content.Context
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.net.Socket
import java.util.zip.ZipInputStream

/**
 * Offline speech-to-text for the ESP's Wi-Fi audio stream.
 *
 * The ESP streams raw 16 kHz / 16-bit mono PCM on a TCP port while the touch
 * pad is held. We connect, feed the bytes to Vosk, and return the transcript
 * when the stream ends (pad released -> ESP closes the socket).
 *
 * The ~40 MB model is downloaded once on first use and cached in filesDir.
 */
class VoskStt(private val ctx: Context) {

    companion object {
        const val ESP_IP = "192.168.1.50"  // set to the IP the ESP prints as "[WiFi] connected, IP ..." (changes each boot; no mDNS yet)
        const val ESP_AUDIO_PORT = 7001
        // Indian English acoustic model: trained on Indian-accent speakers, so
        // it transcribes the user's speech far better than the US model.
        private const val MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip"
        private const val MODEL_DIR = "vosk-model-small-en-in-0.4"  // the zip's top folder
        private const val SAMPLE_RATE = 16000f
    }

    @Volatile private var model: Model? = null

    /** Download + unzip the model once; load it. Blocking — call off the UI thread. */
    private fun ensureModel(): Model {
        model?.let { return it }
        val root = File(ctx.filesDir, MODEL_DIR)
        if (!root.exists()) downloadAndUnzip()
        return Model(root.absolutePath).also { model = it }
    }

    /**
     * A Vosk grammar: a JSON array of allowed phrases. Constraining the
     * recognizer to our command words + every installed app name makes it pick
     * the closest allowed phrase instead of inventing dictionary words, so
     * "insta gram" resolves to "instagram". "[unk]" lets out-of-grammar speech
     * still be recognized (as unknown) rather than forced to a wrong phrase.
     *
     * Vosk needs lowercase words that exist in the model's phonetic dictionary;
     * app names are split into their words (e.g. "google maps") and unknown
     * tokens are dropped per word, so a weird name just contributes its
     * pronounceable parts. Built once and cached.
     * shortcut: rebuilt only per VoskStt instance; if the user installs an app
     * mid-session the grammar misses it until the app restarts.
     */
    @Volatile private var grammar: String? = null
    private fun buildGrammar(): String {
        grammar?.let { return it }
        val words = linkedSetOf<String>()
        // Command vocabulary the recognizer must still handle. Mirrors every
        // trigger word in Intents.keywordIntent() (all features) — keep in sync
        // when a new command/keyword is added there.
        val commands = (
            // open-app + search
            "open launch start search for google look up browser web chrome internet " +
            // calls / sms / notes
            "call dial phone text message sms send to remind me note write down make a " +
            // alarm / timer / calendar
            "set alarm timer in minutes minute hour hours schedule meeting event appointment " +
            "reminder remember my is what " +
            // device / media / volume / bluetooth
            "flashlight torch light battery bluetooth on off toggle enable disable turn " +
            "play pause resume next previous skip track song music volume up down mute unmute " +
            // weather / time / date / fun / math / info / chat
            "weather forecast temperature time clock date today day " +
            "roll a dice coin flip joke tell " +
            "plus minus times divided by calculate " +
            "status how are you your name version who am age can do cheat help commands " +
            "hi hello hey " +
            // number words (math + clock times)
            "zero one two three four five six seven eight nine ten eleven twelve " +
            "twenty thirty forty fifty sixty hundred am pm o'clock " +
            // common Indian apps (seed the grammar; fuzzy-matcher closes the gap
            // for whatever the installed-app list spells them as)
            "zomato swiggy paytm phonepe google pay jio hotstar flipkart amazon ola " +
            "uber myntra meesho cred gpay whatsapp instagram youtube telegram"
            ).split(" ")
        commands.forEach { if (it.isNotBlank()) words += it }
        // every installed launchable app, split into lowercase words
        val pm = ctx.packageManager
        val main = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        for (ri in pm.queryIntentActivities(main, 0)) {
            ri.loadLabel(pm).toString().lowercase()
                .split(Regex("[^a-z0-9]+"))
                .forEach { if (it.isNotBlank()) words += it }
        }
        // JSON array: each word as its own phrase, plus [unk] fallback
        val json = words.joinToString(",", "[", ",\"[unk]\"]") { "\"$it\"" }
        android.util.Log.i("NeedleVoice", "grammar: ${words.size} words")
        return json.also { grammar = it }
    }

    private fun downloadAndUnzip() {
        val client = okhttp3.OkHttpClient()
        val resp = client.newCall(okhttp3.Request.Builder().url(MODEL_URL).build()).execute()
        resp.use {
            if (!it.isSuccessful) throw RuntimeException("model download failed: ${it.code}")
            ZipInputStream(it.body!!.byteStream()).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    val out = File(ctx.filesDir, e.name)
                    if (e.isDirectory) out.mkdirs()
                    else { out.parentFile?.mkdirs(); out.outputStream().use { o -> zip.copyTo(o) } }
                    e = zip.nextEntry
                }
            }
        }
    }

    /**
     * Connect to the ESP audio stream and transcribe until it closes (pad
     * released). Returns the recognized text, or "" if nothing/failed.
     * Blocking — call from a background coroutine.
     */
    fun transcribeStream(): String {
        val m = ensureModel()
        val gram = buildGrammar()   // constrain to commands + installed app names
        android.util.Log.i("NeedleVoice", "model ready, connecting to $ESP_IP:$ESP_AUDIO_PORT")
        Recognizer(m, SAMPLE_RATE, gram).use { rec ->
            Socket(ESP_IP, ESP_AUDIO_PORT).use { sock ->
                android.util.Log.i("NeedleVoice", "socket connected, reading audio")
                val input = sock.getInputStream()
                val buf = ByteArray(4096)
                var total = 0
                while (true) {
                    val got = input.read(buf)
                    if (got < 0) break               // ESP closed = button released
                    total += got
                    rec.acceptWaveForm(buf, got)
                }
                val json = rec.finalResult
                android.util.Log.i("NeedleVoice", "stream ended, $total bytes, result=$json")
                return JSONObject(json).optString("text", "")
            }
        }
    }
}
