package com.srooyesh.seedcounter

import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ExportPreviewAdapter(
    private val settings: AppSettings,
    private val rows: List<ExportRow>,
    private val maxRows: Int = 500
) : RecyclerView.Adapter<ExportPreviewAdapter.RowHolder>() {
    private val columnCount = ExportTableModel.columns(settings).size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder = RowHolder(
        LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = android.view.View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(Color.WHITE)
        },
        columnCount
    )

    override fun onBindViewHolder(holder: RowHolder, position: Int) {
        holder.bind(ExportTableModel.values(rows[position], position + 1, settings))
    }

    override fun getItemCount(): Int = rows.size.coerceAtMost(maxRows)

    class RowHolder(
        private val row: LinearLayout,
        columnCount: Int
    ) : RecyclerView.ViewHolder(row) {
        private val cells = List(columnCount.coerceAtLeast(1)) {
            TextView(row.context).apply {
                textSize = 13f
                setTextColor(Color.rgb(32, 43, 45))
                gravity = Gravity.CENTER
                setPadding(20, 14, 20, 14)
                layoutParams = LinearLayout.LayoutParams(170, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        }

        init {
            cells.forEach(row::addView)
        }

        fun bind(values: List<String>) {
            cells.forEachIndexed { index, cell ->
                if (index < values.size) {
                    cell.text = values[index]
                    cell.visibility = android.view.View.VISIBLE
                } else {
                    cell.text = ""
                    cell.visibility = android.view.View.GONE
                }
            }
        }
    }
}
