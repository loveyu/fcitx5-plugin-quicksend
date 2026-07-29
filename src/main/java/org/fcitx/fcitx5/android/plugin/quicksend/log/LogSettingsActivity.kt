/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.quicksend.log

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.plugin.quicksend.AppLog
import org.fcitx.fcitx5.android.plugin.quicksend.BuildConfig
import org.fcitx.fcitx5.android.plugin.quicksend.R
import org.fcitx.fcitx5.android.plugin.quicksend.ui.components.QuickSendTopBar
import org.fcitx.fcitx5.android.plugin.quicksend.ui.components.SectionHeader
import org.fcitx.fcitx5.android.plugin.quicksend.ui.components.SettingSwitchRow
import org.fcitx.fcitx5.android.plugin.quicksend.ui.theme.QuickSendTheme
import java.util.Locale

/**
 * 调试日志设置页：DEBUG 开关 / 当前级别 / 路径与大小 / 清空 / 分享 / 末尾预览。
 *
 * - 默认仅 WARN 及以上落盘；开启 DEBUG 后追加 INFO/DEBUG（同时写 logcat）。
 * - 从主菜单溢出菜单进入。
 */
class LogSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            QuickSendTheme {
                LogSettingsScreen(onBack = { finish() })
            }
        }
    }
}

@Composable
private fun LogSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var debugEnabled by remember { mutableStateOf(AppLog.isDebugEnabled()) }
    var sizeText by remember { mutableStateOf("") }
    var tailText by remember { mutableStateOf("") }
    var showClear by remember { mutableStateOf(false) }

    fun loadPreview() {
        scope.launch {
            val (size, tail) = withContext(Dispatchers.IO) {
                val f = AppLog.file(context)
                val sz = formatSize(f?.length() ?: 0L)
                val t = runCatching {
                    if (f == null || f.length() == 0L) {
                        context.getString(R.string.log_preview_empty)
                    } else {
                        f.readText().lineSequence().toList()
                            .takeLast(MAX_PREVIEW_LINES)
                            .joinToString("\n")
                    }
                }.getOrDefault(context.getString(R.string.log_preview_empty))
                sz to t
            }
            sizeText = size
            tailText = tail
        }
    }

    fun shareLog() {
        val file = AppLog.file(context)
        if (file == null) {
            Toast.makeText(context, R.string.log_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.log_settings_title))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.log_share_title)))
    }

    LaunchedEffect(Unit) { loadPreview() }

    val levelStr = stringResource(if (debugEnabled) R.string.log_level_debug else R.string.log_level_warn)

    Scaffold(
        topBar = {
            QuickSendTopBar(
                title = stringResource(R.string.log_settings_title),
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
            SettingSwitchRow(
                title = stringResource(R.string.log_debug_switch),
                subtitle = stringResource(R.string.log_debug_summary),
                checked = debugEnabled,
                onChange = {
                    debugEnabled = it
                    AppLog.setDebugEnabled(context, it)
                }
            )
            Text(
                stringResource(R.string.log_current_level, levelStr),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            SectionHeader(stringResource(R.string.log_path_label))
            SelectionContainer {
                Text(
                    AppLog.path(context),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                sizeText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = { showClear = true },
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.log_clear)) }
                OutlinedButton(
                    onClick = { shareLog() },
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.log_share)) }
            }

            SectionHeader(stringResource(R.string.log_preview_section, MAX_PREVIEW_LINES))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(8.dp)
                    )
            ) {
                SelectionContainer {
                    Text(
                        tailText,
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (showClear) {
        AlertDialog(
            onDismissRequest = { showClear = false },
            title = { Text(stringResource(R.string.log_clear)) },
            text = { Text(stringResource(R.string.log_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showClear = false
                    AppLog.clear(context)
                    Toast.makeText(context, R.string.log_cleared_toast, Toast.LENGTH_SHORT).show()
                    loadPreview()
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showClear = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

private fun formatSize(bytes: Long): String {
    val kb = bytes / 1024.0
    return if (kb < 1024.0) {
        String.format(Locale.US, "%.1f KB", kb)
    } else {
        String.format(Locale.US, "%.2f MB", kb / 1024.0)
    }
}

private const val MAX_PREVIEW_LINES = 200
