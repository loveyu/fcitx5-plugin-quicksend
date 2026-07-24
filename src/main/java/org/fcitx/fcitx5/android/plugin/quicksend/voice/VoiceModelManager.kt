/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.quicksend.voice

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.fcitx.fcitx5.android.plugin.quicksend.voice.net.ProxyConfig
import org.fcitx.fcitx5.android.plugin.quicksend.voice.net.VoiceHttp
import org.fcitx.fcitx5.android.plugin.quicksend.voice.sherpa.SherpaModelFiles
import org.fcitx.fcitx5.android.plugin.quicksend.voice.sherpa.SherpaModelNames
import java.io.File
import java.io.IOException

/**
 * 模型下载状态。
 */
sealed interface DownloadState {
    object Idle : DownloadState
    data class Downloading(val percent: Int) : DownloadState // 0..100；-1 表示未知总大小
    object Ready : DownloadState
    data class Failed(val message: String) : DownloadState
}

/**
 * 运行时下载 + 管理 Sherpa 中文流式模型。
 *
 * 默认保存目录：应用专用外部目录 `sherpa/zh-14m/`。
 * 下载源为用户可配置的 HTTP base URL（默认 HuggingFace），逐文件流式下载，
 * 全部完成后校验 4 个文件并写就绪状态。
 */
object VoiceModelManager {

    const val MODEL_DIR_NAME = "sherpa/zh-14m"

    /** 默认下载源（HuggingFace）。用户可在设置页改为镜像。 */
    const val DEFAULT_BASE_URL =
        "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23/resolve/main"

    fun modelDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, MODEL_DIR_NAME)

    fun isReady(context: Context, names: SherpaModelNames = SherpaModelNames()): Boolean = try {
        SherpaModelFiles.resolve(modelDir(context), names)
        true
    } catch (e: Exception) {
        false
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = _state.asStateFlow()
    private var job: Job? = null

    /** 启动下载（若进行中则先取消）。状态经 [state] 流出。 */
    fun download(context: Context, baseUrl: String, names: SherpaModelNames, proxy: ProxyConfig) {
        job?.cancel()
        job = scope.launch {
            _state.value = DownloadState.Downloading(0)
            runCatching { doDownload(context, baseUrl.trimEnd('/'), names, proxy) }
                .onSuccess {
                    _state.value = if (isReady(context, names)) DownloadState.Ready
                    else DownloadState.Failed("Model files incomplete after download")
                }
                .onFailure { _state.value = DownloadState.Failed(it.message ?: it.javaClass.simpleName) }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        if (_state.value is DownloadState.Downloading) _state.value = DownloadState.Idle
    }

    fun delete(context: Context, names: SherpaModelNames = SherpaModelNames()) {
        job?.cancel()
        job = null
        val dir = modelDir(context)
        (names.all() + names.all().map { "$it.part" }).forEach { runCatching { File(dir, it).delete() } }
        _state.value = DownloadState.Idle
    }

    /** 下载完成或删除后，刷新就绪态（设置页 onResume 调用）。 */
    fun refresh(context: Context, names: SherpaModelNames = SherpaModelNames()) {
        if (_state.value !is DownloadState.Downloading) {
            _state.value = if (isReady(context, names)) DownloadState.Ready else DownloadState.Idle
        }
    }

    private suspend fun doDownload(
        context: Context,
        baseUrl: String,
        names: SherpaModelNames,
        proxy: ProxyConfig
    ) = withContext(Dispatchers.IO) {
        val dir = modelDir(context)
        dir.mkdirs()
        val client = VoiceHttp.client(proxy)
        // 先 HEAD 汇总各文件大小用于进度（不可得则为 0 → 进度不可知）
        val total = names.all().sumOf { contentLength(client, "$baseUrl/$it") }.coerceAtLeast(1L)
        var downloaded = 0L
        for (name in names.all()) {
            ensureActive()
            val target = File(dir, name)
            val part = File(dir, "$name.part")
            streamToFile(client, "$baseUrl/$name", part) { delta ->
                downloaded += delta
                val pct = (downloaded * 100 / total).toInt().coerceIn(0, 100)
                _state.value = DownloadState.Downloading(pct)
            }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) {
                throw IOException("Failed to finalize $name")
            }
        }
        SherpaModelFiles.resolve(dir, names)
    }

    private fun contentLength(client: okhttp3.OkHttpClient, url: String): Long =
        runCatching {
            client.newCall(Request.Builder().url(url).head().build()).execute().use { resp ->
                resp.body?.contentLength() ?: 0L
            }
        }.getOrDefault(0L).coerceAtLeast(0L)

    private suspend fun streamToFile(
        client: okhttp3.OkHttpClient,
        url: String,
        dest: File,
        onProgress: (Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        val response = client.newCall(Request.Builder().url(url).build()).execute()
        response.use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for $url")
            val input = resp.body?.byteStream() ?: throw IOException("Empty body for $url")
            dest.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    ensureActive()
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    onProgress(n.toLong())
                }
            }
        }
    }
}
