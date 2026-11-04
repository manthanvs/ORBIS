package com.orbis.app.ui;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.orbis.app.R;
import com.orbis.app.earn.ClearTimeHolder;
import com.orbis.app.earn.EarnAction;
import com.orbis.app.earn.EarnRepository;
import com.orbis.app.earn.FocusSession;
import com.orbis.app.earn.FocusSessionWorker;
import com.orbis.app.earn.FocusState;
import com.orbis.app.usage.DurationFormatter;
import com.orbis.app.util.Background;

/**
 * The earn-back loop: how much clear time is left, and the focus session that
 * tops it up.
 *
 * <p>The session itself lives in {@link FocusSession}, not here, so that locking
 * the phone or swiping ORBIS away - the natural thing to do while focusing - does
 * not quietly cancel it. This screen only displays it.
 */
public class EarnFragment extends Fragment {

    private static final long TICK_MILLIS = 1_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView balanceValue;
    private TextView balanceBody;
    private TextView focusBody;
    private TextView focusCountdown;
    private TextView focusUses;
    private Button focusButton;
    private TextView streakTitle;
    private TextView streakBody;

    private EarnRepository earn;
    private int focusUsesLeft = 1;

    /** Ticks the countdown while a session is running, and only then. */
    private final Runnable tick =
            new Runnable() {
                @Override
                public void run() {
                    renderFocus();
                    if (FocusSession.state().get().getActive()) {
                        handler.postDelayed(this, TICK_MILLIS);
                    }
                }
            };

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_earn, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        Context app = requireContext().getApplicationContext();
        earn = EarnRepository.shared(app);
        FocusSession.init(app);

        balanceValue = view.findViewById(R.id.balance_value);
        balanceBody = view.findViewById(R.id.balance_body);
        focusBody = view.findViewById(R.id.focus_body);
        focusCountdown = view.findViewById(R.id.focus_countdown);
        focusUses = view.findViewById(R.id.focus_uses);
        focusButton = view.findViewById(R.id.focus_button);
        streakTitle = view.findViewById(R.id.streak_title);
        streakBody = view.findViewById(R.id.streak_body);

        focusButton.setOnClickListener(v -> onFocusButton());

        earn.summary()
                .asLiveData()
                .observe(
                        getViewLifecycleOwner(),
                        summary -> {
                            focusUsesLeft = summary.remainingUsesOf(EarnAction.FOCUS_SESSION);
                            focusUses.setText(
                                    getString(R.string.earn_uses_left, focusUsesLeft));
                            if (summary.getStreak() > 0) {
                                streakTitle.setText(
                                        getString(R.string.earn_streak_days, summary.getStreak()));
                                streakBody.setText("");
                            } else {
                                streakTitle.setText(R.string.earn_streak_none);
                                streakBody.setText(R.string.earn_streak_none_body);
                            }
                        });

        // The balance the gate is actually spending against, not the one the
        // ledger last wrote: credit is metered in memory between flushes, so the
        // database would be several seconds stale while a feed is open.
        ClearTimeHolder.remainingMillis()
                .asLiveData()
                .observe(
                        getViewLifecycleOwner(),
                        remaining -> {
                            balanceValue.setText(DurationFormatter.format(remaining));
                            balanceBody.setText(
                                    remaining > 0L
                                            ? R.string.earn_balance_body_credit
                                            : R.string.earn_balance_body_empty);
                        });

        FocusSession.state().asLiveData().observe(getViewLifecycleOwner(), s -> renderFocus());

        Background.run(
                () -> {
                    try {
                        earn.refresh();
                    } catch (RuntimeException ignored) {
                        // The screen simply shows the last published summary.
                    }
                });
    }

    @Override
    public void onResume() {
        super.onResume();
        renderFocus();
        handler.removeCallbacks(tick);
        if (FocusSession.state().get().getActive()) {
            handler.postDelayed(tick, TICK_MILLIS);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
    }

    // ----------------------------------------------------------- focus session

    private void onFocusButton() {
        Context app = requireContext().getApplicationContext();
        if (FocusSession.state().get().getActive()) {
            FocusSession.clear(app);
            FocusSessionWorker.cancel(app);
            return;
        }
        if (focusUsesLeft <= 0) {
            Toast.makeText(requireContext(), R.string.earn_focus_none_left, Toast.LENGTH_SHORT)
                    .show();
            return;
        }
        FocusSession.start(app, System.currentTimeMillis());
        FocusSessionWorker.schedule(app, EarnAction.FOCUS_DURATION_MILLIS);
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, TICK_MILLIS);
        renderFocus();
    }

    /**
     * Shows the running session, and claims it the moment it is done - but only
     * while the user is here to see that happen.
     *
     * <p>A session that finishes unseen is left to {@link FocusSessionWorker},
     * which posts a notification. Claiming it silently in the background, as an
     * earlier version did, paid the user without ever telling them.
     */
    private void renderFocus() {
        if (getView() == null) {
            return;
        }
        Context app = requireContext().getApplicationContext();
        FocusState session = FocusSession.state().get();

        if (session.getBroken()) {
            FocusSession.clear(app);
            FocusSessionWorker.cancel(app);
            Toast.makeText(requireContext(), R.string.earn_focus_broken, Toast.LENGTH_LONG).show();
            return;
        }

        if (!session.getActive()) {
            focusCountdown.setVisibility(View.GONE);
            focusBody.setText(R.string.earn_focus_body);
            focusButton.setText(R.string.earn_focus_start);
            focusButton.setEnabled(focusUsesLeft > 0);
            return;
        }

        long now = System.currentTimeMillis();
        if (session.completeAt(now) && isResumed()) {
            claim(now);
            return;
        }

        focusCountdown.setVisibility(View.VISIBLE);
        focusCountdown.setText(DurationFormatter.format(session.remainingMillis(now)));
        focusBody.setText(R.string.earn_focus_running_body);
        focusButton.setText(R.string.earn_focus_cancel);
        focusButton.setEnabled(true);
    }

    private void claim(long nowMillis) {
        Context app = requireContext().getApplicationContext();
        if (!FocusSession.claimIfComplete(app, nowMillis)) {
            return;
        }
        FocusSessionWorker.cancel(app);
        focusCountdown.setVisibility(View.GONE);

        // The session is already cleared, so this award must finish whatever
        // happens to this screen - it runs on the shared pool, not tied to the
        // fragment, and reports through the repository's own published summary.
        Background.run(
                () -> {
                    long awarded;
                    try {
                        awarded = earn.award(EarnAction.FOCUS_SESSION);
                    } catch (RuntimeException failure) {
                        return;
                    }
                    View root = getView();
                    if (root == null) {
                        return;
                    }
                    long minutes = awarded / 60_000L;
                    root.post(
                            () -> {
                                if (getView() == null) {
                                    return;
                                }
                                Toast.makeText(
                                                requireContext(),
                                                awarded > 0L
                                                        ? getString(
                                                                R.string.focus_done_text, minutes)
                                                        : getString(
                                                                R.string
                                                                        .focus_done_text_capped),
                                                Toast.LENGTH_LONG)
                                        .show();
                                renderFocus();
                            });
                });
    }
}
