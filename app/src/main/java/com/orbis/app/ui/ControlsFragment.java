package com.orbis.app.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.orbis.app.MainActivity;
import com.orbis.app.R;
import com.orbis.app.surface.AccessibilityAccess;
import com.orbis.app.surface.DetectedSurface;
import com.orbis.app.surface.SurfaceMonitor;
import com.orbis.app.throttle.ThrottleSettings;
import com.orbis.app.usage.TargetApp;
import com.orbis.app.vpn.OrbisVpnService;
import com.orbis.app.vpn.TunnelStats;

import java.util.Locale;

/**
 * Controls: what detection can see, the auto-throttle switch, the bounded tunnel
 * test, and the packet counters.
 *
 * <p>The packet counters are observed here and nowhere else. They change several
 * times a second while the tunnel is up, and the old tab layout collected them at
 * the top of the UI, which woke every screen once a second whether or not
 * anything on it showed a packet count.
 */
public class ControlsFragment extends Fragment {

    private TextView seeingValue;
    private TextView slowingValue;
    private Button detectionButton;
    private Switch autoSwitch;
    private Button testButton;
    private TextView detailsBody;

    /** Set while the switch is being written to, so the listener does not echo. */
    private boolean binding;

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_controls, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        seeingValue = view.findViewById(R.id.seeing_value);
        slowingValue = view.findViewById(R.id.slowing_value);
        detectionButton = view.findViewById(R.id.detection_button);
        autoSwitch = view.findViewById(R.id.auto_switch);
        testButton = view.findViewById(R.id.test_button);
        detailsBody = view.findViewById(R.id.details_body);

        detectionButton.setOnClickListener(
                v -> startActivity(AccessibilityAccess.settingsIntent()));

        // A bare heading gives no hint that it opens, so the caret is the
        // affordance: it points down when there is something to reveal.
        TextView detailsHeader = view.findViewById(R.id.details_header);
        showDetails(detailsHeader, false);
        detailsHeader.setOnClickListener(
                v -> showDetails(detailsHeader, detailsBody.getVisibility() != View.VISIBLE));

        testButton.setOnClickListener(
                v -> {
                    if (getActivity() instanceof MainActivity) {
                        ((MainActivity) getActivity()).toggleTunnel();
                    }
                });

        autoSwitch.setOnCheckedChangeListener(
                (button, checked) -> {
                    if (binding || !(getActivity() instanceof MainActivity)) {
                        return;
                    }
                    MainActivity activity = (MainActivity) getActivity();
                    // Never starts the tunnel here: the accessibility gate raises
                    // it when a short-form feed actually appears.
                    if (checked) {
                        activity.enableAutoThrottle();
                    } else {
                        activity.disableAutoThrottle();
                    }
                });

        ThrottleSettings.enabled()
                .asLiveData()
                .observe(
                        getViewLifecycleOwner(),
                        on -> {
                            binding = true;
                            autoSwitch.setChecked(Boolean.TRUE.equals(on));
                            binding = false;
                        });

        Access.state().asLiveData().observe(getViewLifecycleOwner(), access -> renderSeeing());
        SurfaceMonitor.state().asLiveData().observe(getViewLifecycleOwner(), s -> renderSeeing());
        OrbisVpnService.tunnelStats()
                .asLiveData()
                .observe(getViewLifecycleOwner(), this::renderTunnel);

        renderSeeing();
        renderTunnel(OrbisVpnService.tunnelStats().get());
    }

    private void renderSeeing() {
        if (getView() == null) {
            return;
        }
        boolean hasDetection = Access.state().get().hasDetection;
        detectionButton.setVisibility(hasDetection ? View.GONE : View.VISIBLE);

        DetectedSurface detected = SurfaceMonitor.state().get();
        if (!hasDetection) {
            seeingValue.setText(R.string.home_detection_body);
            return;
        }
        if (detected.getPackageName() == null || detected.getPackageName().isEmpty()) {
            seeingValue.setText(R.string.controls_waiting);
            return;
        }
        seeingValue.setText(
                getString(
                        R.string.controls_seeing_value,
                        appLabel(detected.getPackageName()),
                        detected.getSurface().name().toLowerCase(Locale.US)));
    }

    private void renderTunnel(TunnelStats stats) {
        if (getView() == null) {
            return;
        }
        if (stats.isRunning()) {
            slowingValue.setText(
                    getString(
                            R.string.controls_slowing_value,
                            stats.getRouted().isEmpty()
                                    ? getString(R.string.controls_routed_none)
                                    : appLabel(stats.getRouted().get(0))));
            testButton.setText(R.string.controls_stop);
        } else {
            slowingValue.setText(R.string.controls_slowing_idle);
            testButton.setText(R.string.controls_test);
        }

        // The raw counters, collapsed by default. This is the view that proved
        // the TCP proxy was carrying what the UDP relay used to drop.
        detailsBody.setText(
                String.format(
                        Locale.US,
                        "status      %s%n"
                                + "routed      %s%n"
                                + "squeezing   %s (%d of %d ms)%n"
                                + "delay       %d ms%n"
                                + "read        %d%n"
                                + "udp fwd     %d%n"
                                + "policed     %d%n"
                                + "dropped     %d%n"
                                + "tcp dropped %d%n"
                                + "tcp carried %d bytes%n"
                                + "bytes in    %d%n"
                                + "open flows  %d",
                        stats.getStatus(),
                        stats.getRouted().isEmpty() ? "-" : String.join(", ", stats.getRouted()),
                        stats.isSqueezing() ? "yes" : "no",
                        stats.getSqueezeMillis(),
                        stats.getPulsePeriodMillis(),
                        stats.getDelayMillis(),
                        stats.getPacketsRead(),
                        stats.getUdpForwarded(),
                        stats.getPacketsPoliced(),
                        stats.getPacketsDropped(),
                        stats.getTcpDropped(),
                        stats.getTcpCarried(),
                        stats.getBytesIn(),
                        stats.getActiveFlows()));
    }

    private void showDetails(TextView header, boolean open) {
        detailsBody.setVisibility(open ? View.VISIBLE : View.GONE);
        header.setText(
                getString(open ? R.string.controls_details_open : R.string.controls_details_shut));
    }

    private String appLabel(String packageName) {
        TargetApp app = TargetApp.fromPackage(packageName);
        return app != null ? app.getDisplayName() : packageName;
    }
}
