package org.fcitx.fcitx5.android.plugin.quicksend.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.plugin.quicksend.R
import org.fcitx.fcitx5.android.plugin.quicksend.data.db.QuickSendEntry

class QuickSendAdapter(
    private val onSend: (QuickSendEntry) -> Unit,
    private val onEdit: (QuickSendEntry) -> Unit,
    private val onDelete: (QuickSendEntry) -> Unit
) : RecyclerView.Adapter<QuickSendAdapter.VH>() {

    private val items = mutableListOf<QuickSendEntry>()

    fun submit(list: List<QuickSendEntry>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_entry, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val content: TextView = view.findViewById(R.id.entry_content)
        private val mode: TextView = view.findViewById(R.id.entry_mode)
        private val useCount: TextView = view.findViewById(R.id.entry_use_count)
        private val edit: View = view.findViewById(R.id.entry_edit)
        private val delete: View = view.findViewById(R.id.entry_delete)

        fun bind(entry: QuickSendEntry) {
            content.text = SegmentFormatter.displayLabel(entry)
            val isCombination = entry.sendMode == QuickSendEntry.MODE_COMBINATION
            mode.text = if (isCombination) "⊕" else "→"
            mode.contentDescription = itemView.context.getString(
                if (isCombination) R.string.mode_combination_desc else R.string.mode_sequence_desc
            )
            useCount.text = "×${entry.useCount}"
            itemView.setOnClickListener { onSend(entry) }
            edit.setOnClickListener { onEdit(entry) }
            delete.setOnClickListener { onDelete(entry) }
        }
    }
}
