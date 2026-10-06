package com.srooyesh.seedcounter

import org.junit.Assert.assertEquals
import org.junit.Test

class StorageTest {
    @Test fun normalizePacketNumber_persianDigits() {
        assertEquals("12345", Storage.normalizePacketNumber("۱۲۳۴۵"))
    }
    @Test fun normalizePacketNumber_arabicDigits() {
        assertEquals("12345", Storage.normalizePacketNumber("١٢٣٤٥"))
    }
    @Test fun normalizePacketNumber_stripsNonDigits() {
        assertEquals("12345", Storage.normalizePacketNumber("12-34 5"))
    }
    @Test fun normalizeBarcode_stripsWhitespace() {
        assertEquals("ABC123", Storage.normalizeBarcode(" AB C 123 "))
    }
    @Test fun normalizeSearchQuery_persianDigits() {
        assertEquals("123abc", Storage.normalizeSearchQuery("۱۲۳abc"))
    }
}
