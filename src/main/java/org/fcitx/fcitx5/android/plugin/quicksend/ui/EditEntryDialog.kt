package org.fcitx.fcitx5.android.plugin.quicksend.ui

import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.plugin.quicksend.R
import org.fcitx.fcitx5.android.plugin.quicksend.data.ContentSegment
import org.fcitx.fcitx5.android.plugin.quicksend.data.QuickSendManager
import org.fcitx.fcitx5.android.plugin.quicksend.data.db.QuickSendEntry

/**
 * 条目编辑对话框：label、内容段（FlowLayout）、特殊键选择、发送模式、使用次数。
 *
 * 新建时 [entry] = null；编辑时传入已有条目。
 */
object EditEntryDialog {

    fun show(context: Context, entry: QuickSendEntry?) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_edit_entry, null)
        val labelInput = view.findViewById<EditText>(R.id.label_input)
        val container = view.findViewById<ViewGroup>(R.id.segments_container)
        val textInput = view.findViewById<EditText>(R.id.text_input)
        val modeGroup = view.findViewById<RadioGroup>(R.id.mode_group)
        val useCountInput = view.findViewById<EditText>(R.id.use_count)

        val segments: MutableList<ContentSegment> =
            entry?.segments?.toMutableList() ?: mutableListOf()

        if (entry != null) {
            labelInput.setText(entry.label)
            useCountInput.setText(entry.useCount.toString())
            modeGroup.check(
                if (entry.sendMode == QuickSendEntry.MODE_SEQUENCE) R.id.mode_sequence
                else R.id.mode_combination
            )
        }

        fun refresh() {
            container.removeAllViews()
            segments.forEachIndexed { index, seg ->
                val chip = LayoutInflater.from(context)
                    .inflate(R.layout.item_segment_chip, container, false)
                val text = chip.findViewById<TextView>(R.id.segment_text)
                val remove = chip.findViewById<View>(R.id.segment_remove)
                if (seg.type == ContentSegment.TYPE_KEY) {
                    text.text = "[${seg.content}]"
                    text.setBackgroundResource(R.drawable.bg_key_chip)
                } else {
                    text.text = seg.content
                    text.background = null
                }
                remove.setOnClickListener {
                    segments.removeAt(index)
                    refresh()
                }
                container.addView(chip)
            }
        }
        refresh()

        view.findViewById<View>(R.id.add_text).setOnClickListener {
            val s = textInput.text?.toString().orEmpty()
            if (s.isNotEmpty()) {
                segments.add(ContentSegment(ContentSegment.TYPE_TEXT, s))
                textInput.text?.clear()
                refresh()
            }
        }

        view.findViewById<View>(R.id.add_key).setOnClickListener {
            KeyPicker.show(context) { name ->
                segments.add(ContentSegment(ContentSegment.TYPE_KEY, name))
                refresh()
            }
        }

        AlertDialog.Builder(context)
            .setTitle(if (entry == null) R.string.edit_entry_new else R.string.edit_entry_edit)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                if (segments.isEmpty()) {
                    Toast.makeText(context, R.string.segments_empty, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val label = labelInput.text?.toString()?.trim().orEmpty()
                val mode = if (modeGroup.checkedRadioButtonId == R.id.mode_sequence)
                    QuickSendEntry.MODE_SEQUENCE else QuickSendEntry.MODE_COMBINATION
                val useCount = useCountInput.text?.toString()?.toIntOrNull() ?: 0
                val snapshot = segments.toList()
                val id = entry?.id ?: 0L

                QuickSendManager.launch {
                    val ok = if (entry == null) {
                        QuickSendManager.add(label, snapshot, mode)
                    } else {
                        QuickSendManager.update(id, label, snapshot, mode, useCount)
                        true
                    }
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(
                            context,
                            if (ok) R.string.saved else R.string.max_entries_reached,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
