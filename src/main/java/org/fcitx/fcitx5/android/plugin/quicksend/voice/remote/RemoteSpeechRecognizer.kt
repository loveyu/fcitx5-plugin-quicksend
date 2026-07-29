/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.quicksend.voice.remote

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.fcitx.fcitx5.android.plugin.quicksend.voice.ErrorKind
import org.fcitx.fcitx5.android.plugin.quicksend.voice.RecognitionEvent
import org.fcitx.fcitx5.android.plugin.quicksend.voice.RemoteAsrException
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
            // WS 升级失败时 OkHttp 在 response 里携带 HTTP 状态码与体。服务端对 401/503
            // 返回结构化 JSON（{code:"auth"|"overload",fatal,retry}），据此区分鉴权/满载，
            // 不再一律静默回退本地；其它（TCP 不通等 response==null）→ Generic → 回退本地。
            val (kind, detail) = classifyFailure(t, response)
            VoiceLog.e(TAG, "ws failure: ${t.javaClass.simpleName}: ${t.message}$detail", t)
            // 用带分类的 RemoteAsrException 同时完成 wsReady/wsListening/finalResult 与事件通道，
            // 这样 start() 经 wsReady.await() 抛出的异常与事件下发的分类一致，无竞态。
            val ex = RemoteAsrException("remote ASR ${kind.name.lowercase()}: ${t.message}", kind, t)
            if (!wsReady.isCompleted) wsReady.completeExceptionally(ex)
            if (!wsListening.isCompleted) wsListening.completeExceptionally(ex)
            val err = RecognitionEvent.Error(ex, kind)
            if (!finalResult.isCompleted) finalResult.complete(err)
            eventChannel.trySend(err)
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
        awaitNativeThread()
        nativeThread = null

        val w = ws
        if (w != null) {
            w.send("""{"type":"finish"}""")
            // 等服务端 final 若不设上限，遇慢/失联服务端会让"完成"按钮长时间卡在提交中。
            // 这里加超时：超时则以最近 partial 软结束（与 idle 超时策略一致），保证已识别内容落库。
            val result: RecognitionEvent? = try {
                withTimeout(FINAL_AWAIT_MS) { finalResult.await() }
            } catch (e: TimeoutCancellationException) {
                VoiceLog.w(TAG, "final await timed out → soft-finalize with last partial")
                val finalText = lastPartialText
                lastPartialText = ""
                RecognitionEvent.Final(finalText)
            } catch (e: CancellationException) {
                throw e // destroy() 取消作用域，不要吞
            } catch (e: Throwable) {
                // finalResult 已被 onFailure 以异常完成（连接错误），事件通道也会收到 Error；
                // 不重抛、不产出 Final，交由 handle(Error) 收尾，避免与 finish() 重复 endSession。
                VoiceLog.w(TAG, "final await failed: ${e.javaClass.simpleName}", e)
                null
            }
            if (result is RecognitionEvent.Final) eventChannel.trySend(result)
            w.close(1000, "done")
        }
        started = false
    }

    override suspend fun cancel() {
        if (!started) return
        VoiceLog.i(TAG, "cancel")
        running = false
        awaitNativeThread()
        nativeThread = null
        runCatching { ws?.close(1000, "cancelled") }
        started = false
    }

    private suspend fun awaitNativeThread() {
        val t = nativeThread ?: return
        // join 是阻塞调用；VoiceController.finish()/close() 在主线程发起 stop()/cancel()，
        // 必须切到 IO，否则会卡 UI（与 SherpaRecognizer.awaitNativeThread 保持一致）。
        withContext(Dispatchers.IO) {
            val done = runCatching { t.join(2_000); !t.isAlive }.getOrDefault(false)
            if (!done) VoiceLog.w(TAG, "native thread still alive after join(2s)")
        }
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

    /**
     * 把 WS 升级失败归类为 [ErrorKind]。优先看 HTTP 状态码，其次服务端 JSON 体里的 `code`
     * 字段（服务端两者都给，见 protocol.rs::HttpError）。response==null（连不上/非 HTTP
     * 错误）→ [ErrorKind.Generic] → 上层回退本地。
     */
    private fun classifyFailure(t: Throwable, response: Response?): Pair<ErrorKind, String> {
        val code = response?.code ?: 0
        // body.string() 是阻塞读取；onFailure 由 OkHttp 工作线程回调，不会卡 UI。
        val body = runCatching { response?.body?.string() }.getOrNull()
        val serverCode = body?.let {
            runCatching { JSONObject(it).optString("code").ifEmpty { null } }.getOrNull()
        }
        val detail = buildString {
            if (code != 0) append(" | HTTP ").append(code)
            if (serverCode != null) append(" | server code=").append(serverCode)
            if (!body.isNullOrEmpty()) append(" | body=").append(body.take(200))
        }
        val kind = when {
            code == 401 || serverCode == "auth" -> ErrorKind.RemoteAuth
            code == 503 || serverCode == "overload" -> ErrorKind.RemoteOverload
            else -> ErrorKind.Generic
        }
        return kind to detail
    }

    private companion object {
        const val TAG = "RemoteASR"
        /** 点击"完成"后等服务端 final 的最长时间，超时则以最近 partial 软结束。 */
        const val FINAL_AWAIT_MS = 4_000L
    }
}
