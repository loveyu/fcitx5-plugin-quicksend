/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.quicksend.voice.remote.tencent

import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import org.fcitx.fcitx5.android.plugin.quicksend.voice.ErrorKind
import org.fcitx.fcitx5.android.plugin.quicksend.voice.RecognitionEvent
import org.fcitx.fcitx5.android.plugin.quicksend.voice.RemoteAsrException
import org.fcitx.fcitx5.android.plugin.quicksend.voice.VoiceLog
import org.fcitx.fcitx5.android.plugin.quicksend.voice.remote.BaseWsStreamingRecognizer
import org.fcitx.fcitx5.android.plugin.quicksend.voice.remote.TencentAsrV2Backend
import org.json.JSONObject
import java.util.UUID
import kotlin.random.Random

/**
 * 腾讯云实时语音识别 V2（WebSocket）。文档：https://cloud.tencent.com/document/api/1093/131127
 *
 * 全部由客户端直连：签名（HMAC-SHA1 + Base64）在客户端计算后拼进 wss URL。
 * - 握手地址：`wss://asr.cloud.tencent.com/asr/v2/<appid>?<参数字典序>&signature=<urlencode(base64(hmac_sha1))>`
 * - 签名串 = `asr.cloud.tencent.com/asr/v2/<appid>?<参数按 key 字典序 k=v&k=v>`（不含 wss://、不含 signature）
 * - 音频：16k PCM16 单声道，voice_format=1，二进制帧按 ~1:1 实时发送（复用基类录音循环）
 * - 响应：JSON，code!=0 为错误（4002/4003 鉴权、4004/4005 配额、4006 并发满载、4008 超时可软结束）；
 *   code==0 取 sentences.sentence，sentence_type==1 视为本句 Final（提交），否则 Partial。
 */
class TencentAsrV2Recognizer(private val config: TencentAsrV2Backend) :
    BaseWsStreamingRecognizer(config.proxy) {

    override val tag: String = "TencentASR"
    override val requiresListeningState: Boolean = false

    private val voiceId: String = UUID.randomUUID().toString().replace("-", "")

    override fun buildRequest(): Request {
        require(config.baseUrl.isNotBlank()) { "tencent baseUrl is empty" }
        val timestamp = System.currentTimeMillis() / 1000
        // 参与签名的参数（除 signature 外全部）。值用「原始值」拼签名串。
        val params = LinkedHashMap<String, String>().apply {
            put("secretid", config.secretId)
            put("timestamp", timestamp.toString())
            put("expired", (timestamp + SIGN_EXPIRE_SECONDS).toString())
            put("nonce", Random.nextInt(1, 1_000_000_000).toString())
            put("engine_model_type", config.engineModelType)
            put("voice_id", voiceId)
            put("voice_format", config.voiceFormat.toString())
            put("needvad", config.needVad.toString())
            put("filter_dirty", config.filterDirty.toString())
            put("filter_modal", config.filterModal.toString())
            put("convert_num_mode", config.convertNumMode.toString())
            if (config.hotwordList.isNotEmpty()) put("hotword_list", config.hotwordList)
        }
        // 字典序拼接签名串（不含 scheme、不含 signature）→ HMAC-SHA1 签名 → 拼 URL
        val sorted = params.toList().sortedBy { it.first }
        val signString = TencentV2Signing.buildSignString(config.baseUrl, config.appId, sorted)
        val signature = TencentV2Signing.signature(config.secretKey, signString)
        val url = TencentV2Signing.buildUrl(config.baseUrl, config.appId, sorted, signature)
        VoiceLog.i(tag, "wss to appid=${config.appId.takeMasked()} engine=${config.engineModelType} fmt=${config.voiceFormat}")
        return Request.Builder().url(url).build()
    }

    override fun sendFinish(webSocket: WebSocket) {
        webSocket.send("""{"type":"end"}""")
    }

    override fun handleMessage(webSocket: WebSocket, obj: JSONObject) {
        val code = obj.optInt("code", 0)
        if (code != 0) {
            val msg = obj.optString("message", "tencent asr error $code")
            // 4008（音频分片等待超时）属可恢复的业务级超时 → 软结束本轮，不判远端不可用。
            if (code == 4008 && isRecoverableTimeout(msg)) {
                VoiceLog.w(tag, "tencent recoverable timeout ($code): $msg → soft finalize")
                val finalText = lastPartialText
                lastPartialText = ""
                val fe = RecognitionEvent.Final(finalText)
                eventChannel.trySend(fe)
                markFinal(fe)
                return
            }
            val kind = classifyTencentCode(code)
            VoiceLog.w(tag, "tencent error $code: $msg → $kind")
            val ex = RemoteAsrException("tencent asr $code: $msg", kind)
            val err = RecognitionEvent.Error(ex, kind)
            eventChannel.trySend(err)
            markFinal(err)
            return
        }
        markReady()
        val sents = obj.optJSONObject("sentences")
        if (sents != null) {
            val text = sents.optString("sentence", "")
            val stype = sents.optInt("sentence_type", 0)
            if (text.isNotEmpty()) {
                if (stype == 1) {
                    // 本句稳定 → 提交并清空待提交缓存，避免 stop() 重复提交
                    lastPartialText = ""
                    val fe = RecognitionEvent.Final(text)
                    eventChannel.trySend(fe)
                    markFinal(fe)
                } else {
                    lastPartialText = text
                    eventChannel.trySend(RecognitionEvent.Partial(text))
                }
            }
        }
        if (obj.optInt("final", 0) == 1) {
            // 全部识别完成，服务端将关闭连接
            val fe = RecognitionEvent.Final(lastPartialText)
            lastPartialText = ""
            markFinal(fe)
        }
    }

    /** WS 升级失败（多为签名错误返回 HTTP 401/403，或服务端 5xx）的分类。 */
    override fun classifyFailure(t: Throwable, response: Response?): ErrorKind {
        val code = response?.code ?: 0
        return when {
            code == 401 || code == 403 -> ErrorKind.RemoteAuth
            code in 500..599 -> ErrorKind.RemoteOverload
            else -> ErrorKind.Generic
        }
    }

    private fun classifyTencentCode(code: Int): ErrorKind = when (code) {
        4002, 4003, 4004, 4005 -> ErrorKind.RemoteAuth   // 鉴权/未开通/配额/欠费：配置或账户问题
        4006 -> ErrorKind.RemoteOverload                  // 并发超限：瞬时
        else -> ErrorKind.Generic
    }

    private fun String.takeMasked(): String =
        if (length <= 4) "***" else take(4) + "***"

    private companion object {
        /** 签名有效期（秒），需 >0 且与 timestamp 的差 < 90 天。 */
        const val SIGN_EXPIRE_SECONDS = 86_400L
    }
}
