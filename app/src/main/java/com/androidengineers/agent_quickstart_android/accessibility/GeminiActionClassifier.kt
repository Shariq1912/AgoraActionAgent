package com.androidengineers.agent_quickstart_android.accessibility

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object GeminiActionClassifier {

    private val API_KEY get() = GeminiAgent.API_KEY_PUBLIC
    private const val API_URL =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent"
    private const val FALLBACK_API_URL =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.6-flash:generateContent"

    private val SYSTEM_PROMPT = """
You are an intent classifier for an Android voice assistant.
Decide if the user's phrase is a phone action command or just conversation.

Phone actions: opening apps, sending messages, playing/pausing media, setting alarms,
scrolling, screenshots, home/back, searching, closing apps, any direct phone interaction.

Respond ONLY with valid JSON. No explanation, no markdown.

If it IS an action: {"command": "<concise instruction>"}
If it is NOT an action (chatting, questions, unclear): {"command": null}

Examples:
open WhatsApp -> {"command": "open WhatsApp"}
pause the video -> {"command": "pause video"}
send hi to mom -> {"command": "send hi to mom on WhatsApp"}
go home -> {"command": "press home button"}
what time is it -> {"command": null}
hello -> {"command": null}
set alarm for 6am -> {"command": "set alarm for 6am"}
close this app -> {"command": "close current app"}
""".trimIndent()

    /**
     * Fast local rule-based intent classifier (0ms latency, zero network dependency).
     * Strips conversational filler phrases like "can you", "please", "i want to" first.
     */
    private fun tryLocalMatch(userText: String): String? {
        val raw = userText.trim()
        // Strip common conversational prefixes/fillers
        val clean = raw.replace(
            Regex("^(can you|could you|please|kindly|hey|autopilot|i want to|would you|can you please|could you please)\\s+", RegexOption.IGNORE_CASE),
            ""
        ).trim()

        val lower = clean.lowercase()
        return when {
            // Open App commands
            lower.startsWith("open ") || lower.startsWith("launch ") || lower.startsWith("start ") -> {
                val app = clean.replace(Regex("^(open|launch|start)\\s+", RegexOption.IGNORE_CASE), "").trim()
                if (app.isNotBlank()) "open $app" else null
            }
            // Media controls
            lower.contains("pause") -> "pause video"
            lower.contains("play video") || lower.contains("resume video") -> "play video"
            // Alarms
            lower.contains("alarm") || lower.contains("wake me up") -> clean
            // Scrolling
            lower.contains("scroll down") -> "scroll down"
            lower.contains("scroll up") -> "scroll up"
            lower.contains("scroll") -> "scroll down"
            // Global navigation
            lower.contains("go home") || lower == "home" || lower.contains("home screen") -> "press home button"
            lower.contains("go back") || lower == "back" -> "press back button"
            lower.contains("screenshot") -> "take screenshot"
            lower.contains("close app") || lower.contains("close this app") || lower.contains("dismiss app") -> "close current app"
            else -> null
        }
    }

    /**
     * Emergency fallback when network call times out or fails completely.
     * Guarantees an action is dispatched if the user text contains action keywords.
     */
    private fun extractFallbackCommand(userText: String): String? {
        val lower = userText.lowercase()
        return when {
            lower.contains("open ") -> "open " + userText.substringAfter("open ").trim()
            lower.contains("alarm") -> userText.trim()
            lower.contains("pause") -> "pause video"
            lower.contains("play") -> "play video"
            lower.contains("scroll") -> "scroll down"
            lower.contains("home") -> "press home button"
            lower.contains("back") -> "press back button"
            lower.contains("close") -> "close current app"
            else -> null
        }
    }

    suspend fun classify(userText: String): String? = withContext(Dispatchers.IO) {
        if (userText.isBlank()) return@withContext null

        // STEP 1: Try instant local pattern matching first (0ms latency)
        val localCommand = tryLocalMatch(userText)
        if (localCommand != null) {
            Log.i("GeminiClassifier", "Local pattern matched '$userText' -> command: '$localCommand'")
            return@withContext localCommand
        }

        // STEP 2: Fall back to Gemini API for complex / natural queries
        val requestBody = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", "$SYSTEM_PROMPT\nUser utterance: $userText") })
                    })
                })
            })
        }

        fun makeRequest(endpoint: String): String? {
            val url = URL("$endpoint?key=$API_KEY")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 4000 // Reduced to 4s to prevent hanging
            conn.readTimeout = 4000 // Reduced to 4s to prevent hanging
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            OutputStreamWriter(conn.outputStream).use { it.write(requestBody.toString()) }

            if (conn.responseCode == 429) {
                Log.w("GeminiClassifier", "HTTP 429 Quota Exhausted on $endpoint")
                return "429"
            }
            if (conn.responseCode != 200) {
                val errBody = conn.errorStream?.bufferedReader()?.use { it.readText() }
                Log.w("GeminiClassifier", "HTTP ${conn.responseCode} error on $endpoint: $errBody")
                return null
            }

            val responseStr = conn.inputStream.bufferedReader().use { it.readText() }
            val textResponse = runCatching {
                JSONObject(responseStr)
                    .getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
            }.getOrNull() ?: return null

            Log.d("GeminiClassifier", "Raw response from $endpoint: $textResponse")

            // Extract JSON block using regex to withstand any surrounding text or markdown formatting
            val jsonMatch = Regex("\\{.*\\}", RegexOption.DOT_MATCHES_ALL).find(textResponse)?.value ?: textResponse
            val command = runCatching {
                val json = JSONObject(jsonMatch)
                val cmd = json.optString("command", "null")
                if (cmd == "null" || cmd.isBlank()) null else cmd
            }.getOrNull()

            return command
        }

        try {
            val res = makeRequest(API_URL)
            if (res == "429") {
                Log.i("GeminiClassifier", "Falling back to $FALLBACK_API_URL due to 429 error.")
                val fallbackRes = makeRequest(FALLBACK_API_URL)
                return@withContext if (fallbackRes == "429") extractFallbackCommand(userText) else fallbackRes
            }
            return@withContext res ?: extractFallbackCommand(userText)
        } catch (e: Exception) {
            Log.e("GeminiClassifier", "Classification exception: ${e.message} — using emergency fallback")
            val fallback = extractFallbackCommand(userText)
            Log.i("GeminiClassifier", "Emergency fallback for '$userText' -> command: '$fallback'")
            return@withContext fallback
        }
    }
}
