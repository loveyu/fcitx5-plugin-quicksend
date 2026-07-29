/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.quicksend.voice.remote.ui

import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.fcitx.fcitx5.android.plugin.quicksend.QuickSendPrefs
import org.fcitx.fcitx5.android.plugin.quicksend.R
import org.fcitx.fcitx5.android.plugin.quicksend.voice.remote.RemoteBackend
import org.fcitx.fcitx5.android.plugin.quicksend.voice.remote.RemoteBackendStore
import org.fcitx.fcitx5.android.plugin.quicksend.voice.remote.StreamingAsrServerBackend
import org.fcitx.fcitx5.android.plugin.quicksend.voice.remote.TencentAsrV2Backend
import kotlin.math.roundToInt

private val ROW_HEIGHT = 72.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteAsrSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var backends by remember { mutableStateOf(RemoteBackendStore.list(context)) }
    var editing by remember { mutableStateOf<RemoteBackend?>(null) }
    var showAdd by remember { mutableStateOf(false) }

    // 监听后端列表键变化（测试模式在服务里回写 tested 时实时刷新列表）
    DisposableEffect(Unit) {
        val prefs = context.getSharedPreferences(QuickSendPrefs.FILE, Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == QuickSendPrefs.VOICE_REMOTE_BACKENDS) {
                backends = RemoteBackendStore.list(context)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun reload() { backends = RemoteBackendStore.list(context) }

    fun move(from: Int, to: Int) {
        if (from == to) return
        val list = backends.toMutableList()
        val item = list.removeAt(from)
        list.add(to.coerceIn(0, list.size), item)
        RemoteBackendStore.save(context, list)
        backends = list
    }

    fun applyEnable(b: RemoteBackend, enable: Boolean) {
        val updated = backends.map { if (it.id == b.id) it.withEnable(enable) else it }
        // 重新分区：启用在前（保留相对序）、未启用在后（保留相对序）
        val partitioned = updated.filter { it.enable } + updated.filterNot { it.enable }
        RemoteBackendStore.save(context, partitioned)
        backends = partitioned
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.remote_voice_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) { Text("+", style = MaterialTheme.typography.titleLarge) }
        }
    ) { padding ->
        if (backends.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.remote_empty), modifier = Modifier.padding(24.dp))
            }
        } else {
            BackendList(
                backends = backends,
                modifier = Modifier.padding(padding),
                onReorder = ::move,
                onClick = { editing = it },
                onToggleEnable = ::applyEnable,
            )
        }
    }

    // 添加远端：选择类型
    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text(stringResource(R.string.remote_choose_type)) },
            text = {
                Column {
                    TextButton(onClick = {
                        showAdd = false
                        editing = StreamingAsrServerBackend(id = RemoteBackendStore.newId(), name = "streaming-asr-server")
                    }) { Text(stringResource(R.string.remote_type_streaming)) }
                    TextButton(onClick = {
                        showAdd = false
                        editing = TencentAsrV2Backend(id = RemoteBackendStore.newId(), name = "tencent-asr-v2")
                    }) { Text(stringResource(R.string.remote_type_tencent)) }
                }
            },
            confirmButton = { TextButton(onClick = { showAdd = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    editing?.let { b ->
        RemoteBackendEditDrawer(
            backend = b,
            onSave = { saved ->
                RemoteBackendStore.upsert(context, saved)
                reload()
                editing = saved // 保留稳定 id，便于后续测试回写
            },
            onDelete = { id ->
                RemoteBackendStore.remove(context, id)
                reload()
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun BackendList(
    backends: List<RemoteBackend>,
    modifier: Modifier,
    onReorder: (Int, Int) -> Unit,
    onClick: (RemoteBackend) -> Unit,
    onToggleEnable: (RemoteBackend, Boolean) -> Unit,
) {
    val density = LocalDensity.current
    val rowHeightPx = with(density) { ROW_HEIGHT.toPx() }
    var dragFrom by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        itemsIndexed(backends, key = { _, b -> b.id }) { index, backend ->
            // 捕获为局部 val，便于 smart-cast（dragFrom 是 delegated property 不能直接 smart-cast）
            val from = dragFrom
            val tgt = if (from != null) {
                (from + (dragOffset / rowHeightPx).roundToInt()).coerceIn(0, backends.lastIndex)
            } else -1
            val ty = when {
                from == null -> 0f
                index == from -> dragOffset
                from < index && index <= tgt -> -rowHeightPx // 被下拖挤上去
                tgt <= index && index < from -> rowHeightPx  // 被上拖挤下去
                else -> 0f
            }
            OutlinedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .height(ROW_HEIGHT)
                    .graphicsLayer { translationY = ty }
                    .pointerInput(backends.size) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { dragFrom = index; dragOffset = 0f },
                            onDrag = { _, delta -> dragOffset += delta.y },
                            onDragEnd = {
                                val f = dragFrom
                                if (f != null) {
                                    val t = (f + (dragOffset / rowHeightPx).roundToInt())
                                        .coerceIn(0, backends.lastIndex)
                                    if (t != f) onReorder(f, t)
                                }
                                dragFrom = null; dragOffset = 0f
                            },
                            onDragCancel = { dragFrom = null; dragOffset = 0f }
                        )
                    }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("≡", style = MaterialTheme.typography.titleLarge)
                    Column(
                        Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp)
                            .fillMaxSize(),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                backend.name.ifBlank { typeLabel(backend) },
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                "  " + typeLabel(backend),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            stringResource(if (backend.tested) R.string.remote_tested_yes else R.string.remote_tested_no),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = backend.enable,
                        onCheckedChange = { onToggleEnable(backend, it) }
                    )
                }
            }
        }
        item {
            Text(
                stringResource(R.string.remote_priority_hint),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }
    }
}

internal fun toast(context: Context, resId: Int) {
    Toast.makeText(context, resId, Toast.LENGTH_SHORT).show()
}
