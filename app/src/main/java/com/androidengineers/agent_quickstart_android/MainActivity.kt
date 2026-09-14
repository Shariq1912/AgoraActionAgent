package com.androidengineers.agent_quickstart_android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidengineers.agent_quickstart_android.ui.ConversationScreen
import com.androidengineers.agent_quickstart_android.ui.ConversationViewModel
import com.androidengineers.agent_quickstart_android.ui.theme.AgentquickstartandroidTheme

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<ConversationViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val systemDarkTheme = isSystemInDarkTheme()

            LaunchedEffect(systemDarkTheme) {
                viewModel.initializeTheme(systemDarkTheme)
            }

            AgentquickstartandroidTheme(darkTheme = uiState.isDarkTheme) {
                val context = LocalContext.current
                val currentViewModel by rememberUpdatedState(viewModel)
                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { granted ->
                    currentViewModel.updateMicrophonePermission(granted)
                    if (granted) {
                        currentViewModel.startConversation()
                    }
                }

                LaunchedEffect(Unit) {
                    val granted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    currentViewModel.updateMicrophonePermission(granted)
                }

                val isAccessibilityEnabled = isAccessibilityServiceEnabled(context, com.androidengineers.agent_quickstart_android.accessibility.ActionAccessibilityService::class.java)

                ConversationScreen(
                    uiState = uiState,
                    onStartRequested = {
                        if (!isAccessibilityEnabled) {
                            android.widget.Toast.makeText(context, "Please enable Accessibility Service first!", android.widget.Toast.LENGTH_LONG).show()
                            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            return@ConversationScreen
                        }
                        val granted = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                        if (granted) {
                            currentViewModel.startConversation()
                        } else {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onEndConversation = { currentViewModel.endConversation() },
                    onToggleMicrophone = { currentViewModel.toggleMicrophone() },
                    onToggleTheme = { currentViewModel.toggleTheme() },
                    onDismissMessages = { currentViewModel.clearTransientMessages() },
                    onConfirmAction = { currentViewModel.confirmAction() },
                    onCancelAction = { currentViewModel.cancelAction() }
                )
            }
        }
    }

    private fun isAccessibilityServiceEnabled(context: android.content.Context, service: Class<*>): Boolean {
        val am = context.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
        val enabledServices = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val colonSplitter = android.text.TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)
        while (colonSplitter.hasNext()) {
            val componentName = colonSplitter.next()
            if (componentName.equals("${context.packageName}/${service.name}", ignoreCase = true) ||
                componentName.equals("${context.packageName}/${service.canonicalName}", ignoreCase = true)
            ) {
                return true
            }
        }
        return false
    }
}
