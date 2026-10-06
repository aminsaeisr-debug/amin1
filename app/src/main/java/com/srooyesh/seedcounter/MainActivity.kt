package com.srooyesh.seedcounter

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.NumberPicker
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.slider.Slider
import com.google.android.material.materialswitch.MaterialSwitch
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    companion object {
        const val ACTION_QUICK_SCAN = "com.srooyesh.seedcounter.action.QUICK_SCAN"
    }

    private val repo by lazy { SeedRepository(this) }
    private var activeSession: SeedSession? = null
    private val displayRows = mutableListOf<HistoryRow>()
    private lateinit var adapter: HistoryAdapter
    private var refreshJob: Job? = null

    private lateinit var today: TextView
    private lateinit var status: TextView
    private lateinit var active: TextView
    private lateinit var count: TextView
    private lateinit var historySummary: TextView
    private lateinit var historySearch: EditText
    private lateinit var historySearchCount: TextView
    private lateinit var variety: EditText
    private lateinit var customer: EditText
    private lateinit var manual: EditText
    private lateinit var settingsCard: View
    private lateinit var scanMode: Spinner
    private lateinit var recognitionMode: Spinner
    private lateinit var numberScanMode: Spinner
    private lateinit var scanQuality: Spinner
    private lateinit var scanWindow: Spinner
    private lateinit var stableReads: Spinner
    private lateinit var minDigits: NumberPicker
    private lateinit var maxDigits: NumberPicker
    private lateinit var defaultFlash: Spinner
    private lateinit var autoZoom: MaterialSwitch
    private lateinit var autoFocus: MaterialSwitch
    private lateinit var duplicateCheck: MaterialSwitch
    private lateinit var haptic: MaterialSwitch
    private lateinit var sound: MaterialSwitch
    private lateinit var voiceGuidance: MaterialSwitch
    private lateinit var frameWidth: Slider
    private lateinit var frameHeight: Slider
    private lateinit var frameWidthValue: TextView
    private lateinit var frameHeightValue: TextView
    private lateinit var dateOutput: RadioGroup
    private lateinit var outRow: CheckBox
    private lateinit var outVariety: CheckBox
    private lateinit var outCustomer: CheckBox
    private lateinit var outNumber: CheckBox
    private lateinit var outBarcode: CheckBox
    private lateinit var outTime: CheckBox
    private lateinit var outSession: CheckBox
    private var syncingSettings = false
    private var quickScanIntentConsumed = false
    private val settingsStore by lazy { SettingsStore(this) }

    private val openBackup = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) restoreBackup(uri) }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<android.widget.TextView>(R.id.footerVersion).text =
            getString(R.string.footer_version, BuildConfig.VERSION_NAME)

        today = findViewById(R.id.today)
        status = findViewById(R.id.status)
        active = findViewById(R.id.active)
        count = findViewById(R.id.count)
        historySummary = findViewById(R.id.historySummary)
        historySearch = findViewById(R.id.historySearch)
        historySearchCount = findViewById(R.id.historySearchCount)
        variety = findViewById(R.id.variety)
        customer = findViewById(R.id.customer)
        manual = findViewById(R.id.manual)

        val list = findViewById<RecyclerView>(R.id.list)
        adapter = HistoryAdapter(displayRows)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        findViewById<Button>(R.id.start).setOnClickListener { startSession() }
        findViewById<Button>(R.id.add).setOnClickListener {
            addManualNumber(manual.text.toString())
            manual.setText("")
        }
        manual.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_NEXT) {
                addManualNumber(manual.text.toString())
                manual.setText("")
                true
            } else false
        }
        findViewById<Button>(R.id.scan).setOnClickListener { openCamera() }
        findViewById<Button>(R.id.finish).setOnClickListener { finishSession() }
        findViewById<Button>(R.id.previewExcel).setOnClickListener { openExcelPreview() }
        findViewById<Button>(R.id.export).setOnClickListener { exportExcel() }
        findViewById<Button>(R.id.backup).setOnClickListener { backupJson() }
        findViewById<View>(R.id.settings).setOnClickListener {
            val scroll = findViewById<androidx.core.widget.NestedScrollView>(R.id.mainScroll)
            val card = findViewById<View>(R.id.settingsCard)
            scroll.post {
                val target = (card.top - (12 * resources.displayMetrics.density).roundToInt()).coerceAtLeast(0)
                scroll.smoothScrollTo(0, target)
                card.requestFocus()
            }
        }
        findViewById<Button>(R.id.delete).setOnClickListener { confirmDeleteAll() }
        historySearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { refreshScreen() }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        historySearch.setOnEditorActionListener { _, _, _ -> true }

        setupEmbeddedSettings()
        refreshScreen()
        handleQuickScanIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        quickScanIntentConsumed = false
        handleQuickScanIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshScreen()
    }

    private fun setupEmbeddedSettings() {
        settingsCard = findViewById(R.id.settingsCard)
        scanMode = findViewById(R.id.scanMode)
        recognitionMode = findViewById(R.id.recognitionMode)
        numberScanMode = findViewById(R.id.numberScanMode)
        scanQuality = findViewById(R.id.scanQuality)
        scanWindow = findViewById(R.id.scanWindow)
        stableReads = findViewById(R.id.stableReads)
        minDigits = findViewById(R.id.minDigits)
        maxDigits = findViewById(R.id.maxDigits)
        defaultFlash = findViewById(R.id.defaultFlash)
        autoZoom = findViewById(R.id.autoZoom)
        autoFocus = findViewById(R.id.autoFocus)
        duplicateCheck = findViewById(R.id.duplicateCheck)
        haptic = findViewById(R.id.haptic)
        sound = findViewById(R.id.sound)
        voiceGuidance = findViewById(R.id.voiceGuidance)
        frameWidth = findViewById(R.id.frameWidth)
        frameHeight = findViewById(R.id.frameHeight)
        frameWidthValue = findViewById(R.id.frameWidthValue)
        frameHeightValue = findViewById(R.id.frameHeightValue)
        dateOutput = findViewById(R.id.dateOutput)
        outRow = findViewById(R.id.outRow)
        outVariety = findViewById(R.id.outVariety)
        outCustomer = findViewById(R.id.outCustomer)
        outNumber = findViewById(R.id.outNumber)
        outBarcode = findViewById(R.id.outBarcode)
        outTime = findViewById(R.id.outTime)
        outSession = findViewById(R.id.outSession)

        configureSettingsSpinners()
        configureSettingsNumberPickers()
        configureSettingsSliders()
        configureSettingsListeners()
        populateEmbeddedSettings(settingsStore.get())

        findViewById<Button>(R.id.resetFrame).setOnClickListener {
            applySettings(settingsStore.get().copy(scanFrameWidthPct = 84, scanFrameHeightPct = 28))
            populateEmbeddedSettings(settingsStore.get())
            toast(getString(R.string.frame_reset))
        }
        findViewById<Button>(R.id.resetSettings).setOnClickListener {
            applySettings(AppSettings())
            populateEmbeddedSettings(settingsStore.get())
            toast(getString(R.string.settings_reset_saved))
        }
        findViewById<Button>(R.id.backupNow).setOnClickListener {
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { BackupExporter(this@MainActivity).create() }
                if (result.isSuccess) startActivity(result.getOrThrow())
                else toast(result.exceptionOrNull()?.message ?: getString(R.string.backup_error))
            }
        }
        findViewById<Button>(R.id.restoreBackup).setOnClickListener {
            openBackup.launch(arrayOf("application/json", "text/json", "text/plain"))
        }
    }

    private fun configureSettingsSpinners() {
        setSpinner(scanMode, arrayOf(getString(R.string.scan_mode_manual), getString(R.string.scan_mode_auto)))
        setSpinner(recognitionMode, arrayOf(getString(R.string.recognition_number), getString(R.string.recognition_barcode), getString(R.string.recognition_both)))
        setSpinner(numberScanMode, arrayOf(getString(R.string.number_scan_simple), getString(R.string.number_scan_smart)))
        setSpinner(scanQuality, arrayOf(getString(R.string.quality_standard), getString(R.string.quality_precise), getString(R.string.quality_max)))
        setSpinner(defaultFlash, arrayOf(getString(R.string.flash_off), getString(R.string.flash_on)))
        setSpinner(scanWindow, intArrayOf(3,5,8,10).map { getString(R.string.scan_window_value, it) }.toTypedArray())
        setSpinner(stableReads, intArrayOf(2,3,4,5).map { getString(R.string.stable_reads_value, it) }.toTypedArray())

        scanMode.setOnItemSelectedListener(simpleSpinnerListener { index ->
            applySettings(settingsStore.get().copy(scanMode = if (index == 1) ScanMode.AUTO else ScanMode.MANUAL))
        })
        recognitionMode.setOnItemSelectedListener(simpleSpinnerListener { index ->
            applySettings(settingsStore.get().copy(recognitionMode = when (index) {
                1 -> RecognitionMode.BARCODE
                2 -> RecognitionMode.BOTH
                else -> RecognitionMode.NUMBER
            }))
        })
        numberScanMode.setOnItemSelectedListener(simpleSpinnerListener { index ->
            applySettings(settingsStore.get().copy(numberScanMode = if (index == 1) NumberScanMode.SMART else NumberScanMode.SIMPLE))
        })
        scanQuality.setOnItemSelectedListener(simpleSpinnerListener { index ->
            applySettings(settingsStore.get().copy(scanQuality = index.coerceIn(0,2)))
        })
        defaultFlash.setOnItemSelectedListener(simpleSpinnerListener { index ->
            applySettings(settingsStore.get().copy(flashMode = if (index == 1) 1 else 0))
        })
        scanWindow.setOnItemSelectedListener(simpleSpinnerListener { index ->
            val values = intArrayOf(3,5,8,10)
            applySettings(settingsStore.get().copy(manualScanWindowSec = values[index.coerceIn(0, values.lastIndex)]))
        })
        stableReads.setOnItemSelectedListener(simpleSpinnerListener { index ->
            val values = intArrayOf(2,3,4,5)
            applySettings(settingsStore.get().copy(stableReads = values[index.coerceIn(0, values.lastIndex)]))
        })
    }

    private fun configureSettingsNumberPickers() {
        minDigits.minValue = 2
        minDigits.maxValue = 16
        maxDigits.minValue = 2
        maxDigits.maxValue = 16
        minDigits.setOnValueChangedListener { _, _, newValue ->
            if (syncingSettings) return@setOnValueChangedListener
            val max = maxDigits.value.coerceAtLeast(newValue)
            if (maxDigits.value != max) maxDigits.value = max
            applySettings(settingsStore.get().copy(minDigits = newValue, maxDigits = max))
        }
        maxDigits.setOnValueChangedListener { _, _, newValue ->
            if (syncingSettings) return@setOnValueChangedListener
            val min = minDigits.value.coerceAtMost(newValue)
            if (minDigits.value != min) minDigits.value = min
            applySettings(settingsStore.get().copy(minDigits = min, maxDigits = newValue))
        }
    }

    private fun configureSettingsSliders() {
        frameWidth.valueFrom = 56f
        frameWidth.valueTo = 96f
        frameWidth.stepSize = 1f
        frameHeight.valueFrom = 12f
        frameHeight.valueTo = 58f
        frameHeight.stepSize = 1f
        frameWidth.addOnChangeListener { _, value, fromUser ->
            frameWidthValue.text = getString(R.string.frame_width_value, value.roundToInt())
            if (fromUser && !syncingSettings) applySettings(settingsStore.get().copy(scanFrameWidthPct = value.roundToInt()))
        }
        frameHeight.addOnChangeListener { _, value, fromUser ->
            frameHeightValue.text = getString(R.string.frame_height_value, value.roundToInt())
            if (fromUser && !syncingSettings) applySettings(settingsStore.get().copy(scanFrameHeightPct = value.roundToInt()))
        }
    }

    private fun configureSettingsListeners() {
        fun MaterialSwitch.bind(save: (Boolean) -> AppSettings) {
            setOnCheckedChangeListener { _, checked -> if (!syncingSettings) applySettings(save(checked)) }
        }
        autoZoom.bind { settingsStore.get().copy(smartZoom = it) }
        autoFocus.bind { settingsStore.get().copy(autoFocus = it) }
        duplicateCheck.bind { settingsStore.get().copy(duplicateCheck = it) }
        haptic.bind { settingsStore.get().copy(haptic = it) }
        sound.bind { settingsStore.get().copy(sound = it) }
        voiceGuidance.bind { settingsStore.get().copy(voiceGuidance = it) }
        dateOutput.setOnCheckedChangeListener { _, checkedId ->
            if (!syncingSettings) {
                applySettings(settingsStore.get().copy(dateOutput = when (checkedId) {
                    R.id.dateJalali -> "jalali"
                    R.id.dateGregorian -> "gregorian"
                    else -> "both"
                }))
            }
        }
        val checks = listOf(
            outRow to { v: Boolean -> settingsStore.get().copy(outRow = v) },
            outVariety to { v: Boolean -> settingsStore.get().copy(outVariety = v) },
            outCustomer to { v: Boolean -> settingsStore.get().copy(outCustomer = v) },
            outNumber to { v: Boolean -> settingsStore.get().copy(outNumber = v) },
            outBarcode to { v: Boolean -> settingsStore.get().copy(outBarcode = v) },
            outTime to { v: Boolean -> settingsStore.get().copy(outTime = v) },
            outSession to { v: Boolean -> settingsStore.get().copy(outSession = v) }
        )
        checks.forEach { (check, make) -> check.setOnCheckedChangeListener { _, checked -> if (!syncingSettings) applySettings(make(checked)) } }
    }

    private fun populateEmbeddedSettings(s: AppSettings) {
        val settings = s.sanitized()
        syncingSettings = true
        scanMode.setSelection(if (settings.scanMode == ScanMode.AUTO) 1 else 0, false)
        recognitionMode.setSelection(when (settings.recognitionMode) { RecognitionMode.BARCODE -> 1; RecognitionMode.BOTH -> 2; else -> 0 }, false)
        numberScanMode.setSelection(if (settings.numberScanMode == NumberScanMode.SMART) 1 else 0, false)
        scanQuality.setSelection(settings.scanQuality, false)
        defaultFlash.setSelection(if (settings.flashMode == 1) 1 else 0, false)
        scanWindow.setSelection(intArrayOf(3,5,8,10).indexOf(settings.manualScanWindowSec).coerceAtLeast(0), false)
        stableReads.setSelection(intArrayOf(2,3,4,5).indexOf(settings.stableReads).coerceAtLeast(0), false)
        minDigits.value = settings.minDigits
        maxDigits.value = settings.maxDigits
        autoZoom.isChecked = settings.smartZoom
        autoFocus.isChecked = settings.autoFocus
        duplicateCheck.isChecked = settings.duplicateCheck
        haptic.isChecked = settings.haptic
        sound.isChecked = settings.sound
        voiceGuidance.isChecked = settings.voiceGuidance
        frameWidth.value = settings.scanFrameWidthPct.toFloat()
        frameHeight.value = settings.scanFrameHeightPct.toFloat()
        frameWidthValue.text = getString(R.string.frame_width_value, settings.scanFrameWidthPct)
        frameHeightValue.text = getString(R.string.frame_height_value, settings.scanFrameHeightPct)
        when (settings.dateOutput) {
            "jalali" -> dateOutput.check(R.id.dateJalali)
            "gregorian" -> dateOutput.check(R.id.dateGregorian)
            else -> dateOutput.check(R.id.dateBoth)
        }
        outRow.isChecked = settings.outRow
        outVariety.isChecked = settings.outVariety
        outCustomer.isChecked = settings.outCustomer
        outNumber.isChecked = settings.outNumber
        outBarcode.isChecked = settings.outBarcode
        outTime.isChecked = settings.outTime
        outSession.isChecked = settings.outSession
        syncingSettings = false
    }

    private fun applySettings(newSettings: AppSettings) {
        val sanitized = newSettings.sanitized()
        settingsStore.save(sanitized)
        // The camera reads SettingsStore on resume, so changes made here take effect on the next camera open/resume.
    }

    private fun setSpinner(spinner: Spinner, values: Array<String>) {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, values)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
    }

    private fun simpleSpinnerListener(onSelected: (Int) -> Unit) = object : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) { if (!syncingSettings) onSelected(position) }
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
    }

    private fun restoreBackup(uri: Uri) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val json = contentResolver.openInputStream(uri)?.use { input ->
                        BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
                    } ?: error(getString(R.string.restore_error))
                    SeedRepository(this@MainActivity).importBackupJson(json).getOrThrow()
                }
            }
            if (result.isSuccess) {
                populateEmbeddedSettings(settingsStore.get())
                toast(getString(R.string.restore_done, result.getOrThrow()))
                refreshScreen()
            } else toast(result.exceptionOrNull()?.message ?: getString(R.string.restore_error))
        }
    }

    private fun handleQuickScanIntent(intent: Intent?) {
        if (intent?.action != ACTION_QUICK_SCAN || quickScanIntentConsumed) return
        quickScanIntentConsumed = true
        lifecycleScope.launch {
            val session = withContext(Dispatchers.IO) { repo.getActiveSession() }
            if (session == null) {
                toast(getString(R.string.quick_scan_no_active_session))
                return@launch
            }
            activeSession = session
            startActivity(Intent(this@MainActivity, CameraActivity::class.java).apply {
                putExtra(CameraActivity.EXTRA_SESSION_ID, session.id)
            })
        }
    }

    private fun refreshScreen() {
        today.text = getString(R.string.today_prefix, PersianDate.today())
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            val session = withContext(Dispatchers.IO) { repo.getActiveSession() }
            val todaySummary = withContext(Dispatchers.IO) { repo.getTodaySummary() }
            historySummary.text = getString(R.string.history_summary, todaySummary.first, todaySummary.second)
            val previousSessionId = activeSession?.id
            activeSession = session
            val query = historySearch.text.toString().trim()

            if (session != null) {
                // Populate inputs on first load or when the active session actually changes.
                // Ordinary refreshes must not overwrite text the user is currently editing.
                if (previousSessionId != session.id) {
                    variety.setText(session.variety)
                    customer.setText(session.customer)
                }
                val sessionDate = formatSessionDate(session.dateKey)
                active.text = getString(
                    R.string.active_session_label,
                    session.variety,
                    if (session.customer.isBlank()) getString(R.string.no_customer) else session.customer,
                    sessionDate
                )
                val currentCount = withContext(Dispatchers.IO) { repo.getRecordCount(session.id) }
                count.text = getString(R.string.active_count_simple, currentCount)
                status.text = if (session.dateKey == Storage.todayKey()) {
                    getString(R.string.status_active)
                } else {
                    getString(R.string.status_active_old_session, sessionDate)
                }
                findViewById<Button>(R.id.start).isEnabled = false
                findViewById<Button>(R.id.finish).isEnabled = true
                findViewById<Button>(R.id.scan).isEnabled = true
                findViewById<Button>(R.id.add).isEnabled = true

                if (query.isBlank()) {
                    val loaded = withContext(Dispatchers.IO) { repo.getRecords(session.id) }
                    displayRows.clear()
                    displayRows.addAll(
                        loaded.map { record ->
                            HistoryRow(
                                id = record.id,
                                sessionId = record.sessionId,
                                variety = session.variety,
                                customer = session.customer,
                                number = record.number,
                                barcode = record.barcode,
                                dateGregorian = record.dateGregorian,
                                dateJalali = record.dateJalali,
                                time = record.time
                            )
                        }
                    )
                    historySearchCount.text = getString(R.string.history_current_count, loaded.size)
                } else {
                    showSearchResults(query)
                }
            } else {
                active.text = getString(R.string.no_active_variety)
                count.text = getString(R.string.active_count_simple, 0)
                status.text = getString(R.string.status_initial)
                findViewById<Button>(R.id.start).isEnabled = true
                findViewById<Button>(R.id.finish).isEnabled = false
                findViewById<Button>(R.id.scan).isEnabled = false
                findViewById<Button>(R.id.add).isEnabled = false
                if (query.isBlank()) {
                    displayRows.clear()
                    historySearchCount.text = getString(R.string.history_current_count, 0)
                } else {
                    showSearchResults(query)
                }
            }
            adapter.notifyDataSetChanged()
        }
    }

    private fun formatSessionDate(dateKey: String): String {
        val parts = dateKey.split('-')
        if (parts.size == 3) {
            val y = parts[0].toIntOrNull()
            val m = parts[1].toIntOrNull()
            val d = parts[2].toIntOrNull()
            if (y != null && m != null && d != null) {
                return runCatching {
                    val (jy, jm, jd) = PersianDate.gregorianToJalali(y, m, d)
                    "%04d/%02d/%02d".format(jy, jm, jd)
                }.getOrElse { dateKey }
            }
        }
        return dateKey
    }

    private suspend fun showSearchResults(query: String) {
        val found = withContext(Dispatchers.IO) { repo.searchHistory(query) }
        displayRows.clear()
        displayRows.addAll(found)
        historySearchCount.text = getString(R.string.history_search_count, found.size)
        status.text = getString(R.string.history_search_status, found.size)
    }

    private fun startSession() {
        val v = variety.text.toString().trim().replace("|", " ").replace("\n", " ").replace("\r", " ")
        val c = customer.text.toString().trim().replace("|", " ").replace("\n", " ").replace("\r", " ")
        if (v.isBlank()) {
            toast(getString(R.string.variety_required))
            return
        }
        if (v.length > 100 || c.length > 120) {
            toast(getString(R.string.text_too_long))
            return
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { repo.startSession(v, c) }
            if (result.isFailure) {
                toast(result.exceptionOrNull()?.message ?: getString(R.string.active_session_exists))
                return@launch
            }
            refreshScreen()
            toast(getString(R.string.session_started, v))
        }
    }

    private fun openCamera() {
        lifecycleScope.launch {
            val session = withContext(Dispatchers.IO) { repo.getActiveSession() } ?: run {
                toast(getString(R.string.start_variety_required))
                return@launch
            }
            activeSession = session
            startActivity(Intent(this@MainActivity, CameraActivity::class.java).apply {
                putExtra(CameraActivity.EXTRA_SESSION_ID, session.id)
            })
        }
    }

    private fun addManualNumber(raw: String) {
        val session = activeSession ?: run {
            toast(getString(R.string.start_variety_required))
            return
        }
        val number = Storage.normalizePacketNumber(raw)
        val s = SettingsStore(this).get()
        if (number.length !in s.minDigits..s.maxDigits) {
            toast(getString(R.string.invalid_packet_number_range, s.minDigits, s.maxDigits))
            return
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                repo.addRecord(session.id, number, "", SettingsStore(this@MainActivity).get().duplicateCheck)
            }
            if (result.isFailure) {
                toast(result.exceptionOrNull()?.message ?: getString(R.string.save_error))
                return@launch
            }
            refreshScreen()
        }
    }

    private fun finishSession() {
        val session = activeSession ?: run {
            toast(getString(R.string.finish_none))
            return
        }
        lifecycleScope.launch {
            val total = withContext(Dispatchers.IO) { repo.getRecords(session.id).size }
            AlertDialog.Builder(this@MainActivity)
                .setTitle(getString(R.string.finish_title))
                .setMessage(
                    getString(
                        R.string.finish_message_with_customer,
                        session.variety,
                        session.customer.ifBlank { getString(R.string.no_customer) },
                        total
                    )
                )
                .setNegativeButton(getString(R.string.cancel), null)
                .setPositiveButton(getString(R.string.finish_variety)) { _, _ ->
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) { repo.finishSession(session.id) }
                        refreshScreen()
                        toast(getString(R.string.finish_done))
                    }
                }
                .show()
        }
    }

    private fun openExcelPreview() {
        lifecycleScope.launch {
            val hasRows = withContext(Dispatchers.IO) { repo.getAllRows().isNotEmpty() }
            if (!hasRows) {
                toast(getString(R.string.excel_empty))
                return@launch
            }
            startActivity(Intent(this@MainActivity, ExportPreviewActivity::class.java))
        }
    }

    private fun exportExcel() {
        lifecycleScope.launch {
            status.text = getString(R.string.exporting_excel)
            val result = withContext(Dispatchers.IO) { ExcelExporter(this@MainActivity).export() }
            if (result.isSuccess) {
                startActivity(result.getOrThrow())
                status.text = getString(R.string.excel_ready)
            } else {
                status.text = getString(R.string.excel_error)
                toast(result.exceptionOrNull()?.message ?: getString(R.string.excel_error))
            }
        }
    }

    private fun backupJson() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { BackupExporter(this@MainActivity).create() }
            if (result.isSuccess) startActivity(result.getOrThrow())
            else toast(result.exceptionOrNull()?.message ?: getString(R.string.backup_error))
        }
    }

    private fun confirmDeleteAll() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_title))
            .setMessage(getString(R.string.delete_message))
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.delete_confirm)) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { repo.deleteAll() }
                    refreshScreen()
                    toast(getString(R.string.delete_done))
                }
            }
            .show()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
