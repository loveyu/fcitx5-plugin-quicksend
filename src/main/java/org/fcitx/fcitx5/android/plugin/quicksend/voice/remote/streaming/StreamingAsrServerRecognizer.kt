/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.quicksend.voice.remote.streaming

import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import org.fcitx.fcitx5.android.plugin.quicksend.voice.ErrorKind
import org.fcitx.fcitx5.android.plugin.quicksend.voice.RecognitionEvent
import org.fcitx.fcitx5.android.plugin.quicksend.voice.VoiceLog
import org.fcitx.fcitx5.android.plugin.quicksend.voice.remote.BaseWsStreamingRecognizer
import org.json.JSONObject

/**
 * streaming-asr-server（https://github.com/loveyu/streaming-asr-server）识别器。
 *
 * 协议：JSON 文本帧（status:ready/listening、partial、final、error、pong）+ 二进制 PCM。
 * 客户端先发 {"type":"start"}，收到 listening 后开始推流；stop 发 {"type":"finish"}。
 * 可选 Bearer Token 鉴权。服务端 401/JSON code:auth → 鉴权失败；503/code:overload → 满载。
 */
class StreamingAsrServerRecognizer(
    private val serverUrl: String,
    private val authToken: String?
) : BaseWsStreamingRecognizer() {

    override val tag: String = "RemoteASR"
    override val requiresListeningState: Boolean = true

    override fun buildRequest(): Request = authToken?.let { token ->
        Request.Builder().url(serverUrl)
            .addHeader("Authorization", "Bearer $token")
            .build()
    } ?: Request.Builder().url(serverUrl).build()

    override fun sendStart(webSocket: WebSocket) {
        webSocket.send("""{"type":"start"}""")
    }

    override fun sendFinish(webSocket: WebSocket) {
        webSocket.send("""{"type":"finish"}""")
    }

    override fun handleMessage(webSocket: WebSocket, obj: JSONObject) {
        when (obj.optString("type")) {
            "status" -> {
                when (obj.optString("state")) {
                    "ready" -> markReady()
                    "listening" -> markListening()
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
                markFinal(RecognitionEvent.Final(text))
            }
            "error" -> {
                val msg = obj.optString("message", "unknown")
                val fatal = obj.optBoolean("fatal", false)
                // 服务端把 idle/超时标 fatal 时，客户端不判远端不可用：以已识别内容软结束本轮，
                // 走正常 final 流程，保持远端模式继续可用（避免误回退本地/崩溃）。
                if (fatal && isRecoverableTimeout(msg)) {
                    VoiceLog.w(tag, "server recoverable timeout: $msg → soft finalize")
                    val finalText = lastPartialText
                    lastPartialText = ""
                    val fe = RecognitionEvent.Final(finalText)
                    eventChannel.trySend(fe)
                    markFinal(fe)
                } else {
                    VoiceLog.w(tag, "server error: $msg (fatal=$fatal)")
                    if (fatal) {
                        val err = RecognitionEvent.Error(RuntimeException(msg))
                        eventChannel.trySend(err)
                        markFinal(err)
                    }
                }
            }
            "pong" -> { /* heartbeat */ }
        }
    }

    /**
     * 优先看 HTTP 状态码，其次服务端 JSON 体里的 `code` 字段（auth/overload）。
     * response==null（连不上/非 HTTP 错误）→ [ErrorKind.Generic] → 上层回退本地。
     */
    override fun classifyFailure(t: Throwable, response: Response?): ErrorKind {
        val code = response?.code ?: 0
        val body = runCatching { response?.body?.string() }.getOrNull()
        val serverCode = body?.let {
            runCatching { JSONObject(it).optString("code").ifEmpty { null } }.getOrNull()
        }
        return when {
            code == 401 || serverCode == "auth" -> ErrorKind.RemoteAuth
            code == 503 || serverCode == "overload" -> ErrorKind.RemoteOverload
            else -> ErrorKind.Generic
        }
    }
}
