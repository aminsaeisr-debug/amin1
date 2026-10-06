package com.srooyesh.seedcounter

import java.util.Calendar
import java.util.Date

object PersianDate {
    fun today(): String {
        val c = Calendar.getInstance()
        val (jy, jm, jd) = gregorianToJalali(
            c.get(Calendar.YEAR),
            c.get(Calendar.MONTH) + 1,
            c.get(Calendar.DAY_OF_MONTH)
        )
        return "%04d/%02d/%02d".format(jy, jm, jd)
    }

    fun fromGregorian(date: Date): String {
        val c = Calendar.getInstance().apply { time = date }
        val (jy, jm, jd) = gregorianToJalali(
            c.get(Calendar.YEAR),
            c.get(Calendar.MONTH) + 1,
            c.get(Calendar.DAY_OF_MONTH)
        )
        return "%04d/%02d/%02d".format(jy, jm, jd)
    }

    fun gregorianToJalali(gy: Int, gm: Int, gd: Int): Triple<Int, Int, Int> {
        require(gy in 1900..2100) { "Year out of supported range: $gy" }
        require(gm in 1..12) { "Invalid month: $gm" }
        require(gd in 1..31) { "Invalid day: $gd" }
        val gdm = intArrayOf(0,31,59,90,120,151,181,212,243,273,304,334)
        var gy2 = gy
        if (gm > 2) gy2++
        var days = 355666 + (365 * gy) + ((gy2 + 3) / 4) -
                ((gy2 + 99) / 100) + ((gy2 + 399) / 400) + gd + gdm[gm - 1]

        var jy = -1595 + 33 * (days / 12053)
        days %= 12053
        jy += 4 * (days / 1461)
        days %= 1461

        if (days > 365) {
            jy += (days - 1) / 365
            days = (days - 1) % 365
        }

        val jm = if (days < 186) 1 + days / 31 else 7 + (days - 186) / 30
        val jd = 1 + if (days < 186) days % 31 else (days - 186) % 30
        return Triple(jy, jm, jd)
    }
}
