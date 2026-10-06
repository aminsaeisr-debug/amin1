package com.srooyesh.seedcounter

import org.junit.Assert.assertTrue
import org.junit.Test

class NumberCandidateExtractorTest {
    @Test
    fun irregularGroupsAreJoinedWithoutFourDigitAssumption() {
        val cases = listOf(
            "12 345 67" to "1234567",
            "1 23 456" to "123456",
            "12-34-5" to "12345",
            "123 45 6 789" to "123456789"
        )
        cases.forEach { (input, expected) ->
            assertTrue(
                "Expected $expected from '$input'",
                NumberCandidateExtractor.fromText(input).any { it.value == expected }
            )
        }
    }

    @Test
    fun PersianAndArabicDigitsAreNormalized() {
        assertTrue(NumberCandidateExtractor.fromText("۱۲ ۳۴۵ ۶۷۰").any { it.value == "12345670" })
        assertTrue(NumberCandidateExtractor.fromText("١٢ ٣٤ ٥٦").any { it.value == "123456" })
    }

    @Test
    fun smartModeCandidateKeepsRecognizedSeparatorsForDisplay() {
        val candidates = NumberCandidateExtractor.fromText("12 , 345-67")
        assertTrue(candidates.any {
            it.value == "1234567" &&
                it.displayValue == "12,345-67"
        })
    }

    @Test
    fun smartModeHandlesTwoAndThreeDigitGroups() {
        val candidates = NumberCandidateExtractor.fromText("1 23 456")
        assertTrue(candidates.any { it.value == "123456" && it.groups == 3 })
    }
}
