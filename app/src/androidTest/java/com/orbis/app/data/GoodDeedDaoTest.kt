package com.orbis.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.orbis.app.deed.GoodDeedRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class GoodDeedDaoTest {

    private lateinit var database: OrbisDatabase
    private lateinit var repository: GoodDeedRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, OrbisDatabase::class.java).build()
        repository = GoodDeedRepository(database.goodDeedDao())
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun recordingADeedMakesItObservable() = runBlocking {
        repository.record(photoPath = null, note = "Helped a neighbour")

        val entries = repository.observeAll().first()

        assertEquals(1, entries.size)
        assertEquals("Helped a neighbour", entries.single().note)
        assertTrue(entries.single().completed)
    }

    @Test
    fun streakCountsTodaysDeed() = runBlocking {
        assertEquals(0, repository.streak())

        repository.record(null, "one")

        assertEquals(1, repository.streak(LocalDate.now()))
        assertTrue(repository.doneToday())
    }

    @Test
    fun noDeedMeansNotDoneToday() = runBlocking {
        assertFalse(repository.doneToday())
    }

    @Test
    fun entriesComeBackNewestFirst() = runBlocking {
        repository.record(null, "first")
        Thread.sleep(5)
        repository.record(null, "second")

        val notes = repository.observeAll().first().map { it.note }

        assertEquals(listOf("second", "first"), notes)
    }

    @Test
    fun photoPathIsOptional() = runBlocking {
        repository.record(photoPath = null, note = "no photo")

        assertEquals(null, repository.observeAll().first().single().photoPath)
    }
}
