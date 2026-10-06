package com.srooyesh.seedcounter

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SettingsStoreTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("seedcounter_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun tearDown() {
        context.getSharedPreferences("seedcounter_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun defaultsAreValid() {
        val s = SettingsStore(context).get()
        assertEquals(4, s.minDigits)
        assertTrue(s.maxDigits >= s.minDigits)
    }

    @Test fun saveSanitizesInvalidRange() {
        SettingsStore(context).save(AppSettings(minDigits = 20, maxDigits = 1))
        val s = SettingsStore(context).get()
        assertEquals(16, s.minDigits)
        assertEquals(16, s.maxDigits)
    }

    @Test fun replaceFromMapCommitsValues() {
        val store = SettingsStore(context)
        store.replaceFromMap(mapOf("scan_mode" to "auto", "stable_reads" to 5))
        assertEquals(ScanMode.AUTO, store.get().scanMode)
        assertEquals(5, store.get().stableReads)
    }
}
