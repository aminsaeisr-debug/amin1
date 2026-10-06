package com.srooyesh.seedcounter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportTableModelTest {
    @Test fun columnsBothContainsBothDates() {
        val columns = ExportTableModel.columns(AppSettings(dateOutput = "both", outJalali = true, outGregorian = true))
        assertTrue("تاریخ شمسی" in columns)
        assertTrue("تاریخ میلادی" in columns)
    }

    @Test fun valuesRespectSelectedDateOutput() {
        val row = ExportRow(1, "V", "C", "1234", "BC", "2026-10-06", "1405/07/14", "12:00:00")
        val values = ExportTableModel.values(row, 1, AppSettings(dateOutput = "jalali", outGregorian = false, outJalali = true))
        assertTrue("1405/07/14" in values)
        assertTrue("2026-10-06" !in values)
    }

    @Test fun emptySelectionFallsBackToInformationColumn() {
        val settings = AppSettings(outRow = false, outVariety = false, outCustomer = false, outNumber = false, outBarcode = false, outTime = false, outSession = false, outJalali = false, outGregorian = false)
        assertEquals(listOf("اطلاعات"), ExportTableModel.columns(settings))
    }
}
