package com.androidengineers.agent_quickstart_android.actions

object ActionPolicy {
    fun requiresConfirmation(tool: String): Boolean {
        return when (tool) {
            "send_whatsapp_message" -> true
            "make_phone_call" -> true
            "start_navigation" -> false
            "search_youtube" -> false
            "open_app" -> false
            "agora_auto_pilot" -> false
            else -> true // default to safe
        }
    }
}
