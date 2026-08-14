package com.orbis.app

import android.app.Activity
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.orbis.app.deed.GoodDeedScheduler
import com.orbis.app.surface.AccessibilityAccess
import com.orbis.app.throttle.ThrottleSettings
import com.orbis.app.ui.ControlsScreen
import com.orbis.app.ui.GoodDeedScreen
import com.orbis.app.ui.GoodDeedViewModel
import com.orbis.app.ui.HomeScreen
import com.orbis.app.ui.HomeViewModel
import com.orbis.app.ui.ProtectionUiState
import com.orbis.app.ui.theme.OrbisTheme
import com.orbis.app.usage.UsageAccess
import com.orbis.app.vpn.OrbisVpnService
import java.time.LocalDate

class MainActivity : ComponentActivity() {

    companion object {
        /** Set by the good-deed notification so the app opens on that tab. */
        const val ACTION_GOOD_DEED = "com.orbis.app.action.GOOD_DEED"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThrottleSettings.init(this)
        GoodDeedScheduler.schedule(this)
        enableEdgeToEdge()

        val openOnDeeds = intent?.action == ACTION_GOOD_DEED

        setContent {
            OrbisTheme {
                OrbisApp(openOnDeeds = openOnDeeds)
            }
        }
    }
}

private enum class Destination(
    val label: String,
    @param:DrawableRes val icon: Int,
) {
    HOME("Home", R.drawable.ic_nav_home),
    DEEDS("Deeds", R.drawable.ic_nav_deeds),
    CONTROLS("Controls", R.drawable.ic_nav_controls),
}

@Composable
private fun OrbisApp(openOnDeeds: Boolean) {
    val context = LocalContext.current

    val homeViewModel: HomeViewModel = viewModel(factory = HomeViewModel.factory(context))
    val protection by homeViewModel.protection.collectAsStateWithLifecycle()

    var destination by rememberSaveable {
        mutableStateOf(if (openOnDeeds) Destination.DEEDS else Destination.HOME)
    }

    // VpnService.prepare() returns an Intent the first time; consent is a system
    // dialog and cannot be granted any other way.
    var startAfterConsent by remember { mutableStateOf(false) }
    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && startAfterConsent) {
            OrbisVpnService.start(context)
        }
        startAfterConsent = false
    }

    // Both special permissions are granted over in Settings, so returning to the
    // foreground is the only reliable moment to re-check them.
    LifecycleResumeEffect(Unit) {
        homeViewModel.onPermissionsChanged(
            hasUsageAccess = UsageAccess.isGranted(context),
            hasDetection = AccessibilityAccess.isEnabled(context),
        )
        onPauseOrDispose { }
    }

    LaunchedEffect(Unit) { homeViewModel.prune() }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = destination == entry,
                        onClick = { destination = entry },
                        icon = {
                            Icon(
                                painter = painterResource(entry.icon),
                                contentDescription = null,
                            )
                        },
                        label = { Text(entry.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        val contentModifier = Modifier.padding(innerPadding)

        // A `when` rather than three always-composed screens: only the selected
        // branch is composed, so the tab that is not on screen collects nothing.
        when (destination) {
            Destination.HOME -> HomeRoute(
                viewModel = homeViewModel,
                protection = protection,
                onOpenDeeds = { destination = Destination.DEEDS },
                modifier = contentModifier,
            )

            Destination.DEEDS -> DeedsRoute(modifier = contentModifier)

            Destination.CONTROLS -> ControlsRoute(
                protection = protection,
                onToggleTunnel = {
                    if (protection.tunnelRunning) {
                        OrbisVpnService.stop(context)
                    } else {
                        val consent = VpnService.prepare(context)
                        if (consent == null) {
                            OrbisVpnService.start(context)
                        } else {
                            // Starting it was the point, so carry that intent
                            // across the dialog.
                            startAfterConsent = true
                            consentLauncher.launch(consent)
                        }
                    }
                },
                onAutoThrottleChange = { wanted ->
                    // Consent must exist before the service can gate itself on, so
                    // ask at the moment the user opts in - but do not start the
                    // tunnel here. The accessibility gate raises it when a
                    // short-form feed actually appears.
                    if (wanted) {
                        VpnService.prepare(context)?.let { consent ->
                            startAfterConsent = false
                            consentLauncher.launch(consent)
                        }
                    }
                    ThrottleSettings.setEnabled(wanted)
                    if (!wanted && protection.tunnelRunning) OrbisVpnService.stop(context)
                },
                modifier = contentModifier,
            )
        }
    }
}

@Composable
private fun HomeRoute(
    viewModel: HomeViewModel,
    protection: ProtectionUiState,
    onOpenDeeds: () -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    HomeScreen(
        state = state,
        protection = protection,
        today = LocalDate.now(),
        onGrantUsageAccess = { context.startActivity(UsageAccess.settingsIntent()) },
        onEnableDetection = { context.startActivity(AccessibilityAccess.settingsIntent()) },
        onEnableThrottle = { ThrottleSettings.setEnabled(true) },
        onOpenDeeds = onOpenDeeds,
        modifier = modifier,
    )
}

@Composable
private fun DeedsRoute(modifier: Modifier) {
    val context = LocalContext.current
    val viewModel: GoodDeedViewModel = viewModel(factory = GoodDeedViewModel.factory(context))
    val state by viewModel.state.collectAsStateWithLifecycle()

    GoodDeedScreen(
        state = state,
        onStartCapture = viewModel::startCapture,
        onCancelCapture = viewModel::cancelCapture,
        onSave = viewModel::save,
        onSendTestPrompt = { GoodDeedScheduler.promptNow(context) },
        modifier = modifier,
    )
}

/**
 * The packet counters are collected here and nowhere else.
 *
 * They change several times a second while the tunnel is up. Collecting them at
 * the top of the composition, as the old tab layout did, woke every screen once
 * a second whether or not anything on it showed a packet count.
 */
@Composable
private fun ControlsRoute(
    protection: ProtectionUiState,
    onToggleTunnel: () -> Unit,
    onAutoThrottleChange: (Boolean) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val tunnel by OrbisVpnService.tunnelStats.collectAsStateWithLifecycle()

    ControlsScreen(
        protection = protection,
        tunnel = tunnel,
        onEnableDetection = { context.startActivity(AccessibilityAccess.settingsIntent()) },
        onToggleTunnel = onToggleTunnel,
        onAutoThrottleChange = onAutoThrottleChange,
        modifier = modifier,
    )
}
