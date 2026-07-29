/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.quicksend.voice.remote

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.fcitx.fcitx5.android.plugin.quicksend.voice.RecognitionEvent
import org.fcitx.fcitx5.android.plugin.quicksend.voice.SpeechRecognizer
import org.fcitx.fcitx5.android.plugin.quicksend.voice.VoiceLog
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class RemoteSpeechRecognizer(
    private val serverUrl: String,
    private val authToken: String?
) : SpeechRecognizer {

    private val sampleRate = 16000
    private val eventChannel = Channel<RecognitionEvent>(Channel.BUFFERED)
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var started = false
    @Volatile private var nativeThread: Thread? = null
    /** 最近一次 partial 文本：服务端业务级超时（idle）时用作软结束的 final 内容。 */
    @Volatile private var lastPartialText: String = ""
    @Volatile private var ws: WebSocket? = null
    @Volatile private var record: AudioRecord? = null

    private lateinit var wsReady: CompletableDeferred<Boolean>
    private lateinit var wsListening: CompletableDeferred<Boolean>
    private lateinit var finalResult: CompletableDeferred<RecognitionEvent>

    private inner class WsHandler : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            VoiceLog.i(TAG, "ws opened (${response.code})")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                handleMessage(JSONObject(text))
            } catch (e: Throwable) {
                VoiceLog.w(TAG, "bad ws message: $text", e)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            VoiceLog.i(TAG, "ws closing: $code $reason")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            VoiceLog.i(TAG, "ws closed: $code $reason")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            VoiceLog.e(TAG, "ws failure: ${t.message}", t)
            if (!wsReady.isCompleted) wsReady.completeExceptionally(t)
            if (!wsListening.isCompleted) wsListening.completeExceptionally(t)
            if (!finalResult.isCompleted) finalResult.complete(RecognitionEvent.Error(t))
            eventChannel.trySend(RecognitionEvent.Error(t))
        }

        private fun handleMessage(obj: JSONObject) {
            when (obj.optString("type")) {
                "status" -> {
                    when (obj.optString("state")) {
                        "ready" -> if (!wsReady.isCompleted) wsReady.complete(true)
                        "listening" -> if (!wsListening.isCompleted) wsListening.complete(true)
                    }
                }
                "partial" -> {
                    val text = obj.optString("text", "")
                    if (text.isNotEmpty()) {
                        lastPartialText = text
                        eventChannel.trySend(RecognitionEvent.Partial(text))
                    }
                }
                "final" -> {
                    val text = obj.optString("text", "")
                    if (!finalResult.isCompleted) finalResult.complete(RecognitionEvent.Final(text))
                }
                "error" -> {
                    val msg = obj.optString("message", "unknown")
                    val fatal = obj.optBoolean("fatal", false)
                    // 服务端把 idle/超时标 fatal 时，客户端不判远端不可用：以已识别内容软结束本轮，
                    // 走正常 final 流程，保持远端模式继续可用（避免误回退本地/崩溃）。
                    if (fatal && isRecoverableTimeout(msg)) {
                        VoiceLog.w(TAG, "server recoverable timeout: $msg → soft finalize")
                        val finalText = lastPartialText
                        lastPartialText = ""
                        eventChannel.trySend(RecognitionEvent.Final(finalText))
                        if (!finalResult.isCompleted) finalResult.complete(RecognitionEvent.Final(finalText))
                    } else {
                        VoiceLog.w(TAG, "server error: $msg (fatal=$fatal)")
                        if (fatal) {
                            val err = RecognitionEvent.Error(RuntimeException(msg))
                            eventChannel.trySend(err)
                            if (!finalResult.isCompleted) finalResult.complete(err)
                        }
                    }
                }
                "pong" -> { /* heartbeat */ }
            }
        }
    }

    override val events: Flow<RecognitionEvent> = eventChannel.receiveAsFlow()

    override suspend fun start() {
        if (started) return
        VoiceLog.i(TAG, "start: connecting to $serverUrl")

        wsReady = CompletableDeferred()
        wsListening = CompletableDeferred()
        finalResult = CompletableDeferred()
        running = true
        paused = false
        lastPartialText = ""

        val request = authToken?.let { token ->
            Request.Builder().url(serverUrl)
                .addHeader("Authorization", "Bearer $token")
                .build()
        } ?: Request.Builder().url(serverUrl).build()

        val freshWs = client.newWebSocket(request, WsHandler())
        ws = freshWs

        if (!wsReady.await()) {
            throw IllegalStateException("WebSocket handshake failed")
        }
        VoiceLog.i(TAG, "ws ready, sending start")
        freshWs.send("""{"type":"start"}""")

        if (!wsListening.await()) {
            throw IllegalStateException("Server did not enter listening state")
        }
        VoiceLog.i(TAG, "ws listening, starting recording")

        val t = Thread({ recordingLoop(freshWs) }, "remote-asr-rec").apply { isDaemon = true }
        nativeThread = t
        t.start()
        started = true
    }

    @SuppressLint("MissingPermission")
    private fun recordingLoop(webSocket: WebSocket) {
        try {
            val ar = createAudioRecord()
            if (ar.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { ar.release() }
                throw IllegalStateException("AudioRecord init failed")
            }
            ar.startRecording()
            record = ar

            val chunkSamples = sampleRate / 10 // 100ms
            val buf = ShortArray(chunkSamples)
            while (running) {
                if (paused) {
                    if (ar.recordingState == AudioRecord.RECORDSTATE_RECORDING) ar.stop()
                    Thread.sleep(100)
                    continue
                }
                if (ar.recordingState != AudioRecord.RECORDSTATE_RECORDING) ar.startRecording()
                val n = ar.read(buf, 0, chunkSamples)
                if (n <= 0) continue
                val pcmBytes = shortArrayToBytes(buf, n)
                val sent = webSocket.send(pcmBytes.toByteString())
                if (!sent) {
                    VoiceLog.w(TAG, "ws send failed, stopping")
                    eventChannel.trySend(RecognitionEvent.Error(RuntimeException("WebSocket send queue full")))
                    break
                }
            }
            runCatching { ar.stop() }
        } catch (e: Throwable) {
            VoiceLog.e(TAG, "recording loop error", e)
            eventChannel.trySend(RecognitionEvent.Error(e))
        } finally {
            runCatching { record?.release() }
            record = null
        }
    }

    override suspend fun stop() {
        if (!started) return
        VoiceLog.i(TAG, "stop: finishing")
        running = false
        nativeThread?.join(2_000)
        nativeThread = null

        val w = ws
        if (w != null) {
            w.send("""{"type":"finish"}""")
            val result = finalResult.await()
            if (result is RecognitionEvent.Final) eventChannel.trySend(result)
            w.close(1000, "done")
        }
        started = false
    }

    override suspend fun cancel() {
        if (!started) return
        VoiceLog.i(TAG, "cancel")
        running = false
        nativeThread?.join(2_000)
        nativeThread = null
        runCatching { ws?.close(1000, "cancelled") }
        started = false
    }

    override fun pauseRecording() {
        if (!started || paused) return
        VoiceLog.i(TAG, "pauseRecording")
        paused = true
    }

    override fun resumeRecording() {
        if (!started || !paused) return
        VoiceLog.i(TAG, "resumeRecording")
        paused = false
    }

    override fun releaseNow() {
        VoiceLog.i(TAG, "releaseNow")
        running = false
        nativeThread?.let { runCatching { it.join(2_000) } }
        nativeThread = null
        runCatching { ws?.close(1000, "released") }
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

    private fun shortArrayToBytes(src: ShortArray, count: Int): ByteArray {
        val bytes = ByteArray(count * 2)
        for (i in 0 until count) {
            val v = src[i].toInt()
            bytes[i * 2] = (v and 0xFF).toByte()
            bytes[i * 2 + 1] = (v shr 8 and 0xFF).toByte()
        }
        return bytes
    }

    /** 判断是否"可恢复的业务级超时"（如用户说话停顿导致的 idle timeout），不应判远端不可用。 */
    private fun isRecoverableTimeout(message: String): Boolean {
        val m = message.lowercase()
        return m.contains("idle") || m.contains("timeout")
    }

    private companion object {
        const val TAG = "RemoteASR"
    }
}
