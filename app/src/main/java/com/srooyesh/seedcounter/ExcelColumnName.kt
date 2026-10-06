package com.srooyesh.seedcounter

/** Safe Excel column naming shared by workbook generation and tests. */
object ExcelColumnName {
    fun of(number: Int): String {
        require(number > 0) { "Excel column number must be greater than zero" }
        var n = number
        val out = StringBuilder()
        while (n > 0) {
            val rem = (n - 1) % 26
            out.append(('A'.code + rem).toChar())
            n = (n - 1) / 26
        }
        return out.reverse().toString()
    }
}
