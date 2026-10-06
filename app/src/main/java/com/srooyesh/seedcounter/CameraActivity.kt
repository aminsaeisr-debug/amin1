package com.srooyesh.seedcounter

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Bundle
import android.os.CountDownTimer
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

class CameraActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_SESSION_ID = "session_id"
        private const val ANALYSIS_INTERVAL_FAST_MS = 210L
        private const val ANALYSIS_INTERVAL_PRECISE_MS = 250L
        private const val AUTO_COOLDOWN_MS = 900L
        private const val BOTH_PAIR_WINDOW_MS = 2500L
        private const val WATCHDOG_MS = 8000L
    }

    private val preview by lazy { findViewById<PreviewView>(R.id.preview) }
    private val scanOverlay by lazy { findViewById<ScanOverlayView>(R.id.scanOverlay) }
    private val status by lazy { findViewById<TextView>(R.id.cameraStatus) }
    private val frameSize by lazy { findViewById<TextView>(R.id.frameSize) }
    private val detectedNumber by lazy { findViewById<EditText>(R.id.detectedNumber) }
    private val detectedBarcode by lazy { findViewById<EditText>(R.id.detectedBarcode) }
    private val numberState by lazy { findViewById<TextView>(R.id.numberState) }
    private val barcodeState by lazy { findViewById<TextView>(R.id.barcodeState) }
    private val resultPanel by lazy { findViewById<View>(R.id.resultPanel) }
    private val bottomControls by lazy { findViewById<View>(R.id.bottomControls) }
    private val confirmButton by lazy { findViewById<Button>(R.id.confirm) }
    private val retryButton by lazy { findViewById<Button>(R.id.retry) }
    private val scanButton by lazy { findViewById<Button>(R.id.scanNow) }
    private val countView by lazy { findViewById<TextView>(R.id.cameraCount) }

    private val executor = Executors.newSingleThreadExecutor()
    private val destroyed = AtomicBoolean(false)
    private val settingsStore by lazy { SettingsStore(this) }
    private val repo by lazy { SeedRepository(this) }
    private lateinit var feedback: FeedbackController

    @Volatile private var settings = AppSettings()
    @Volatile private var scanBusy = false
    @Volatile private var scanBusySince = 0L
    @Volatile private var lastFrameAt = 0L
    @Volatile private var scanGeneration = 0L
    @Volatile private var scanArmed = false
    @Volatile private var scanRectSnapshot = Rect()

    private var sessionId = -1L
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var engine: NumberRecognitionEngine? = null
    private var scanStartedAt = 0L
    private var cooldownUntil = 0L
    private var stableNumber = ""
    private var stableNumberDisplay = ""
    private var stableNumberCount = 0
    private var stableBarcode = ""
    private var stableBarcodeCount = 0
    private var stableNumberSince = 0L
    private var stableBarcodeSince = 0L
    private var pendingNumber = ""
    private var pendingBarcode = ""
    private var localCount = 0
    private var manualScanCountdown: CountDownTimer? = null
    private var announcedNumberFound = false
    private var firstResume = true
    private var announcedBarcodeRequired = false
    private var announcedNumberRequired = false

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else {
            toast(getString(R.string.permission_denied))
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.camera)
        feedback = FeedbackController(this)
        sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1L)
        if (sessionId <= 0L) {
            toast(getString(R.string.invalid_session))
            finish()
            return
        }

        settings = settingsStore.get()
        scanOverlay.setFractions(settings.scanFrameWidthPct / 100f, settings.scanFrameHeightPct / 100f)
        updateScanRectSnapshot()
        updateCameraUi()
        bottomControls.doOnLayout { repositionResultPanel() }
        bottomControls.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> repositionResultPanel() }

        findViewById<ImageButton>(R.id.exitCamera).setOnClickListener { finish() }

        preview.setOnTouchListener { _, event ->
            if (event.actionMasked == android.view.MotionEvent.ACTION_UP && settings.autoFocus) {
                focusAt(event.x, event.y)
            }
            false
        }

        detectedNumber.addTextChangedListener(simpleWatcher())
        detectedBarcode.addTextChangedListener(simpleWatcher())
        confirmButton.setOnClickListener { savePending() }
        retryButton.setOnClickListener { clearPendingAndRetry() }
        scanButton.setOnClickListener { startScanCycle(true) }

        lifecycleScope.launch {
            localCount = withContext(Dispatchers.IO) { repo.getRecordCount(sessionId) }
            countView.text = getString(R.string.camera_count, localCount)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        if (firstResume) {
            firstResume = false
            return
        }
        reloadSettingsAfterReturn()
    }

    private fun reloadSettingsAfterReturn() {
        val previous = settings
        settings = settingsStore.get().sanitized()
        scanOverlay.setFractions(settings.scanFrameWidthPct / 100f, settings.scanFrameHeightPct / 100f)
        updateScanRectSnapshot()
        updateCameraUi()
        val provider = cameraProvider
        val cameraNeedsRebind = previous.scanQuality != settings.scanQuality || previous.smartZoom != settings.smartZoom
        if (provider != null && cameraNeedsRebind) {
            preview.post {
                runCatching { bindCamera(provider, CameraSelector.DEFAULT_BACK_CAMERA) }
            }
        } else {
            camera?.cameraControl?.enableTorch(settings.flashMode == 1)
            if (settings.autoFocus) focusCenter()
            if (settings.scanMode == ScanMode.AUTO && !scanArmed) startScanCycle(false)
        }
    }

    private fun repositionResultPanel() {
        if (bottomControls.height <= 0) return
        val params = resultPanel.layoutParams as? android.widget.FrameLayout.LayoutParams ?: return
        params.bottomMargin = bottomControls.height + dp(18f).roundToInt()
        resultPanel.layoutParams = params
    }

    private fun analysisIntervalMs(): Long = when (settings.scanQuality) {
        0 -> ANALYSIS_INTERVAL_FAST_MS
        1 -> ANALYSIS_INTERVAL_PRECISE_MS
        else -> ANALYSIS_INTERVAL_PRECISE_MS
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun simpleWatcher() = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { refreshConfirmState() }
        override fun afterTextChanged(s: Editable?) = Unit
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                val selector = CameraSelector.DEFAULT_BACK_CAMERA
                if (!provider.hasCamera(selector)) throw IllegalStateException("back camera unavailable")

                preview.post {
                    try {
                        bindCamera(provider, selector)
                    } catch (_: Exception) {
                        toast(getString(R.string.camera_unavailable))
                        finish()
                    }
                }
            } catch (_: Exception) {
                toast(getString(R.string.camera_unavailable))
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera(provider: ProcessCameraProvider, selector: CameraSelector, attempt: Int = 0) {
        if (destroyed.get()) return
        val viewPort = preview.viewPort
        if (viewPort == null) {
            if (attempt < 10 && !destroyed.get()) {
                preview.postDelayed({ bindCamera(provider, selector, attempt + 1) }, 120L)
            } else {
                toast(getString(R.string.camera_unavailable))
            }
            return
        }

        provider.unbindAll()
        val targetRotation = preview.display?.rotation ?: windowManager.defaultDisplay.rotation

        val previewUseCase = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_16_9)
            .setTargetRotation(targetRotation)
            .build()
            .also { it.setSurfaceProvider(preview.surfaceProvider) }

        val resolution = when (settings.scanQuality) {
            0 -> android.util.Size(1280, 720)
            else -> android.util.Size(1920, 1080)
        }
        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    resolution,
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            )
            .build()
        val analyzer = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            .setTargetRotation(targetRotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()

        val group = UseCaseGroup.Builder()
            .setViewPort(viewPort)
            .addUseCase(previewUseCase)
            .addUseCase(analyzer)
            .build()

        analyzer.setAnalyzer(executor) { proxy -> analyze(proxy) }
        camera = provider.bindToLifecycle(this, selector, group)
        val maxZoom = camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f
        engine?.close()
        engine = NumberRecognitionEngine(
            smartZoom = settings.smartZoom,
            maxZoomRatio = maxZoom,
            currentZoomRatio = { camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f },
            applyZoom = { ratio ->
                camera?.cameraControl?.setZoomRatio(ratio)
                true
            }
        )

        camera?.cameraControl?.enableTorch(settings.flashMode == 1)
        if (settings.autoFocus) focusCenter()

        runOnUiThread {
            updateScanRectSnapshot()
            updateCameraUi()
            if (settings.scanMode == ScanMode.AUTO) startScanCycle(false)
        }
    }

    private fun updateCameraUi() {
        findViewById<android.widget.TextView>(R.id.modeLabel).text = getString(
            R.string.camera_mode_summary,
            if (settings.scanMode == ScanMode.AUTO) getString(R.string.mode_auto) else getString(R.string.mode_manual),
            if (settings.numberScanMode == NumberScanMode.SMART) getString(R.string.number_scan_smart_short) else getString(R.string.number_scan_simple_short)
        )
        scanButton.text = getString(
            if (settings.scanMode == ScanMode.AUTO) R.string.scan_again else R.string.scan_now
        )
        status.text = getString(
            if (settings.scanMode == ScanMode.AUTO) R.string.auto_ready else R.string.camera_ready_manual
        )
        updateFrameSize()
        applyRecognitionVisibility()
    }

    private fun updateFrameSize() {
        frameSize.text = getString(
            R.string.frame_size_value,
            scanOverlay.getWidthPercent(),
            scanOverlay.getHeightPercent()
        )
    }

    private fun applyRecognitionVisibility() {
        findViewById<View>(R.id.numberRow).visibility = if (settings.recognitionMode == RecognitionMode.BARCODE) View.GONE else View.VISIBLE
        findViewById<View>(R.id.barcodeRow).visibility = if (settings.recognitionMode == RecognitionMode.NUMBER) View.GONE else View.VISIBLE
    }

    private fun updateScanRectSnapshot() {
        if (scanOverlay.width > 0 && scanOverlay.height > 0) {
            scanRectSnapshot = scanOverlay.scanRectInPreview()
        }
    }

    private fun focusCenter() = preview.post { if (preview.width > 0 && preview.height > 0) focusAt(preview.width / 2f, preview.height / 2f) }

    private fun focusAt(x: Float, y: Float) {
        val currentCamera = camera ?: return
        if (!settings.autoFocus || preview.width <= 0 || preview.height <= 0) return
        runCatching {
            val point = preview.meteringPointFactory.createPoint(
                x.coerceIn(0f, preview.width.toFloat()),
                y.coerceIn(0f, preview.height.toFloat())
            )
            val action = androidx.camera.core.FocusMeteringAction.Builder(point)
                .setAutoCancelDuration(2, TimeUnit.SECONDS)
                .build()
            currentCamera.cameraControl.startFocusAndMetering(action)
        }
    }

    private fun startScanCycle(manualRequest: Boolean) {
        if (destroyed.get() || scanBusy) return
        cooldownUntil = 0L
        lastFrameAt = 0L
        resetStability()
        engine?.resetZoomAssist()
        announcedNumberFound = false
        announcedBarcodeRequired = false
        announcedNumberRequired = false
        scanGeneration++
        pendingNumber = ""
        pendingBarcode = ""
        resultPanel.visibility = View.GONE
        detectedNumber.setText("")
        detectedBarcode.setText("")
        scanArmed = true
        scanStartedAt = SystemClock.elapsedRealtime()
        manualScanCountdown?.cancel()
        if (settings.autoFocus) focusCenter()

        if (settings.scanMode == ScanMode.MANUAL) {
            manualScanCountdown = object : CountDownTimer(settings.manualScanWindowSec * 1000L, 100) {
                override fun onTick(millisUntilFinished: Long) {
                    status.text = getString(
                        R.string.scanning_now_seconds,
                        String.format(Locale.US, "%.1f", millisUntilFinished / 1000.0)
                    )
                }
                override fun onFinish() {
                    if (destroyed.get()) return
                    scanArmed = false
                    status.text = getString(R.string.scan_window_finished)
                }
            }.start()
        } else {
            status.text = getString(R.string.scanning_now)
        }
    }

    private fun analyze(proxy: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (destroyed.get() || !scanArmed || now < cooldownUntil) {
            proxy.close()
            return
        }
        if (scanBusy && now - scanBusySince > WATCHDOG_MS) {
            scanBusy = false
            scanBusySince = 0L
            scanGeneration++
        }
        if (scanBusy) {
            proxy.close()
            return
        }
        if (settings.scanMode == ScanMode.MANUAL && now - scanStartedAt > settings.manualScanWindowSec * 1000L) {
            scanArmed = false
            runOnUiThread { status.text = getString(R.string.scan_window_finished) }
            proxy.close()
            return
        }
        if (now - lastFrameAt < analysisIntervalMs()) {
            proxy.close()
            return
        }
        lastFrameAt = now
        val roi = scanRectSnapshot
        if (roi.width() < 8 || roi.height() < 8 || preview.width <= 0 || preview.height <= 0) {
            proxy.close()
            return
        }

        scanBusy = true
        scanBusySince = now
        val generation = scanGeneration
        val localEngine = engine
        if (localEngine == null) {
            scanBusy = false
            proxy.close()
            return
        }

        try {
            localEngine.process(
                proxy = proxy,
                scanRectPreview = roi,
                previewWidth = preview.width,
                previewHeight = preview.height,
                mode = settings.recognitionMode,
                numberScanMode = settings.numberScanMode,
                minDigits = settings.minDigits,
                maxDigits = settings.maxDigits,
                onResult = { result ->
                    if (generation == scanGeneration && !destroyed.get()) handleResult(result)
                },
                onComplete = {
                    if (generation == scanGeneration) {
                        scanBusy = false
                        scanBusySince = 0L
                    }
                }
            )
        } catch (_: Exception) {
            runCatching { proxy.close() }
            if (generation == scanGeneration) {
                scanBusy = false
                scanBusySince = 0L
            }
        }
    }

    private fun handleResult(result: NumberRecognitionEngine.Result) {
        val number = Storage.normalizePacketNumber(result.number)
        val numberDisplay = result.numberDisplay.trim().ifBlank { number }
        val barcode = Storage.normalizeBarcode(result.barcode)
        if (number.isBlank() && barcode.isBlank()) return

        if (settings.scanMode == ScanMode.AUTO) {
            val now = SystemClock.elapsedRealtime()
            if (number.isNotBlank()) {
                if (number == stableNumber) stableNumberCount++ else {
                    stableNumber = number
                    stableNumberDisplay = numberDisplay
                    stableNumberCount = 1
                    stableNumberSince = now
                }
            }
            if (barcode.isNotBlank()) {
                if (barcode == stableBarcode) stableBarcodeCount++ else {
                    stableBarcode = barcode
                    stableBarcodeCount = 1
                    stableBarcodeSince = now
                }
            }

            val required = settings.stableReads.coerceIn(2, 5)
            val numberStable = stableNumber.isNotBlank() && stableNumberCount >= required
            val barcodeStable = stableBarcode.isNotBlank() && stableBarcodeCount >= required
            val pairWindowExceeded = settings.recognitionMode == RecognitionMode.BOTH &&
                numberStable && barcodeStable &&
                kotlin.math.abs(stableNumberSince - stableBarcodeSince) > BOTH_PAIR_WINDOW_MS
            if (pairWindowExceeded) {
                if (stableNumberSince < stableBarcodeSince) {
                    resetNumberStability()
                } else {
                    resetBarcodeStability()
                }
                return
            }

            val accepted = when (settings.recognitionMode) {
                RecognitionMode.NUMBER -> numberStable
                RecognitionMode.BARCODE -> barcodeStable
                RecognitionMode.BOTH -> numberStable && barcodeStable
            }

            if (!accepted) {
                runOnUiThread {
                    if (destroyed.get()) return@runOnUiThread
                    if (settings.recognitionMode == RecognitionMode.BOTH) {
                        when {
                            numberStable && !barcodeStable -> {
                                status.text = getString(R.string.waiting_for_barcode_only)
                                if (!announcedBarcodeRequired) {
                                    announcedBarcodeRequired = true
                                    feedback.speak(getString(R.string.voice_barcode_required), settings.voiceGuidance)
                                }
                            }
                            barcodeStable && !numberStable -> {
                                status.text = getString(R.string.waiting_for_number_only)
                                if (!announcedNumberRequired) {
                                    announcedNumberRequired = true
                                    feedback.speak(getString(R.string.voice_number_required), settings.voiceGuidance)
                                }
                            }
                            else -> status.text = getString(R.string.stabilizing_reading)
                        }
                    } else if (stableNumberCount > 0 || stableBarcodeCount > 0) {
                        status.text = getString(R.string.stabilizing_reading)
                    }
                }
                return
            }
        }

        runOnUiThread {
            if (destroyed.get()) return@runOnUiThread
            val finalNumber = if (settings.scanMode == ScanMode.AUTO && stableNumber.isNotBlank()) stableNumberDisplay.ifBlank { stableNumber } else numberDisplay
            val finalBarcode = if (settings.scanMode == ScanMode.AUTO && stableBarcode.isNotBlank()) stableBarcode else barcode
            mergePending(finalNumber, finalBarcode)
            refreshResultPanel()
            announceReadyResult()
            scanArmed = false
            manualScanCountdown?.cancel()
            status.text = getString(R.string.result_ready_to_check)
        }
    }

    private fun announceReadyResult() {
        if (announcedNumberFound) return
        announcedNumberFound = true
        val message = when (settings.recognitionMode) {
            RecognitionMode.NUMBER -> getString(R.string.voice_number_found)
            RecognitionMode.BARCODE -> getString(R.string.voice_barcode_found)
            RecognitionMode.BOTH -> getString(R.string.voice_both_found)
        }
        feedback.speak(message, settings.voiceGuidance)
    }

    private fun mergePending(number: String, barcode: String) {
        if (number.isNotBlank()) pendingNumber = number
        if (barcode.isNotBlank()) pendingBarcode = barcode
    }

    private fun refreshResultPanel() {
        resultPanel.visibility = View.VISIBLE
        detectedNumber.setText(pendingNumber)
        detectedBarcode.setText(pendingBarcode)
        detectedNumber.setSelection(detectedNumber.text.length)
        detectedBarcode.setSelection(detectedBarcode.text.length)
        numberState.text = if (pendingNumber.isBlank()) getString(R.string.not_found) else getString(R.string.found)
        barcodeState.text = if (pendingBarcode.isBlank()) getString(R.string.not_found) else getString(R.string.found)
        refreshConfirmState()
    }

    private fun refreshConfirmState() {
        val number = Storage.normalizePacketNumber(detectedNumber.text.toString())
        val barcode = Storage.normalizeBarcode(detectedBarcode.text.toString())
        val ready = when (settings.recognitionMode) {
            RecognitionMode.NUMBER -> number.length in settings.minDigits..settings.maxDigits
            RecognitionMode.BARCODE -> barcode.isNotBlank()
            RecognitionMode.BOTH -> number.length in settings.minDigits..settings.maxDigits && barcode.isNotBlank()
        }
        confirmButton.isEnabled = ready
    }

    private fun isReadyToConfirm(number: String, barcode: String): Boolean = when (settings.recognitionMode) {
        RecognitionMode.NUMBER -> number.length in settings.minDigits..settings.maxDigits
        RecognitionMode.BARCODE -> barcode.isNotBlank()
        RecognitionMode.BOTH -> number.length in settings.minDigits..settings.maxDigits && barcode.isNotBlank()
    }

    private fun savePending() {
        val number = Storage.normalizePacketNumber(detectedNumber.text.toString())
        val barcode = Storage.normalizeBarcode(detectedBarcode.text.toString())
        if (!isReadyToConfirm(number, barcode)) {
            refreshConfirmState()
            if (settings.sound) feedback.errorTone()
            toast(getString(R.string.complete_required_fields))
            return
        }

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                repo.addRecord(sessionId, number, barcode, settings.duplicateCheck)
            }
            if (result.isFailure) {
                if (settings.sound) feedback.errorTone()
                toast(result.exceptionOrNull()?.message ?: getString(R.string.save_error))
                return@launch
            }

            localCount++
            countView.text = getString(R.string.camera_count, localCount)
            resultPanel.visibility = View.GONE
            pendingNumber = ""
            pendingBarcode = ""
            resetStability()
            if (settings.haptic) preview.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            if (settings.sound) feedback.successTone()
            feedback.speak(getString(R.string.voice_saved), settings.voiceGuidance)

            if (settings.scanMode == ScanMode.AUTO) {
                status.text = getString(R.string.saved_continue)
                cooldownUntil = SystemClock.elapsedRealtime() + AUTO_COOLDOWN_MS
                scanArmed = true
                announcedNumberFound = false
            } else {
                status.text = getString(R.string.saved_press_scan)
                scanArmed = false
            }
        }
    }

    private fun clearPendingAndRetry() {
        pendingNumber = ""
        pendingBarcode = ""
        detectedNumber.setText("")
        detectedBarcode.setText("")
        resultPanel.visibility = View.GONE
        manualScanCountdown?.cancel()
        startScanCycle(true)
    }

    private fun resetNumberStability() {
        stableNumber = ""
        stableNumberDisplay = ""
        stableNumberCount = 0
        stableNumberSince = 0L
    }

    private fun resetBarcodeStability() {
        stableBarcode = ""
        stableBarcodeCount = 0
        stableBarcodeSince = 0L
    }

    private fun resetStability() {
        resetNumberStability()
        resetBarcodeStability()
    }

    override fun onDestroy() {
        destroyed.set(true)
        scanGeneration++
        manualScanCountdown?.cancel()
        runCatching { cameraProvider?.unbindAll() }
        runCatching { analyzerShutdown() }
        runCatching { engine?.close() }
        runCatching { feedback.release() }
        super.onDestroy()
    }

    private fun analyzerShutdown() {
        // Do not block the main thread while ML Kit finishes an in-flight frame.
        executor.shutdownNow()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

