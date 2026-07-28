package com.orbis.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.orbis.app.surface.AccessibilityAccess
import com.orbis.app.surface.SurfaceMonitor
import com.orbis.app.ui.UsageScreen
import com.orbis.app.ui.UsageViewModel
import com.orbis.app.ui.theme.OrbisTheme
import com.orbis.app.usage.UsageAccess

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OrbisTheme {
                val context = LocalContext.current
                val viewModel: UsageViewModel =
                    viewModel(factory = UsageViewModel.factory(context))
                val state by viewModel.state.collectAsStateWithLifecycle()
                val detected by SurfaceMonitor.state.collectAsStateWithLifecycle()
                var hasDetection by remember { mutableStateOf(false) }

                // Both permissions are granted over in Settings, so coming back to
                // the foreground is the only reliable moment to re-check them.
                LifecycleResumeEffect(Unit) {
                    viewModel.onUsageAccessChanged(UsageAccess.isGranted(context))
                    hasDetection = AccessibilityAccess.isEnabled(context)
                    onPauseOrDispose { }
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    UsageScreen(
                        state = state,
                        detected = detected,
                        hasDetection = hasDetection,
                        onGrantUsageAccess = {
                            context.startActivity(UsageAccess.settingsIntent())
                        },
                        onEnableDetection = {
                            context.startActivity(AccessibilityAccess.settingsIntent())
                        },
                        onRefresh = viewModel::refresh,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }
}
