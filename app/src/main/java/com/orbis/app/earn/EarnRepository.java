package com.orbis.app.earn;

import android.content.Context;

import com.orbis.app.data.ClearTimeDao;
import com.orbis.app.data.ClearTimeEntry;
import com.orbis.app.data.DatabaseProvider;
import com.orbis.app.util.StateHolder;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads and writes the clear-time ledger.
 *
 * <p>Everything is scoped to <i>today</i>: clear time expires overnight by
 * design, so there is no such thing as a running balance and no query ever needs
 * more than one day. The streak is the one exception, and it reads dates only.
 *
 * <p>Every method blocks and belongs on {@link com.orbis.app.util.Background}.
 * Screens read {@link #summary()} instead, which this class republishes after
 * every write - the Kotlin build observed a Flow that re-queried as the date
 * turned over; here the screen refreshes on resume and while a session is
 * running, which it has to do for the countdown anyway.
 */
public final class EarnRepository {

    /** Matches usage_log's retention; the ledger is no more precious. */
    public static final long RETENTION_DAYS = 60L;

    private static volatile EarnRepository shared;

    private final ClearTimeDao dao;
    private final Clock clock;

    private final StateHolder<EarnSummary> summary = new StateHolder<>(EarnSummary.EMPTY);

    public EarnRepository(ClearTimeDao dao, Clock clock) {
        this.dao = dao;
        this.clock = clock;
    }

    public EarnRepository(ClearTimeDao dao) {
        this(dao, Clock.systemDefaultZone());
    }

    /**
     * Process-wide, for the same reason the usage repository is: the
     * accessibility service and the UI both need it, and two instances would each
     * publish their own idea of the balance to one holder.
     */
    public static EarnRepository shared(Context context) {
        EarnRepository existing = shared;
        if (existing != null) {
            return existing;
        }
        synchronized (EarnRepository.class) {
            if (shared == null) {
                shared =
                        new EarnRepository(
                                DatabaseProvider.get(context.getApplicationContext())
                                        .clearTimeDao());
            }
            return shared;
        }
    }

    /** Today's ledger as the UI needs it. Republished after every write. */
    public StateHolder<EarnSummary> summary() {
        return summary;
    }

    /**
     * Credits the action and returns what it actually paid.
     *
     * <p>Returns zero when the action is out of uses or the day is at its cap,
     * and writes nothing - a movement worth nothing is noise in the log, and the
     * UI needs to be able to tell the user the difference.
     */
    public long award(EarnAction action) {
        LocalDate date = today();
        List<ClearTimeMovement> movements = movementsFor(date);
        long reward = ClearTime.rewardFor(action, movements);
        if (reward <= 0L) {
            return 0L;
        }

        dao.insert(
                new ClearTimeEntry(
                        0L, clock.millis(), date.toString(), action.name(), reward, 0L));
        refresh();
        return reward;
    }

    /**
     * Writes back the spend metered by {@link ClearTimeHolder} since the last call.
     *
     * <p>Drained first and written second: if the write fails the time is lost
     * from the ledger, which under-charges the user. Draining second could
     * double-bill them, and of the two, only one is a bug worth having.
     */
    public void flushSpend() {
        long spent = ClearTimeHolder.drainPendingSpend();
        if (spent <= 0L) {
            return;
        }
        dao.insert(
                new ClearTimeEntry(
                        0L,
                        clock.millis(),
                        today().toString(),
                        ClearTimeMovement.SPEND,
                        0L,
                        spent));
    }

    /** Recomputes today's balance from the ledger and publishes it. */
    public void refresh() {
        List<ClearTimeMovement> movements = movementsFor(today());
        ClearTimeBalance balance = ClearTime.balance(movements);
        ClearTimeHolder.publish(balance.getRemainingMillis());

        Map<EarnAction, Integer> remainingUses = new HashMap<>();
        for (EarnAction action : EarnAction.values()) {
            remainingUses.put(action, ClearTime.remainingUses(action, movements));
        }
        summary.set(new EarnSummary(balance, streak(), remainingUses));
    }

    /**
     * Consecutive days on which something was earned.
     *
     * <p>"Yesterday still counts, a full missed day breaks it" is the same
     * question {@link EarnStreak} already answers, purely and under test.
     */
    public int streak() {
        List<LocalDate> dates = new ArrayList<>();
        for (String raw : dao.earnedDates()) {
            try {
                dates.add(LocalDate.parse(raw));
            } catch (Exception malformed) {
                // A row written by a build that stored dates differently is not
                // worth crashing a streak over.
            }
        }
        return EarnStreak.current(dates, today());
    }

    public void prune(long keepDays) {
        dao.deleteBefore(today().minusDays(keepDays).toString());
    }

    public void prune() {
        prune(RETENTION_DAYS);
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private List<ClearTimeMovement> movementsFor(LocalDate date) {
        List<ClearTimeMovement> movements = new ArrayList<>();
        for (ClearTimeEntry entry : dao.forDate(date.toString())) {
            movements.add(
                    new ClearTimeMovement(entry.action, entry.earnedMillis, entry.spentMillis));
        }
        return movements;
    }
}
