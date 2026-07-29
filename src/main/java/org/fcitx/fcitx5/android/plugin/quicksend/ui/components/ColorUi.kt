/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.quicksend.ui.components

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.border
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.fcitx.fcitx5.android.plugin.quicksend.R
import kotlin.math.roundToInt

/** 预设配色（名称 → ARGB）。 */
internal val COLOR_PRESETS: List<Pair<String, Int>> = listOf(
    "经典蓝" to 0xE61976D2.toInt(),
    "珊瑚红" to 0xE6FF5722.toInt(),
    "翡翠绿" to 0xE64CAF50.toInt(),
    "琥珀橙" to 0xE6FF9800.toInt(),
    "紫罗兰" to 0xE69C27B0.toInt(),
    "深洋蓝" to 0xE60096A7.toInt(),
    "靛青" to 0xE63F51B5.toInt(),
    "玫瑰粉" to 0xE6E91E63.toInt(),
    "石墨灰" to 0xE6607D8B.toInt(),
    "冰霜白" to 0xD9FAFAFA.toInt(),
    "柠檬黄" to 0xD9FFEB3B.toInt(),
    "森林绿" to 0xE62E7D32.toInt(),
    "烈焰橙" to 0xE6E64A19.toInt(),
    "宝石蓝" to 0xE60D47A1.toInt(),
    "藤紫" to 0xE68E24AA.toInt(),
    "薄荷青" to 0xE600BCD4.toInt(),
    "暗夜黑" to 0xD9424242.toInt(),
    "桃红" to 0xE6F06292.toInt(),
    "橄榄绿" to 0xE6689F38.toInt(),
    "暮光紫" to 0xE67E57C2.toInt(),
)

/** 按背景亮度选黑/白文字，保证对比度。 */
internal fun contrastTextColor(bg: Int): Int {
    val a = AndroidColor.alpha(bg)
    if (a < 128) return AndroidColor.WHITE
    val luminance =
        (0.299 * AndroidColor.red(bg) + 0.587 * AndroidColor.green(bg) + 0.114 * AndroidColor.blue(bg)) / 255.0
    return if (luminance > 0.5) AndroidColor.BLACK else AndroidColor.WHITE
}

/** chip 描边色：浅色芯片用深边、深色芯片用浅边。 */
internal fun chipBorderColor(chipColor: Int): Int {
    val luminance =
        (0.299 * AndroidColor.red(chipColor) + 0.587 * AndroidColor.green(chipColor) + 0.114 * AndroidColor.blue(chipColor)) / 255.0
    val alpha = AndroidColor.alpha(chipColor)
    return if (luminance > 0.7 && alpha > 180) {
        AndroidColor.argb(200, 60, 60, 60)
    } else {
        AndroidColor.argb(80, 160, 160, 160)
    }
}

/** 透明度棋盘格背景（显示 alpha 用）。 */
@Composable
internal fun Checkerboard(modifier: Modifier = Modifier, cellDp: Dp = 4.dp) {
    val cellPx = with(LocalDensity.current) { cellDp.toPx() }
    val c1 = Color(0xFFCCCCCC)
    val c2 = Color(0xFFFFFFFF)
    Canvas(modifier) {
        val cols = (size.width / cellPx).toInt()
        val rows = (size.height / cellPx).toInt()
        drawRect(c2)
        for (i in 0 until cols) {
            for (j in 0 until rows) {
                if ((i + j) % 2 == 0) {
                    drawRect(
                        c1,
                        topLeft = Offset(i * cellPx, j * cellPx),
                        size = Size(cellPx, cellPx)
                    )
                }
            }
        }
    }
}

/** 悬浮按钮预览：棋盘格底 + 圆形按钮（背景色 + 文字色）。 */
@Composable
internal fun PreviewButton(
    text: String,
    bgColor: Int,
    textColor: Int,
    modifier: Modifier = Modifier,
    frameSizeDp: Dp = 80.dp
) {
    Box(
        modifier = modifier
            .size(frameSizeDp)
            .clip(CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Checkerboard(Modifier.matchParentSize())
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Color(bgColor)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                color = Color(textColor),
                fontSize = 16.sp,
                maxLines = 1
            )
        }
    }
}

/** 圆形色块（可点击），描边按芯片亮度自适应。 */
@Composable
internal fun ColorChip(
    color: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    sizeDp: Dp = 36.dp
) {
    Box(
        modifier = modifier
            .size(sizeDp)
            .clip(CircleShape)
            .background(Color(color))
            .border(2.dp, Color(chipBorderColor(color)), CircleShape)
            .clickable(onClick = onClick)
    )
}

/**
 * HSV 颜色选择对话框：4 滑块 + ARGB hex + 预览 + 预设网格。
 *
 * @param otherColor 非空时，预览以该色作为搭配（背景/文字互补）；为空则文字用对比色。
 * @param isEditingBackground otherColor 非空时，true=正在编辑背景色，false=正在编辑文字色。
 */
@Composable
internal fun ColorPickerDialog(
    initial: Int,
    buttonText: String,
    onDismiss: () -> Unit,
    onPicked: (Int) -> Unit,
    otherColor: Int? = null,
    isEditingBackground: Boolean = true,
) {
    var current by remember { mutableIntStateOf(initial) }
    var hex by remember { mutableStateOf(String.format("#%08X", initial)) }

    val hsv = remember(current) {
        FloatArray(3).also { AndroidColor.colorToHSV(current, it) }
    }
    val hue = hsv[0]
    val sat = hsv[1] * 100f
    val bri = hsv[2] * 100f
    val alpha = AndroidColor.alpha(current).toFloat()

    fun applyHsv(h: Float, s: Float, v: Float, a: Int) {
        val c = AndroidColor.HSVToColor(a, floatArrayOf(h, s / 100f, v / 100f))
        current = c
        hex = String.format("#%08X", c)
    }

    val (previewBg, previewText) = if (otherColor != null) {
        if (isEditingBackground) current to otherColor else otherColor to current
    } else {
        current to contrastTextColor(current)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.color_picker_title)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp)
            ) {
                Row(
                    modifier = Modifier.padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PreviewButton(buttonText, previewBg, previewText, frameSizeDp = 64.dp)
                    OutlinedTextField(
                        value = hex,
                        onValueChange = { v ->
                            val limited = v.take(9)
                            hex = limited
                            val pure = limited.removePrefix("#")
                            if (pure.length == 8) {
                                runCatching { (pure.toLong(16) and 0xFFFFFFFFL).toInt() }
                                    .onSuccess { current = it }
                            }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        modifier = Modifier.width(140.dp)
                    )
                }

                SliderRow(stringResource(R.string.color_hue), hue, 0f..360f) { applyHsv(it, sat, bri, alpha.roundToInt()) }
                SliderRow(stringResource(R.string.color_saturation), sat, 0f..100f) { applyHsv(hue, it, bri, alpha.roundToInt()) }
                SliderRow(stringResource(R.string.color_brightness), bri, 0f..100f) { applyHsv(hue, sat, it, alpha.roundToInt()) }
                SliderRow(stringResource(R.string.color_alpha), alpha, 0f..255f) { applyHsv(hue, sat, bri, it.roundToInt()) }

                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.color_presets),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(vertical = 6.dp)
                )
                // 预设网格（自动换行）
                val rows = COLOR_PRESETS.chunked(5)
                rows.forEach { rowItems ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(vertical = 4.dp)
                    ) {
                        rowItems.forEach { (_, color) ->
                            ColorChip(color = color, onClick = {
                                current = color
                                hex = String.format("#%08X", color)
                            })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPicked(current) }) { Text(stringResource(R.string.confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(56.dp)
        )
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.weight(1f)
        )
    }
}
