/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.quicksend.voice.remote.glm

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.fcitx.fcitx5.android.plugin.quicksend.voice.ErrorKind
import org.fcitx.fcitx5.android.plugin.quicksend.voice.RecognitionEvent
import org.fcitx.fcitx5.android.plugin.quicksend.voice.RemoteAsrException
import org.fcitx.fcitx5.android.plugin.quicksend.voice.SpeechRecognizer
import org.fcitx.fcitx5.android.plugin.quicksend.voice.VoiceLog
import org.fcitx.fcitx5.android.plugin.quicksend.voice.net.ProxyConfig
import org.fcitx.fcitx5.android.plugin.quicksend.voice.net.applyProxy
import org.fcitx.fcitx5.android.plugin.quicksend.voice.remote.GlmAsrBackend
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * 智谱 GLM-ASR-2512 语音转文本识别器（HTTP REST multipart + SSE）。
 *
 * 协议：HTTP POST multipart/form-data 上传完整 WAV → `stream=true` 时服务端通过 SSE 下发
 * `transcript.text.delta`（增量）/ `transcript.text.done`（完成）/ `[DONE]`（流结束）。
 *
 * 非 WebSocket 实时流，不适合逐帧推流。为提高用户体验，录音期间做周期性「预览上传」：
 * - [start] 时启动录音线程 + 预视协程（每隔 [PREVIEW_INTERVAL_MS] 把当前已累积音频快照
 *   上传并解析 SSE delta，实时下发 Partial 到浮层）；
 * - [stop] 时取消预视、做最终上传（[finalMode]=true），得到 Final 后结束会话。
 */
class GlmAsrRecognizer(private val config: GlmAsrBackend) : SpeechRecognizer {

    private val eventChannel = Channel<RecognitionEvent>(Channel.BUFFERED)
    override val events: Flow<RecognitionEvent> = eventChannel.receiveAsFlow()
    private val proxyConfig = ProxyConfig.fromUri(config.proxy)
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .applyProxy(proxyConfig)
        .build()

    @Volatile private var running = false
    @Volatile private var started = false
    @Volatile private var nativeThread: Thread? = null
    @Volatile private var record: AudioRecord? = null

    private val sampleRate = 16000
    private val audioBuffer = ByteArrayOutputStream()

    private val previewScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var previewJob: Job? = null

    override suspend fun start() {
        if (started) return
        VoiceLog.i(TAG, "start: recording")
        running = true
        started = true
        audioBuffer.reset()
        val t = Thread({ recordingLoop() }, "glm-asr-rec").apply { isDaemon = true }
        nativeThread = t
        t.start()

        previewJob = previewScope.launch {
            delay(PREVIEW_INITIAL_DELAY_MS)
            while (isActive && running) {
                val pcm: ByteArray = synchronized(audioBuffer) { audioBuffer.toByteArray() }
                if (pcm.size >= MIN_PCM_BYTES_FOR_PREVIEW) {
                    try {
                        withContext(Dispatchers.IO) { uploadAndParse(pcm, finalMode = false) }
                    } catch (_: CancellationException) {
                        throw CancellationException("preview cancelled")
                    } catch (e: Throwable) {
                        VoiceLog.w(TAG, "preview upload failed", e)
                    }
                }
                delay(PREVIEW_INTERVAL_MS)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun recordingLoop() {
        try {
            val ar = createAudioRecord()
            if (ar.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { ar.release() }
                eventChannel.trySend(RecognitionEvent.Error(IllegalStateException("AudioRecord init failed")))
                return
            }
            ar.startRecording()
            record = ar
            val chunkSamples = sampleRate / 10 // 100ms
            val buf = ShortArray(chunkSamples)
            while (running) {
                val n = ar.read(buf, 0, chunkSamples)
                if (n <= 0) continue
                val pcmBytes = shortArrayToBytes(buf, n)
                synchronized(audioBuffer) { audioBuffer.write(pcmBytes) }
                if (audioBuffer.size() >= MAX_PCM_BYTES) {
                    VoiceLog.w(TAG, "audio buffer full (${audioBuffer.size()} bytes), stopping")
                    running = false
                }
            }
            runCatching { ar.stop() }
        } catch (e: Throwable) {
            VoiceLog.e(TAG, "recording error", e)
            eventChannel.trySend(RecognitionEvent.Error(e))
        } finally {
            runCatching { record?.release() }
            record = null
        }
    }

    override suspend fun stop() {
        if (!started) return
        VoiceLog.i(TAG, "stop: uploading ${audioBuffer.size()} bytes")
        running = false
        previewJob?.cancel()
        previewJob = null
        awaitNativeThread()
        nativeThread = null

        val pcmBytes: ByteArray = synchronized(audioBuffer) { audioBuffer.toByteArray() }
        if (pcmBytes.isEmpty()) {
            eventChannel.trySend(RecognitionEvent.Final(""))
            started = false
            return
        }
        try {
            withContext(Dispatchers.IO) { uploadAndParse(pcmBytes, finalMode = true) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            VoiceLog.e(TAG, "upload failed", e)
            val kind = classifyHttpError(e)
            eventChannel.trySend(RecognitionEvent.Error(RemoteAsrException(e.message ?: "upload failed", kind, e), kind))
        }
        started = false
    }

    override suspend fun cancel() {
        if (!started) return
        VoiceLog.i(TAG, "cancel")
        running = false
        previewJob?.cancel()
        previewJob = null
        awaitNativeThread()
        nativeThread = null
        started = false
    }

    override fun releaseNow() {
        VoiceLog.i(TAG, "releaseNow")
        running = false
        previewJob?.cancel()
        previewScope.cancel()
        nativeThread?.let { runCatching { it.join(2_000) } }
        nativeThread = null
        runCatching { eventChannel.close() }
    }

    private suspend fun awaitNativeThread() {
        val t = nativeThread ?: return
        withContext(Dispatchers.IO) {
            val done = runCatching { t.join(2_000); !t.isAlive }.getOrDefault(false)
            if (!done) VoiceLog.w(TAG, "native thread still alive after join(2s)")
        }
    }

    /**
     * 上传 PCM 数据（封装为 WAV）→ 解析 SSE 响应 → 下发事件。
     *
     * [finalMode]=true：点击「完成」后的最终上传，正常下发 Partial + Final。
     * [finalMode]=false：录音期间的预视上传，仅下发 Partial（绝不发 Final），且受 [running] 闸门
     * 保护——若 [stop] 已复位 [running]=false（例如用户提前点了完成），后续 delta 不再下发，
     * 避免在最终结果之后平添一条过时 partial。
     */
    private fun uploadAndParse(pcmBytes: ByteArray, finalMode: Boolean = true) {
        val wavFile = pcmToWavFile(pcmBytes)
        if (finalMode) {
            eventChannel.trySend(RecognitionEvent.Partial("")) // 触发 UI 进入提交态
        }
        try {
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "audio.wav",
                    wavFile.asRequestBody("audio/wav".toMediaType()))
                .addFormDataPart("model", "glm-asr-2512")
                .addFormDataPart("stream", "true")
                .apply {
                    if (config.hotwords.isNotBlank()) {
                        config.hotwords.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                            .forEach { addFormDataPart("hotwords", it) }
                    }
                }
                .build()

            val request = Request.Builder()
                .url("${config.baseUrl}/paas/v4/audio/transcriptions")
                .addHeader("Authorization", "Bearer ${config.apiKey}")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string() ?: "HTTP ${response.code}"
                    val kind = when (response.code) {
                        401, 403 -> ErrorKind.RemoteAuth
                        in 500..599 -> ErrorKind.RemoteOverload
                        else -> ErrorKind.Generic
                    }
                    throw RemoteAsrException("glm asr HTTP ${response.code}: $body", kind)
                }
                response.body?.let { body ->
                    val sb = StringBuilder()
                    body.source().use { source ->
                        while (!source.exhausted()) {
                            val line = source.readUtf8Line() ?: break
                            if (line.startsWith("data: ") && line.length > 6) {
                                val data = line.substring(6)
                                if (data == "[DONE]") {
                                    if (finalMode) {
                                        val finalText = sb.toString()
                                        VoiceLog.i(TAG, "sse done, final: $finalText")
                                        eventChannel.trySend(RecognitionEvent.Final(finalText))
                                    }
                                    break
                                }
                                try {
                                    val obj = JSONObject(data)
                                    val type = obj.optString("type", "")
                                    val delta = obj.optString("delta", "")
                                    when (type) {
                                        "transcript.text.delta" -> {
                                            if (delta.isNotEmpty()) {
                                                sb.append(delta)
                                                if (finalMode || running) {
                                                    VoiceLog.d(TAG, "delta: $delta → partial: $sb")
                                                    eventChannel.trySend(RecognitionEvent.Partial(sb.toString()))
                                                }
                                            }
                                        }
                                        "transcript.text.done" -> {
                                            val finalInDone = obj.optString("text", "")
                                            if (finalInDone.isNotEmpty()) {
                                                sb.setLength(0)
                                                sb.append(finalInDone)
                                            }
                                            if (finalMode) {
                                                val finalText = sb.toString()
                                                VoiceLog.i(TAG, "done: $finalText")
                                                eventChannel.trySend(RecognitionEvent.Final(finalText))
                                            } else if (running) {
                                                eventChannel.trySend(RecognitionEvent.Partial(sb.toString()))
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    VoiceLog.w(TAG, "bad sse data: $data", e)
                                }
                            }
                        }
                    }
                } ?: throw RemoteAsrException("empty response body", ErrorKind.Generic)
            }
        } finally {
            wavFile.delete()
        }
    }

    /**
     * 原始 PCM16 单声道数据 → 临时 WAV 文件（加 RIFF 头）。
     */
    private fun pcmToWavFile(pcm: ByteArray): File {
        val dataSize = pcm.size
        val file = File.createTempFile("glm_asr_", ".wav")
        RandomAccessFile(file, "rw").use { raf ->
            // RIFF header
            val header = ByteBuffer.allocate(44).apply {
                order(ByteOrder.LITTLE_ENDIAN)
                putInt(0x46464952)                // "RIFF"
                putInt(36 + dataSize)              // chunk size
                putInt(0x45564157)                 // "WAVE"
                putInt(0x20746D66)                 // "fmt "
                putInt(16)                         // subchunk1 size
                putShort(1)                        // PCM
                putShort(1)                        // mono
                putInt(sampleRate)
                putInt(sampleRate * 2)             // byte rate (16000 * 1 * 2)
                putShort(2)                        // block align
                putShort(16)                       // bits per sample
                putInt(0x61746164)                 // "data"
                putInt(dataSize)
            }.array()
            raf.write(header)
            raf.write(pcm)
        }
        return file
    }

    private fun classifyHttpError(t: Throwable): ErrorKind = when {
        t is RemoteAsrException -> t.kind
        else -> ErrorKind.Generic
    }

    private fun shortArrayToBytes(src: ShortArray, count: Int): ByteArray {
        val bytes = ByteArray(count * 2)
        for (i in 0 until count) {
            val v = src[i].toInt()
            bytes[i * 2] = (v and 0xFF).toByte()
            bytes[i * 2 + 1] = (v shr 8 and 0xFF).toByte()
        }
        return bytes
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(): AudioRecord {
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufSize = maxOf(minBuf * 2, sampleRate * 2)
        return AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufSize
        )
    }

    companion object {
        private const val TAG = "GlmASR"
        /** 最大 PCM 字节数 = 30s × 16000Hz × 2 bytes。 */
        private const val MAX_PCM_BYTES = 30 * 16000 * 2
        /** 首次预视上传延后，给录音攒够一段可识别音频（1.5s）。 */
        private const val PREVIEW_INITIAL_DELAY_MS = 1500L
        /** 后续预视上传间隔（2s）。 */
        private const val PREVIEW_INTERVAL_MS = 2000L
        /** 预视上传最小 PCM 字节数（1.5s × 16000Hz × 2 = 48000），不足则跳过一次。 */
        private const val MIN_PCM_BYTES_FOR_PREVIEW = 48000
    }
}
