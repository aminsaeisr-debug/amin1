package com.srooyesh.seedcounter

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BackupCodecTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        clean()
    }

    @After fun tearDown() {
        clean()
    }

    @Test fun exportContainsSessionsRecordsAndSettings() {
        val repo = SeedRepository(context)
        val session = repo.startSession("V", "C").getOrThrow()
        repo.addRecord(session.id, "1234", "ABC", false).getOrThrow()
        val json = repo.exportBackupJson()
        assertTrue(json.contains("\"sessions\""))
        assertTrue(json.contains("\"records\""))
        assertTrue(json.contains("\"settings\""))
    }

    @Test fun importRoundTripAddsRecord() {
        val repo = SeedRepository(context)
        val session = repo.startSession("V", "C").getOrThrow()
        repo.addRecord(session.id, "1234", "ABC", false).getOrThrow()
        val json = repo.exportBackupJson()
        cleanDatabaseOnly()
        val imported = SeedRepository(context).importBackupJson(json).getOrThrow()
        assertEquals(1, imported)
        assertEquals(1, SeedRepository(context).getAllRows().size)
    }

    @Test fun duplicateImportDoesNotDuplicateExistingRecord() {
        val repo = SeedRepository(context)
        val session = repo.startSession("V", "C").getOrThrow()
        repo.addRecord(session.id, "1234", "ABC", false).getOrThrow()
        val json = repo.exportBackupJson()
        assertEquals(0, repo.importBackupJson(json).getOrThrow())
        assertEquals(1, repo.getAllRows().size)
    }

    private fun clean() {
        SeedDatabase.close()
        context.deleteDatabase("seedcounter.db")
        context.getSharedPreferences(LegacyKeys.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("seed_data", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("seedcounter_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun cleanDatabaseOnly() {
        SeedDatabase.close()
        context.deleteDatabase("seedcounter.db")
    }
}
