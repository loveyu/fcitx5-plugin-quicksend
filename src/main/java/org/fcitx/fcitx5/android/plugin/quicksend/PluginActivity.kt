/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.quicksend

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.plugin.quicksend.data.QuickSendManager
import org.fcitx.fcitx5.android.plugin.quicksend.data.ContentSegment
import org.fcitx.fcitx5.android.plugin.quicksend.data.db.QuickSendEntry
import org.fcitx.fcitx5.android.plugin.quicksend.log.LogSettingsActivity
import org.fcitx.fcitx5.android.plugin.quicksend.ui.EditEntrySheet
import org.fcitx.fcitx5.android.plugin.quicksend.ui.DelayVisualStyle
import org.fcitx.fcitx5.android.plugin.quicksend.ui.SegmentFormatter
import org.fcitx.fcitx5.android.plugin.quicksend.ui.components.QuickSendTopBar
import org.fcitx.fcitx5.android.plugin.quicksend.ui.components.SettingSwitchRow
import org.fcitx.fcitx5.android.plugin.quicksend.ui.theme.QuickSendTheme
import org.fcitx.fcitx5.android.plugin.quicksend.voice.VoiceSettingsActivity
import org.fcitx.fcitx5.android.plugin.quicksend.voice.remote.RemoteAsrSettingsActivity

/**
 * 插件主页：悬浮开关 + 条目列表（点条目发送 / 编辑 / 删除）+ 溢出菜单（外观/远端语音/本地语音/日志）。
 * 由 host 经 `${fcitxAppId}.plugin.MANIFEST` 拉起。
 */
class PluginActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            QuickSendTheme {
                PluginScreen(onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PluginScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(QuickSendPrefs.FILE, Context.MODE_PRIVATE)
    }
    val scope = rememberCoroutineScope()

    val entries by QuickSendManager.items.collectAsState()
    var overlayEnabled by remember {
        mutableStateOf(prefs.getBoolean(QuickSendPrefs.OVERLAY_ENABLED, false))
    }

    var sheetVisible by remember { mutableStateOf(false) }
    var editingEntry by remember { mutableStateOf<QuickSendEntry?>(null) }
    var deleteTarget by remember { mutableStateOf<QuickSendEntry?>(null) }
    var menuOpen by remember { mutableStateOf(false) }

    // 返回页面时按 prefs 同步开关态（用户可能去系统授予了悬浮权限后返回）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayEnabled = prefs.getBoolean(QuickSendPrefs.OVERLAY_ENABLED, false)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun toggleOverlay(enable: Boolean) {
        val intent = Intent(context, QuickSendOverlayService::class.java)
        if (enable) {
            if (!Settings.canDrawOverlays(context)) return
            context.startService(intent)
        } else {
            intent.action = QuickSendOverlayService.ACTION_HIDE
            context.startService(intent)
        }
    }

    fun sendEntry(entry: QuickSendEntry) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) { QuickSendExecutor.execute(entry) }
            if (!ok) {
                Toast.makeText(context, R.string.send_unavailable, Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            QuickSendTopBar(
                title = stringResource(R.string.entries_title),
                onBack = onBack,
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_more_vert),
                                contentDescription = stringResource(R.string.more),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.btn_appearance)) },
                                onClick = {
                                    menuOpen = false
                                    context.startActivity(Intent(context, AppearanceActivity::class.java))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.remote_voice_entry)) },
                                onClick = {
                                    menuOpen = false
                                    context.startActivity(Intent(context, RemoteAsrSettingsActivity::class.java))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.voice_settings_entry)) },
                                onClick = {
                                    menuOpen = false
                                    context.startActivity(Intent(context, VoiceSettingsActivity::class.java))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.log_settings_entry)) },
                                onClick = {
                                    menuOpen = false
                                    context.startActivity(Intent(context, LogSettingsActivity::class.java))
                                }
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                editingEntry = null
                sheetVisible = true
            }) { Text("+", style = MaterialTheme.typography.titleLarge) }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            SettingSwitchRow(
                title = stringResource(R.string.overlay_enable),
                checked = overlayEnabled,
                onChange = { checked ->
                    if (checked && !Settings.canDrawOverlays(context)) {
                        Toast.makeText(context, R.string.overlay_permission_rationale, Toast.LENGTH_LONG).show()
                        context.startActivity(
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                        )
                    } else {
                        overlayEnabled = checked
                        prefs.edit().putBoolean(QuickSendPrefs.OVERLAY_ENABLED, checked).apply()
                        toggleOverlay(checked)
                        Toast.makeText(
                            context,
                            if (checked) R.string.overlay_enabled_hint else R.string.overlay_disabled_hint,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            if (entries.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.empty_list),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)
                ) {
                    items(entries, key = { it.id }) { entry ->
                        EntryCard(
                            entry = entry,
                            onSend = { sendEntry(entry) },
                            onEdit = {
                                editingEntry = entry
                                sheetVisible = true
                            },
                            onDelete = { deleteTarget = entry }
                        )
                    }
                }
            }
        }
    }

    if (sheetVisible) {
        EditEntrySheet(
            entry = editingEntry,
            onDismiss = { sheetVisible = false }
        )
    }

    deleteTarget?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            text = { Text(stringResource(R.string.delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    scope.launch {
                        withContext(Dispatchers.IO) { QuickSendManager.delete(entry.id) }
                    }
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EntryCard(
    entry: QuickSendEntry,
    onSend: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val isCombination = entry.sendMode == QuickSendEntry.MODE_COMBINATION
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clickable { onSend() }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (isCombination) "⊕" else "→",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                entry.label.trim().takeIf { it.isNotEmpty() }?.let { label ->
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                }
                SegmentPreview(entry.segments)
                Text(
                    "×${entry.useCount}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onEdit) { Text(stringResource(R.string.edit_entry_edit)) }
            TextButton(onClick = onDelete) {
                Text(stringResource(R.string.delete_entry), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SegmentPreview(segments: List<ContentSegment>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        segments.forEach { segment ->
            when (segment.type) {
                ContentSegment.TYPE_KEY -> PreviewToken(
                    text = "[${segment.content}]",
                    background = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
                ContentSegment.TYPE_DELAY -> ContentSegment.delayMillis(segment.content)?.let { delay ->
                    PreviewToken(
                        text = "{$delay}",
                        background = DelayVisualStyle.Container,
                        contentColor = DelayVisualStyle.Content,
                        border = true
                    )
                }
                else -> Text(
                    text = segment.content,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun PreviewToken(
    text: String,
    background: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
    border: Boolean = false
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = contentColor,
        modifier = Modifier
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .then(
                if (border) Modifier.border(1.dp, DelayVisualStyle.Border, RoundedCornerShape(6.dp))
                else Modifier
            )
            .padding(horizontal = 6.dp, vertical = 3.dp)
    )
}
