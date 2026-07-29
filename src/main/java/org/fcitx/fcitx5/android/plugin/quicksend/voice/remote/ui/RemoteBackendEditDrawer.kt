/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.quicksend.voice.remote.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.plugin.quicksend.R
import org.fcitx.fcitx5.android.plugin.quicksend.voice.VoiceOverlayService
import org.fcitx.fcitx5.android.plugin.quicksend.voice.remote.RemoteBackend
import org.fcitx.fcitx5.android.plugin.quicksend.voice.remote.RemoteBackendStore
import org.fcitx.fcitx5.android.plugin.quicksend.voice.remote.StreamingAsrServerBackend
import org.fcitx.fcitx5.android.plugin.quicksend.voice.remote.TencentAsrBackend
import org.fcitx.fcitx5.android.plugin.quicksend.voice.remote.TencentAsrV1Backend
import org.fcitx.fcitx5.android.plugin.quicksend.voice.remote.TencentAsrV2Backend

/**
 * 从下往上弹出的编辑抽屉。按后端类型渲染对应表单，支持保存/删除/单后端测试。
 * 测试：先保存（拿到稳定 id 供 tested 回写）→ 序列化 → 拉起 [VoiceOverlayService] 测试模式 → 关闭抽屉。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteBackendEditDrawer(
    backend: RemoteBackend,
    onSave: (RemoteBackend) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // 以 backend.id 为 key，切换编辑对象时整体重置表单状态
    val streaming = backend as? StreamingAsrServerBackend
    val tencentV2 = backend as? TencentAsrV2Backend
    val tencentV1 = backend as? TencentAsrV1Backend
    // V1/V2 字段同构，取实际后端的值；都没有时按各自默认（仅影响新建瞬时态）
    val tencent: TencentAsrBackend? = tencentV1 ?: tencentV2

    var name by remember(backend.id) { mutableStateOf(backend.name) }
    var enable by remember(backend.id) { mutableStateOf(backend.enable) }
    var proxy by remember(backend.id) { mutableStateOf(backend.proxy) }
    var baseUrl by remember(backend.id) { mutableStateOf(tencent?.baseUrl ?: "") }
    var url by remember(backend.id) { mutableStateOf(streaming?.url ?: "") }
    var token by remember(backend.id) { mutableStateOf(streaming?.token ?: "") }
    var appId by remember(backend.id) { mutableStateOf(tencent?.appId ?: "") }
    var secretId by remember(backend.id) { mutableStateOf(tencent?.secretId ?: "") }
    var secretKey by remember(backend.id) { mutableStateOf(tencent?.secretKey ?: "") }
    var engine by remember(backend.id) {
        mutableStateOf(tencent?.engineModelType ?: if (tencentV1 != null) "16k_zh" else "16k_zh_en_2.0")
    }
    var voiceFormat by remember(backend.id) { mutableStateOf((tencent?.voiceFormat ?: 1).toString()) }
    var needVad by remember(backend.id) { mutableStateOf((tencent?.needVad ?: 1).toString()) }
    var filterDirty by remember(backend.id) { mutableStateOf((tencent?.filterDirty ?: 0).toString()) }
    var filterModal by remember(backend.id) { mutableStateOf((tencent?.filterModal ?: 0).toString()) }
    var filterPunc by remember(backend.id) { mutableStateOf((tencentV1?.filterPunc ?: 0).toString()) }
    var convertNum by remember(backend.id) { mutableStateOf((tencent?.convertNumMode ?: 1).toString()) }
    var hotword by remember(backend.id) { mutableStateOf(tencent?.hotwordList ?: "") }

    fun buildSaved(): RemoteBackend? = when (backend) {
        is StreamingAsrServerBackend -> {
            if (url.isBlank()) null
            else backend.copy(
                name = name.trim().ifBlank { "streaming-asr-server" },
                enable = enable, proxy = proxy.trim(),
                url = url.trim(), token = token.trim()
            )
        }
        is TencentAsrV2Backend -> {
            if (baseUrl.isBlank() || appId.isBlank() || secretId.isBlank() || secretKey.isBlank()) null
            else backend.copy(
                name = name.trim().ifBlank { "tencent-asr-v2" },
                enable = enable, proxy = proxy.trim(),
                baseUrl = baseUrl.trim(),
                appId = appId.trim(),
                secretId = secretId.trim(),
                secretKey = secretKey.trim(),
                engineModelType = engine.trim().ifBlank { "16k_zh_en_2.0" },
                voiceFormat = voiceFormat.toIntOrNull() ?: 1,
                needVad = needVad.toIntOrNull() ?: 1,
                filterDirty = filterDirty.toIntOrNull() ?: 0,
                filterModal = filterModal.toIntOrNull() ?: 0,
                convertNumMode = convertNum.toIntOrNull() ?: 1,
                hotwordList = hotword.trim(),
            )
        }
        is TencentAsrV1Backend -> {
            if (baseUrl.isBlank() || appId.isBlank() || secretId.isBlank() || secretKey.isBlank()) null
            else backend.copy(
                name = name.trim().ifBlank { "tencent-asr-v1" },
                enable = enable, proxy = proxy.trim(),
                baseUrl = baseUrl.trim(),
                appId = appId.trim(),
                secretId = secretId.trim(),
                secretKey = secretKey.trim(),
                engineModelType = engine.trim().ifBlank { "16k_zh" },
                voiceFormat = voiceFormat.toIntOrNull() ?: 1,
                needVad = needVad.toIntOrNull() ?: 1,
                filterDirty = filterDirty.toIntOrNull() ?: 0,
                filterModal = filterModal.toIntOrNull() ?: 0,
                filterPunc = filterPunc.toIntOrNull() ?: 0,
                convertNumMode = convertNum.toIntOrNull() ?: 1,
                hotwordList = hotword.trim(),
            )
        }
    }

    fun startTest(saved: RemoteBackend) {
        // 先 upsert（保证 tested 能按 id 回写），再拉起测试浮层
        onSave(saved)
        val json = RemoteBackendStore.encode(saved)
        context.startForegroundService(
            Intent(context, VoiceOverlayService::class.java)
                .setAction(VoiceOverlayService.ACTION_START)
                .putExtra(VoiceOverlayService.EXTRA_TEST_MODE, true)
                .putExtra(VoiceOverlayService.EXTRA_TEST_BACKEND_JSON, json)
        )
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = typeLabel(backend), style = androidx.compose.material3.MaterialTheme.typography.titleMedium)

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text(stringResource(R.string.remote_field_name)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.size(8.dp))
                Checkbox(checked = enable, onCheckedChange = { enable = it })
                Text(stringResource(R.string.remote_field_enable))
            }

            when (backend) {
                is StreamingAsrServerBackend -> {
                    OutlinedTextField(
                        value = url, onValueChange = { url = it },
                        label = { Text(stringResource(R.string.streaming_field_url)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = token, onValueChange = { token = it },
                        label = { Text(stringResource(R.string.streaming_field_token)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                }
                is TencentAsrV1Backend, is TencentAsrV2Backend -> {
                    OutlinedTextField(
                        value = baseUrl, onValueChange = { baseUrl = it },
                        label = { Text(stringResource(R.string.tencent_field_baseurl)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    TextButton(onClick = {
                        val defaultUrl = if (tencentV1 != null) TencentAsrV1Backend.DEFAULT_BASE_URL
                        else TencentAsrV2Backend.DEFAULT_BASE_URL
                        clipboard.setText(AnnotatedString(defaultUrl))
                        toast(context, R.string.tencent_default_copied)
                    }) { Text(stringResource(R.string.tencent_copy_default)) }
                    OutlinedTextField(value = appId, onValueChange = { appId = it }, label = { Text(stringResource(R.string.tencent_field_appid)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = secretId, onValueChange = { secretId = it }, label = { Text(stringResource(R.string.tencent_field_secretid)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = secretKey, onValueChange = { secretKey = it }, label = { Text(stringResource(R.string.tencent_field_secretkey)) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = engine, onValueChange = { engine = it }, label = { Text(stringResource(if (tencentV1 != null) R.string.tencent_field_engine_v1 else R.string.tencent_field_engine)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = voiceFormat, onValueChange = { voiceFormat = it }, label = { Text(stringResource(R.string.tencent_field_voice_format)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = needVad, onValueChange = { needVad = it }, label = { Text(stringResource(R.string.tencent_field_needvad)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = filterDirty, onValueChange = { filterDirty = it }, label = { Text(stringResource(R.string.tencent_field_filter_dirty)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = filterModal, onValueChange = { filterModal = it }, label = { Text(stringResource(R.string.tencent_field_filter_modal)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                    if (tencentV1 != null) {
                        OutlinedTextField(value = filterPunc, onValueChange = { filterPunc = it }, label = { Text(stringResource(R.string.tencent_field_filter_punc)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                    }
                    OutlinedTextField(value = convertNum, onValueChange = { convertNum = it }, label = { Text(stringResource(R.string.tencent_field_convert_num)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = hotword, onValueChange = { hotword = it }, label = { Text(stringResource(R.string.tencent_field_hotword)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            }

            OutlinedTextField(
                value = proxy, onValueChange = { proxy = it },
                label = { Text(stringResource(R.string.remote_field_proxy)) },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )

            Text(
                text = stringResource(R.string.remote_test_hint),
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = {
                    val saved = buildSaved()
                    if (saved == null) {
                        toast(context, R.string.remote_required_missing)
                        return@OutlinedButton
                    }
                    onSave(saved)
                    scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
                }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.save)) }

                Button(onClick = {
                    val saved = buildSaved() ?: run {
                        toast(context, R.string.remote_required_missing); return@Button
                    }
                    startTest(saved)
                }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.remote_test)) }
            }

            TextButton(onClick = {
                onDelete(backend.id)
                scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
            }) { Text(stringResource(R.string.remote_delete)) }

            Spacer(Modifier.size(8.dp))
        }
    }
}

internal fun typeLabel(backend: RemoteBackend): String = when (backend) {
    is StreamingAsrServerBackend -> "streaming-asr-server"
    is TencentAsrV1Backend -> "tencent-asr-v1"
    is TencentAsrV2Backend -> "tencent-asr-v2"
}

@Composable
private fun stringResource(id: Int): String = androidx.compose.ui.res.stringResource(id)
