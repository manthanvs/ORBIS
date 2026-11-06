package com.orbis.app.ui;

import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.orbis.app.MainActivity;
import com.orbis.app.R;
import com.orbis.app.dashboard.DayTotal;
import com.orbis.app.dashboard.ReclaimedSummary;
import com.orbis.app.dashboard.ReclaimedTime;
import com.orbis.app.data.UsageRepository;
import com.orbis.app.earn.EarnRepository;
import com.orbis.app.surface.AccessibilityAccess;
import com.orbis.app.surface.DetectedSurface;
import com.orbis.app.surface.SurfaceMonitor;
import com.orbis.app.throttle.ThrottleEngine;
import com.orbis.app.throttle.ThrottleLevel;
import com.orbis.app.usage.DurationFormatter;
import com.orbis.app.usage.TargetApp;
import com.orbis.app.usage.UsageAccess;
import com.orbis.app.usage.UsageProfile;
import com.orbis.app.usage.UsageProfileHolder;
import com.orbis.app.util.Background;
import com.orbis.app.vpn.OrbisVpnService;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.Map;

/**
 * Home: whether ORBIS is protecting anything, and what it has bought back.
 *
 * <p>The onboarding steps and the live status share one card, because they are
 * the same question - "is this working?" - answered at different stages.
 */
public class HomeFragment extends Fragment {

    private static final int BAR_MIN_DP = 3;
    private static final int BAR_LABEL_DP = 18;
    private static final int BAR_MAX_WIDTH_DP = 36;
    private static final int BAR_MIN_WIDTH_DP = 6;

    private AppUsageAdapter adapter;

    private LinearLayout statusCard;
    private TextView statusStep;
    private TextView statusTitle;
    private TextView statusBody;
    private android.widget.Button statusButton;
    private TextView reclaimedTitle;
    private TextView reclaimedValue;
    private TextView reclaimedBody;
    private TextView streakTeaser;
    private LinearLayout weekCard;
    private LinearLayout weekChart;
    private TextView weekCaption;
    private TextView levelTitle;
    private TextView levelBody;
    private TextView nothingTracked;

    /** The slow-moving half of the screen, loaded off the main thread. */
    private UsageProfile profile = UsageProfile.EMPTY;
    private ReclaimedSummary summary;
    private ThrottleLevel level = ThrottleLevel.NUDGE;

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        statusCard = view.findViewById(R.id.status_card);
        statusStep = view.findViewById(R.id.status_step);
        statusTitle = view.findViewById(R.id.status_title);
        statusBody = view.findViewById(R.id.status_body);
        statusButton = view.findViewById(R.id.status_button);
        reclaimedTitle = view.findViewById(R.id.reclaimed_title);
        reclaimedValue = view.findViewById(R.id.reclaimed_value);
        reclaimedBody = view.findViewById(R.id.reclaimed_body);
        streakTeaser = view.findViewById(R.id.streak_teaser);
        weekCard = view.findViewById(R.id.week_card);
        weekChart = view.findViewById(R.id.week_chart);
        weekCaption = view.findViewById(R.id.week_caption);
        levelTitle = view.findViewById(R.id.level_title);
        levelBody = view.findViewById(R.id.level_body);

        androidx.recyclerview.widget.RecyclerView list = view.findViewById(R.id.usage_list);
        adapter = new AppUsageAdapter();
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        list.setAdapter(adapter);
        list.setNestedScrollingEnabled(false);

        // "Nothing tracked yet" shares the usage card rather than getting one of
        // its own, so the card never collapses to a bare heading.
        nothingTracked = new TextView(requireContext());
        nothingTracked.setText(R.string.home_nothing_tracked);
        nothingTracked.setTextColor(color(R.color.orbis_text_muted));
        nothingTracked.setTextSize(15f);
        ((ViewGroup) list.getParent()).addView(nothingTracked);

        Access.state().asLiveData().observe(getViewLifecycleOwner(), access -> renderStatus());
        SurfaceMonitor.state().asLiveData().observe(getViewLifecycleOwner(), s -> renderStatus());
        OrbisVpnService.isRunning()
                .asLiveData()
                .observe(getViewLifecycleOwner(), running -> renderStatus());
        com.orbis.app.throttle.ThrottleSettings.enabled()
                .asLiveData()
                .observe(getViewLifecycleOwner(), on -> renderStatus());

        EarnRepository.shared(requireContext().getApplicationContext())
                .summary()
                .asLiveData()
                .observe(
                        getViewLifecycleOwner(),
                        earn -> {
                            if (earn.getStreak() > 1) {
                                streakTeaser.setVisibility(View.VISIBLE);
                                streakTeaser.setText(
                                        getString(R.string.home_streak_teaser, earn.getStreak()));
                            } else {
                                streakTeaser.setVisibility(View.GONE);
                            }
                        });

        renderStatus();
        renderNumbers();
    }

    @Override
    public void onResume() {
        super.onResume();
        load();
    }

    // ------------------------------------------------------------------ loading

    /**
     * Today's numbers, read off the main thread.
     *
     * <p>Today first, so the screen reflects the current session rather than
     * whatever was last written, and the baseline second.
     */
    private void load() {
        Context app = requireContext().getApplicationContext();
        if (!UsageAccess.isGranted(app)) {
            return;
        }
        Background.run(
                () -> {
                    UsageProfile loaded;
                    ReclaimedSummary loadedSummary;
                    ThrottleLevel loadedLevel;
                    try {
                        UsageRepository usage = UsageRepository.shared(app);
                        loaded = usage.refreshToday();
                        Map<LocalDate, Long> history =
                                usage.dailyHistory(ReclaimedTime.BASELINE_WINDOW_DAYS + 1);
                        loadedSummary = ReclaimedTime.summarize(history, LocalDate.now());
                        loadedLevel =
                                ThrottleEngine.levelFor(
                                        loaded, UsageProfileHolder.baselineMillis().get());
                        EarnRepository.shared(app).refresh();
                    } catch (RuntimeException failure) {
                        return;
                    }

                    View root = getView();
                    if (root == null) {
                        return;
                    }
                    root.post(
                            () -> {
                                if (getView() == null) {
                                    return;
                                }
                                profile = loaded;
                                summary = loadedSummary;
                                level = loadedLevel;
                                renderNumbers();
                            });
                });
    }

    // ---------------------------------------------------------------- rendering

    /** The one card that answers "is this working?". */
    private void renderStatus() {
        if (getView() == null) {
            return;
        }
        Access access = Access.state().get();
        boolean autoThrottle =
                Boolean.TRUE.equals(com.orbis.app.throttle.ThrottleSettings.enabled().get());
        boolean tunnelRunning = Boolean.TRUE.equals(OrbisVpnService.isRunning().get());

        statusStep.setVisibility(View.GONE);
        statusButton.setVisibility(View.GONE);

        if (!access.hasUsageAccess) {
            step(1, R.string.home_setup_title, R.string.home_setup_body, R.string.home_setup_button,
                    () -> startActivity(UsageAccess.settingsIntent()));
            return;
        }
        if (!access.hasDetection) {
            step(2, R.string.home_detection_title, R.string.home_detection_body,
                    R.string.home_detection_button,
                    () -> startActivity(AccessibilityAccess.settingsIntent()));
            return;
        }
        // A running tunnel is proof of consent, whatever the last check said.
        if (!autoThrottle || !(access.hasVpnConsent || tunnelRunning)) {
            step(3, R.string.home_enable_title, R.string.home_enable_body,
                    R.string.home_enable_button,
                    () -> {
                        if (getActivity() instanceof MainActivity) {
                            ((MainActivity) getActivity()).enableAutoThrottle();
                        }
                    });
            return;
        }

        DetectedSurface detected = SurfaceMonitor.state().get();
        if (tunnelRunning && detected.getSurface().getThrottled()) {
            statusTitle.setText(R.string.home_slowing_title);
            statusBody.setText(
                    getString(R.string.home_slowing_body, appLabel(detected.getPackageName())));
        } else {
            statusTitle.setText(R.string.home_ready_title);
            statusBody.setText(R.string.home_ready_body);
        }
    }

    private void step(int number, int title, int body, int button, Runnable action) {
        statusStep.setVisibility(View.VISIBLE);
        statusStep.setText(getString(R.string.home_step, number));
        statusTitle.setText(title);
        statusBody.setText(body);
        statusButton.setVisibility(View.VISIBLE);
        statusButton.setText(button);
        statusButton.setOnClickListener(v -> action.run());
    }

    /** Reclaimed time, the week, the level and today's split. */
    private void renderNumbers() {
        if (getView() == null) {
            return;
        }
        adapter.submit(profile.getEntries(), profile.getTotalMillis());
        nothingTracked.setVisibility(profile.getEntries().isEmpty() ? View.VISIBLE : View.GONE);

        levelTitle.setText(
                getString(R.string.home_level_title, level.getNumber(), level.getLabel()));
        levelBody.setText(
                getString(
                        R.string.home_level_body,
                        DurationFormatter.format(shortFormMillis()),
                        seconds(level.getFriction().getSqueezeMillis()),
                        seconds(level.getFriction().getPeriodMillis())));

        // The chart and the reclaimed figure answer different questions, so they
        // appear independently: a couple of recorded days are worth drawing long
        // before there is enough history to claim a baseline.
        // Two recorded days minimum: a single bar is a data point, not a trend,
        // and it rendered as one full-width slab across the whole card.
        if (summary == null || summary.getWeek().size() < 2) {
            weekCard.setVisibility(View.GONE);
        } else {
            weekCard.setVisibility(View.VISIBLE);
            // The bar width is derived from the chart's own width, which is 0
            // until the first layout pass; on that pass, draw after it.
            final ReclaimedSummary drawn = summary;
            if (weekChart.getWidth() == 0) {
                weekChart.post(
                        () -> {
                            if (getView() != null) {
                                drawWeek(drawn);
                            }
                        });
            } else {
                drawWeek(drawn);
            }
        }

        if (summary == null || summary.getBaselineMillis() == null) {
            // Nothing to compare against yet. Saying so beats inventing a number.
            reclaimedTitle.setText(R.string.home_learning_title);
            reclaimedValue.setVisibility(View.GONE);
            reclaimedBody.setText(R.string.home_learning_body);
            return;
        }

        long baseline = summary.getBaselineMillis();
        long reclaimed = summary.getReclaimedTodayMillis();
        reclaimedValue.setVisibility(View.VISIBLE);
        reclaimedTitle.setText(R.string.home_time_back_title);
        reclaimedValue.setText(DurationFormatter.format(Math.abs(reclaimed)));
        reclaimedBody.setText(
                getString(
                        reclaimed >= 0L ? R.string.home_reclaimed_body : R.string.home_over_body,
                        DurationFormatter.format(baseline)));
    }

    /** Today's short-form minutes across every tracked app, as the level sees them. */
    private long shortFormMillis() {
        long total = 0L;
        for (TargetApp app : TargetApp.getThrottleable()) {
            total += profile.durationOf(app);
        }
        return total;
    }

    private static String seconds(long millis) {
        long tenths = Math.round(millis / 100.0);
        return tenths % 10 == 0
                ? String.valueOf(tenths / 10)
                : (tenths / 10) + "." + (tenths % 10);
    }

    /**
     * Seven bars, one per day, today on the right.
     *
     * <p>Drawn rather than shipped as a chart library: seven rectangles do not
     * justify a dependency, and these inherit the theme's colours for free.
     */
    private void drawWeek(ReclaimedSummary summary) {
        weekChart.removeAllViews();
        long max = 0L;
        for (DayTotal day : summary.getWeek()) {
            max = Math.max(max, day.getMillis());
        }

        float density = getResources().getDisplayMetrics().density;
        int barArea = weekChart.getLayoutParams().height - (int) (BAR_LABEL_DP * density);
        int minHeight = (int) (BAR_MIN_DP * density);
        LocalDate today = LocalDate.now();
        long week = 0L;

        for (DayTotal day : summary.getWeek()) {
            week += day.getMillis();
            int height =
                    max <= 0L
                            ? minHeight
                            : Math.max(minHeight, (int) (barArea * day.getMillis() / max));

            LinearLayout column = new LinearLayout(requireContext());
            column.setOrientation(LinearLayout.VERTICAL);
            column.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            column.setLayoutParams(
                    new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

            View bar = new View(requireContext());
            bar.setBackgroundColor(
                    color(
                            day.getDate().equals(today)
                                    ? R.color.orbis_accent
                                    : R.color.orbis_outline));
            // Capped rather than MATCH_PARENT: with only two or three recorded
            // days a column is a third of the card wide, and a bar that fills it
            // reads as a block of colour instead of a bar.
            int gap = (int) (3 * density);
            int columnWidth = weekChart.getWidth() / summary.getWeek().size();
            int barWidth = (int) (BAR_MAX_WIDTH_DP * density);
            if (columnWidth > 0) {
                barWidth = Math.min(barWidth, columnWidth - 2 * gap);
            }
            barWidth = Math.max(barWidth, (int) (BAR_MIN_WIDTH_DP * density));
            LinearLayout.LayoutParams barParams =
                    new LinearLayout.LayoutParams(barWidth, height);
            barParams.setMargins(gap, 0, gap, (int) (4 * density));
            bar.setLayoutParams(barParams);

            TextView label = new TextView(requireContext());
            label.setText(
                    day.getDate()
                            .getDayOfWeek()
                            .getDisplayName(TextStyle.NARROW, Locale.getDefault()));
            label.setTextSize(11f);
            label.setTextColor(color(R.color.orbis_text_faint));
            label.setGravity(Gravity.CENTER);

            column.addView(bar);
            column.addView(label);
            weekChart.addView(column);
        }

        weekCaption.setText(
                getString(R.string.home_week_caption, DurationFormatter.format(week)));
    }

    private int color(int res) {
        return ContextCompat.getColor(requireContext(), res);
    }

    /** The app's own name where ORBIS knows it, the package where it does not. */
    private String appLabel(String packageName) {
        TargetApp app = TargetApp.fromPackage(packageName);
        return app != null ? app.getDisplayName() : packageName;
    }
}
