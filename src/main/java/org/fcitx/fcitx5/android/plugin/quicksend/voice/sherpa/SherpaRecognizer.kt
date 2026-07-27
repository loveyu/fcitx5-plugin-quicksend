/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.quicksend.voice.sherpa

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.plugin.quicksend.voice.RecognitionEvent
import org.fcitx.fcitx5.android.plugin.quicksend.voice.SpeechRecognizer
import org.fcitx.fcitx5.android.plugin.quicksend.voice.VoiceLog

/**
 * 基于 Sherpa-ONNX 的本地流式识别器（中文 Zipformer-transducer）。
 *
 * 模型由 [SherpaModelHolder] 全局加载、进程内共享；本类只负责单次录音会话
 * （stream + AudioRecord + 录音循环）。暂停/恢复通过 [pauseRecording]/[resumeRecording]
 * 控制录音而不销毁原生对象。
 *
 * 录音：16kHz 单声道 PCM16；识别：OnlineRecognizer 流式，~10fps 产出 Partial；
 * [stop] 触发 inputFinished 并产出 Final；[cancel] 直接清理不产出结果。
 *
 * 调用方须先授予 RECORD_AUDIO。
 *
 * 线程模型：**所有 stream/record 原生对象只在唯一的 [nativeThread] 上创建、
 * 使用、释放**。[start]/[stop]/[cancel]/[releaseNow] 仅翻转 volatile 标志并 join 该线程，
 * 绝不直接接触原生对象——从而避免在识别循环仍在调用 acceptWaveform/decode 时释放原生句柄
 * 造成 use-after-free。
 */
class SherpaRecognizer(
    private val onlineRec: OnlineRecognizer
) : SpeechRecognizer {

    private val sampleRate = 16000

    private val eventChannel = Channel<RecognitionEvent>(Channel.BUFFERED)

    /** 识别循环运行标志，仅由 nativeThread 读取、由控制方法置 false。 */
    @Volatile
    private var running = false

    /** true=暂停录音但保留 stream；仅 nativeThread 读取。[pauseRecording]/[resumeRecording] 翻转。 */
    @Volatile
    private var paused = false

    /** true=stop 时 flush 最终结果（Final）；false=cancel 丢弃。 */
    @Volatile
    private var commitFinal = false

    @Volatile
    private var started = false

    @Volatile
    private var nativeThread: Thread? = null

    private var stream: OnlineStream? = null
    private var record: AudioRecord? = null

    override val events: Flow<RecognitionEvent> = eventChannel.receiveAsFlow()

    override suspend fun start() {
        if (started) return
        VoiceLog.i(TAG, "start: creating stream + AudioRecord")
        val ready = CompletableDeferred<Boolean>()
        running = true
        commitFinal = false
        val t = Thread({ runNativeSession(ready) }, "sherpa-native").apply { isDaemon = true }
        nativeThread = t
        t.start()
        if (!ready.await()) {
            VoiceLog.e(TAG, "start: initialization failed")
            throw IllegalStateException("Sherpa recognizer initialization failed")
        }
        started = true
        VoiceLog.i(TAG, "start: native ready")
    }

    private fun runNativeSession(ready: CompletableDeferred<Boolean>) {
        try {
            val st = onlineRec.createStream()
            stream = st

            val ar = createAudioRecord()
            if (ar.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { ar.release() }
                throw IllegalStateException("AudioRecord initialization failed")
            }
            ar.startRecording()
            record = ar

            ready.complete(true)

            recordingLoop(onlineRec, st, ar)

            if (commitFinal) {
                VoiceLog.i(TAG, "native: flushing final result")
                try {
                    st.inputFinished()
                    while (onlineRec.isReady(st)) onlineRec.decode(st)
                    val text = onlineRec.getResult(st).text.trim()
                    VoiceLog.i(TAG, "native: final=\"$text\"")
                    eventChannel.trySend(RecognitionEvent.Final(text))
                } catch (e: Throwable) {
                    VoiceLog.e(TAG, "native: flush final failed", e)
                    eventChannel.trySend(RecognitionEvent.Error(e))
                }
            }
        } catch (e: Throwable) {
            VoiceLog.e(TAG, "native session error: ${e.message}", e)
            eventChannel.trySend(RecognitionEvent.Error(e))
            ready.complete(false)
        } finally {
            cleanup()
            VoiceLog.i(TAG, "native: cleanup done")
            runCatching { eventChannel.close() }
        }
    }

    private fun recordingLoop(rec: OnlineRecognizer, st: OnlineStream, ar: AudioRecord) {
        val samplesPerRead = sampleRate / 10
        val buf16 = ShortArray(samplesPerRead)
        val bufFloat = FloatArray(samplesPerRead)
        var lastPartial = ""
        while (running) {
            if (paused) {
                if (ar.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    ar.stop()
                }
                Thread.sleep(100)
                continue
            }
            if (ar.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                ar.startRecording()
            }
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

    fun pauseRecording() {
        if (!started || paused) return
        VoiceLog.i(TAG, "pauseRecording")
        paused = true
    }

    fun resumeRecording() {
        if (!started || !paused) return
        VoiceLog.i(TAG, "resumeRecording")
        paused = false
    }

    override suspend fun stop() {
        if (!started) return
        VoiceLog.i(TAG, "stop")
        commitFinal = true
        running = false
        awaitNativeThread()
        started = false
    }

    override suspend fun cancel() {
        if (!started) return
        VoiceLog.i(TAG, "cancel")
        commitFinal = false
        running = false
        awaitNativeThread()
        started = false
    }

    private suspend fun awaitNativeThread() {
        val t = nativeThread ?: return
        withContext(Dispatchers.IO) {
            val done = runCatching { t.join(2_000); !t.isAlive }.getOrDefault(false)
            if (!done) VoiceLog.w(TAG, "native thread still alive after join(2s)")
        }
    }

    /**
     * 同步强制释放（不等待识别循环产出结果）。用于服务销毁等不可挂起的场景，幂等。
     * 仅释放 stream + AudioRecord，不释放共享的 [onlineRec]（由 [SherpaModelHolder] 管理）。
     */
    fun releaseNow() {
        VoiceLog.i(TAG, "releaseNow")
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

    private fun cleanup() {
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        runCatching { stream?.release() }
        stream = null
    }

    private companion object {
        const val TAG = "SherpaRec"
    }
}
