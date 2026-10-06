package com.srooyesh.seedcounter

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** In-app spreadsheet preview; the same model feeds Excel export. */
class ExportPreviewActivity : AppCompatActivity() {
    private val repo by lazy { SeedRepository(this) }
    private val settingsStore by lazy { SettingsStore(this) }
    private lateinit var countText: TextView
    private lateinit var recycler: RecyclerView
    private var rows: List<ExportRow> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_export_preview)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        countText = findViewById(R.id.previewCount)
        recycler = findViewById(R.id.previewList)
        recycler.layoutManager = LinearLayoutManager(this)

        findViewById<Button>(R.id.openExcel).setOnClickListener { openExcelFile() }
        findViewById<Button>(R.id.shareExcel).setOnClickListener { shareExcelFile() }
        loadRows()
    }

    private fun loadRows() {
        lifecycleScope.launch {
            rows = withContext(Dispatchers.IO) { repo.getAllRows() }
            countText.text = getString(R.string.preview_rows, rows.size, rows.size.coerceAtMost(500))
            val settings = settingsStore.get()
            renderHeader(settings)
            recycler.adapter = ExportPreviewAdapter(settings = settings, rows = rows)
        }
    }

    private fun renderHeader(settings: AppSettings) {
        val header = findViewById<LinearLayout>(R.id.previewHeader)
        header.removeAllViews()
        ExportTableModel.columns(settings).forEach { title ->
            header.addView(TextView(this).apply {
                text = title
                textSize = 13f
                setTextColor(0xFF0D4F5D.toInt())
                gravity = android.view.Gravity.CENTER
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(20, 14, 20, 14)
                layoutParams = LinearLayout.LayoutParams(170, LinearLayout.LayoutParams.WRAP_CONTENT)
            })
        }
    }

    private fun openExcelFile() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { ExcelExporter(this@ExportPreviewActivity).createReportFile(rows) }
            }
            if (result.isFailure) {
                toast(result.exceptionOrNull()?.message ?: getString(R.string.excel_error))
                return@launch
            }
            val file = result.getOrNull() ?: run {
                toast(getString(R.string.excel_error))
                return@launch
            }
            val uri = FileProvider.getUriForFile(
                this@ExportPreviewActivity,
                "${packageName}.provider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, ExcelExporter.MIME_XLSX)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (packageManager.queryIntentActivities(intent, 0).isEmpty()) {
                toast(getString(R.string.no_excel_app))
                return@launch
            }
            startActivity(Intent.createChooser(intent, getString(R.string.open_excel)))
        }
    }

    private fun shareExcelFile() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { ExcelExporter(this@ExportPreviewActivity).createShareIntent(rows) }
            }
            if (result.isSuccess) {
                result.getOrNull()?.let { startActivity(it) }
            } else {
                toast(result.exceptionOrNull()?.message ?: getString(R.string.excel_error))
            }
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
