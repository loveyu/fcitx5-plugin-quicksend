/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.quicksend.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.plugin.quicksend.BuildConfig
import org.fcitx.fcitx5.android.plugin.quicksend.QuickSendPrefs
import org.fcitx.fcitx5.android.plugin.quicksend.R
import org.fcitx.fcitx5.android.plugin.quicksend.ui.components.QuickSendTopBar
import org.fcitx.fcitx5.android.plugin.quicksend.ui.components.SectionHeader
import org.fcitx.fcitx5.android.plugin.quicksend.ui.components.SettingSwitchRow
import org.fcitx.fcitx5.android.plugin.quicksend.ui.components.SettingTextFieldRow
import org.fcitx.fcitx5.android.plugin.quicksend.ui.theme.QuickSendTheme
import org.fcitx.fcitx5.android.plugin.quicksend.voice.net.ProxyConfig
import org.fcitx.fcitx5.android.plugin.quicksend.voice.sherpa.LoadResult
import org.fcitx.fcitx5.android.plugin.quicksend.voice.sherpa.NativeLibManager
import org.fcitx.fcitx5.android.plugin.quicksend.voice.sherpa.SherpaModelNames

/** 识别参数项定义：key / 标题 / 帮助文案 / 默认值 / 键盘类型。 */
private data class ParamSpec(
    val key: String,
    val title: String,
    val help: String,
    val default: String,
    val keyboard: KeyboardType
)

private val PARAM_PREF_KEYS = mapOf(
    "decodingMethod" to QuickSendPrefs.VOICE_DECODING_METHOD,
    "maxActivePaths" to QuickSendPrefs.VOICE_MAX_ACTIVE_PATHS,
    "blankPenalty" to QuickSendPrefs.VOICE_BLANK_PENALTY,
    "endpointSilence" to QuickSendPrefs.VOICE_ENDPOINT_SILENCE,
    "endpointMaxUtterance" to QuickSendPrefs.VOICE_ENDPOINT_MAX_UTTER,
    "numThreads" to QuickSendPrefs.VOICE_NUM_THREADS,
    "provider" to QuickSendPrefs.VOICE_PROVIDER
)

private fun buildParamSpecs(context: Context): List<ParamSpec> = recognitionParamHelps.map { h ->
    val keyboard = when (h.key) {
        "maxActivePaths", "numThreads" -> KeyboardType.Number
        "blankPenalty", "endpointSilence", "endpointMaxUtterance" -> KeyboardType.Decimal
        else -> KeyboardType.Text
    }
    val help = buildString {
        append(h.description).append("\n\n")
        append(context.getString(R.string.voice_param_default, h.default)).append("\n")
        append(context.getString(R.string.voice_param_recommended, h.recommended))
    }
    ParamSpec(h.key, h.title, help, h.default, keyboard)
}

/**
 * 语音输入设置页：模型下载（自定义 HTTP 地址 + 代理）+ 识别参数 + 高级文件名。
 * 进入时申请 RECORD_AUDIO；状态来自 [VoiceModelManager]。
 */
class VoiceSettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            @Suppress("DEPRECATION")
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), RC_AUDIO)
        }
        setContent {
            QuickSendTheme {
                VoiceSettingsScreen(onBack = { finish() })
            }
        }
    }

    private companion object {
        const val RC_AUDIO = 0x7e02
    }
}

@Composable
private fun VoiceSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(QuickSendPrefs.FILE, Context.MODE_PRIVATE)
    }
    val paramSpecs = remember { buildParamSpecs(context) }

    var modelUrl by remember {
        mutableStateOf(
            prefs.getString(QuickSendPrefs.VOICE_MODEL_BASE_URL, VoiceModelManager.DEFAULT_BASE_URL)
                ?: VoiceModelManager.DEFAULT_BASE_URL
        )
    }
    var proxyUri by remember { mutableStateOf(loadProxyUri(prefs)) }
    var nativeUrl by remember {
        mutableStateOf(
            prefs.getString(QuickSendPrefs.VOICE_NATIVE_LIB_URL, null) ?: NativeLibManager.defaultUrl()
        )
    }
    var nameEncoder by remember { mutableStateOf(prefs.getString(QuickSendPrefs.VOICE_NAME_ENCODER, SherpaModelNames.DEFAULT_ENCODER) ?: SherpaModelNames.DEFAULT_ENCODER) }
    var nameDecoder by remember { mutableStateOf(prefs.getString(QuickSendPrefs.VOICE_NAME_DECODER, SherpaModelNames.DEFAULT_DECODER) ?: SherpaModelNames.DEFAULT_DECODER) }
    var nameJoiner by remember { mutableStateOf(prefs.getString(QuickSendPrefs.VOICE_NAME_JOINER, SherpaModelNames.DEFAULT_JOINER) ?: SherpaModelNames.DEFAULT_JOINER) }
    var nameTokens by remember { mutableStateOf(prefs.getString(QuickSendPrefs.VOICE_NAME_TOKENS, SherpaModelNames.DEFAULT_TOKENS) ?: SherpaModelNames.DEFAULT_TOKENS) }

    val params = remember { mutableStateMapOf<String, String>().also { loadParams(prefs, paramSpecs, it) } }

    var autoPauseMedia by remember {
        mutableStateOf(prefs.getBoolean(QuickSendPrefs.VOICE_AUTO_PAUSE_MEDIA, false))
    }

    val downloadState by VoiceModelManager.state.collectAsState()
    val nativeState by NativeLibManager.state.collectAsState()
    val needsRestart = remember { mutableStateOf(NativeLibManager.needsRestart(context)) }

    // 进入页面时刷新就绪态（下载/删除可能在设置页之外发生）
    LaunchedEffect(Unit) {
        VoiceModelManager.refresh(context)
        NativeLibManager.refresh(context)
        needsRestart.value = NativeLibManager.needsRestart(context)
    }
    // 下载完成/删除后重算「需重启」态
    LaunchedEffect(nativeState) {
        needsRestart.value = NativeLibManager.needsRestart(context)
    }

    fun baseUrl(): String = modelUrl.trim().ifBlank { VoiceModelManager.DEFAULT_BASE_URL }
    fun names(): SherpaModelNames = SherpaModelNames(
        encoder = nameEncoder.trim().ifBlank { SherpaModelNames.DEFAULT_ENCODER },
        decoder = nameDecoder.trim().ifBlank { SherpaModelNames.DEFAULT_DECODER },
        joiner = nameJoiner.trim().ifBlank { SherpaModelNames.DEFAULT_JOINER },
        tokens = nameTokens.trim().ifBlank { SherpaModelNames.DEFAULT_TOKENS }
    )
    fun proxy(): ProxyConfig = ProxyConfig.fromUri(proxyUri.trim())

    Scaffold(
        topBar = {
            QuickSendTopBar(
                title = stringResource(R.string.voice_settings_title),
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
            // —— 通用设置 ——
            SectionHeader(stringResource(R.string.voice_general_section))
            SettingSwitchRow(
                title = stringResource(R.string.voice_auto_pause_media),
                subtitle = stringResource(R.string.voice_auto_pause_media_summary),
                checked = autoPauseMedia,
                onChange = { checked ->
                    autoPauseMedia = checked
                    prefs.edit().putBoolean(QuickSendPrefs.VOICE_AUTO_PAUSE_MEDIA, checked).apply()
                }
            )

            Spacer(Modifier.height(8.dp))

            // —— 识别模型 ——
            SectionHeader(stringResource(R.string.voice_model_section))
            Text(
                modelStatusText(downloadState, context),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (downloadState is DownloadState.Downloading) {
                val percent = (downloadState as DownloadState.Downloading).percent
                if (percent < 0) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                } else {
                    LinearProgressIndicator(
                        progress = { percent / 100f },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                    )
                }
            }
            OutlinedTextField(
                value = modelUrl,
                onValueChange = { v ->
                    modelUrl = v
                    prefs.edit()
                        .putString(QuickSendPrefs.VOICE_MODEL_BASE_URL, v.trim().ifBlank { VoiceModelManager.DEFAULT_BASE_URL })
                        .apply()
                },
                label = { Text(stringResource(R.string.voice_model_url)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { VoiceModelManager.download(context, baseUrl(), names(), proxy()) },
                    enabled = downloadState !is DownloadState.Downloading,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        stringResource(
                            if (downloadState is DownloadState.Ready) R.string.voice_redownload
                            else R.string.voice_download
                        )
                    )
                }
                OutlinedButton(
                    onClick = {
                        VoiceModelManager.delete(context, names())
                    },
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.voice_delete_model)) }
            }
            OutlinedButton(
                onClick = {
                    prefs.edit()
                        .remove(QuickSendPrefs.VOICE_MODEL_BASE_URL)
                        .remove(QuickSendPrefs.VOICE_NAME_ENCODER)
                        .remove(QuickSendPrefs.VOICE_NAME_DECODER)
                        .remove(QuickSendPrefs.VOICE_NAME_JOINER)
                        .remove(QuickSendPrefs.VOICE_NAME_TOKENS)
                        .remove(QuickSendPrefs.VOICE_PROXY_URI)
                        .remove(QuickSendPrefs.VOICE_NATIVE_LIB_URL)
                        .remove(QuickSendPrefs.VOICE_DECODING_METHOD)
                        .remove(QuickSendPrefs.VOICE_MAX_ACTIVE_PATHS)
                        .remove(QuickSendPrefs.VOICE_BLANK_PENALTY)
                        .remove(QuickSendPrefs.VOICE_ENDPOINT_SILENCE)
                        .remove(QuickSendPrefs.VOICE_ENDPOINT_MAX_UTTER)
                        .remove(QuickSendPrefs.VOICE_NUM_THREADS)
                        .remove(QuickSendPrefs.VOICE_PROVIDER)
                        .apply()
                    modelUrl = VoiceModelManager.DEFAULT_BASE_URL
                    proxyUri = ""
                    nativeUrl = NativeLibManager.defaultUrl()
                    nameEncoder = SherpaModelNames.DEFAULT_ENCODER
                    nameDecoder = SherpaModelNames.DEFAULT_DECODER
                    nameJoiner = SherpaModelNames.DEFAULT_JOINER
                    nameTokens = SherpaModelNames.DEFAULT_TOKENS
                    params.clear()
                    loadParams(prefs, paramSpecs, params)
                    Toast.makeText(context, "已恢复默认配置", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) { Text(stringResource(R.string.voice_reset_model)) }

            Spacer(Modifier.height(8.dp))

            // —— 本地识别引擎（运行时下载 .so） ——
            SectionHeader(stringResource(R.string.voice_native_section))
            Text(
                nativeStatusText(nativeState, needsRestart.value, context),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                stringResource(R.string.voice_native_abi, NativeLibManager.deviceAbi),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
            if (nativeState is DownloadState.Downloading) {
                val percent = (nativeState as DownloadState.Downloading).percent
                if (percent < 0) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                } else {
                    LinearProgressIndicator(
                        progress = { percent / 100f },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                    )
                }
            }
            OutlinedTextField(
                value = nativeUrl,
                onValueChange = { v ->
                    nativeUrl = v
                    prefs.edit()
                        .putString(
                            QuickSendPrefs.VOICE_NATIVE_LIB_URL,
                            v.trim().ifBlank { NativeLibManager.defaultUrl() }
                        )
                        .apply()
                },
                label = { Text(stringResource(R.string.voice_native_url)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {
                        NativeLibManager.download(
                            context,
                            NativeLibManager.effectiveUrl(nativeUrl),
                            proxy()
                        )
                    },
                    enabled = nativeState !is DownloadState.Downloading,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        stringResource(
                            if (nativeState is DownloadState.Ready) R.string.voice_native_redownload
                            else R.string.voice_native_download
                        )
                    )
                }
                OutlinedButton(
                    onClick = {
                        NativeLibManager.delete(context)
                        needsRestart.value = NativeLibManager.needsRestart(context)
                    },
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.voice_native_delete)) }
            }
            Text(
                stringResource(R.string.voice_native_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            if (BuildConfig.DEBUG) {
                // Debug：实测下载 + System.load + 路径注入 + OnlineRecognizer 静态块是否成功
                // （无需 host 主程序）。结果以 Toast 显示，便于 AVD 验证动态加载链路。
                val dscope = rememberCoroutineScope()
                OutlinedButton(
                    onClick = {
                        dscope.launch {
                            val msg = when (val r = NativeLibManager.loadIfReady(context)) {
                                LoadResult.Loaded -> {
                                    val classOk = try {
                                        Class.forName("com.k2fsa.sherpa.onnx.OnlineRecognizer")
                                        "OnlineRecognizer 类初始化成功"
                                    } catch (t: Throwable) {
                                        "类初始化失败: ${t.javaClass.simpleName}: ${t.message}"
                                    }
                                    "loadIfReady=Loaded; $classOk"
                                }
                                LoadResult.NotDownloaded -> "loadIfReady=NotDownloaded"
                                is LoadResult.NeedsRestart ->
                                    "loadIfReady=NeedsRestart(loaded=${r.loaded}, downloaded=${r.downloaded})"
                                is LoadResult.Failed -> "loadIfReady=Failed: ${r.message}"
                            }
                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) { Text("测试加载（debug）") }
            }

            // —— 代理 ——
            SectionHeader(stringResource(R.string.voice_proxy_section))
            OutlinedTextField(
                value = proxyUri,
                onValueChange = { v ->
                    proxyUri = v
                    prefs.edit().putString(QuickSendPrefs.VOICE_PROXY_URI, v.trim()).apply()
                },
                label = { Text(stringResource(R.string.voice_proxy_uri)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                stringResource(R.string.voice_proxy_examples),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )

            // —— 识别参数 ——
            SectionHeader(stringResource(R.string.voice_recognition_section))
            paramSpecs.forEach { spec ->
                SettingTextFieldRow(
                    label = spec.title,
                    value = params[spec.key] ?: spec.default,
                    onValueChange = { v ->
                        params[spec.key] = v
                        val t = v.trim()
                        val e = prefs.edit()
                        val prefKey = PARAM_PREF_KEYS[spec.key]!!
                        if (t.isNotEmpty()) e.putString(prefKey, t) else e.remove(prefKey)
                        e.apply()
                    },
                    helpText = spec.help,
                    keyboardOptions = KeyboardOptions(keyboardType = spec.keyboard)
                )
                Spacer(Modifier.height(4.dp))
            }

            // —— 高级：文件名 ——
            SectionHeader(stringResource(R.string.voice_advanced_section))
            NameField(stringResource(R.string.voice_name_encoder), nameEncoder) { v ->
                nameEncoder = v
                prefs.edit()
                    .putString(QuickSendPrefs.VOICE_NAME_ENCODER, v.trim().ifBlank { SherpaModelNames.DEFAULT_ENCODER })
                    .apply()
            }
            NameField(stringResource(R.string.voice_name_decoder), nameDecoder) { v ->
                nameDecoder = v
                prefs.edit()
                    .putString(QuickSendPrefs.VOICE_NAME_DECODER, v.trim().ifBlank { SherpaModelNames.DEFAULT_DECODER })
                    .apply()
            }
            NameField(stringResource(R.string.voice_name_joiner), nameJoiner) { v ->
                nameJoiner = v
                prefs.edit()
                    .putString(QuickSendPrefs.VOICE_NAME_JOINER, v.trim().ifBlank { SherpaModelNames.DEFAULT_JOINER })
                    .apply()
            }
            NameField(stringResource(R.string.voice_name_tokens), nameTokens) { v ->
                nameTokens = v
                prefs.edit()
                    .putString(QuickSendPrefs.VOICE_NAME_TOKENS, v.trim().ifBlank { SherpaModelNames.DEFAULT_TOKENS })
                    .apply()
            }

            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.voice_usage_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun NameField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
    )
}

private fun modelStatusText(state: DownloadState, context: Context): String = when (state) {
    DownloadState.Idle -> context.getString(R.string.voice_model_status_idle)
    DownloadState.Ready -> context.getString(R.string.voice_model_status_ready)
    is DownloadState.Downloading -> state.label.ifBlank {
        context.getString(R.string.voice_downloading, state.percent)
    }
    is DownloadState.Failed -> context.getString(R.string.voice_download_failed, state.message)
}

private fun nativeStatusText(state: DownloadState, needsRestart: Boolean, context: Context): String = when {
    needsRestart -> context.getString(R.string.voice_native_status_restart)
    state is DownloadState.Ready -> context.getString(R.string.voice_native_status_loaded)
    state is DownloadState.Downloading -> state.label.ifBlank {
        context.getString(R.string.voice_downloading, state.percent)
    }
    state is DownloadState.Failed -> context.getString(R.string.voice_download_failed, state.message)
    else -> context.getString(R.string.voice_native_status_idle)
}

/** 读取代理 URI；首次升级时把旧版多字段代理迁移成一条 URI。 */
private fun loadProxyUri(prefs: android.content.SharedPreferences): String {
    prefs.getString(QuickSendPrefs.VOICE_PROXY_URI, null)?.let { return it }
    if (!prefs.getBoolean(QuickSendPrefs.VOICE_PROXY_ENABLED_LEGACY, false)) return ""
    val scheme = if (prefs.getString(QuickSendPrefs.VOICE_PROXY_TYPE_LEGACY, "HTTP") == "SOCKS") "socks5" else "http"
    val host = prefs.getString(QuickSendPrefs.VOICE_PROXY_HOST_LEGACY, "") ?: ""
    val port = prefs.getInt(QuickSendPrefs.VOICE_PROXY_PORT_LEGACY, 7890)
    val user = prefs.getString(QuickSendPrefs.VOICE_PROXY_USER_LEGACY, "") ?: ""
    val pass = prefs.getString(QuickSendPrefs.VOICE_PROXY_PASS_LEGACY, "") ?: ""
    val uri = buildString {
        append(scheme).append("://")
        if (user.isNotEmpty()) {
            append(user)
            if (pass.isNotEmpty()) append(":").append(pass)
            append("@")
        }
        append(host).append(":").append(port)
    }
    prefs.edit().putString(QuickSendPrefs.VOICE_PROXY_URI, uri).apply()
    return uri
}

private fun loadParams(
    prefs: android.content.SharedPreferences,
    specs: List<ParamSpec>,
    target: kotlin.collections.MutableMap<String, String>
) {
    specs.forEach { spec ->
        target[spec.key] = prefs.getString(PARAM_PREF_KEYS[spec.key]!!, null) ?: spec.default
    }
}
