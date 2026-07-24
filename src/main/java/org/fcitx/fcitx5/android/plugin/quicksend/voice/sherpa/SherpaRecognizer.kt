/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.quicksend.voice.sherpa

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.plugin.quicksend.voice.RecognitionEvent
import org.fcitx.fcitx5.android.plugin.quicksend.voice.SpeechRecognizer
import java.io.File

/**
 * 基于 Sherpa-ONNX 的本地流式识别器（中文 Zipformer-transducer）。
 *
 * 录音：16kHz 单声道 PCM16；识别：OnlineRecognizer 流式，~10fps 产出 Partial；
 * [stop] 触发 inputFinished 并产出 Final；[cancel] 直接清理不产出结果。
 *
 * 调用方须先授予 RECORD_AUDIO 并确保模型已下载到 [modelDir]。
 *
 * 线程模型：**所有原生对象（recognizer/stream/record）只在唯一的 [nativeThread] 上创建、
 * 使用、释放**。[start]/[stop]/[cancel]/[releaseNow] 仅翻转 volatile 标志并 join 该线程，
 * 绝不直接接触原生对象——从而避免在识别循环仍在调用 acceptWaveform/decode 时释放原生句柄
 * 造成 use-after-free（曾导致 acceptWaveform 处 native SIGSEGV）。
 */
class SherpaRecognizer(
    @Suppress("unused") private val context: Context,
    private val modelDir: File,
    private val names: SherpaModelNames = SherpaModelNames()
) : SpeechRecognizer {

    private val sampleRate = 16000

    private val eventChannel = Channel<RecognitionEvent>(Channel.BUFFERED)

    /** 识别循环运行标志，仅由 nativeThread 读取、由控制方法置 false。 */
    @Volatile
    private var running = false

    /** true=stop 时 flush 最终结果（Final）；false=cancel 丢弃。 */
    @Volatile
    private var commitFinal = false

    @Volatile
    private var started = false

    @Volatile
    private var nativeThread: Thread? = null

    // 原生句柄：仅在 nativeThread 上访问
    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private var record: AudioRecord? = null

    override val events: Flow<RecognitionEvent> = eventChannel.receiveAsFlow()

    override suspend fun start() {
        if (started) return
        val ready = CompletableDeferred<Boolean>()
        running = true
        commitFinal = false
        val t = Thread({ runNativeSession(ready) }, "sherpa-native").apply { isDaemon = true }
        nativeThread = t
        t.start()
        // 等待原生对象初始化完成（加载模型/AudioRecord 较重）
        if (!ready.await()) {
            // nativeSession 已发 Error 并清理、关闭 channel
            throw IllegalStateException("Sherpa recognizer initialization failed")
        }
        started = true
    }

    /**
     * nativeThread 主体：建原生对象 → 录音循环 → 按 [commitFinal] 做收尾 → 清理。
     * 全程只在此线程接触原生对象；任何异常都走 finally 释放。
     */
    private fun runNativeSession(ready: CompletableDeferred<Boolean>) {
        var initialized = false
        try {
            val files = SherpaModelFiles.resolve(modelDir, names)
            val config = OnlineRecognizerConfig(
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = files.encoder,
                        decoder = files.decoder,
                        joiner = files.joiner
                    ),
                    tokens = files.tokens,
                    numThreads = 2,
                    debug = false
                ),
                // 端点检测（VAD）开启，phase1 默认手动停止；接口已留自动完成
                enableEndpoint = true
            )
            val rec = OnlineRecognizer(config = config)
            recognizer = rec
            val st = rec.createStream()
            stream = st

            val ar = createAudioRecord()
            if (ar.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { ar.release() }
                throw IllegalStateException("AudioRecord initialization failed")
            }
            ar.startRecording()
            record = ar

            initialized = true
            ready.complete(true)

            recordingLoop(rec, st, ar)

            // 正常停止（running 被置 false 后循环退出）：flush 最终结果
            if (commitFinal) {
                try {
                    st.inputFinished()
                    while (rec.isReady(st)) rec.decode(st)
                    val text = rec.getResult(st).text.trim()
                    eventChannel.trySend(RecognitionEvent.Final(text))
                } catch (e: Throwable) {
                    eventChannel.trySend(RecognitionEvent.Error(e))
                }
            }
        } catch (e: Throwable) {
            eventChannel.trySend(RecognitionEvent.Error(e))
            if (!initialized) ready.complete(false)
        } finally {
            cleanup()
            runCatching { eventChannel.close() }
        }
    }

    private fun recordingLoop(rec: OnlineRecognizer, st: OnlineStream, ar: AudioRecord) {
        val samplesPerRead = sampleRate / 10 // 100ms ≈ 1600 samples
        val buf16 = ShortArray(samplesPerRead)
        val bufFloat = FloatArray(samplesPerRead)
        var lastPartial = ""
        while (running) {
            val n = ar.read(buf16, 0, samplesPerRead)
            if (n <= 0) continue
            for (i in 0 until n) {
                bufFloat[i] = buf16[i] / 32768.0f
            }
            st.acceptWaveform(bufFloat.copyOfRange(0, n), sampleRate)
            while (rec.isReady(st)) rec.decode(st)
            val text = rec.getResult(st).text.trim()
            if (text != lastPartial) {
                lastPartial = text
                eventChannel.trySend(RecognitionEvent.Partial(text))
            }
        }
    }

    override suspend fun stop() {
        if (!started) return
        commitFinal = true
        running = false
        awaitNativeThread()
        started = false
    }

    override suspend fun cancel() {
        if (!started) return
        commitFinal = false
        running = false
        awaitNativeThread()
        started = false
    }

    private suspend fun awaitNativeThread() {
        val t = nativeThread ?: return
        // join 是阻塞调用，切到 IO 线程等待 nativeThread 结束（它会在 ~100ms 内退出）
        withContext(Dispatchers.IO) {
            runCatching { t.join(2_000) }
        }
    }

    /**
     * 同步强制释放（不等待识别循环产出结果）。用于服务销毁等不可挂起的场景，幂等。
     * 仅置 running=false 并 join nativeThread（由其自身 finally 释放原生句柄），
     * 本方法不直接接触原生对象，故即使与 stop/cancel 并发也安全。
     */
    fun releaseNow() {
        commitFinal = false
        running = false
        nativeThread?.let { runCatching { it.join(2_000) } }
        nativeThread = null
        runCatching { eventChannel.close() }
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

    /** 仅在 nativeThread 上调用：释放原生句柄与 AudioRecord。 */
    private fun cleanup() {
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        runCatching { stream?.release() }
        stream = null
        runCatching { recognizer?.release() }
        recognizer = null
    }
}
