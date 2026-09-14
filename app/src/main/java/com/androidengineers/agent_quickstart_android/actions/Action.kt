package com.androidengineers.agent_quickstart_android.actions

data class ActionRequest(
    val tool: String,
    val arguments: Map<String, String>
)

enum class ActionState {
    IDLE,
    PENDING,
    RUNNING,
    WAITING_FOR_CONFIRMATION,
    SUCCESS,
    FAILED,
    CANCELLED
}

data class ActionResult(
    val success: Boolean,
    val action: String,
    val error: String? = null
)
