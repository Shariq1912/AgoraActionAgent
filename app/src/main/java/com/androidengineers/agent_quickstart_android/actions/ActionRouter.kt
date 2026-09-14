package com.androidengineers.agent_quickstart_android.actions

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ActionRouter {
    private val actionQueue = ArrayDeque<ActionRequest>()
    
    private val _pendingAction = MutableStateFlow<ActionRequest?>(null)
    val pendingAction: StateFlow<ActionRequest?> = _pendingAction.asStateFlow()

    private val _actionState = MutableStateFlow<ActionState>(ActionState.IDLE)
    val actionState: StateFlow<ActionState> = _actionState.asStateFlow()

    fun routeAction(request: ActionRequest) {
        actionQueue.clear()
        _actionState.value = ActionState.IDLE // Force state change to trigger observers
        
        _pendingAction.value = request
        if (ActionPolicy.requiresConfirmation(request.tool)) {
            _actionState.value = ActionState.WAITING_FOR_CONFIRMATION
        } else {
            executeCurrentAction()
        }
    }

    private fun processNextAction() {
        if (actionQueue.isEmpty()) {
            _pendingAction.value = null
            _actionState.value = ActionState.IDLE
            return
        }
        val next = actionQueue.removeFirst()
        _pendingAction.value = next
        
        if (ActionPolicy.requiresConfirmation(next.tool)) {
            _actionState.value = ActionState.WAITING_FOR_CONFIRMATION
        } else {
            executeCurrentAction()
        }
    }

    fun confirmAction() {
        if (_actionState.value == ActionState.WAITING_FOR_CONFIRMATION) {
            executeCurrentAction()
        }
    }

    fun cancelAction() {
        _actionState.value = ActionState.CANCELLED
        // Clear queue when cancelled? Usually good practice for safety
        actionQueue.clear()
        processNextAction()
    }

    private fun executeCurrentAction() {
        val request = _pendingAction.value ?: return
        _actionState.value = ActionState.RUNNING
    }
    
    fun setActionState(state: ActionState) {
        _actionState.value = state
        if (state == ActionState.SUCCESS || state == ActionState.FAILED || state == ActionState.CANCELLED) {
            processNextAction() // Move to next action in queue
        }
    }
}
