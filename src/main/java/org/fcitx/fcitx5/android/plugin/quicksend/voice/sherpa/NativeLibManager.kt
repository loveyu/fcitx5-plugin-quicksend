/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.quicksend.voice.sherpa

import android.content.Context
import android.os.Build
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
import org.fcitx.fcitx5.android.plugin.quicksend.BuildConfig
import org.fcitx.fcitx5.android.plugin.quicksend.voice.DownloadState
import org.fcitx.fcitx5.android.plugin.quicksend.voice.VoiceLog
import org.fcitx.fcitx5.android.plugin.quicksend.voice.net.ProxyConfig
import org.fcitx.fcitx5.android.plugin.quicksend.voice.net.VoiceHttp
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * 运行时下载、解压并加载 Sherpa-ONNX 原生库（4 个 .so，按设备 ABI 取一份）。
 *
 * 背景：APK 不再打包 .so，体积大幅缩小；仅在用户启用本地语音识别时按需下载。
 * 发布时各 ABI 的 4 个 .so 打成 zip 上传到 GitHub Release 资产。
 *
 * - 下载地址默认值由构建期生成（[BuildConfig.NATIVE_LIB_DEFAULT_URL]，含 `{ABI}` 占位符），
 *   运行时按设备 ABI 替换；用户可在设置页改成镜像/自建源。
 * - 与模型下载共用同一个代理（[ProxyConfig]，键 `voice_proxy_uri`）。
 * - .so 一个进程只能加载一次，更换版本需重启 App（见 [NativeLibLoader]）。
 */
object NativeLibManager {

    private const val TAG = "NativeLibMgr"

    /** 已发布的 ABI 集合（与 CI 上传的 .so zip 一致）。 */
    private val PUBLISHED_ABIS = setOf("arm64-v8a", "armeabi-v7a", "x86_64")

    /** 设备首选 ABI（仅取已发布集合内的；都不命中则回退 arm64-v8a）。 */
    val deviceAbi: String
        get() = Build.SUPPORTED_ABIS.firstOrNull { it in PUBLISHED_ABIS } ?: "arm64-v8a"

    /** .so 安装目录：<filesDir>/native_libs/sherpa/<abi>/。 */
    fun libDir(context: Context): File =
        File(context.filesDir, "native_libs/sherpa/$deviceAbi")

    private fun markerFile(context: Context): File = File(libDir(context), ".source_url")

    /** 构建期默认下载地址（已替换为本机 ABI）。 */
    fun defaultUrl(): String =
        BuildConfig.NATIVE_LIB_DEFAULT_URL.replace("{ABI}", deviceAbi)

    /**
     * 实际生效的下载地址：用户偏好非空则用偏好，否则用 [defaultUrl]。
     */
    fun effectiveUrl(savedUrl: String?): String {
        val s = savedUrl?.trim().orEmpty()
        return s.ifBlank { defaultUrl() }
    }

    /** 4 个 .so 文件名（与 AAR 内 jni/<abi>/ 一致）。 */
    private val EXPECTED_LIBS = listOf(
        "libonnxruntime.so",
        "libsherpa-onnx-c-api.so",
        "libsherpa-onnx-cxx-api.so",
        "libsherpa-onnx-jni.so"
    )

    /** 已下载就绪（4 个 .so 均存在且非空）。 */
    fun isReady(context: Context): Boolean = EXPECTED_LIBS.all {
        File(libDir(context), it).let { f -> f.isFile && f.length() > 0 }
    }

    /** 已下载的版本标识（源 URL）；未就绪返回 null。 */
    fun downloadedVersion(context: Context): String? {
        if (!isReady(context)) return null
        val m = markerFile(context)
        return runCatching { if (m.exists()) m.readText().trim() else null }.getOrNull()
    }

    /** 是否需要重启才能用上新下载的 .so（进程内已加载旧版本）。 */
    fun needsRestart(context: Context): Boolean {
        val loaded = NativeLibLoader.loadedVersion() ?: return false
        val downloaded = downloadedVersion(context) ?: return false
        return loaded != downloaded
    }

    // ── 下载状态流 ────────────────────────────────────────────────────────────

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = _state.asStateFlow()
    private var job: Job? = null

    /** 启动下载（若进行中先取消）。状态经 [state] 流出。 */
    fun download(context: Context, url: String, proxy: ProxyConfig) {
        job?.cancel()
        val effective = url.trim().ifBlank { defaultUrl() }
        VoiceLog.i(
            TAG,
            "download: url=$effective, abi=$deviceAbi, proxy=${if (proxy.enabled) "${proxy.type} ${proxy.host}:${proxy.port}" else "none"}"
        )
        job = scope.launch {
            _state.value = DownloadState.Downloading(0)
            runCatching { downloadAndExtract(context, effective, proxy) }
                .onSuccess {
                    val ready = isReady(context)
                    _state.value = if (ready) DownloadState.Ready
                    else DownloadState.Failed("Native libs incomplete after download")
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

    fun delete(context: Context) {
        job?.cancel()
        job = null
        val dir = libDir(context)
        EXPECTED_LIBS.forEach { runCatching { File(dir, it).delete() } }
        runCatching { markerFile(context).delete() }
        _state.value = DownloadState.Idle
    }

    /** 刷新就绪态（设置页 onResume 调用）。 */
    fun refresh(context: Context) {
        if (_state.value !is DownloadState.Downloading) {
            _state.value = if (isReady(context)) DownloadState.Ready else DownloadState.Idle
        }
    }

    private suspend fun downloadAndExtract(context: Context, url: String, proxy: ProxyConfig) {
        val dir = libDir(context)
        dir.mkdirs()
        val client = VoiceHttp.client(proxy)
        val total = contentLength(client, url).coerceAtLeast(1L)
        VoiceLog.i(TAG, "download zip: $url, total≈$total bytes → ${dir.absolutePath}")

        val tmp = File(context.cacheDir, "native_lib_${System.currentTimeMillis()}.zip")
        var downloaded = 0L
        try {
            streamToFile(client, url, tmp) { delta ->
                downloaded += delta
                val pct = (downloaded * 100 / total).toInt().coerceIn(0, 100)
                _state.value = DownloadState.Downloading(pct, "下载中 $pct%")
            }
            VoiceLog.i(TAG, "download done (${tmp.length()} bytes), extracting...")
            _state.value = DownloadState.Downloading(-1, "解压中…")
            extractZip(tmp, dir)
            markerFile(context).writeText(url)
        } finally {
            runCatching { tmp.delete() }
        }
    }

    /** 解压 zip 中所有 .so（取 basename）到 [destDir]，要求 4 个预期 .so 齐全。临时文件 + 原子重命名。 */
    private fun extractZip(zip: File, destDir: File) {
        val names = HashSet<String>()
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (entry.isDirectory) continue
                if (!entry.name.endsWith(".so", ignoreCase = true)) continue
                val base = File(entry.name).name
                names.add(base)
                val target = File(destDir, base)
                val part = File(destDir, "$base.part")
                part.outputStream().buffered().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = zis.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                    }
                }
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) {
                    throw IOException("Failed to finalize $base")
                }
                VoiceLog.d(TAG, "extracted: $base (${target.length()} bytes)")
            }
        }
        val missing = EXPECTED_LIBS.filter { it !in names }
        if (missing.isNotEmpty()) {
            throw IOException("zip missing native libs: $missing, got=$names")
        }
    }

    // ── 加载 ──────────────────────────────────────────────────────────────────

    /** 加载已下载的 .so；返回 [LoadResult]。 */
    suspend fun loadIfReady(context: Context): LoadResult = withContext(Dispatchers.IO) {
        val version = downloadedVersion(context)
            ?: return@withContext LoadResult.NotDownloaded
        val ok = NativeLibLoader.ensureLoaded(context, libDir(context), version)
        when {
            ok -> LoadResult.Loaded
            NativeLibLoader.isLoaded -> LoadResult.NeedsRestart(
                loaded = NativeLibLoader.loadedVersion(),
                downloaded = version
            )
            else -> LoadResult.Failed("Native lib load failed (see log)")
        }
    }

    // ── HTTP 工具（与 VoiceModelManager 一致风格） ────────────────────────────

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
            dest.outputStream().buffered().use { out ->
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

/** [NativeLibManager.loadIfReady] 的结果。 */
sealed interface LoadResult {
    /** 加载成功（或本进程已加载同版本）。 */
    object Loaded : LoadResult
    /** 尚未下载 .so。 */
    object NotDownloaded : LoadResult
    /** 进程内已加载旧版本，需重启才能用新版本。 */
    data class NeedsRestart(val loaded: String?, val downloaded: String) : LoadResult
    /** 加载失败。 */
    data class Failed(val message: String) : LoadResult
}
