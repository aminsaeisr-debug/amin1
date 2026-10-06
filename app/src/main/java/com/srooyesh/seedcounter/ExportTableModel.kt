package com.srooyesh.seedcounter

/** Single source of truth for the visible/exported Excel table. */
object ExportTableModel {
    fun columns(settings: AppSettings): List<String> = buildList {
        if (settings.outRow) add("ردیف")
        if (settings.outVariety) add("نام رقم")
        if (settings.outCustomer) add("نام مشتری")
        if (settings.outNumber) add("شماره پاکت")
        if (settings.outBarcode) add("بارکد")
        when (settings.dateOutput) {
            "jalali" -> if (settings.outJalali) add("تاریخ شمسی")
            "gregorian" -> if (settings.outGregorian) add("تاریخ میلادی")
            else -> {
                if (settings.outJalali) add("تاریخ شمسی")
                if (settings.outGregorian) add("تاریخ میلادی")
            }
        }
        if (settings.outTime) add("ساعت ثبت")
        if (settings.outSession) add("شناسه جلسه")
        if (isEmpty()) add("اطلاعات")
    }

    fun values(row: ExportRow, index: Int, settings: AppSettings): List<String> {
        val result = mutableListOf<String>()
        if (settings.outRow) result += index.toString()
        if (settings.outVariety) result += row.variety
        if (settings.outCustomer) result += row.customer
        if (settings.outNumber) result += row.number
        if (settings.outBarcode) result += row.barcode
        when (settings.dateOutput) {
            "jalali" -> if (settings.outJalali) result += row.dateJalali
            "gregorian" -> if (settings.outGregorian) result += row.dateGregorian
            else -> {
                if (settings.outJalali) result += row.dateJalali
                if (settings.outGregorian) result += row.dateGregorian
            }
        }
        if (settings.outTime) result += row.time
        if (settings.outSession) result += row.sessionId.toString()
        return if (result.isEmpty()) listOf("") else result
    }
}
