/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.quicksend.voice.remote.alibaba

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import org.fcitx.fcitx5.android.plugin.quicksend.voice.ErrorKind
import org.fcitx.fcitx5.android.plugin.quicksend.voice.RecognitionEvent
import org.fcitx.fcitx5.android.plugin.quicksend.voice.RemoteAsrException
import org.fcitx.fcitx5.android.plugin.quicksend.voice.VoiceLog
import org.fcitx.fcitx5.android.plugin.quicksend.voice.remote.AlibabaCloudAsrBackend
import org.fcitx.fcitx5.android.plugin.quicksend.voice.remote.BaseWsStreamingRecognizer
import org.json.JSONObject
import java.util.UUID

/**
 * 阿里云智能语音交互实时语音识别（SpeechTranscriber）。文档：
 * https://help.aliyun.com/zh/isi/developer-reference/api-reference
 *
 * 协议：WebSocket 文本帧（header/payload/context）+ 二进制 PCM。
 * - 握手地址：`wss://nls-gateway-cn-shanghai.aliyuncs.com/ws/v1?token=<TOKEN>`
 * - 客户端先发 StartTranscription，服务端响应 TranscriptionStarted 后开始推流。
 * - 运行中持续收到 TranscriptionResultChanged（中间结果）和 SentenceEnd（稳态句）。
 * - 客户端发 StopTranscription 结束，服务端响应 TranscriptionComplete。
 * - 多句识别：按句返回 SentenceEnd 结果累积在 [appendStable]，TranscriptionComplete 时一次性提交。
 *
 * Token 支持两种方式（优先前者）：
 * 1. 手动填入 token 字段；
 * 2. 提供 AK/SK（accessKeyId + accessKeySecret），连接时自动调用 CreateToken API 获取。
 */
class AlibabaCloudAsrRecognizer(private val config: AlibabaCloudAsrBackend) :
    BaseWsStreamingRecognizer(config.proxy) {

    override val tag: String = "AlibabaASR"
    override val requiresListeningState: Boolean = true

    /** StartTranscription 发出的 task_id，StopTranscription 时回传匹配。 */
    private var taskId: String = ""
    /** 自动获取的 Token 缓存（内存，进程生命周期）。 */
    @Volatile private var cachedToken: AlibabaCreateToken.TokenResult? = null

    override fun buildRequest(): Request {
        require(config.url.isNotBlank()) { "alibaba cloud url is empty" }
        require(config.appKey.isNotBlank()) { "alibaba cloud appKey is empty" }
        val token = resolveToken() ?: throw IllegalStateException("alibaba cloud token is empty and AK/SK not provided")
        val wsUrl = "${config.url}?token=$token"
        VoiceLog.i(tag, "wss to ${config.url.take(40)}... appKey=${config.appKey.takeMasked()}")
        return Request.Builder().url(wsUrl).build()
    }

    /**
     * 解析 Token：手动填入优先，否则用 AK/SK 自动获取（带内存缓存，过期自动刷新）。
     *
     * 网络调用经 [runBlocking] 切到 [Dispatchers.IO]——因为 [buildRequest] 非 suspend 且可能被
     * 主线程协程调用，直接在主线程同步 HTTP 会触发 NetworkOnMainThreadException。
     */
    private fun resolveToken(): String? {
        if (config.token.isNotBlank()) return config.token
        if (!config.canAutoFetchToken) return null

        // 检查缓存（未过期直接用，避免不必要请求）
        val cached = cachedToken
        if (cached != null && cached.isValid) {
            VoiceLog.d(tag, "using cached token, expires at ${cached.expireTime} " +
                "(remaining ${(cached.expireTime - System.currentTimeMillis() / 1000)}s)")
            return cached.id
        }

        VoiceLog.i(tag, "auto-fetching token via CreateToken API")
        val result = runBlocking(Dispatchers.IO) {
            AlibabaCreateToken.fetchToken(config.accessKeyId, config.accessKeySecret)
        }
        if (result != null && result.isValid) {
            cachedToken = result
            VoiceLog.i(tag, "token fetched, expires at ${result.expireTime}")
            return result.id
        }
        VoiceLog.w(tag, "token fetch failed")
        return null
    }

    override fun sendStart(webSocket: WebSocket) {
        val msgId = UUID.randomUUID().toString()
        val startMsg = JSONObject().apply {
            put("header", JSONObject().apply {
                put("namespace", "SpeechTranscriber")
                put("name", "StartTranscription")
                put("message_id", msgId)
                put("task_id", msgId)
            })
            put("payload", JSONObject().apply {
                put("appkey", config.appKey)
                put("format", "pcm")
                put("sample_rate", config.sampleRate)
                if (config.enableIntermediateResult) put("enable_intermediate_result", true)
                if (config.enablePunctuationPrediction) put("enable_punctuation_prediction", true)
                if (config.enableInverseTextNormalization) put("enable_inverse_text_normalization", true)
            })
            put("context", JSONObject())
        }
        webSocket.send(startMsg.toString())
    }

    override fun sendFinish(webSocket: WebSocket) {
        val msgId = UUID.randomUUID().toString()
        val stopMsg = JSONObject().apply {
            put("header", JSONObject().apply {
                put("namespace", "SpeechTranscriber")
                put("name", "StopTranscription")
                put("message_id", msgId)
                put("task_id", taskId.ifBlank { msgId })
            })
            put("payload", JSONObject())
            put("context", JSONObject())
        }
        webSocket.send(stopMsg.toString())
    }

    override fun handleMessage(webSocket: WebSocket, obj: JSONObject) {
        val header = obj.optJSONObject("header")
        val payload = obj.optJSONObject("payload")
        val name = header?.optString("name", "") ?: ""
        val status = header?.optInt("status", 0) ?: 0

        if (status != 0 && status != 20000000) {
            val statusText = header?.optString("status_text", "unknown error") ?: "unknown error"
            VoiceLog.w(tag, "alibaba error status=$status: $statusText")
            val kind = classifyStatusCode(status)
            failSession(
                RemoteAsrException("alibaba asr $status: $statusText", kind),
                kind
            )
            return
        }

        taskId = header?.optString("task_id", taskId) ?: taskId

        when (name) {
            "TranscriptionStarted" -> {
                VoiceLog.i(tag, "transcription started (taskId=$taskId)")
                markReady()
                markListening()
            }
            "SentenceBegin" -> {
                VoiceLog.d(tag, "sentence begin: index=${payload?.optInt("index", -1)}")
            }
            "TranscriptionResultChanged" -> {
                val result = payload?.optString("result", "") ?: ""
                if (result.isNotEmpty()) {
                    val display = setPartial(result)
                    eventChannel.trySend(RecognitionEvent.Partial(display))
                }
            }
            "SentenceEnd" -> {
                val result = payload?.optString("result", "") ?: ""
                if (result.isNotEmpty()) {
                    val display = appendStable(result)
                    eventChannel.trySend(RecognitionEvent.Partial(display))
                }
            }
            "TranscriptionComplete" -> {
                VoiceLog.i(tag, "transcription complete")
                markFinal(RecognitionEvent.Final(lastPartialText))
            }
        }
    }

    /** WS 升级失败：401/403 → 鉴权；5xx → 过载。 */
    override fun classifyFailure(t: Throwable, response: Response?): ErrorKind {
        val code = response?.code ?: 0
        return when {
            code == 401 || code == 403 -> ErrorKind.RemoteAuth
            code in 500..599 -> ErrorKind.RemoteOverload
            else -> ErrorKind.Generic
        }
    }

    private fun classifyStatusCode(status: Int): ErrorKind = when (status) {
        40000001 -> ErrorKind.RemoteAuth       // Token 过期/无效
        40000002 -> ErrorKind.Generic           // 消息无效
        40000003 -> ErrorKind.Generic           // 参数无效
        40000005 -> ErrorKind.RemoteOverload    // 并发过多
        40000010 -> ErrorKind.RemoteOverload    // 试用期结束 / 欠费
        else -> ErrorKind.Generic
    }

    private fun String.takeMasked(): String =
        if (length <= 4) "***" else take(4) + "***"

    private companion object {
        /** 阿里云成功状态码。 */
        internal const val OK = 20000000
    }
}
