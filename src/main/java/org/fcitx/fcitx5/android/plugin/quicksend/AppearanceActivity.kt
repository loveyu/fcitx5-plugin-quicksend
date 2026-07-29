/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.quicksend

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.fcitx.fcitx5.android.plugin.quicksend.ui.components.ColorChip
import org.fcitx.fcitx5.android.plugin.quicksend.ui.components.ColorPickerDialog
import org.fcitx.fcitx5.android.plugin.quicksend.ui.components.PreviewButton
import org.fcitx.fcitx5.android.plugin.quicksend.ui.components.COLOR_PRESETS
import org.fcitx.fcitx5.android.plugin.quicksend.ui.components.QuickSendTopBar
import org.fcitx.fcitx5.android.plugin.quicksend.ui.components.SectionHeader
import org.fcitx.fcitx5.android.plugin.quicksend.ui.components.contrastTextColor
import org.fcitx.fcitx5.android.plugin.quicksend.ui.theme.QuickSendTheme

/**
 * 悬浮按钮外观设置：按钮文字 + 日/夜模式的背景色/文字色（HSV 选择器 + 预设）+ 实时预览 +
 * 重置位置。颜色独立存储日/夜两套。
 */
class AppearanceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            QuickSendTheme {
                AppearanceScreen(onBack = { finish() })
            }
        }
    }
}

private data class PickerTarget(
    val initial: Int,
    val otherColor: Int,
    val isBackground: Boolean,
    val onPicked: (Int) -> Unit
)

@Composable
private fun AppearanceScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(QuickSendPrefs.FILE, Context.MODE_PRIVATE)
    }

    var buttonText by remember {
        mutableStateOf(
            prefs.getString(QuickSendPrefs.BUTTON_TEXT, QuickSendPrefs.BUTTON_TEXT_DEFAULT)
                ?: QuickSendPrefs.BUTTON_TEXT_DEFAULT
        )
    }
    var bgLight by remember { mutableIntStateOf(prefs.getInt(QuickSendPrefs.OVERLAY_BG_COLOR, QuickSendPrefs.DEFAULT_BG_COLOR)) }
    var textLight by remember { mutableIntStateOf(prefs.getInt(QuickSendPrefs.OVERLAY_TEXT_COLOR, QuickSendPrefs.DEFAULT_TEXT_COLOR)) }
    var bgDark by remember { mutableIntStateOf(prefs.getInt(QuickSendPrefs.OVERLAY_BG_COLOR_NIGHT, QuickSendPrefs.DEFAULT_BG_COLOR)) }
    var textDark by remember { mutableIntStateOf(prefs.getInt(QuickSendPrefs.OVERLAY_TEXT_COLOR_NIGHT, QuickSendPrefs.DEFAULT_TEXT_COLOR)) }

    var picker by remember { mutableStateOf<PickerTarget?>(null) }
    var presetsLight by remember { mutableStateOf(false to false) } // (show, isLight)

    val displayText = buttonText.ifBlank { QuickSendPrefs.BUTTON_TEXT_DEFAULT }

    Scaffold(
        topBar = {
            QuickSendTopBar(
                title = stringResource(R.string.btn_appearance),
                onBack = onBack
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            SectionHeader(stringResource(R.string.overlay_button_text))
            OutlinedTextField(
                value = buttonText,
                onValueChange = { v ->
                    val limited = v.take(4)
                    buttonText = limited
                    prefs.edit()
                        .putString(QuickSendPrefs.BUTTON_TEXT, limited.ifBlank { QuickSendPrefs.BUTTON_TEXT_DEFAULT })
                        .apply()
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            SectionHeader(stringResource(R.string.btn_light_mode))
            ModeBlock(
                previewText = displayText,
                bgColor = bgLight,
                textColor = textLight,
                presetsLabel = stringResource(R.string.btn_preset_light_button),
                onBgClick = {
                    picker = PickerTarget(bgLight, textLight, isBackground = true) { c ->
                        bgLight = c
                        prefs.edit().putInt(QuickSendPrefs.OVERLAY_BG_COLOR, c).apply()
                    }
                },
                onTextClick = {
                    picker = PickerTarget(textLight, bgLight, isBackground = false) { c ->
                        textLight = c
                        prefs.edit().putInt(QuickSendPrefs.OVERLAY_TEXT_COLOR, c).apply()
                    }
                },
                onPresets = { presetsLight = true to true }
            )

            SectionHeader(stringResource(R.string.btn_dark_mode))
            ModeBlock(
                previewText = displayText,
                bgColor = bgDark,
                textColor = textDark,
                presetsLabel = stringResource(R.string.btn_preset_dark_button),
                onBgClick = {
                    picker = PickerTarget(bgDark, textDark, isBackground = true) { c ->
                        bgDark = c
                        prefs.edit().putInt(QuickSendPrefs.OVERLAY_BG_COLOR_NIGHT, c).apply()
                    }
                },
                onTextClick = {
                    picker = PickerTarget(textDark, bgDark, isBackground = false) { c ->
                        textDark = c
                        prefs.edit().putInt(QuickSendPrefs.OVERLAY_TEXT_COLOR_NIGHT, c).apply()
                    }
                },
                onPresets = { presetsLight = true to false }
            )

            OutlinedButton(
                onClick = {
                    prefs.edit()
                        .remove(QuickSendPrefs.OVERLAY_GRAVITY)
                        .remove(QuickSendPrefs.OVERLAY_X)
                        .remove(QuickSendPrefs.OVERLAY_Y)
                        .apply()
                    Toast.makeText(context, R.string.reset_overlay_done, Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp)
            ) { Text(stringResource(R.string.reset_overlay_position)) }
        }
    }

    picker?.let { target ->
        ColorPickerDialog(
            initial = target.initial,
            buttonText = displayText,
            otherColor = target.otherColor,
            isEditingBackground = target.isBackground,
            onDismiss = { picker = null },
            onPicked = { c ->
                target.onPicked(c)
                picker = null
            }
        )
    }

    val (showPresets, presetsIsLight) = presetsLight
    if (showPresets) {
        PresetsDialog(
            isLight = presetsIsLight,
            currentBg = if (presetsIsLight) bgLight else bgDark,
            onPick = { color ->
                val txt = contrastTextColor(color)
                if (presetsIsLight) {
                    bgLight = color
                    textLight = txt
                    prefs.edit()
                        .putInt(QuickSendPrefs.OVERLAY_BG_COLOR, color)
                        .putInt(QuickSendPrefs.OVERLAY_TEXT_COLOR, txt)
                        .apply()
                } else {
                    bgDark = color
                    textDark = txt
                    prefs.edit()
                        .putInt(QuickSendPrefs.OVERLAY_BG_COLOR_NIGHT, color)
                        .putInt(QuickSendPrefs.OVERLAY_TEXT_COLOR_NIGHT, txt)
                        .apply()
                }
            },
            onDismiss = { presetsLight = false to false }
        )
    }
}

@Composable
private fun ModeBlock(
    previewText: String,
    bgColor: Int,
    textColor: Int,
    presetsLabel: String,
    onBgClick: () -> Unit,
    onTextClick: () -> Unit,
    onPresets: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PreviewButton(previewText, bgColor, textColor)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.padding(start = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.btn_bg_color),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                ColorChip(color = bgColor, onClick = onBgClick)
            }
            Spacer(Modifier.padding(vertical = 4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.btn_text_color),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                ColorChip(color = textColor, onClick = onTextClick)
            }
            TextButton(onClick = onPresets) { Text(presetsLabel) }
        }
    }
}

@Composable
private fun PresetsDialog(
    isLight: Boolean,
    currentBg: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (isLight) R.string.btn_preset_light_button else R.string.btn_preset_dark_button))
        },
        text = {
            Column {
                COLOR_PRESETS.chunked(5).forEach { rowItems ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(vertical = 4.dp)
                    ) {
                        rowItems.forEach { (_, color) ->
                            ColorChip(
                                color = color,
                                sizeDp = 40.dp,
                                onClick = {
                                    onPick(color)
                                    onDismiss()
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.confirm)) }
        }
    )
}
