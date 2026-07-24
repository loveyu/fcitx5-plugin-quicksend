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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.plugin.quicksend.voice.RecognitionEvent
import org.fcitx.fcitx5.android.plugin.quicksend.voice.SpeechRecognizer
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * 基于 Sherpa-ONNX 的本地流式识别器（中文 Zipformer-transducer）。
 *
 * 录音：16kHz 单声道 PCM16；识别：OnlineRecognizer 流式，~10fps 产出 Partial；
 * stop() 触发 inputFinished 并产出 Final；cancel() 直接清理不产出结果。
 *
 * 调用方须先授予 RECORD_AUDIO 并确保模型已下载到 [modelDir]。
 */
class SherpaRecognizer(
    @Suppress("unused") private val context: Context,
    private val modelDir: File,
    private val names: SherpaModelNames = SherpaModelNames()
) : SpeechRecognizer {

    private val sampleRate = 16000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val eventChannel = Channel<RecognitionEvent>(Channel.BUFFERED)

    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private var record: AudioRecord? = null
    private var loopJob: Job? = null

    @Volatile
    private var active = false

    override val events: Flow<RecognitionEvent> = eventChannel.receiveAsFlow()

    override suspend fun start() {
        if (active) return
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
            cleanup()
            throw IllegalStateException("AudioRecord initialization failed")
        }
        ar.startRecording()
        record = ar
        active = true
        loopJob = scope.launch { recordingLoop(rec, st, ar) }
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

    private suspend fun recordingLoop(rec: OnlineRecognizer, st: OnlineStream, ar: AudioRecord) {
        val samplesPerRead = sampleRate / 10 // 100ms ≈ 1600 samples
        val buf16 = ShortArray(samplesPerRead)
        val bufFloat = FloatArray(samplesPerRead)
        var lastPartial = ""
        try {
            while (coroutineContext.isActive && active) {
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
                    eventChannel.send(RecognitionEvent.Partial(text))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            eventChannel.send(RecognitionEvent.Error(e))
        }
    }

    override suspend fun stop() {
        if (!active) {
            cleanup()
            eventChannel.close()
            return
        }
        active = false
        loopJob?.join()
        val rec = recognizer
        val st = stream
        if (rec != null && st != null) {
            try {
                st.inputFinished()
                while (rec.isReady(st)) rec.decode(st)
                val text = rec.getResult(st).text.trim()
                eventChannel.send(RecognitionEvent.Final(text))
            } catch (e: Throwable) {
                eventChannel.send(RecognitionEvent.Error(e))
            }
        }
        cleanup()
        eventChannel.close()
    }

    override suspend fun cancel() {
        active = false
        loopJob?.join()
        cleanup()
        eventChannel.close()
    }

    /**
     * 同步强制释放（不等待识别循环）。用于服务销毁等不可挂起的场景，幂等。
     * 防止 controller 协程被取消后仍残留 AudioRecord/原生句柄。
     */
    fun releaseNow() {
        active = false
        runCatching { eventChannel.close() }
        cleanup()
        runCatching { scope.cancel() }
    }

    private fun cleanup() {
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        runCatching { stream?.release() }
        stream = null
        runCatching { recognizer?.release() }
        recognizer = null
        loopJob = null
    }
}
