package com.androidengineers.agent_quickstart_android.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.*
import com.androidengineers.agent_quickstart_android.actions.ActionRouter
import com.androidengineers.agent_quickstart_android.actions.ActionState

class ActionAccessibilityService : AccessibilityService() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private var actionJob: Job? = null

    private var tts: android.speech.tts.TextToSpeech? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i("ActionAccService", "Service connected")
        
        tts = android.speech.tts.TextToSpeech(this) { status ->
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                tts?.language = java.util.Locale.US
            }
        }
        
        serviceScope.launch {
            ActionRouter.actionState.collect { state ->
                when (state) {
                    ActionState.RUNNING -> {
                        val request = ActionRouter.pendingAction.value
                        if (request != null) {
                            actionJob?.cancel()
                            actionJob = serviceScope.launch {
                                try {
                                    executeRequest(request.tool, request.arguments)
                                } catch (e: CancellationException) {
                                    Log.i("ActionAccService", "Action cancelled by new command")
                                }
                            }
                        }
                    }
                    ActionState.IDLE, ActionState.CANCELLED -> {
                        actionJob?.cancel()
                    }
                    else -> {}
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We can monitor window changes here if needed
    }

    override fun onInterrupt() {
        actionJob?.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        tts?.shutdown()
    }

    private suspend fun executeRequest(tool: String, args: Map<String, String>) {
        withContext(Dispatchers.Default) {
            try {
                Log.i("ActionAccService", "Executing tool: $tool")
                when (tool) {
                    "agora_auto_pilot" -> executeAgoraAutoPilot(args)
                    "send_whatsapp_message" -> executeWhatsAppMessage(args)
                    "start_navigation" -> executeMapsNavigation(args)
                    "search_youtube" -> executeYouTubeSearch(args)
                    "open_app" -> executeOpenApp(args)
                    else -> Log.w("ActionAccService", "Unknown tool: $tool")
                }
            } catch (e: Exception) {
                Log.e("ActionAccService", "Error executing tool", e)
                ActionRouter.setActionState(ActionState.FAILED)
            }
        }
    }

    private suspend fun executeAgoraAutoPilot(args: Map<String, String>) {
        val command = args["command"] ?: return
        val requestedApp = args["app_name"]
        Log.i("ActionAccService", "Starting Agora Auto Pilot for: $command in app: $requestedApp")
        
        var isDone = false
        var iterations = 0
        val actionHistory = mutableListOf<String>()
        
        while (!isDone && iterations < 10) {
            val xml = rootInActiveWindow.toSimpleXml()
            val decision = GeminiAgent.decideNextAction(xml, command, actionHistory)
            if (decision != null) {
                try {
                    val json = org.json.JSONObject(decision)
                    Log.i("GeminiAgent", "Received decision: $decision")
                    val actionType = json.getString("action")
                    actionHistory.add("Action: $actionType, details: $decision")
                    when (actionType) {
                        "open_app" -> {
                            val targetAppName = json.optString("app_name", "")
                            val success = executeOpenApp(mapOf("app_name" to targetAppName), isStandalone = false)
                            if (!success) {
                                tts?.speak("I couldn't find the app $targetAppName on your phone. Would you like me to check on the Play Store?", android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, null)
                                isDone = true
                                ActionRouter.setActionState(ActionState.FAILED)
                            }
                        }
                        "global_action" -> {
                            val actionId = json.optString("id", "")
                            when (actionId) {
                                "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
                                "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
                                "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                                "take_screenshot" -> {
                                    if (android.os.Build.VERSION.SDK_INT >= 28) {
                                        performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
                                    }
                                }
                            }
                        }
                        "click" -> {
                            val text = json.optString("text", "")
                            // Prefer clicking non-EditTexts (like search suggestions) over the search box itself
                            val allMatches = rootInActiveWindow?.findAccessibilityNodeInfosByText(text) ?: emptyList()
                            var node = allMatches.firstOrNull { it.className?.toString() != "android.widget.EditText" && it.text?.toString()?.equals(text, ignoreCase = true) == true } 
                                ?: allMatches.firstOrNull { it.className?.toString() != "android.widget.EditText" }
                                ?: findNodeByText(text) 
                                ?: findNodeByContentDescription(text)

                            var clickableNode = node
                            while (clickableNode != null && !clickableNode.isClickable) {
                                clickableNode = clickableNode.parent
                            }
                            (clickableNode ?: node)?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        }
                        "type" -> {
                            val text = json.optString("text", "")
                            val targetClass = json.optString("target_class", "")
                            val node = if (targetClass.isNotEmpty()) {
                                findNodeByClassName(targetClass) 
                            } else {
                                findNodeByText("Search") ?: findNodeByContentDescription("Search")
                            }
                            
                            if (node == null) {
                                Log.w("ActionAccService", "Could not find node to type into! targetClass=$targetClass")
                            } else {
                                // Try clicking it first to give it focus (many apps require this)
                                var clickableNode: AccessibilityNodeInfo? = node
                                while (clickableNode != null && !clickableNode.isClickable) {
                                    clickableNode = clickableNode.parent
                                }
                                (clickableNode ?: node).performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                kotlinx.coroutines.delay(500)

                                val cleanText = text.removeSuffix("\n")
                                val textArgs = android.os.Bundle().apply {
                                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, cleanText)
                                }
                                val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, textArgs)
                                
                                var activeNode = node
                                if (!success) {
                                    Log.w("ActionAccService", "ACTION_SET_TEXT failed, trying ACTION_PASTE fallback")
                                    if (!activeNode.refresh()) {
                                        val newRoot = rootInActiveWindow
                                        val freshNode = if (targetClass.isNotEmpty()) findNodeByClassName(targetClass, newRoot) else findNodeByClassName("android.widget.EditText", newRoot)
                                        if (freshNode != null) activeNode = freshNode
                                    }
                                    
                                    val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    val clip = android.content.ClipData.newPlainText("text", cleanText)
                                    clipboard.setPrimaryClip(clip)
                                    activeNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                                    activeNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                                }
                                
                                if (text.endsWith("\n") && android.os.Build.VERSION.SDK_INT >= 30) {
                                    kotlinx.coroutines.delay(500)
                                    activeNode.performAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                                }
                            }
                        }
                        "swipe" -> {
                            if (android.os.Build.VERSION.SDK_INT >= 24) {
                                val direction = json.optString("direction", "up")
                                val targetApp = json.optString("target_app", "")
                                val metrics = resources.displayMetrics
                                val width = metrics.widthPixels.toFloat()
                                val height = metrics.heightPixels.toFloat()
                                
                                // If a target_app is specified and we're swiping up (dismiss from recents),
                                // find the exact app card and swipe on IT to avoid removing the wrong app.
                                var swiped = false
                                if (direction == "up" && targetApp.isNotEmpty()) {
                                    val root = rootInActiveWindow
                                    // Step 1: Find the text label node matching the target app name
                                    val labelNode = root?.findAccessibilityNodeInfosByText(targetApp)
                                        ?.firstOrNull { node ->
                                            node.text?.toString()?.contains(targetApp, ignoreCase = true) == true
                                        }
                                    if (labelNode != null) {
                                        // Step 2: Walk UP the tree from the label to find the card container.
                                        // Stop when width >= 60% of screen AND still < 95% of screen width
                                        // (to avoid picking up the root/full-screen container).
                                        val minCardWidth = (width * 0.6f).toInt()
                                        val maxCardWidth = (width * 0.95f).toInt()
                                        var cardNode: AccessibilityNodeInfo = labelNode
                                        var parent = labelNode.parent
                                        while (parent != null) {
                                            val b = android.graphics.Rect()
                                            parent.getBoundsInScreen(b)
                                            if (b.width() >= minCardWidth && b.width() <= maxCardWidth) {
                                                cardNode = parent
                                                break
                                            }
                                            // Stop if we've already found something reasonable but parent is too wide
                                            if (b.width() > maxCardWidth) break
                                            parent = parent.parent
                                        }
                                        val bounds = android.graphics.Rect()
                                        cardNode.getBoundsInScreen(bounds)
                                        val cardCenterX = bounds.centerX().toFloat()
                                        val cardCenterY = bounds.centerY().toFloat()
                                        Log.i("ActionAccService", "Swiping to dismiss '$targetApp' from card bounds=$bounds center=($cardCenterX, $cardCenterY)")
                                        // Step 3: Fast fling upward — Android needs velocity to dismiss cards.
                                        // Duration must be SHORT (120ms) so it registers as a fling, not a slow drag.
                                        // GestureDescription Y must be >= 0 (no negative coords allowed).
                                        val swipeEndY = maxOf(10f, cardCenterY - height * 0.8f)
                                        val path = android.graphics.Path()
                                        path.moveTo(cardCenterX, cardCenterY)
                                        path.lineTo(cardCenterX, swipeEndY)
                                        val gestureBuilder = android.accessibilityservice.GestureDescription.Builder()
                                        gestureBuilder.addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 120))
                                        dispatchGesture(gestureBuilder.build(), null, null)
                                        // Wait for dismiss animation then mark done
                                        kotlinx.coroutines.delay(800)
                                        isDone = true
                                        swiped = true
                                    } else {
                                        Log.w("ActionAccService", "Could not find app card for '$targetApp' in recents — skipping swipe to avoid removing wrong app")
                                        swiped = true // safer to skip than dismiss wrong app
                                    }
                                }
                                
                                // Generic directional swipe (scroll, or dismiss when no target_app specified)
                                if (!swiped) {
                                    val startX = width / 2f
                                    val startY = height / 2f
                                    val endX: Float
                                    val endY: Float
                                    when (direction) {
                                        "up"    -> { endX = startX; endY = height * 0.1f }
                                        "down"  -> { endX = startX; endY = height * 0.9f }
                                        "left"  -> { endX = width * 0.1f; endY = startY }
                                        "right" -> { endX = width * 0.9f; endY = startY }
                                        else    -> { endX = startX; endY = height * 0.1f }
                                    }
                                    val path = android.graphics.Path()
                                    path.moveTo(startX, startY)
                                    path.lineTo(endX, endY)
                                    val gestureBuilder = android.accessibilityservice.GestureDescription.Builder()
                                    val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 300)
                                    gestureBuilder.addStroke(stroke)
                                    dispatchGesture(gestureBuilder.build(), null, null)
                                }
                            } else {
                                Log.w("ActionAccService", "Swipe requires API level 24+")
                            }
                        }
                        "tap" -> {
                            if (android.os.Build.VERSION.SDK_INT >= 24) {
                                val xPercent = json.optDouble("x", 50.0).toFloat()
                                val yPercent = json.optDouble("y", 50.0).toFloat()
                                
                                val metrics = resources.displayMetrics
                                val targetX = (metrics.widthPixels * (xPercent / 100f))
                                val targetY = (metrics.heightPixels * (yPercent / 100f))
                                
                                val path = android.graphics.Path()
                                path.moveTo(targetX, targetY)
                                
                                val gestureBuilder = android.accessibilityservice.GestureDescription.Builder()
                                val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 50)
                                gestureBuilder.addStroke(stroke)
                                dispatchGesture(gestureBuilder.build(), null, null)
                                
                                // After revealing controls (e.g. YouTube), immediately try to click Pause/Play
                                // before the controls auto-hide (typically after ~2 seconds)
                                kotlinx.coroutines.delay(800)
                                val pauseNode = findNodeByContentDescription("Pause video")
                                    ?: findNodeByContentDescription("Play video")
                                    ?: findNodeByContentDescription("Pause")
                                    ?: findNodeByContentDescription("Play")
                                if (pauseNode != null) {
                                    var clickable = pauseNode
                                    while (clickable != null && !clickable.isClickable) clickable = clickable.parent
                                    (clickable ?: pauseNode).performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                    Log.i("ActionAccService", "Clicked pause/play button after tap")
                                    isDone = true
                                }
                            }
                        }
                        "set_picker_value" -> {
                            // Sets the value of an Android NumberPicker scroll wheel.
                            // picker_index: 0 = first picker (hours), 1 = second picker (minutes)
                            // value: the integer value to set (e.g. 5 for 5 AM, 0 for :00 minutes)
                            if (android.os.Build.VERSION.SDK_INT >= 24) {
                                val pickerIndex = json.optInt("picker_index", 0)
                                val value = json.optString("value", "0")
                                val pickers = findAllNodesByClassName("android.widget.NumberPicker")
                                Log.i("ActionAccService", "Found ${pickers.size} NumberPicker(s), targeting index $pickerIndex with value=$value")
                                val targetPicker = pickers.getOrNull(pickerIndex)
                                if (targetPicker != null) {
                                    // Try to set via the EditText child (most reliable on AOSP/stock Android)
                                    var editText: AccessibilityNodeInfo? = null
                                    for (i in 0 until targetPicker.childCount) {
                                        val child = targetPicker.getChild(i)
                                        if (child?.className?.toString() == "android.widget.EditText") {
                                            editText = child
                                            break
                                        }
                                    }
                                    if (editText != null) {
                                        editText.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                        kotlinx.coroutines.delay(200)
                                        val args2 = android.os.Bundle().apply {
                                            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                                        }
                                        editText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args2)
                                        // Dismiss keyboard and commit value by pressing Enter
                                        if (android.os.Build.VERSION.SDK_INT >= 30) {
                                            kotlinx.coroutines.delay(300)
                                            editText.performAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                                        }
                                        Log.i("ActionAccService", "Set NumberPicker[$pickerIndex] EditText to '$value'")
                                    } else {
                                        // Fallback: use scroll actions to reach value from current
                                        val currentText = targetPicker.findAccessibilityNodeInfosByText("") 
                                            .firstOrNull()?.text?.toString()?.toIntOrNull() ?: 0
                                        val target = value.toIntOrNull() ?: 0
                                        val diff = target - currentText
                                        val action = if (diff > 0) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                                        repeat(Math.abs(diff)) {
                                            targetPicker.performAction(action)
                                            Thread.sleep(80)
                                        }
                                        Log.i("ActionAccService", "Scrolled NumberPicker[$pickerIndex] by $diff steps to reach $value")
                                    }
                                } else {
                                    Log.w("ActionAccService", "No NumberPicker found at index $pickerIndex")
                                }
                            }
                        }
                        "done" -> isDone = true
                    }
                } catch (e: Exception) {
                    Log.e("ActionAccService", "Error parsing decision", e)
                }
            }
            iterations++
            kotlinx.coroutines.delay(1500)
        }
        ActionRouter.setActionState(ActionState.SUCCESS)
    }

    private suspend fun executeOpenApp(args: Map<String, String>, isStandalone: Boolean = true): Boolean {
        val appName = args["app_name"]?.lowercase() ?: return false
        Log.i("ActionAccService", "executeOpenApp requested for: $appName")
        
        // Dynamic app discovery
        var packageName: String? = when {
            appName.contains("whatsapp") -> "com.whatsapp"
            appName.contains("maps") -> "com.google.android.apps.maps"
            appName.contains("youtube") -> "com.google.android.youtube"
            appName.contains("gmail") -> "com.google.android.gm"
            else -> null
        }
        
        if (packageName == null) {
            val apps = packageManager.getInstalledApplications(0)
            for (app in apps) {
                val name = packageManager.getApplicationLabel(app).toString().lowercase()
                if (name == appName) {
                    packageName = app.packageName
                    break
                }
            }
            if (packageName == null) {
                for (app in apps) {
                    val name = packageManager.getApplicationLabel(app).toString().lowercase()
                    if (name.contains(appName) || appName.contains(name)) {
                        packageName = app.packageName
                        break
                    }
                }
            }
        }

        Log.i("ActionAccService", "executeOpenApp resolved package: $packageName")
        if (packageName != null) {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                Log.i("ActionAccService", "executeOpenApp launching intent for: $packageName")
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                if (isStandalone) ActionRouter.setActionState(ActionState.SUCCESS)
                return true
            } else {
                Log.w("ActionAccService", "executeOpenApp no launch intent found for: $packageName")
            }
        } else {
            Log.w("ActionAccService", "executeOpenApp could not find package for: $appName")
        }
        if (isStandalone) ActionRouter.setActionState(ActionState.FAILED)
        return false
    }

    private suspend fun executeWhatsAppMessage(args: Map<String, String>) {
        val contact = args["contact"] ?: return
        val message = args["message"] ?: return

        // 1. Open WhatsApp
        val intent = packageManager.getLaunchIntentForPackage("com.whatsapp")
        if (intent != null) {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            Log.e("ActionAccService", "WhatsApp not installed")
            ActionRouter.setActionState(ActionState.FAILED)
            return
        }

        delay(3000) // Wait for app to open

        // 2. Click Search (Content Description "Search")
        val searchIcon = findNodeByContentDescription("Search")
        searchIcon?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        delay(1000)

        // 3. Type contact name (Look for any EditText)
        val searchBox = findNodeByClassName("android.widget.EditText")
        if (searchBox == null) {
            Log.e("ActionAccService", "Could not find search box")
            ActionRouter.setActionState(ActionState.FAILED)
            return
        }
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, contact)
        }
        searchBox.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        delay(2000)

        // 4. Click contact (usually the first result in the list, skipping the EditText)
        var contactNode = findNodeByText(contact)
        if (contactNode?.className?.toString() == "android.widget.EditText") {
            // Find the NEXT occurrence of this text that isn't the search box!
            val allNodes = rootInActiveWindow?.findAccessibilityNodeInfosByText(contact) ?: emptyList()
            contactNode = allNodes.firstOrNull { it.className?.toString() != "android.widget.EditText" }
        }
        if (contactNode == null) {
            Log.e("ActionAccService", "Could not find contact in list")
            ActionRouter.setActionState(ActionState.FAILED)
            return
        }
        
        var clickableNode: AccessibilityNodeInfo? = contactNode
        while (clickableNode != null && !clickableNode.isClickable) {
            clickableNode = clickableNode.parent
        }
        (clickableNode ?: contactNode).performAction(AccessibilityNodeInfo.ACTION_CLICK)
        
        delay(1500)

        // 5. Type message
        val messageBox = findNodeByClassName("android.widget.EditText")
        if (messageBox == null) {
            Log.e("ActionAccService", "Could not find message box")
            ActionRouter.setActionState(ActionState.FAILED)
            return
        }
        val msgArgs = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message)
        }
        messageBox.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, msgArgs)
        delay(1000)

        // 6. Click send (Content Description "Send")
        val sendBtn = findNodeByContentDescription("Send")
        sendBtn?.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        ActionRouter.setActionState(ActionState.SUCCESS)
    }

    private suspend fun executeMapsNavigation(args: Map<String, String>) {
        val destination = args["destination"] ?: return

        val intent = packageManager.getLaunchIntentForPackage("com.google.android.apps.maps")
        if (intent != null) {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            ActionRouter.setActionState(ActionState.FAILED)
            return
        }

        delay(3000)
        
        val searchBox = findNodeByText("Search here")
        searchBox?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        delay(1000)

        val activeSearchBox = findNodeByText("Search here") // sometimes text remains
        val mapArgs = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, destination)
        }
        activeSearchBox?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, mapArgs)
        delay(3000)

        // Click the first result
        val result = findNodeByText(destination)
        result?.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: result?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        delay(3000)

        val directionsBtn = findNodeByText("Directions") ?: findNodeByContentDescription("Directions")
        directionsBtn?.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        ActionRouter.setActionState(ActionState.SUCCESS)
    }

    private suspend fun executeYouTubeSearch(args: Map<String, String>) {
        val query = args["query"] ?: return
        
        val intent = packageManager.getLaunchIntentForPackage("com.google.android.youtube")
        if (intent != null) {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            ActionRouter.setActionState(ActionState.FAILED)
            return
        }

        delay(3000)
        
        val searchIcon = findNodeByContentDescription("Search")
        searchIcon?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        delay(1000)
        
        val searchBox = findNodeByText("Search YouTube")
        val ytArgs = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query)
        }
        searchBox?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, ytArgs)
        delay(2000)
        
        // Emulate enter? It's harder with just accessibility, we can click a search result suggestion
        val suggestion = findNodeByText(query)
        suggestion?.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: suggestion?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        
        ActionRouter.setActionState(ActionState.SUCCESS)
    }

    // Helper functions
    private fun findNodeByText(text: String, node: AccessibilityNodeInfo? = rootInActiveWindow): AccessibilityNodeInfo? {
        if (node == null) return null
        val list = node.findAccessibilityNodeInfosByText(text)
        if (list.isNotEmpty()) {
            for (n in list) {
                if (n.text?.toString()?.equals(text, ignoreCase = true) == true) {
                    return n
                }
            }
            return list[0] // fallback to first partial match
        }
        return null
    }

    private fun findNodeByContentDescription(desc: String, node: AccessibilityNodeInfo? = rootInActiveWindow): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.contentDescription?.toString()?.contains(desc, ignoreCase = true) == true) {
            return node
        }
        for (i in 0 until node.childCount) {
            val result = findNodeByContentDescription(desc, node.getChild(i))
            if (result != null) return result
        }
        return null
    }

    private fun findNodeByClassName(className: String, node: AccessibilityNodeInfo? = rootInActiveWindow): AccessibilityNodeInfo? {
        if (node == null) return null
        val nodeClass = node.className?.toString() ?: ""
        if (nodeClass == className || nodeClass.endsWith(className, ignoreCase = true)) {
            return node
        }
        for (i in 0 until node.childCount) {
            val result = findNodeByClassName(className, node.getChild(i))
            if (result != null) return result
        }
        return null
    }

    private fun findAllNodesByClassName(className: String, node: AccessibilityNodeInfo? = rootInActiveWindow, results: MutableList<AccessibilityNodeInfo> = mutableListOf()): List<AccessibilityNodeInfo> {
        if (node == null) return results
        val nodeClass = node.className?.toString() ?: ""
        if (nodeClass == className || nodeClass.endsWith(className, ignoreCase = true)) {
            results.add(node)
        }
        for (i in 0 until node.childCount) {
            findAllNodesByClassName(className, node.getChild(i), results)
        }
        return results
    }
}
