package com.orbis.app.earn

import android.content.Context
import com.orbis.app.data.ClearTimeDao
import com.orbis.app.data.ClearTimeEntry
import com.orbis.app.data.DatabaseProvider
import com.orbis.app.deed.GoodDeedStreak
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.LocalDate

/**
 * What today's ledger adds up to, ready for the UI.
 */
data class EarnSummary(
    val balance: ClearTimeBalance = ClearTimeBalance.EMPTY,
    val streak: Int = 0,
    /** Uses left today, per action, so buttons can be disabled honestly. */
    val remainingUses: Map<EarnAction, Int> = emptyMap(),
)

/**
 * Reads and writes the clear-time ledger.
 *
 * Everything is scoped to *today*: clear time expires overnight by design, so
 * there is no such thing as a running balance and no query ever needs more than
 * one day. The streak is the one exception, and it reads dates only.
 */
class EarnRepository(
    private val dao: ClearTimeDao,
    private val clock: Clock = Clock.systemDefaultZone(),
) {

    private fun today(): LocalDate = LocalDate.now(clock)

    fun observeToday(): Flow<List<ClearTimeMovement>> =
        dao.observeForDate(today().toString()).map { entries -> entries.map(::toMovement) }

    /**
     * Credits [action] and returns what it actually paid.
     *
     * Returns zero when the action is out of uses or the day is at its cap, and
     * writes nothing - a movement worth nothing is noise in the log, and the UI
     * needs to be able to tell the user the difference.
     */
    suspend fun award(action: EarnAction): Long {
        val date = today()
        val movements = dao.forDate(date.toString()).map(::toMovement)
        val reward = ClearTime.rewardFor(action, movements)
        if (reward <= 0L) return 0L

        dao.insert(
            ClearTimeEntry(
                timestampMillis = clock.millis(),
                date = date.toString(),
                action = action.name,
                earnedMillis = reward,
            )
        )
        refresh()
        return reward
    }

    /**
     * Writes back the spend metered by [ClearTimeHolder] since the last call.
     *
     * Drained first and written second: if the write fails the time is lost from
     * the ledger, which under-charges the user. Draining second could double-bill
     * them, and of the two, only one is a bug worth having.
     */
    suspend fun flushSpend() {
        val spent = ClearTimeHolder.drainPendingSpend()
        if (spent <= 0L) return

        dao.insert(
            ClearTimeEntry(
                timestampMillis = clock.millis(),
                date = today().toString(),
                action = ClearTimeMovement.SPEND,
                spentMillis = spent,
            )
        )
    }

    /** Recomputes today's balance from the ledger and publishes it. */
    suspend fun refresh() {
        val movements = dao.forDate(today().toString()).map(::toMovement)
        ClearTimeHolder.publish(ClearTime.balance(movements).remainingMillis)
    }

    suspend fun summarize(movements: List<ClearTimeMovement>): EarnSummary = EarnSummary(
        balance = ClearTime.balance(movements),
        streak = streak(),
        remainingUses = EarnAction.entries.associateWith {
            ClearTime.remainingUses(it, movements)
        },
    )

    /**
     * Consecutive days on which something was earned.
     *
     * Reuses the good-deed streak rules unchanged - "yesterday still counts, a
     * full missed day breaks it" is the same question, already pure and tested.
     */
    suspend fun streak(): Int {
        val dates = dao.earnedDates().mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
        return GoodDeedStreak.current(dates, today())
    }

    suspend fun prune(keepDays: Long = RETENTION_DAYS) {
        dao.deleteBefore(today().minusDays(keepDays).toString())
    }

    private fun toMovement(entry: ClearTimeEntry) = ClearTimeMovement(
        actionName = entry.action,
        earnedMillis = entry.earnedMillis,
        spentMillis = entry.spentMillis,
    )

    companion object {
        /** Matches `usage_log`'s retention; the ledger is no more precious. */
        const val RETENTION_DAYS = 60L

        @Volatile
        private var shared: EarnRepository? = null

        /**
         * Process-wide, for the same reason [com.orbis.app.data.UsageRepository]
         * is: the accessibility service and the UI both need it, and two instances
         * would each publish their own idea of the balance to one holder.
         */
        fun shared(context: Context): EarnRepository =
            shared ?: synchronized(this) {
                shared ?: EarnRepository(
                    DatabaseProvider.get(context.applicationContext).clearTimeDao()
                ).also { shared = it }
            }
    }
}
