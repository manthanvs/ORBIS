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
import com.orbis.app.throttle.ThrottleSettings
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
        ThrottleSettings.init(this)
        enableEdgeToEdge()
        setContent {
            OrbisTheme {
                val context = LocalContext.current
                val viewModel: UsageViewModel =
                    viewModel(factory = UsageViewModel.factory(context))
                val state by viewModel.state.collectAsStateWithLifecycle()
                val detected by SurfaceMonitor.state.collectAsStateWithLifecycle()
                val tunnelRunning by OrbisVpnService.isRunning.collectAsStateWithLifecycle()
                val autoThrottle by ThrottleSettings.enabled.collectAsStateWithLifecycle()
                var hasDetection by remember { mutableStateOf(false) }
                var tunnelStats by remember { mutableStateOf(TunnelUiState()) }

                // The service updates these from its own threads, so poll them
                // rather than pretending they are reactive state.
                LaunchedEffect(tunnelRunning) {
                    while (true) {
                        tunnelStats = TunnelUiState(
                            running = tunnelRunning,
                            udpForwarded = OrbisVpnService.udpPacketsForwarded,
                            tcpDropped = OrbisVpnService.tcpPacketsDropped,
                            packetsRead = OrbisVpnService.packetsRead,
                            status = OrbisVpnService.status,
                        )
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
                        tunnel = tunnelStats.copy(
                            running = tunnelRunning,
                            autoThrottle = autoThrottle,
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
                        onAutoThrottleChange = { wanted ->
                            // Consent must exist before the service can gate itself
                            // on, so ask for it at the moment the user opts in.
                            val consent = if (wanted) VpnService.prepare(context) else null
                            if (consent != null) {
                                consentLauncher.launch(consent)
                            }
                            ThrottleSettings.setEnabled(wanted)
                            if (!wanted && tunnelRunning) OrbisVpnService.stop(context)
                        },
                        onRefresh = viewModel::refresh,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }
}
