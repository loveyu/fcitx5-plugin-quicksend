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
import org.fcitx.fcitx5.android.plugin.quicksend.voice.net.ProxyConfig
import org.fcitx.fcitx5.android.plugin.quicksend.voice.net.applyProxy
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 「WebSocket + 16k PCM16 直采 + 单 native 线程」流式识别器基类。把跨后端公共的录音/线程/收尾/
 * 错误分类骨架抽到这里，子类只实现协议差异（握手 URL/签名、start/finish 文本帧、消息解析、错误分类）。
 *
 * 复用自原 RemoteSpeechRecognizer 的全部生命周期不变式：
 * - 所有 stream/AudioRecord 原生对象只在唯一 nativeThread 上创建/使用/释放；
 * - stop/cancel/releaseNow 仅翻 @Volatile 标志并 join nativeThread，绝不跨线程接触原生对象；
 * - 「完成」按钮的 stop() 内 join 切到 IO（[awaitNativeThread]），避免阻塞主线程。
 *
 * [proxyUri] 为可选代理（http://host:port 或 socks5://user:pass@host:port），与模型下载同源逻辑。
 */
abstract class BaseWsStreamingRecognizer(
    private val proxyUri: String = ""
) : SpeechRecognizer {

    protected val sampleRate = 16000
    protected val eventChannel = Channel<RecognitionEvent>(Channel.BUFFERED)
    private val proxyConfig = ProxyConfig.fromUri(proxyUri)
    protected val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .applyProxy(proxyConfig)
        .build()

    @Volatile protected var running = false
    @Volatile protected var paused = false
    @Volatile protected var started = false
    @Volatile protected var nativeThread: Thread? = null
    /** 最近一次 partial 文本：服务端业务级超时（idle）时用作软结束的 final 内容。 */
    @Volatile protected var lastPartialText: String = ""

    /**
     * 稳态整句累积区。腾讯等「按句返回多个稳态结果」的后端在此逐句追加，会话结束时一次性
     * 作为 Final 提交——而不是每句都发 Final。因为 [VoiceController] 收到 Final 必定结束会话，
     * 逐句 Final 会在首句就终止会话（后续句子丢失）并由 stop() 再次下发造成重复提交。
     * streaming-asr-server 不用此区（服务端直接给整段 final 文本）。
     */
    protected val stableText = StringBuilder()
    @Volatile private var ws: WebSocket? = null
    @Volatile private var record: AudioRecord? = null

    private lateinit var wsReady: CompletableDeferred<Boolean>
    private lateinit var wsListening: CompletableDeferred<Boolean>
    private lateinit var finalResult: CompletableDeferred<RecognitionEvent>

    protected abstract val tag: String

    /** 是否需要等待服务端「listening」态（streaming-asr-server 需要；腾讯首个 code:0 即就绪，不需要）。 */
    protected open val requiresListeningState: Boolean = true

    /** 构造握手 Request（含鉴权/签名，由子类拼装）。 */
    protected abstract fun buildRequest(): Request

    /** 握手成功（wsReady）后发送的起始帧（如 {"type":"start"}）；默认不发。 */
    protected open fun sendStart(webSocket: WebSocket) {}

    /** stop() 时发送的结束帧（如 {"type":"finish"}/{"type":"end"}）。 */
    protected abstract fun sendFinish(webSocket: WebSocket)

    /** 解析一条文本消息：完成 wsReady/wsListening/finalResult、更新 lastPartialText、下发事件。 */
    protected abstract fun handleMessage(webSocket: WebSocket, obj: JSONObject)

    /** 判断「可恢复的业务级超时」（idle/timeout 等），命中则软结束而非判远端不可用。 */
    protected open fun isRecoverableTimeout(message: String): Boolean {
        val m = message.lowercase()
        return m.contains("idle") || m.contains("timeout")
    }

    /** 把 WS 升级失败归类为 [ErrorKind]（HTTP 码 / 服务端 JSON code）。 */
    protected abstract fun classifyFailure(t: Throwable, response: Response?): ErrorKind

    private inner class WsHandler : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            VoiceLog.i(tag, "ws opened (${response.code})")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                handleMessage(webSocket, JSONObject(text))
            } catch (e: Throwable) {
                VoiceLog.w(tag, "bad ws message: $text", e)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            VoiceLog.i(tag, "ws closing: $code $reason")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            VoiceLog.i(tag, "ws closed: $code $reason")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            // 若已通过消息收到明确错误/最终结果（finalResult 已完成），此处只是连接随后关闭
            // （如服务端下发错误码后断开），不再以 Generic 覆盖先前更准确的分类
            // （例如腾讯 4004 已判为 RemoteAuth，随后的 EOF 不应再降级为 Generic）。
            if (finalResult.isCompleted) {
                VoiceLog.i(tag, "ws failure ignored (session already terminated): ${t.javaClass.simpleName}: ${t.message}")
                return
            }
            // WS 升级失败时 OkHttp 在 response 里携带 HTTP 状态码与体，据此区分鉴权/满载，
            // 不再一律静默回退本地；其它（TCP 不通等 response==null）→ Generic → 回退本地。
            val kind = classifyFailure(t, response)
            val detail = buildString {
                val code = response?.code ?: 0
                if (code != 0) append(" | HTTP ").append(code)
                val body = runCatching { response?.body?.string() }.getOrNull()
                if (!body.isNullOrEmpty()) append(" | body=").append(body.take(200))
            }
            VoiceLog.e(tag, "ws failure: ${t.javaClass.simpleName}: ${t.message}$detail", t)
            // 用带分类的 RemoteAsrException 同时完成 wsReady/wsListening/finalResult 与事件通道，
            // 这样 start() 经 wsReady.await() 抛出的异常与事件下发的分类一致，无竞态。
            val ex = RemoteAsrException("remote ASR ${kind.name.lowercase()}: ${t.message}", kind, t)
            if (!wsReady.isCompleted) wsReady.completeExceptionally(ex)
            if (!wsListening.isCompleted) wsListening.completeExceptionally(ex)
            val err = RecognitionEvent.Error(ex, kind)
            if (!finalResult.isCompleted) finalResult.complete(err)
            eventChannel.trySend(err)
        }
    }

    override val events: Flow<RecognitionEvent> = eventChannel.receiveAsFlow()

    /** 子类在握手就绪消息到达时调用。 */
    protected fun markReady() {
        if (!wsReady.isCompleted) wsReady.complete(true)
    }

    /** 子类在进入 listening 态时调用（仅 requiresListeningState=true 的协议）。 */
    protected fun markListening() {
        if (!wsListening.isCompleted) wsListening.complete(true)
    }

    /** 子类收到最终结果时调用。 */
    protected fun markFinal(event: RecognitionEvent) {
        if (!finalResult.isCompleted) finalResult.complete(event)
    }

    /**
     * 稳态整句到达时调用：追加到 [stableText]，并把 [lastPartialText] 刷新为全量稳态文本。
     * 返回该全量文本，子类据此下发 Partial（让浮层显示已稳态的全部内容）。多句识别的关键：
     * 稳态句只更新展示、不提交，等会话结束（[markFinal]）一次性提交。
     */
    protected fun appendStable(text: String): String {
        if (text.isNotEmpty()) stableText.append(text)
        lastPartialText = stableText.toString()
        return lastPartialText
    }

    /**
     * 非稳态 partial 到达时调用：把 [lastPartialText] 刷新为「已稳态文本 + 当前句 partial」，
     * 返回该全量文本供下发 Partial。
     */
    protected fun setPartial(text: String): String {
        lastPartialText = stableText.toString() + text
        return lastPartialText
    }

    /**
     * 子类收到致命错误消息时调用：用带分类的异常完成握手/最终 deferred（让 [start] 以**正确分类**抛出，
     * 而不是干等随后连接关闭触发的 onFailure 以 Generic 覆盖——例如腾讯首帧即 4004，此前 wsReady 未完成，
     * start() 会卡在 wsReady.await()），并下发错误事件。之后连接关闭触发的 onFailure 会被忽略。
     */
    protected fun failSession(throwable: Throwable, kind: ErrorKind) {
        val ex = (throwable as? RemoteAsrException)
            ?: RemoteAsrException("remote ASR ${kind.name.lowercase()}: ${throwable.message}", kind, throwable)
        if (!wsReady.isCompleted) wsReady.completeExceptionally(ex)
        if (!wsListening.isCompleted) wsListening.completeExceptionally(ex)
        val err = RecognitionEvent.Error(ex, kind)
        if (!finalResult.isCompleted) finalResult.complete(err)
        eventChannel.trySend(err)
    }

    override suspend fun start() {
        if (started) return
        VoiceLog.i(tag, "start: connecting")

        wsReady = CompletableDeferred()
        wsListening = CompletableDeferred()
        finalResult = CompletableDeferred()
        running = true
        paused = false
        lastPartialText = ""
        stableText.setLength(0)

        val freshWs = client.newWebSocket(buildRequest(), WsHandler())
        ws = freshWs

        if (!wsReady.await()) {
            throw IllegalStateException("WebSocket handshake failed")
        }
        VoiceLog.i(tag, "ws ready, sending start")
        sendStart(freshWs)

        if (requiresListeningState) {
            if (!wsListening.await()) {
                throw IllegalStateException("Server did not enter listening state")
            }
        }
        VoiceLog.i(tag, "ws listening, starting recording")

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
                    VoiceLog.w(tag, "ws send failed, stopping")
                    eventChannel.trySend(RecognitionEvent.Error(RuntimeException("WebSocket send queue full")))
                    break
                }
            }
            runCatching { ar.stop() }
        } catch (e: Throwable) {
            VoiceLog.e(tag, "recording loop error", e)
            eventChannel.trySend(RecognitionEvent.Error(e))
        } finally {
            runCatching { record?.release() }
            record = null
        }
    }

    override suspend fun stop() {
        if (!started) return
        VoiceLog.i(tag, "stop: finishing")
        running = false
        awaitNativeThread()
        nativeThread = null

        val w = ws
        if (w != null) {
            sendFinish(w)
            // 等服务端 final 若不设上限，遇慢/失联服务端会让「完成」按钮长时间卡在提交中。
            // 这里加超时：超时则以最近 partial 软结束，保证已识别内容落库。
            val result: RecognitionEvent? = try {
                withTimeout(FINAL_AWAIT_MS) { finalResult.await() }
            } catch (e: TimeoutCancellationException) {
                VoiceLog.w(tag, "final await timed out → soft-finalize with last partial")
                val finalText = lastPartialText
                lastPartialText = ""
                RecognitionEvent.Final(finalText)
            } catch (e: CancellationException) {
                throw e // destroy() 取消作用域，不要吞
            } catch (e: Throwable) {
                VoiceLog.w(tag, "final await failed: ${e.javaClass.simpleName}", e)
                null
            }
            if (result is RecognitionEvent.Final) eventChannel.trySend(result)
            w.close(1000, "done")
        }
        started = false
    }

    override suspend fun cancel() {
        if (!started) return
        VoiceLog.i(tag, "cancel")
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
            if (!done) VoiceLog.w(tag, "native thread still alive after join(2s)")
        }
    }

    override fun pauseRecording() {
        if (!started || paused) return
        VoiceLog.i(tag, "pauseRecording")
        paused = true
    }

    override fun resumeRecording() {
        if (!started || !paused) return
        VoiceLog.i(tag, "resumeRecording")
        paused = false
    }

    override fun releaseNow() {
        VoiceLog.i(tag, "releaseNow")
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

    private companion object {
        /** 点击「完成」后等服务端 final 的最长时间，超时则以最近 partial 软结束。 */
        const val FINAL_AWAIT_MS = 4_000L
    }
}
