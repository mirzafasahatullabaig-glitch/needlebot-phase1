package com.needlebot.app

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Talks to `needle --serve` running locally on the phone.
 * Verified contract (2026-10-28):
 *   POST /complete {"input":"..."} ->
 *   {"function_calls":[{"name":"intent_joke","arguments":{}}],"confidence":0.78,...}
 * Returns an intent code ("INTENT_JOKE") or "" when needle declines / is unsure.
 */
class NeedleClient(private val baseUrl: String = "http://127.0.0.1:8080") {

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = "application/json".toMediaType()
    private val confidenceMin = 0.70

    /** Returns INTENT_* or "" (caller then uses the keyword fallback). */
    fun classify(userText: String): String {
        val body = JSONObject().put("input", userText).toString().toRequestBody(json)
        val req = Request.Builder().url("$baseUrl/complete").post(body).build()
        return try {
            http.newCall(req).execute().use { resp ->
                val out = resp.body?.string().orEmpty()
                parseIntent(out)
            }
        } catch (e: Exception) {
            // needle --serve not running / unreachable -> let caller fall back
            ""
        }
    }

    /** Parse needle's tool-call JSON into an intent code. Mirrors
     *  _intent_from_needle in needle_host.py. */
    private fun parseIntent(out: String): String {
        return try {
            val data = JSONObject(out.trim().lines().last())
            val calls = data.optJSONArray("function_calls") ?: return ""
            if (calls.length() == 0) return ""            // model declined
            val conf = data.optDouble("confidence", 1.0)
            if (conf < confidenceMin) return ""           // too unsure -> fallback
            val name = calls.getJSONObject(0).optString("name", "")
            val intent = name.uppercase()                 // intent_joke -> INTENT_JOKE
            if (Intents.ALL.contains(intent)) intent else ""
        } catch (e: Exception) {
            ""
        }
    }
}
