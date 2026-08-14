package com.orbis.app.deed

import com.orbis.app.data.GoodDeedDao
import com.orbis.app.data.GoodDeedEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** What the deed screen needs to know, derived in one pass over the log. */
data class GoodDeedSummary(
    val streak: Int = 0,
    val doneToday: Boolean = false,
)

class GoodDeedRepository(
    private val dao: GoodDeedDao,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    fun observeAll(): Flow<List<GoodDeedEntry>> = dao.observeAll()

    suspend fun record(photoPath: String?, note: String): Long = withContext(Dispatchers.IO) {
        dao.insert(
            GoodDeedEntry(
                timestampMillis = System.currentTimeMillis(),
                photoPath = photoPath,
                note = note,
                completed = true,
            )
        )
    }

    /**
     * Streak and "done today" from a single pass.
     *
     * They were computed independently, and each read the whole `good_deed` table
     * and mapped every row through `Instant`/`ZonedDateTime`. Since the ViewModel
     * recomputes on every emission of [observeAll], that was two full-table reads
     * per insert.
     */
    fun summarize(
        entries: List<GoodDeedEntry>,
        today: LocalDate = LocalDate.now(zone),
    ): GoodDeedSummary {
        val dates = HashSet<LocalDate>(entries.size)
        entries.forEach { if (it.completed) dates += it.localDate() }

        return GoodDeedSummary(
            streak = GoodDeedStreak.current(dates, today),
            doneToday = GoodDeedStreak.doneToday(dates, today),
        )
    }

    suspend fun summary(today: LocalDate = LocalDate.now(zone)): GoodDeedSummary =
        withContext(Dispatchers.IO) { summarize(dao.completed(), today) }

    private fun GoodDeedEntry.localDate(): LocalDate =
        Instant.ofEpochMilli(timestampMillis).atZone(zone).toLocalDate()

    /**
     * Deletes the photo for an entry that was never saved.
     *
     * CameraX writes the file before the row exists, so a cancelled capture would
     * otherwise leave orphans accumulating in private storage.
     */
    suspend fun discardPhoto(path: String?) = withContext(Dispatchers.IO) {
        path?.let { runCatching { File(it).delete() } }
        Unit
    }
}
