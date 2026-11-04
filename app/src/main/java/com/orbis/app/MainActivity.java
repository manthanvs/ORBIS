package com.orbis.app;

import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.orbis.app.data.UsageRepository;
import com.orbis.app.throttle.ThrottleEngine;
import com.orbis.app.throttle.ThrottleSettings;
import com.orbis.app.ui.Access;
import com.orbis.app.ui.AboutFragment;
import com.orbis.app.ui.ControlsFragment;
import com.orbis.app.ui.EarnFragment;
import com.orbis.app.ui.HomeFragment;
import com.orbis.app.util.Background;
import com.orbis.app.vpn.OrbisVpnService;

/**
 * The whole app's UI: four fragments behind a bottom navigation bar.
 *
 * <p>Only the selected one is attached, so the tab that is not on screen observes
 * nothing. That matters because the tunnel republishes its packet counters
 * several times a second, and the Controls screen is the only one that shows
 * them.
 */
public class MainActivity extends AppCompatActivity {

    /** Set by the focus-session notification so the app opens on that tab. */
    public static final String ACTION_EARN = "com.orbis.app.action.EARN";

    private static final String STATE_TAB = "tab";

    private ActivityResultLauncher<Intent> consentLauncher;

    /**
     * What to do once the VPN consent dialog comes back OK.
     *
     * <p>Consent is a system dialog and the only way to get it, so the intent
     * behind the request has to be carried across it.
     */
    private boolean startAfterConsent;
    private boolean enableAfterConsent;

    private int selectedTab = R.id.nav_home;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThrottleSettings.init(this);
        setContentView(R.layout.activity_main);

        consentLauncher =
                registerForActivityResult(
                        new ActivityResultContracts.StartActivityForResult(),
                        result -> {
                            if (result.getResultCode() == RESULT_OK) {
                                if (startAfterConsent) {
                                    startTunnelTest();
                                }
                                if (enableAfterConsent) {
                                    ThrottleSettings.setEnabled(true);
                                }
                            }
                            // A denial leaves auto-slowing off. Switching it on
                            // regardless is what produced "You are all set" on a
                            // phone where nothing could be slowed.
                            startAfterConsent = false;
                            enableAfterConsent = false;
                            Access.refresh(this);
                        });

        BottomNavigationView nav = findViewById(R.id.bottom_nav);
        nav.setOnItemSelectedListener(
                item -> {
                    show(item.getItemId());
                    return true;
                });

        if (savedInstanceState != null) {
            selectedTab = savedInstanceState.getInt(STATE_TAB, R.id.nav_home);
        } else if (ACTION_EARN.equals(getIntent() == null ? null : getIntent().getAction())) {
            selectedTab = R.id.nav_earn;
        }
        nav.setSelectedItemId(selectedTab);
        // Selecting an item that is already selected does not fire the listener,
        // and on a rotation the FragmentManager has restored the fragment itself.
        if (getSupportFragmentManager().findFragmentById(R.id.content) == null) {
            show(selectedTab);
        }

        // Housekeeping, once per launch rather than on every refresh.
        Background.run(
                () -> {
                    try {
                        UsageRepository.shared(getApplicationContext())
                                .pruneHistory(UsageRepository.RETENTION_DAYS);
                    } catch (RuntimeException ignored) {
                        // Pruning is not worth a crash.
                    }
                });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The special permissions are granted over in Settings or in system
        // dialogs, so returning to the foreground is the only reliable moment to
        // re-check them.
        Access.refresh(this);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_TAB, selectedTab);
    }

    private void show(int itemId) {
        Fragment fragment;
        if (itemId == R.id.nav_earn) {
            fragment = new EarnFragment();
        } else if (itemId == R.id.nav_controls) {
            fragment = new ControlsFragment();
        } else if (itemId == R.id.nav_about) {
            fragment = new AboutFragment();
        } else {
            fragment = new HomeFragment();
        }
        selectedTab = itemId;
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.content, fragment)
                .commit();
    }

    /**
     * The one way to switch auto-slowing on, from any screen.
     *
     * <p>Consent comes first and the setting only flips once Android has granted
     * it. The home screen's "Turn it on" used to set the flag directly and never
     * ask, so onboarding finished, the screen said "You are all set", and ORBIS
     * could not slow a single thing.
     */
    public void enableAutoThrottle() {
        Intent consent = VpnService.prepare(this);
        if (consent == null) {
            ThrottleSettings.setEnabled(true);
            Access.refresh(this);
            return;
        }
        startAfterConsent = false;
        enableAfterConsent = true;
        consentLauncher.launch(consent);
    }

    public void disableAutoThrottle() {
        ThrottleSettings.setEnabled(false);
        if (Boolean.TRUE.equals(OrbisVpnService.isRunning().get())) {
            OrbisVpnService.stop(this);
        }
    }

    /** The manual diagnostic on Controls, which asks for consent if it must. */
    public void toggleTunnel() {
        if (Boolean.TRUE.equals(OrbisVpnService.isRunning().get())) {
            OrbisVpnService.stop(this);
            return;
        }
        Intent consent = VpnService.prepare(this);
        if (consent == null) {
            startTunnelTest();
            return;
        }
        startAfterConsent = true;
        enableAfterConsent = false;
        consentLauncher.launch(consent);
    }

    /**
     * The manual tunnel, as a bounded diagnostic.
     *
     * <p>It routes every app ORBIS is willing to route - far wider than the
     * automatic path, which points the tunnel at the single app on screen. So it
     * is given a hard stop: raised by hand it used to stay up until the user
     * remembered to stop it, and while it is up every routed app's TCP is
     * dropped. Auto-throttle off meant nothing would ever take it down at all.
     */
    private void startTunnelTest() {
        OrbisVpnService.start(
                this,
                ThrottleEngine.TEST_FRICTION,
                OrbisVpnService.routedPackages(),
                OrbisVpnService.MANUAL_TEST_MILLIS);
    }
}
