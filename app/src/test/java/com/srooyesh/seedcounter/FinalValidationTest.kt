package com.srooyesh.seedcounter

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FinalValidationTest {
    @Test
    fun settingsSanitizeDateOutputNeverLeavesDateColumnsEmpty() {
        val jalali = AppSettings(dateOutput = "jalali", outJalali = false, outGregorian = false).sanitized()
        assertTrue(jalali.outJalali)

        val gregorian = AppSettings(dateOutput = "gregorian", outJalali = false, outGregorian = false).sanitized()
        assertTrue(gregorian.outGregorian)

        val both = AppSettings(dateOutput = "both", outJalali = false, outGregorian = false).sanitized()
        assertTrue(both.outJalali || both.outGregorian)
    }

    @Test
    fun excelColumnNamesAreSafe() {
        assertEquals("A", ExcelColumnName.of(1))
        assertEquals("Z", ExcelColumnName.of(26))
        assertEquals("AA", ExcelColumnName.of(27))
        assertEquals("AZ", ExcelColumnName.of(52))
        assertEquals("BA", ExcelColumnName.of(53))
    }

    @Test
    fun persianDateNewYearRegression() {
        val result = PersianDate.gregorianToJalali(2025, 3, 21)
        assertEquals(1404, result.first)
        assertEquals(1, result.second)
        assertEquals(1, result.third)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidGregorianMonthIsRejected() {
        PersianDate.gregorianToJalali(2025, 13, 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidGregorianYearIsRejected() {
        PersianDate.gregorianToJalali(1800, 1, 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun gregorianToJalaliRejectsInvalidDay() {
        PersianDate.gregorianToJalali(2025, 2, 32)
    }

    @Test
    fun previewRectStaysInsideRotatedImage() {
        val mapped = PreviewImageMapper.previewRectToImageRect(
            previewRect = Rect(100, 100, 900, 500),
            previewWidth = 1000,
            previewHeight = 600,
            rawImageWidth = 1920,
            rawImageHeight = 1080,
            cropRectRaw = Rect(0, 0, 1920, 1080),
            rotationDegrees = 90
        )
        assertTrue(mapped.left >= 0 && mapped.top >= 0)
        assertTrue(mapped.right <= 1080 && mapped.bottom <= 1920)
        assertTrue(mapped.width() > 0 && mapped.height() > 0)
    }
}
