package com.srooyesh.seedcounter

import android.content.Context
import android.content.SharedPreferences

/** Scan trigger behavior. */
enum class ScanMode(val key: String) {
    MANUAL("manual"),
    AUTO("auto");

    companion object {
        fun fromStored(value: String?): ScanMode = entries.firstOrNull { it.key == value } ?: MANUAL
    }
}

/** What the camera is expected to recognize. */
enum class RecognitionMode(val key: String) {
    NUMBER("number"),
    BARCODE("barcode"),
    BOTH("both");

    companion object {
        fun fromStored(value: String?): RecognitionMode = entries.firstOrNull { it.key == value } ?: NUMBER
    }
}


/** How aggressively the number OCR combines separated/irregular digit groups. */
enum class NumberScanMode(val key: String) {
    SIMPLE("simple"),
    SMART("smart");

    companion object {
        fun fromStored(value: String?): NumberScanMode = entries.firstOrNull { it.key == value } ?: SIMPLE
    }
}

/** One versionable model for all user-adjustable behavior. */
data class AppSettings(
    val scanMode: ScanMode = ScanMode.MANUAL,
    val recognitionMode: RecognitionMode = RecognitionMode.NUMBER,
    /** Number grouping intelligence: SIMPLE reads contiguous digits; SMART joins irregular groups and recognizes separators. */
    val numberScanMode: NumberScanMode = NumberScanMode.SMART,
    val minDigits: Int = 4,
    val maxDigits: Int = 6,
    /** Kept for backup compatibility; BOTH mode still requires both values. */
    val requireBoth: Boolean = true,
    /** 0=standard, 1=precise, 2=max accuracy. */
    val scanQuality: Int = 2,
    /** Number of consecutive identical detections required in AUTO mode. */
    val stableReads: Int = 3,
    /** Recognition window in manual mode. */
    val manualScanWindowSec: Int = 5,
    /** 0=off, 1=on. */
    val flashMode: Int = 0,
    /** Smart zoom may be driven by ML Kit / camera assist. */
    val smartZoom: Boolean = true,
    /** Auto-focus is enabled at the scan center and on tap-to-focus. */
    val autoFocus: Boolean = true,
    val haptic: Boolean = true,
    val sound: Boolean = true,
    val voiceGuidance: Boolean = true,
    val duplicateCheck: Boolean = true,
    /** Scan frame width as a percentage of the visible PreviewView. */
    val scanFrameWidthPct: Int = 84,
    /** Scan frame height as a percentage of the visible PreviewView. */
    val scanFrameHeightPct: Int = 28,
    val dateOutput: String = "both",
    val outRow: Boolean = true,
    val outVariety: Boolean = true,
    val outCustomer: Boolean = true,
    val outNumber: Boolean = true,
    val outBarcode: Boolean = true,
    val outJalali: Boolean = true,
    val outGregorian: Boolean = true,
    val outTime: Boolean = true,
    val outSession: Boolean = false
) {
    fun sanitized(): AppSettings {
        val min = minDigits.coerceIn(2, 16)
        val max = maxDigits.coerceIn(min, 16)
        val normalizedDateOutput = dateOutput.lowercase().let {
            if (it in setOf("jalali", "gregorian", "both")) it else "both"
        }
        val (finalJalali, finalGregorian) = when (normalizedDateOutput) {
            "jalali" -> true to false
            "gregorian" -> false to true
            else -> {
                // "both": at least one date column must remain enabled.
                if (!outJalali && !outGregorian) true to true else outJalali to outGregorian
            }
        }

        return copy(
            minDigits = min,
            maxDigits = max,
            scanQuality = scanQuality.coerceIn(0, 2),
            stableReads = stableReads.coerceIn(2, 5),
            manualScanWindowSec = manualScanWindowSec.coerceIn(3, 10),
            flashMode = flashMode.coerceIn(0, 1),
            scanFrameWidthPct = scanFrameWidthPct.coerceIn(56, 96),
            scanFrameHeightPct = scanFrameHeightPct.coerceIn(12, 58),
            dateOutput = normalizedDateOutput,
            outJalali = finalJalali,
            outGregorian = finalGregorian
        )
    }
}

class SettingsStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("seedcounter_settings", Context.MODE_PRIVATE)

    fun get(): AppSettings {
        val min = prefs.getInt("min_digits", 4).coerceIn(2, 16)
        val max = prefs.getInt("max_digits", 6).coerceIn(min, 16)
        // Keep the old preference key as a fallback for existing installations.
        val legacySmartZoom = prefs.getBoolean("barcode_auto_zoom", true)
        return AppSettings(
            scanMode = ScanMode.fromStored(prefs.getString("scan_mode", ScanMode.MANUAL.key)),
            recognitionMode = RecognitionMode.fromStored(prefs.getString("recognition_mode", RecognitionMode.NUMBER.key)),
            numberScanMode = NumberScanMode.fromStored(prefs.getString("number_scan_mode", NumberScanMode.SMART.key)),
            minDigits = min,
            maxDigits = max,
            requireBoth = true,
            scanQuality = prefs.getInt("scan_quality", 2).coerceIn(0, 2),
            stableReads = prefs.getInt("stable_reads", 3).coerceIn(2, 5),
            manualScanWindowSec = prefs.getInt("manual_scan_window_sec", 5).coerceIn(3, 10),
            flashMode = prefs.getInt("flash_mode", 0).coerceIn(0, 1),
            smartZoom = prefs.getBoolean("smart_zoom", legacySmartZoom),
            autoFocus = prefs.getBoolean("auto_focus", true),
            haptic = prefs.getBoolean("haptic", true),
            sound = prefs.getBoolean("sound", true),
            voiceGuidance = prefs.getBoolean("voice_guidance", true),
            duplicateCheck = prefs.getBoolean("duplicate_check", true),
            scanFrameWidthPct = prefs.getInt("scan_frame_width_pct", 84).coerceIn(56, 96),
            scanFrameHeightPct = prefs.getInt("scan_frame_height_pct", 28).coerceIn(12, 58),
            dateOutput = prefs.getString("date_output", "both") ?: "both",
            outRow = prefs.getBoolean("out_row", true),
            outVariety = prefs.getBoolean("out_variety", true),
            outCustomer = prefs.getBoolean("out_customer", true),
            outNumber = prefs.getBoolean("out_number", true),
            outBarcode = prefs.getBoolean("out_barcode", true),
            outJalali = prefs.getBoolean("out_jalali", true),
            outGregorian = prefs.getBoolean("out_gregorian", true),
            outTime = prefs.getBoolean("out_time", true),
            outSession = prefs.getBoolean("out_session", false)
        ).sanitized()
    }

    fun save(settings: AppSettings) {
        val s = settings.sanitized()
        prefs.edit()
            .putString("scan_mode", s.scanMode.key)
            .putString("recognition_mode", s.recognitionMode.key)
            .putString("number_scan_mode", s.numberScanMode.key)
            .putInt("min_digits", s.minDigits)
            .putInt("max_digits", s.maxDigits)
            .putBoolean("require_both", true)
            .putInt("scan_quality", s.scanQuality)
            .putInt("stable_reads", s.stableReads)
            .putInt("manual_scan_window_sec", s.manualScanWindowSec)
            .putInt("flash_mode", s.flashMode)
            .putBoolean("smart_zoom", s.smartZoom)
            .putBoolean("barcode_auto_zoom", s.smartZoom)
            .putBoolean("auto_focus", s.autoFocus)
            .putBoolean("haptic", s.haptic)
            .putBoolean("sound", s.sound)
            .putBoolean("voice_guidance", s.voiceGuidance)
            .putBoolean("duplicate_check", s.duplicateCheck)
            .putInt("scan_frame_width_pct", s.scanFrameWidthPct)
            .putInt("scan_frame_height_pct", s.scanFrameHeightPct)
            .putString("date_output", s.dateOutput)
            .putBoolean("out_row", s.outRow)
            .putBoolean("out_variety", s.outVariety)
            .putBoolean("out_customer", s.outCustomer)
            .putBoolean("out_number", s.outNumber)
            .putBoolean("out_barcode", s.outBarcode)
            .putBoolean("out_jalali", s.outJalali)
            .putBoolean("out_gregorian", s.outGregorian)
            .putBoolean("out_time", s.outTime)
            .putBoolean("out_session", s.outSession)
            .apply()
    }

    fun raw(): Map<String, *> = prefs.all.toMap()

    /** Replaces known and unknown preference keys for forward-compatible backups. */
    fun replaceFromMap(map: Map<String, *>) {
        val editor = prefs.edit().clear()
        map.forEach { (key, value) ->
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Double -> editor.putFloat(key, value.toFloat())
                is Number -> editor.putLong(key, value.toLong())
                is String -> editor.putString(key, value)
            }
        }
        check(editor.commit()) { "ذخیره تنظیمات پشتیبان انجام نشد." }
    }
}
