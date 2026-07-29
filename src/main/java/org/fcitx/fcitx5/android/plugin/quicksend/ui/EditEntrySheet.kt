/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.quicksend.ui

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.plugin.quicksend.R
import org.fcitx.fcitx5.android.plugin.quicksend.data.ContentSegment
import org.fcitx.fcitx5.android.plugin.quicksend.data.KeyNameMapping
import org.fcitx.fcitx5.android.plugin.quicksend.data.QuickSendManager
import org.fcitx.fcitx5.android.plugin.quicksend.data.db.QuickSendEntry
import org.fcitx.fcitx5.android.plugin.quicksend.ui.components.SectionHeader

/**
 * 条目编辑底部抽屉：label / 内容段（文本 + 特殊键，FlowRow 芯片）/ 发送模式 / 使用次数。
 * 新建时 [entry] = null；编辑时传入已有条目。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditEntrySheet(entry: QuickSendEntry?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var label by remember { mutableStateOf(entry?.label ?: "") }
    val segments = remember {
        mutableStateListOf<ContentSegment>().apply { entry?.segments?.let { addAll(it) } }
    }
    var mode by remember { mutableStateOf(entry?.sendMode ?: QuickSendEntry.MODE_COMBINATION) }
    var useCount by remember { mutableStateOf(entry?.useCount?.toString() ?: "") }
    var textInput by remember { mutableStateOf("") }
    var showKeyPicker by remember { mutableStateOf(false) }

    fun close() {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    fun doSave() {
        if (segments.isEmpty()) {
            Toast.makeText(context, R.string.segments_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val lbl = label.trim()
        val snapshot = segments.toList()
        val id = entry?.id ?: 0L
        val uc = useCount.trim().toIntOrNull() ?: 0
        QuickSendManager.launch {
            val ok = if (entry == null) {
                QuickSendManager.add(lbl, snapshot, mode)
            } else {
                QuickSendManager.update(id, lbl, snapshot, mode, uc)
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
        close()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            // 顶栏：标题 + 取消 + 保存
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(if (entry == null) R.string.edit_entry_new else R.string.edit_entry_edit),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { close() }) { Text(stringResource(R.string.cancel)) }
                Button(onClick = { doSave() }) { Text(stringResource(R.string.save)) }
            }

            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text(stringResource(R.string.label_field)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )

            SectionHeader(stringResource(R.string.content_segments))
            if (segments.isEmpty()) {
                Text(
                    stringResource(R.string.segments_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                segments.forEachIndexed { index, seg ->
                    SegmentChip(seg) {
                        segments.removeAt(index)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    label = { Text(stringResource(R.string.text_input_hint)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = {
                        if (textInput.isNotEmpty()) {
                            segments.add(ContentSegment(ContentSegment.TYPE_TEXT, textInput))
                            textInput = ""
                        }
                    }
                ) { Text(stringResource(R.string.add_text)) }
            }
            TextButton(onClick = { showKeyPicker = true }) {
                Text(stringResource(R.string.add_special_key))
            }

            SectionHeader(stringResource(R.string.send_mode))
            ModeRadioRow(
                label = stringResource(R.string.mode_combination),
                selected = mode == QuickSendEntry.MODE_COMBINATION,
                onSelect = { mode = QuickSendEntry.MODE_COMBINATION }
            )
            ModeRadioRow(
                label = stringResource(R.string.mode_sequence),
                selected = mode == QuickSendEntry.MODE_SEQUENCE,
                onSelect = { mode = QuickSendEntry.MODE_SEQUENCE }
            )

            OutlinedTextField(
                value = useCount,
                onValueChange = { useCount = it.filter { c -> c.isDigit() }.take(6) },
                label = { Text(stringResource(R.string.use_count)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
        }
    }

    if (showKeyPicker) {
        KeyPickerDialog(
            onPick = { name ->
                segments.add(ContentSegment(ContentSegment.TYPE_KEY, name))
                showKeyPicker = false
            },
            onDismiss = { showKeyPicker = false }
        )
    }
}

@Composable
private fun SegmentChip(seg: ContentSegment, onRemove: () -> Unit) {
    val isKey = seg.type == ContentSegment.TYPE_KEY
    Row(
        modifier = Modifier
            .padding(2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isKey) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (isKey) "[${seg.content}]" else seg.content,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "×",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clickable { onRemove() }
        )
    }
}

@Composable
private fun ModeRadioRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable { onSelect() }
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** 特殊键分组选择对话框：分组标题（不可选）+ 键名（点击回填）。 */
@Composable
private fun KeyPickerDialog(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Column(modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                KeyNameMapping.groups.forEach { group ->
                    Text(
                        "【${group.title}】",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
                    )
                    group.keys.forEach { name ->
                        Text(
                            name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(name) }
                                .padding(horizontal = 4.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
