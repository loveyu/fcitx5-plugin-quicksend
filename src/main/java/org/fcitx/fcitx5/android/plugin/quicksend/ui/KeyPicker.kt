package org.fcitx.fcitx5.android.plugin.quicksend.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import androidx.core.content.ContextCompat
import org.fcitx.fcitx5.android.plugin.quicksend.R
import org.fcitx.fcitx5.android.plugin.quicksend.data.KeyNameMapping

/**
 * 特殊键分组选择对话框。分组标题不可选，键名点击回调。
 */
object KeyPicker {

    private data class Row(val isHeader: Boolean, val text: String)

    fun show(context: Context, onPick: (String) -> Unit) {
        val rows = buildList {
            for (group in KeyNameMapping.groups) {
                add(Row(true, "【${group.title}】"))
                group.keys.forEach { add(Row(false, it)) }
            }
        }

        val adapter = object : BaseAdapter() {
            override fun getCount(): Int = rows.size
            override fun getItem(position: Int): Any = rows[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun isEnabled(position: Int): Boolean = !rows[position].isHeader

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val row = rows[position]
                val tv = (convertView as? TextView) ?: TextView(context).apply {
                    val dp = resources.displayMetrics.density
                    setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
                }
                tv.text = row.text
                if (row.isHeader) {
                    tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                    tv.setTypeface(null, Typeface.BOLD)
                    tv.setTextColor(ContextCompat.getColor(context, R.color.qs_field_label))
                } else {
                    tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
                    tv.setTypeface(null, Typeface.NORMAL)
                    tv.setTextColor(ContextCompat.getColor(context, R.color.qs_text_primary))
                }
                return tv
            }
        }

        AlertDialog.Builder(context)
            .setAdapter(adapter) { _, which ->
                val row = rows[which]
                if (!row.isHeader) onPick(row.text)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
