package com.androidengineers.agent_quickstart_android.accessibility

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object GeminiAgent {
    // We will hardcode the key for the hackathon MVP
    private const val API_KEY = "YOUR_GEMINI_API_KEY"
    private const val API_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.6-flash:generateContent?key=$API_KEY"
    private const val FALLBACK_API_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-lite:generateContent?key=$API_KEY"
    suspend fun decideNextAction(xml: String, instruction: String, actionHistory: List<String>): String? {
        return withContext(Dispatchers.IO) {
            try {
                val historyStr = if (actionHistory.isEmpty()) "None" else actionHistory.joinToString("\n")
            val prompt = """
                Given the current Android UI state (represented as XML), your task is to figure out the next step to accomplish this instruction: '$instruction'.
                
                Previously Taken Actions:
                $historyStr
                
                Your job is to read the provided Android UI XML and the history of actions you've already taken, and output a JSON decision on what to do next.
                Output JSON ONLY:
                {"action": "click", "text": "Button Text"}
                {"action": "type", "text": "...\\n", "target_class": "..."} // Append \n to the text if you want to press Enter/Search on the keyboard
                {"action": "swipe", "direction": "up|down|left|right", "target_app": "App Name"} // When dismissing from Recents, ALWAYS include target_app so the correct card is swiped. For plain scrolling, omit target_app.
                {"action": "tap", "x": 50, "y": 30} // Tap a specific screen coordinate (percentages 0-100). Use to click things not in XML.
                {"action": "set_picker_value", "picker_index": 0, "value": "5"} // Sets the value of a scroll-wheel number picker. picker_index 0=hours, 1=minutes. Use for alarm time pickers.
                {"action": "open_app", "app_name": "exact app name"}
                {"action": "global_action", "id": "home|back|recents|take_screenshot"}
                {"action": "done"}
                
                When the instruction is fully complete, you MUST output {"action": "done"} to stop the loop.
                CRITICAL: If you need to open an app (like WhatsApp, YouTube, etc), always output the "open_app" action first.
                CRITICAL: If you just clicked the 'Send' button in a messaging app, or if you see the message you wanted to send in the chat history, you MUST output {"action": "done"} immediately. DO NOT type or send the message again!
                CRITICAL: If you see that you are repeating the same actions in your 'Previously Taken Actions' history without making progress, or if the task is impossible (like uninstalling an app), you MUST output {"action": "done"} to abort and prevent an infinite loop!
                CRITICAL: If you are asked to pause/play a YouTube video, the controls are usually hidden! You MUST output {"action": "tap", "x": 50, "y": 30} to tap the video player to reveal the controls, then on the next step click "Pause video" or "Play video".
                CRITICAL: For setting alarm time, the time picker is a scroll wheel (NumberPicker). DO NOT try to click or type into the numbers. ALWAYS use set_picker_value: first {"action": "set_picker_value", "picker_index": 0, "value": "5"} for hour, then {"action": "set_picker_value", "picker_index": 1, "value": "0"} for minute. Then click "Done" or "OK" to save.
                CRITICAL: To dismiss or snooze a ringing alarm, first tap the center of the screen to reveal the dismiss/snooze controls, then click "Dismiss" or "Snooze".
                
                Here is the current screen layout in XML format:
                $xml
            """.trimIndent()

            val requestBody = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", prompt)
                            })
                        })
                    })
                })
            }

            fun makeRequest(apiUrl: String): String? {
                val url = URL(apiUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 15000 // 15 seconds
                conn.readTimeout = 15000 // 15 seconds
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true

                OutputStreamWriter(conn.outputStream).use { it.write(requestBody.toString()) }

                val responseCode = conn.responseCode
                if (responseCode == 200) {
                    val responseStr = conn.inputStream.bufferedReader().use { it.readText() }
                    val responseJson = JSONObject(responseStr)
                    val textResponse = responseJson
                        .getJSONArray("candidates")
                        .getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")
                        .getJSONObject(0)
                        .getString("text")
                    
                    // Clean up Markdown formatting from Gemini
                    val cleanJson = textResponse.replace("```json", "").replace("```", "").trim()
                    Log.i("GeminiAgent", "Received decision: $cleanJson")
                    return cleanJson
                } else if (responseCode == 429) {
                    Log.w("GeminiAgent", "API Error 429 Quota Exhausted on url: $apiUrl")
                    throw Exception("429 Quota Exhausted")
                } else {
                    val errorStr = conn.errorStream?.bufferedReader()?.use { it.readText() }
                    Log.e("GeminiAgent", "API Error: $responseCode - $errorStr")
                    return null
                }
            }

            try {
                return@withContext makeRequest(API_URL)
            } catch (e: Exception) {
                if (e.message == "429 Quota Exhausted") {
                    Log.i("GeminiAgent", "Falling back to default gemini model due to 429 error.")
                    return@withContext makeRequest(FALLBACK_API_URL)
                }
                return@withContext null
            }
        } catch (e: Exception) {
            Log.e("GeminiAgent", "Exception in decision logic", e)
            return@withContext null
        }
    }
}
}
