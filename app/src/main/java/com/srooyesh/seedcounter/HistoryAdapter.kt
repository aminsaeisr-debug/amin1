package com.srooyesh.seedcounter

import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class HistoryAdapter(private val items: List<HistoryRow>) : RecyclerView.Adapter<HistoryAdapter.Holder>() {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = TextView(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT
            )
            setPadding(16, 14, 16, 14)
            textSize = 15f
        }
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        val first = mutableListOf("${position + 1}.", item.variety)
        if (item.customer.isNotBlank()) first.add("مشتری: ${item.customer}")
        if (item.number.isNotBlank()) first.add("شماره: ${item.number}")
        if (item.barcode.isNotBlank()) first.add("بارکد: ${item.barcode}")
        val second = listOf(item.dateJalali.takeIf(String::isNotBlank), item.dateGregorian.takeIf(String::isNotBlank), item.time.takeIf(String::isNotBlank))
            .filterNotNull()
            .joinToString(" | ")
        holder.text.text = if (second.isBlank()) first.joinToString("  |  ") else first.joinToString("  |  ") + "\n" + second
    }

    override fun getItemCount(): Int = items.size

    class Holder(val text: TextView) : RecyclerView.ViewHolder(text)
}
