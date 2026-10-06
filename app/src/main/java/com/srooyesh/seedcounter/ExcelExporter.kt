package com.srooyesh.seedcounter

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Small dependency-free OOXML writer. All packet IDs/barcodes are written as
 * inline strings so leading zeros survive Excel/LibreOffice/Windows imports.
 */
class ExcelExporter(private val ctx: Context) {
    companion object {
        const val MIME_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        private const val REPORT_PREFIX = "seed-mas-report_"
        private const val REPORT_SUFFIX = ".xlsx"
    }

    private val repo by lazy { SeedRepository(ctx) }
    private val settingsStore by lazy { SettingsStore(ctx) }

    fun export(): Result<Intent> = runCatching { createShareIntent() }

    fun createReportFile(): File {
        val rows = repo.getAllRows()
        return createReportFile(rows)
    }

    /** Reuses an already loaded row set, avoiding a second database query in previews. */
    fun createReportFile(rows: List<ExportRow>): File = runCatching {
        if (rows.isEmpty()) error(ctx.getString(R.string.excel_empty))
        val settings = settingsStore.get()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val target = File(ctx.filesDir, "$REPORT_PREFIX$stamp$REPORT_SUFFIX")
        val temp = File(ctx.filesDir, "$REPORT_PREFIX$stamp.tmp")
        try {
            writeWorkbook(temp, rows.groupBy { it.dateGregorian }.toSortedMap(), settings)
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            deleteOldReports(except = target)
            target
        } catch (t: Throwable) {
            temp.delete()
            target.delete()
            throw t
        }
    }.getOrThrow()

    fun createShareIntent(): Intent = createShareIntent(repo.getAllRows())

    fun createShareIntent(rows: List<ExportRow>): Intent {
        val file = createReportFile(rows)
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.provider", file)
        return Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = MIME_XLSX
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            ctx.getString(R.string.share_excel_title)
        )
    }

    private fun deleteOldReports(except: File? = null) {
        ctx.filesDir.listFiles { f -> f.name.startsWith(REPORT_PREFIX) && f.name.endsWith(REPORT_SUFFIX) }
            ?.filter { except == null || it.absolutePath != except.absolutePath }
            ?.forEach { it.delete() }
    }

    private fun writeWorkbook(file: File, grouped: Map<String, List<ExportRow>>, settings: AppSettings) {
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            putText(zip, "[Content_Types].xml", contentTypes(grouped.size))
            putText(zip, "_rels/.rels", rootRels())
            putText(zip, "docProps/core.xml", coreProps())
            putText(zip, "docProps/app.xml", appProps())
            putText(zip, "xl/workbook.xml", workbookXml(grouped.keys.toList()))
            putText(zip, "xl/_rels/workbook.xml.rels", workbookRels(grouped.size))
            putText(zip, "xl/styles.xml", stylesXml())
            putText(zip, "xl/theme/theme1.xml", themeXml())
            grouped.entries.forEachIndexed { index, entry ->
                putText(zip, "xl/worksheets/sheet${index + 1}.xml", sheetXml(entry.key, entry.value, settings, isFirst = index == 0))
            }
        }
    }

    private fun putText(zip: ZipOutputStream, path: String, text: String) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun contentTypes(count: Int) = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
        append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
        append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
        append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
        append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>")
        append("<Override PartName=\"/xl/theme/theme1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.theme+xml\"/>")
        append("<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>")
        append("<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>")
        repeat(count) { index ->
            append("<Override PartName=\"/xl/worksheets/sheet${index + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>")
        }
        append("</Types>")
    }

    private fun rootRels() = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
            <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
            <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
            <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/>
        </Relationships>
    """.trimIndent()

    private fun coreProps(): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val now = formatter.format(Date())
        return """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                <dc:creator>seed-mas</dc:creator>
                <cp:lastModifiedBy>seed-mas</cp:lastModifiedBy>
                <dcterms:created xsi:type="dcterms:W3CDTF">$now</dcterms:created>
                <dcterms:modified xsi:type="dcterms:W3CDTF">$now</dcterms:modified>
            </cp:coreProperties>
        """.trimIndent()
    }

    private fun appProps() = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties" xmlns:vt="http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes">
            <Application>seed-mas</Application>
            <AppVersion>${BuildConfig.VERSION_NAME}</AppVersion>
        </Properties>
    """.trimIndent()

    private fun workbookXml(dates: List<String>) = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        append("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">")
        append("<fileVersion appName=\"xl\" lastEdited=\"7\" lowestEdited=\"7\" rupBuild=\"27231\"/>")
        append("<workbookPr defaultThemeVersion=\"166925\"/>")
        append("<bookViews><workbookView xWindow=\"0\" yWindow=\"0\" windowWidth=\"28800\" windowHeight=\"18000\"/></bookViews>")
        append("<sheets>")
        val usedNames = mutableSetOf<String>()
        dates.forEachIndexed { i, date ->
            val parts = date.split("-")
            val sheetBase = formatJalaliForSheetName(date).ifBlank { "Sheet-${i + 1}" }
            var sheetName = sheetBase.take(31).ifBlank { "Sheet-${i + 1}" }
            var counter = 2
            while (!usedNames.add(sheetName)) {
                val suffix = "_$counter"
                sheetName = sheetBase.take((31 - suffix.length).coerceAtLeast(1)) + suffix
                counter++
            }
            append("<sheet name=\"${xmlEscape(sheetName)}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>")
        }
        append("</sheets><calcPr calcId=\"191029\"/>")
        append("</workbook>")
    }

    private fun workbookRels(count: Int) = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
        repeat(count) { i ->
            val n = i + 1
            append("<Relationship Id=\"rId$n\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet$n.xml\"/>")
        }
        append("<Relationship Id=\"rId${count + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>")
        append("<Relationship Id=\"rId${count + 2}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme\" Target=\"theme/theme1.xml\"/>")
        append("</Relationships>")
    }

    private fun stylesXml() = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
            <numFmts count="0"/>
            <fonts count="3">
                <font><sz val="11"/><color theme="1"/><name val="Calibri"/><family val="2"/><scheme val="minor"/></font>
                <font><b/><sz val="11"/><color theme="1"/><name val="Calibri"/><family val="2"/><scheme val="minor"/></font>
                <font><b/><sz val="12"/><color rgb="FF0D7081"/><name val="Calibri"/><family val="2"/><scheme val="minor"/></font>
            </fonts>
            <fills count="3">
                <fill><patternFill patternType="none"/></fill>
                <fill><patternFill patternType="gray125"/></fill>
                <fill><patternFill patternType="solid"><fgColor rgb="FFE8F3F1"/><bgColor indexed="64"/></patternFill></fill>
            </fills>
            <borders count="2">
                <border><left/><right/><top/><bottom/><diagonal/></border>
                <border><left style="thin"><color rgb="FFB0BEC5"/></left><right style="thin"><color rgb="FFB0BEC5"/></right><top style="thin"><color rgb="FFB0BEC5"/></top><bottom style="thin"><color rgb="FFB0BEC5"/></bottom><diagonal/></border>
            </borders>
            <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
            <cellXfs count="4">
                <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
                <xf numFmtId="0" fontId="2" fillId="2" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf>
                <xf numFmtId="0" fontId="1" fillId="2" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf>
                <xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf>
            </cellXfs>
            <cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
            <dxfs count="0"/><tableStyles count="0" defaultTableStyle="TableStyleMedium2" defaultPivotStyle="PivotStyleLight16"/>
        </styleSheet>
    """.trimIndent()

    private fun themeXml() = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="Office Theme">
            <a:themeElements>
                <a:clrScheme name="Office">
                    <a:dk1><a:sysClr val="windowText" lastClr="000000"/></a:dk1>
                    <a:lt1><a:sysClr val="window" lastClr="FFFFFF"/></a:lt1>
                    <a:dk2><a:srgbClr val="44546A"/></a:dk2>
                    <a:lt2><a:srgbClr val="E7E6E6"/></a:lt2>
                    <a:accent1><a:srgbClr val="4472C4"/></a:accent1>
                    <a:accent2><a:srgbClr val="ED7D31"/></a:accent2>
                    <a:accent3><a:srgbClr val="A5A5A5"/></a:accent3>
                    <a:accent4><a:srgbClr val="FFC000"/></a:accent4>
                    <a:accent5><a:srgbClr val="5B9BD5"/></a:accent5>
                    <a:accent6><a:srgbClr val="70AD47"/></a:accent6>
                    <a:hlink><a:srgbClr val="0563C1"/></a:hlink>
                    <a:folHlink><a:srgbClr val="954F72"/></a:folHlink>
                </a:clrScheme>
                <a:fontScheme name="Office"><a:majorFont><a:latin typeface="Calibri Light"/></a:majorFont><a:minorFont><a:latin typeface="Calibri"/></a:minorFont></a:fontScheme>
                <a:fmtScheme name="Office"><a:fillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:fillStyleLst><a:lnStyleLst><a:ln w="6350" cap="flat" cmpd="sng" algn="ctr"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:prstDash val="solid"/></a:ln></a:lnStyleLst><a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst><a:bgFillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:bgFillStyleLst></a:fmtScheme>
            </a:themeElements>
            <a:objectDefaults/><a:extraClrSchemeLst/>
        </a:theme>
    """.trimIndent()

    private fun sheetXml(date: String, rows: List<ExportRow>, settings: AppSettings, isFirst: Boolean): String {
        val headers = ExportTableModel.columns(settings)
        val totalRows = 4 + rows.size
        val lastCol = columnName(headers.size)
        val jalali = formatJalaliForDisplay(date)

        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
            append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">")
            append("<dimension ref=\"A1:${lastCol}${totalRows}\"/>")
            append("<sheetViews><sheetView tabSelected=\"${if (isFirst) 1 else 0}\" workbookViewId=\"0\" rightToLeft=\"1\"><pane ySplit=\"4\" topLeftCell=\"A5\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>")
            append("<sheetFormatPr defaultRowHeight=\"18\"/>")
            append("<cols>")
            headers.indices.forEach { i -> append("<col min=\"${i + 1}\" max=\"${i + 1}\" width=\"20\" customWidth=\"1\"/>") }
            append("</cols><sheetData>")
            addRow(this, 1, listOf("گزارش خروج بذر • seed-mas"), 1)
            addRow(this, 2, listOf("تاریخ شمسی: $jalali  |  تاریخ میلادی: $date"), 3)
            addRow(this, 3, listOf("تعداد کل: ${rows.size}"), 3)
            addRow(this, 4, headers, 2)
            rows.forEachIndexed { index, row -> addRow(this, 5 + index, ExportTableModel.values(row, index + 1, settings), 3) }
            append("</sheetData>")
            append("<autoFilter ref=\"A4:${lastCol}${totalRows}\"/>")
            append("<pageMargins left=\"0.7\" right=\"0.7\" top=\"0.75\" bottom=\"0.75\" header=\"0.3\" footer=\"0.3\"/>")
            append("</worksheet>")
        }
    }

    private fun addRow(sb: StringBuilder, row: Int, cells: List<String>, style: Int) {
        sb.append("<row r=\"$row\">")
        cells.forEachIndexed { i, value ->
            val col = columnName(i + 1)
            sb.append("<c r=\"$col$row\" t=\"inlineStr\" s=\"$style\"><is><t xml:space=\"preserve\">${xmlEscape(value)}</t></is></c>")
        }
        sb.append("</row>")
    }

    private fun formatJalaliOrNull(date: String): String? = runCatching {
        val parts = date.split("-")
        require(parts.size == 3)
        val gy = parts[0].toInt()
        val gm = parts[1].toInt()
        val gd = parts[2].toInt()
        val (jy, jm, jd) = PersianDate.gregorianToJalali(gy, gm, gd)
        "%04d/%02d/%02d".format(jy, jm, jd)
    }.getOrNull()

    private fun formatJalaliForDisplay(date: String): String = formatJalaliOrNull(date) ?: "-"

    private fun formatJalaliForSheetName(date: String): String = formatJalaliOrNull(date) ?: ""

    private fun columnName(number: Int): String = ExcelColumnName.of(number)

    private fun xmlEscape(value: String): String = buildString(value.length + 16) {
        value.forEach { c ->
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                '\t', '\n', '\r' -> append(c)
                else -> if (c.code >= 0x20) append(c)
            }
        }
    }
}

class BackupExporter(private val ctx: Context) {
    fun create(): Result<Intent> = runCatching {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(ctx.filesDir, "seed-mas-backup_$stamp.json")
        val temp = File(ctx.filesDir, "seed-mas-backup_$stamp.tmp")
        try {
            temp.writeText(BackupCodec.export(SeedDatabase.get(ctx), ctx, SettingsStore(ctx)), Charsets.UTF_8)
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
            ctx.filesDir.listFiles { f -> f.name.startsWith("seed-mas-backup_") && f.name.endsWith(".json") && f.absolutePath != file.absolutePath }?.forEach { it.delete() }
        } catch (t: Throwable) {
            temp.delete()
            file.delete()
            throw t
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.provider", file)
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            ctx.getString(R.string.share_backup_title)
        )
    }
}
