package com.srooyesh.seedcounter

import android.graphics.Rect
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.ZoomSuggestionOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Real-time recognition engine. CameraX YUV frames go directly to ML Kit, while
 * the exact visual ROI is mapped through the CameraX cropRect/ViewPort.
 */
class NumberRecognitionEngine(
    private val smartZoom: Boolean = true,
    private val maxZoomRatio: Float = 1f,
    private val currentZoomRatio: (() -> Float)? = null,
    private val applyZoom: ((Float) -> Boolean)? = null
) {
    data class Result(val number: String = "", val barcode: String = "", val numberDisplay: String = "")

    private val barcodeScanner: BarcodeScanner
    private val textRecognizer: TextRecognizer
    private var lastZoomAt = 0L
    private var zoomAttempts = 0

    init {
        val builder = BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
        if (smartZoom && applyZoom != null && maxZoomRatio > 1f) {
            builder.setZoomSuggestionOptions(
                ZoomSuggestionOptions.Builder { ratio -> applyZoom.invoke(ratio) }
                    .setMaxSupportedZoomRatio(maxZoomRatio)
                    .build()
            )
        }
        barcodeScanner = BarcodeScanning.getClient(builder.build())
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    fun resetZoomAssist() {
        lastZoomAt = 0L
        zoomAttempts = 0
    }

    fun process(
        proxy: ImageProxy,
        scanRectPreview: Rect,
        previewWidth: Int,
        previewHeight: Int,
        mode: RecognitionMode,
        numberScanMode: NumberScanMode,
        minDigits: Int,
        maxDigits: Int,
        onResult: (Result) -> Unit,
        onComplete: () -> Unit
    ) {
        val mediaImage = proxy.image
        if (mediaImage == null) {
            proxy.close()
            onComplete()
            return
        }
        require(minDigits in 2..16) { "Invalid minimum digit length: $minDigits" }
        require(maxDigits in minDigits..16) { "Invalid maximum digit length: $maxDigits" }

        val rotation = proxy.imageInfo.rotationDegrees
        val input = InputImage.fromMediaImage(mediaImage, rotation)
        val roi = PreviewImageMapper.previewRectToImageRect(
            previewRect = scanRectPreview,
            previewWidth = previewWidth,
            previewHeight = previewHeight,
            rawImageWidth = proxy.width,
            rawImageHeight = proxy.height,
            cropRectRaw = proxy.cropRect,
            rotationDegrees = rotation
        )
        val rotatedHeight = if (rotation % 180 == 0) proxy.height else proxy.width

        val needNumber = mode == RecognitionMode.NUMBER || mode == RecognitionMode.BOTH
        val needBarcode = mode == RecognitionMode.BARCODE || mode == RecognitionMode.BOTH
        val workCount = (if (needNumber) 1 else 0) + (if (needBarcode) 1 else 0)
        val remaining = AtomicInteger(workCount)
        val delivered = AtomicBoolean(false)
        var number = ""
        var numberDisplay = ""
        var barcode = ""

        fun finish() {
            if (remaining.decrementAndGet() == 0) {
                if (delivered.compareAndSet(false, true)) onResult(Result(number, barcode, numberDisplay))
                proxy.close()
                onComplete()
            }
        }

        if (workCount == 0) {
            proxy.close()
            onComplete()
            return
        }

        if (needNumber) {
            textRecognizer.process(input)
                .addOnSuccessListener { text ->
                    val candidate = chooseBestNumber(text, roi, minDigits, maxDigits, numberScanMode)
                    number = candidate?.value.orEmpty()
                    numberDisplay = candidate?.displayValue ?: number
                    if (smartZoom && number.isBlank()) {
                        maybeSuggestZoom(text, roi, rotatedHeight)
                    }
                }
                .addOnFailureListener { number = "" }
                .addOnCompleteListener { finish() }
        }

        if (needBarcode) {
            barcodeScanner.process(input)
                .addOnSuccessListener { codes ->
                    barcode = codes.asSequence()
                        .filter { intersectsRoi(it.boundingBox, roi) }
                        .mapNotNull { it.rawValue?.trim()?.takeIf(String::isNotBlank) }
                        .maxByOrNull(String::length)
                        ?: ""
                }
                .addOnFailureListener { barcode = "" }
                .addOnCompleteListener { finish() }
        }
    }

    private fun chooseBestNumber(
        text: Text,
        roi: Rect,
        minDigits: Int,
        maxDigits: Int,
        numberScanMode: NumberScanMode
    ): NumberCandidateExtractor.Candidate? {
        data class Evidence(
            var bestScore: Float,
            var hits: Int,
            var centerScore: Float
        )

        val evidence = linkedMapOf<String, Evidence>()
        val bestCandidateByValue = linkedMapOf<String, NumberCandidateExtractor.Candidate>()
        text.textBlocks.flatMap { it.lines }.forEach { line ->
            val box = line.boundingBox ?: return@forEach
            if (!intersectsRoi(box, roi)) return@forEach
            NumberCandidateExtractor.fromLine(line, minDigits, maxDigits).forEach { candidate ->
                val allowed = when (numberScanMode) {
                    NumberScanMode.SIMPLE -> candidate.source == "compact" && candidate.groups == 1 && !candidate.displayValue.any { !it.isDigit() }
                    NumberScanMode.SMART -> true
                }
                if (!allowed) return@forEach
                val center = centerScore(box, roi)
                val entry = evidence.getOrPut(candidate.value) { Evidence(0f, 0, 0f) }
                entry.bestScore = max(entry.bestScore, candidate.score)
                entry.hits += 1
                entry.centerScore = max(entry.centerScore, center)
                val previous = bestCandidateByValue[candidate.value]
                if (previous == null || candidate.score > previous.score) {
                    bestCandidateByValue[candidate.value] = candidate
                }
            }
        }

        val winner = evidence.maxByOrNull { (value, e) ->
            val lengthQuality = (value.length.coerceAtMost(maxDigits) / maxDigits.toFloat()) * 0.25f
            val sourceBoost = if (numberScanMode == NumberScanMode.SMART && bestCandidateByValue[value]?.groups?.let { it > 1 } == true) 0.12f else 0f
            e.bestScore + min(0.24f, e.hits * 0.06f) + e.centerScore * 0.28f + lengthQuality + sourceBoost
        }?.key
        return winner?.let { bestCandidateByValue[it] }
    }

    private fun centerScore(box: Rect, roi: Rect): Float {
        val roiCx = (roi.left + roi.right) / 2f
        val roiCy = (roi.top + roi.bottom) / 2f
        val boxCx = (box.left + box.right) / 2f
        val boxCy = (box.top + box.bottom) / 2f
        val dx = abs(boxCx - roiCx) / max(1f, roi.width().toFloat())
        val dy = abs(boxCy - roiCy) / max(1f, roi.height().toFloat())
        return 1f - (dx + dy).coerceAtMost(1f)
    }

    private fun maybeSuggestZoom(text: Text, roi: Rect, imageHeight: Int) {
        if (!smartZoom || applyZoom == null || maxZoomRatio <= 1f || zoomAttempts >= 3) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastZoomAt < 900L) return

        val hasSmallDigitLine = text.textBlocks.flatMap { it.lines }.any { line ->
            val box = line.boundingBox ?: return@any false
            if (!intersectsRoi(box, roi)) return@any false
            val digits = NumberCandidateExtractor.normalizeCharacters(line.text).count(Char::isDigit)
            digits >= 2 && (box.height().toFloat() / max(1, imageHeight)) < 0.14f
        }
        if (!hasSmallDigitLine) return

        val current = (currentZoomRatio?.invoke() ?: 1f).coerceAtLeast(1f)
        val target = (current * 1.28f).coerceAtMost(maxZoomRatio)
        if (target <= current + 0.05f) return
        if (applyZoom(target)) {
            lastZoomAt = now
            zoomAttempts++
        }
    }

    private fun intersectsRoi(box: Rect?, roi: Rect): Boolean = box?.let { Rect.intersects(it, roi) } == true

    fun close() {
        runCatching { barcodeScanner.close() }
        runCatching { textRecognizer.close() }
    }
}
