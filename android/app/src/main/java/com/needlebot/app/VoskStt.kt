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
        private const val MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
        private const val MODEL_DIR = "vosk-model-small-en-us-0.15"  // the zip's top folder
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
        android.util.Log.i("NeedleVoice", "model ready, connecting to $ESP_IP:$ESP_AUDIO_PORT")
        Recognizer(m, SAMPLE_RATE).use { rec ->
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
