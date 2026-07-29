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

    suspend fun streak(today: LocalDate = LocalDate.now(zone)): Int = withContext(Dispatchers.IO) {
        GoodDeedStreak.current(completedDates(), today)
    }

    suspend fun doneToday(today: LocalDate = LocalDate.now(zone)): Boolean =
        withContext(Dispatchers.IO) { GoodDeedStreak.doneToday(completedDates(), today) }

    private suspend fun completedDates(): List<LocalDate> =
        dao.completed().map { it.localDate() }

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
