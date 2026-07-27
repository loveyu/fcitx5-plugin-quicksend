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
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.fcitx.fcitx5.android.plugin.quicksend.voice.net.ProxyConfig
import org.fcitx.fcitx5.android.plugin.quicksend.voice.net.VoiceHttp
import org.fcitx.fcitx5.android.plugin.quicksend.voice.sherpa.SherpaModelFiles
import org.fcitx.fcitx5.android.plugin.quicksend.voice.sherpa.SherpaModelNames
import java.io.BufferedInputStream
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
 * 默认下载 `.tar.bz2` 压缩包（GitHub Releases），解压到 `sherpa/zh-large-2025/`。
 * 同时也兼容旧版逐文件下载（HuggingFace base URL）。
 */
object VoiceModelManager {

    private const val TAG = "VoiceModel"

    const val MODEL_DIR_NAME = "sherpa/zh-large-2025"

    /** 默认下载源（GitHub Releases .tar.bz2）。用户可在设置页改为镜像或 HuggingFace URL。 */
    const val DEFAULT_BASE_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30.tar.bz2"

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
        VoiceLog.i(
            TAG,
            "download: baseUrl=${baseUrl.trimEnd('/')}, proxy=${if (proxy.enabled) "${proxy.type} ${proxy.host}:${proxy.port}" else "none"}"
        )
        job = scope.launch {
            _state.value = DownloadState.Downloading(0)
            runCatching {
                if (baseUrl.endsWith(".tar.bz2", ignoreCase = true)) {
                    downloadAndExtractTarBz2(context, baseUrl, names, proxy)
                } else {
                    downloadIndividualFiles(context, baseUrl.trimEnd('/'), names, proxy)
                }
            }
                .onSuccess {
                    val ready = isReady(context, names)
                    _state.value = if (ready) DownloadState.Ready
                    else DownloadState.Failed("Model files incomplete after download")
                    VoiceLog.i(TAG, "download finished, ready=$ready")
                }
                .onFailure {
                    VoiceLog.e(TAG, "download failed: ${it.message}", it)
                    _state.value = DownloadState.Failed(it.message ?: it.javaClass.simpleName)
                }
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

    private suspend fun downloadAndExtractTarBz2(
        context: Context,
        url: String,
        names: SherpaModelNames,
        proxy: ProxyConfig
    ) {
        val dir = modelDir(context)
        dir.mkdirs()
        val client = VoiceHttp.client(proxy)
        val total = contentLength(client, url).coerceAtLeast(1L)
        VoiceLog.i(TAG, "download tar.bz2: $url, total≈$total bytes → ${dir.absolutePath}")

        val tmp = File(dir, "model.tar.bz2.part")
        var downloaded = 0L
        streamToFile(client, url, tmp) { delta ->
            downloaded += delta
            val pct = (downloaded * 100 / total).toInt().coerceIn(0, 100)
            _state.value = DownloadState.Downloading(pct)
        }
        VoiceLog.i(TAG, "download done (${tmp.length()} bytes), extracting...")

        try {
            extractTarBz2(tmp, dir, names)
            VoiceLog.i(TAG, "extraction complete")
        } finally {
            runCatching { tmp.delete() }
        }
    }

    /** 解压 .tar.bz2 → [destDir]，跳过顶层目录名，仅提取 [names.all()] 中的文件。 */
    private fun extractTarBz2(archive: File, destDir: File, names: SherpaModelNames) {
        val wanted = names.all().toSet()
        var found = 0
        BZip2CompressorInputStream(BufferedInputStream(archive.inputStream())).use { bzIn ->
            TarArchiveInputStream(bzIn).use { tarIn ->
                while (true) {
                    val entry: TarArchiveEntry = tarIn.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val name = File(entry.name).name
                    if (name !in wanted) continue
                    // 跳过顶层目录：entry.name 形如 "sherpa-onnx-.../encoder.int8.onnx"
                    val target = File(destDir, name)
                    target.outputStream().use { out ->
                        tarIn.copyTo(out)
                    }
                    found++
                    VoiceLog.d(TAG, "extracted: $name → ${target.absolutePath} (${target.length()} bytes)")
                }
            }
        }
        if (found < wanted.size) {
            throw IOException("Archive missing files: expected $wanted, found $found")
        }
    }

    private suspend fun downloadIndividualFiles(
        context: Context,
        baseUrl: String,
        names: SherpaModelNames,
        proxy: ProxyConfig
    ) = withContext(Dispatchers.IO) {
        val dir = modelDir(context)
        dir.mkdirs()
        val client = VoiceHttp.client(proxy)
        val total = names.all().sumOf { contentLength(client, "$baseUrl/$it") }.coerceAtLeast(1L)
        VoiceLog.i(TAG, "download: ${names.all().size} files, total≈$total bytes → ${dir.absolutePath}")
        var downloaded = 0L
        for (name in names.all()) {
            ensureActive()
            val target = File(dir, name)
            val part = File(dir, "$name.part")
            VoiceLog.d(TAG, "download file: $name")
            streamToFile(client, "$baseUrl/$name", part) { delta ->
                downloaded += delta
                val pct = (downloaded * 100 / total).toInt().coerceIn(0, 100)
                _state.value = DownloadState.Downloading(pct)
            }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) {
                throw IOException("Failed to finalize $name")
            }
            VoiceLog.d(TAG, "download file done: $name (${target.length()} bytes)")
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
