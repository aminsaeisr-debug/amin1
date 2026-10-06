package com.srooyesh.seedcounter

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportTest {

    @Test
    fun testExportExcel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Start from a deterministic clean state so an earlier instrumentation run
        // cannot leave an active session that makes this test fail.
        SeedDatabase.close()
        context.deleteDatabase("seedcounter.db")
        context.getSharedPreferences("seedcounter_settings", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(LegacyKeys.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("seed_data", Context.MODE_PRIVATE).edit().clear().commit()

        val repo = SeedRepository(context)
        val session = repo.startSession("Test", "Customer").getOrThrow()
        repo.addRecord(session.id, "1234", "", duplicateCheck = false).getOrThrow()
        val record = repo.getRecords(session.id).single()
        assertTrue("تاریخ رکورد باید متعلق به تاریخ جلسه باشد!", record.dateGregorian == session.dateKey)

        val file = ExcelExporter(context).createReportFile()
        assertTrue("فایل اکسل ساخته نشد!", file.exists())
        assertTrue("نام فایل خروجی نادرست است!", file.name.startsWith("seed-mas-report_") && file.name.endsWith(".xlsx"))
        assertTrue("فایل اکسل خالی است!", file.length() > 0L)
    }
}
