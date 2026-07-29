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
import org.fcitx.fcitx5.android.plugin.quicksend.voice.remote.TencentAsrV1Backend
import org.json.JSONObject
import java.util.UUID
import kotlin.random.Random

/**
 * 腾讯云实时语音识别 V1（WebSocket）。文档：https://cloud.tencent.com/document/product/1093/48982
 *
 * 与 V2 共用同一握手地址（`wss://asr.cloud.tencent.com/asr/v2/<appid>`）与同一签名算法
 * （[TencentV2Signing]：HMAC-SHA1 + Base64 + urlencode，签名串不含 scheme）。区别是引擎与响应：
 * - V1 支持**通用引擎**（默认 `16k_zh`），对应通用资源包；
 * - 响应按句返回 `result.slice_type`（0 开始/1 进行中非稳态/2 结束稳态）+ `voice_text_str`；
 * - `final==1` 表示全部识别完成、服务端将关闭连接。
 *
 * 多句处理：稳态整句（slice_type==2）累积到 [stableText] 只更新展示，不逐句提交——因为
 * [VoiceController] 收到 Final 必结束会话，逐句 Final 会在首句终止会话并重复提交。
 * 会话结束（`final==1` 或 stop 超时软结束）才把全量稳态文本作为 Final 一次性提交。
 */
class TencentAsrV1Recognizer(private val config: TencentAsrV1Backend) :
    BaseWsStreamingRecognizer(config.proxy) {

    override val tag: String = "TencentASRv1"
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
            put("filter_punc", config.filterPunc.toString())
            put("convert_num_mode", config.convertNumMode.toString())
            if (config.hotwordList.isNotEmpty()) put("hotword_list", config.hotwordList)
        }
        // 字典序拼接签名串（不含 scheme、不含 signature）→ HMAC-SHA1 签名 → 拼 URL（与 V2 同算法）
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
            // 4008（客户端 15 秒未发音频）属可恢复的业务级超时 → 软结束本轮，不判远端不可用。
            if (code == 4008 && isRecoverableTimeout(msg)) {
                VoiceLog.w(tag, "tencent recoverable timeout ($code): $msg → soft finalize")
                markFinal(RecognitionEvent.Final(lastPartialText))
                return
            }
            val kind = classifyTencentCode(code)
            VoiceLog.w(tag, "tencent error $code: $msg → $kind")
            // 用 failSession：完成 wsReady（让 start() 以正确分类抛出，而非等 onFailure 的 Generic 覆盖）
            failSession(RemoteAsrException("tencent asr v1 $code: $msg", kind), kind)
            return
        }
        markReady()
        val result = obj.optJSONObject("result")
        if (result != null) {
            val text = result.optString("voice_text_str", "")
            val sliceType = result.optInt("slice_type", 0)
            // slice_type 2 = 本句稳态（不再变化）→ 累积；0/1 = 进行中 → 作 partial 展示
            val display = if (sliceType == 2) appendStable(text) else setPartial(text)
            if (display.isNotEmpty()) eventChannel.trySend(RecognitionEvent.Partial(display))
        }
        if (obj.optInt("final", 0) == 1) {
            // 全部识别完成，服务端将关闭连接：把全量稳态文本一次性作为 Final 提交。
            markFinal(RecognitionEvent.Final(lastPartialText))
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
