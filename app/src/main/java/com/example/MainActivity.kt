package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableIntStateOf
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.sol.ui.SolAppRoot
import com.example.sol.ui.SolViewModel

class MainActivity : ComponentActivity() {
    private val voiceTriggerNonce = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        handleVoiceLaunchIntent(intent)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            val solViewModel: SolViewModel = viewModel()
            SolAppRoot(
                viewModel = solViewModel,
                externalVoiceTriggerNonce = voiceTriggerNonce.intValue,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleVoiceLaunchIntent(intent)
    }

    private fun handleVoiceLaunchIntent(launchIntent: Intent?) {
        if (launchIntent?.getBooleanExtra(EXTRA_START_VOICE, false) == true) {
            launchIntent.removeExtra(EXTRA_START_VOICE)
            voiceTriggerNonce.intValue += 1
        }
    }

    companion object {
        const val EXTRA_START_VOICE = "com.example.sol.EXTRA_START_VOICE"
    }
}
