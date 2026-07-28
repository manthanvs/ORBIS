package com.orbis.app

import android.app.Activity
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
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
import com.orbis.app.ui.TunnelUiState
import com.orbis.app.ui.UsageScreen
import com.orbis.app.ui.UsageViewModel
import com.orbis.app.ui.theme.OrbisTheme
import com.orbis.app.usage.UsageAccess
import com.orbis.app.vpn.OrbisVpnService
import kotlinx.coroutines.delay

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
                val tunnelRunning by OrbisVpnService.isRunning.collectAsStateWithLifecycle()
                var hasDetection by remember { mutableStateOf(false) }
                var counters by remember { mutableStateOf(0L to 0L) }

                // The service updates counters from its own threads, so poll them
                // rather than pretending they are reactive state.
                LaunchedEffect(tunnelRunning) {
                    while (true) {
                        counters = OrbisVpnService.udpPacketsForwarded to
                            OrbisVpnService.tcpPacketsDropped
                        delay(1_000)
                    }
                }

                // VpnService.prepare() returns an Intent the first time; consent is
                // a system dialog and cannot be granted any other way.
                val consentLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    if (result.resultCode == Activity.RESULT_OK) {
                        OrbisVpnService.start(context)
                    }
                }

                // Both permissions are granted over in Settings, so returning to
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
                        tunnel = TunnelUiState(
                            running = tunnelRunning,
                            udpForwarded = counters.first,
                            tcpDropped = counters.second,
                        ),
                        onGrantUsageAccess = {
                            context.startActivity(UsageAccess.settingsIntent())
                        },
                        onEnableDetection = {
                            context.startActivity(AccessibilityAccess.settingsIntent())
                        },
                        onToggleTunnel = {
                            if (tunnelRunning) {
                                OrbisVpnService.stop(context)
                            } else {
                                val consent = VpnService.prepare(context)
                                if (consent != null) {
                                    consentLauncher.launch(consent)
                                } else {
                                    OrbisVpnService.start(context)
                                }
                            }
                        },
                        onRefresh = viewModel::refresh,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }
}
