package com.orbis.app

import android.app.Activity
import android.content.Context
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.orbis.app.settings.UiSettings
import com.orbis.app.surface.AccessibilityAccess
import com.orbis.app.throttle.ThrottleEngine
import com.orbis.app.throttle.ThrottleSettings
import com.orbis.app.ui.AboutScreen
import com.orbis.app.ui.ControlsScreen
import com.orbis.app.ui.EarnScreen
import com.orbis.app.ui.EarnViewModel
import com.orbis.app.ui.HomeScreen
import com.orbis.app.ui.HomeViewModel
import com.orbis.app.ui.ProtectionUiState
import com.orbis.app.ui.SimpleHomeScreen
import com.orbis.app.ui.theme.OrbisTheme
import com.orbis.app.usage.UsageAccess
import com.orbis.app.vpn.OrbisVpnService
import java.time.LocalDate

class MainActivity : ComponentActivity() {

    companion object {
        /** Set by the focus-session notification so the app opens on that tab. */
        const val ACTION_EARN = "com.orbis.app.action.EARN"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThrottleSettings.init(this)
        UiSettings.init(this)
        enableEdgeToEdge()

        val openOnEarn = intent?.action == ACTION_EARN

        setContent {
            OrbisTheme {
                OrbisApp(openOnEarn = openOnEarn)
            }
        }
    }
}

private enum class Destination(
    val label: String,
    @param:DrawableRes val icon: Int,
) {
    HOME("Home", R.drawable.ic_nav_home),

    // Named for what the user comes here to do: buy their feed back to full
    // speed, by finishing a focus session.
    EARN("Earn", R.drawable.ic_nav_deeds),
    CONTROLS("Controls", R.drawable.ic_nav_controls),

    // A tab rather than a menu item behind the header: the explainer is only
    // useful to someone who has not worked the app out yet, and that is exactly
    // the person who will not go looking in an overflow menu for it.
    ABOUT("About", R.drawable.ic_nav_about),
}

@Composable
private fun OrbisApp(openOnEarn: Boolean) {
    val context = LocalContext.current

    val homeViewModel: HomeViewModel = viewModel(factory = HomeViewModel.factory(context))
    val protection by homeViewModel.protection.collectAsStateWithLifecycle()

    // Which home screen to show. Cheap to collect at this level - it changes only
    // when the user flips the switch, unlike the packet counters.
    val simpleMode by UiSettings.simpleMode.collectAsStateWithLifecycle()

    var destination by rememberSaveable {
        mutableStateOf(if (openOnEarn) Destination.EARN else Destination.HOME)
    }

    // VpnService.prepare() returns an Intent the first time; consent is a system
    // dialog and cannot be granted any other way. What to do once it is granted
    // is carried across the dialog in these two flags.
    var startAfterConsent by remember { mutableStateOf(false) }
    var enableAfterConsent by remember { mutableStateOf(false) }
    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            if (startAfterConsent) startTunnelTest(context)
            if (enableAfterConsent) ThrottleSettings.setEnabled(true)
        }
        // A denial leaves auto-slowing off. Switching it on regardless is what
        // produced "You are all set" on a phone where nothing could be slowed.
        startAfterConsent = false
        enableAfterConsent = false
    }

    /**
     * The one way to switch auto-slowing on, from any screen.
     *
     * The home screen's "Turn it on" used to set the flag directly and never ask
     * for VPN consent - so onboarding finished, the screen said "You are all set",
     * and ORBIS could not slow a single thing. Now consent comes first, and the
     * setting only flips once Android has actually granted it.
     */
    val turnOnAutoThrottle: () -> Unit = {
        val consent = VpnService.prepare(context)
        if (consent == null) {
            ThrottleSettings.setEnabled(true)
        } else {
            startAfterConsent = false
            enableAfterConsent = true
            consentLauncher.launch(consent)
        }
    }

    // The special permissions are granted over in Settings or in system dialogs,
    // so returning to the foreground is the only reliable moment to re-check them.
    LifecycleResumeEffect(Unit) {
        homeViewModel.onPermissionsChanged(
            hasUsageAccess = UsageAccess.isGranted(context),
            hasDetection = AccessibilityAccess.isEnabled(context),
            // Re-read every time, never cached: another VPN app taking over
            // revokes it without ORBIS being told.
            hasVpnConsent = VpnService.prepare(context) == null,
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
        // Capped and centred rather than edge-to-edge: on a tablet, an unfolded
        // phone or landscape, a full-width line of body text is unreadable. The
        // cap is on the content, so every screen inherits it from here.
        val contentModifier = Modifier
            .padding(innerPadding)
            .widthIn(max = 640.dp)

        // A `when` rather than three always-composed screens: only the selected
        // branch is composed, so the tab that is not on screen collects nothing.
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            when (destination) {
                Destination.HOME -> HomeRoute(
                    viewModel = homeViewModel,
                    protection = protection,
                    simpleMode = simpleMode,
                    onEnableThrottle = turnOnAutoThrottle,
                    onOpenEarn = { destination = Destination.EARN },
                    onOpenAbout = { destination = Destination.ABOUT },
                    onSimpleModeChange = UiSettings::setSimpleMode,
                    modifier = contentModifier,
                )

                Destination.EARN -> EarnRoute(modifier = contentModifier)

                Destination.CONTROLS -> ControlsRoute(
                    protection = protection,
                    onToggleTunnel = {
                        if (protection.tunnelRunning) {
                            OrbisVpnService.stop(context)
                        } else {
                            val consent = VpnService.prepare(context)
                            if (consent == null) {
                                startTunnelTest(context)
                            } else {
                                // Starting it was the point, so carry that intent
                                // across the dialog.
                                startAfterConsent = true
                                consentLauncher.launch(consent)
                            }
                        }
                    },
                    onAutoThrottleChange = { wanted ->
                        // Never starts the tunnel here: the accessibility gate raises it
                        // when a short-form feed actually appears.
                        if (wanted) {
                            turnOnAutoThrottle()
                        } else {
                            ThrottleSettings.setEnabled(false)
                            if (protection.tunnelRunning) OrbisVpnService.stop(context)
                        }
                    },
                    modifier = contentModifier,
                )

                Destination.ABOUT -> AboutRoute(
                    protection = protection,
                    modifier = contentModifier,
                )
            }
        }
    }
}

/**
 * The manual tunnel on the Controls screen, as a bounded diagnostic.
 *
 * It routes every app ORBIS is willing to route, which is far wider than the
 * automatic path - that one points the tunnel at the single app on screen. So it
 * is deliberately given a hard stop: raised by hand it used to stay up until the
 * user remembered to stop it, and while it is up every routed app's TCP is
 * dropped. Auto-throttle off meant nothing would ever take it down at all.
 */
private fun startTunnelTest(context: Context) {
    OrbisVpnService.start(
        context,
        friction = ThrottleEngine.TEST_FRICTION,
        routePackages = OrbisVpnService.routedPackages(),
        autoStopMillis = OrbisVpnService.MANUAL_TEST_MILLIS,
    )
}

/**
 * Home, in one of its two forms.
 *
 * Both are fed from the same [HomeViewModel] and the same state - the difference
 * is entirely presentational, so switching modes never re-queries anything or
 * shows the user two disagreeing versions of today.
 */
@Composable
private fun HomeRoute(
    viewModel: HomeViewModel,
    protection: ProtectionUiState,
    simpleMode: Boolean,
    onEnableThrottle: () -> Unit,
    onOpenEarn: () -> Unit,
    onOpenAbout: () -> Unit,
    onSimpleModeChange: (Boolean) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    val grantUsageAccess = { context.startActivity(UsageAccess.settingsIntent()) }
    val enableDetection = { context.startActivity(AccessibilityAccess.settingsIntent()) }
    val enableThrottle = onEnableThrottle

    if (simpleMode) {
        SimpleHomeScreen(
            state = state,
            protection = protection,
            onGrantUsageAccess = grantUsageAccess,
            onEnableDetection = enableDetection,
            onEnableThrottle = enableThrottle,
            onOpenEarn = onOpenEarn,
            onOpenAbout = onOpenAbout,
            onShowDetails = { onSimpleModeChange(false) },
            modifier = modifier,
        )
    } else {
        HomeScreen(
            state = state,
            protection = protection,
            today = LocalDate.now(),
            onGrantUsageAccess = grantUsageAccess,
            onEnableDetection = enableDetection,
            onEnableThrottle = enableThrottle,
            onOpenEarn = onOpenEarn,
            onShowSimple = { onSimpleModeChange(true) },
            modifier = modifier,
        )
    }
}

/**
 * The explainer.
 *
 * Takes the permission flags so the page can say which ones are already on -
 * this is the screen someone reads while deciding whether to grant them.
 */
@Composable
private fun AboutRoute(protection: ProtectionUiState, modifier: Modifier) {
    val context = LocalContext.current
    val versionName = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }

    AboutScreen(
        versionName = versionName,
        hasUsageAccess = protection.hasUsageAccess,
        hasDetection = protection.hasDetection,
        autoThrottle = protection.autoThrottle && protection.canSlow,
        onGrantUsageAccess = { context.startActivity(UsageAccess.settingsIntent()) },
        onEnableDetection = { context.startActivity(AccessibilityAccess.settingsIntent()) },
        modifier = modifier,
    )
}

@Composable
private fun EarnRoute(modifier: Modifier) {
    val context = LocalContext.current
    val viewModel: EarnViewModel = viewModel(factory = EarnViewModel.factory(context))
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Tells the ViewModel when the user can actually see this screen - see
    // EarnViewModel.visible for why a finished session must not be claimed unseen.
    LifecycleResumeEffect(viewModel) {
        viewModel.onVisibilityChanged(true)
        onPauseOrDispose { viewModel.onVisibilityChanged(false) }
    }

    EarnScreen(
        state = state,
        onStartFocus = viewModel::startFocus,
        onCancelFocus = viewModel::cancelFocus,
        onDismissMessage = viewModel::dismissMessage,
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
