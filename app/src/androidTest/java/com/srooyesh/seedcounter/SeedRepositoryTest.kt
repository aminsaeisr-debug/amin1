package com.srooyesh.seedcounter

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SeedRepositoryTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        clean()
    }

    @After
    fun tearDown() {
        clean()
    }

    @Test
    fun secondDifferentActiveSessionIsRejected() {
        val repo = SeedRepository(context)
        val first = repo.startSession("گوجه", "مشتری ۱").getOrThrow()

        val second = repo.startSession("خیار", "مشتری ۲")

        assertTrue(second.isFailure)
        assertEquals(first.id, repo.getActiveSession()?.id)
    }

    @Test
    fun sameActiveSessionRequestIsIdempotent() {
        val repo = SeedRepository(context)
        val first = repo.startSession("گوجه", "مشتری ۱").getOrThrow()
        val second = repo.startSession("گوجه", "مشتری ۱").getOrThrow()

        assertEquals(first.id, second.id)
        assertEquals(1, repo.getSessions().count { it.active })
    }

    @Test
    fun newRecordUsesOwningSessionBusinessDate() {
        val repo = SeedRepository(context)
        val session = repo.startSession("گوجه", "").getOrThrow()

        repo.addRecord(session.id, "1234", "", duplicateCheck = false).getOrThrow()
        val record = repo.getRecords(session.id).single()

        assertEquals(session.dateKey, record.dateGregorian)
        assertEquals(session.id, record.sessionId)
    }

    @Test
    fun invalidPacketLengthIsRejected() {
        val repo = SeedRepository(context)
        val session = repo.startSession("گوجه", "").getOrThrow()

        assertTrue(repo.addRecord(session.id, "1", "", false).isFailure)
        assertTrue(repo.addRecord(session.id, "12345678901234567", "", false).isFailure)
    }

    private fun clean() {
        SeedDatabase.close()
        context.deleteDatabase("seedcounter.db")
        context.getSharedPreferences("seedcounter_settings", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(LegacyKeys.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("seed_data", Context.MODE_PRIVATE).edit().clear().commit()
    }
}
