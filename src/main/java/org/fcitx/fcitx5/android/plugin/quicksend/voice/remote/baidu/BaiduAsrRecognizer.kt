/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.quicksend.voice.remote.baidu

import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import org.fcitx.fcitx5.android.plugin.quicksend.voice.ErrorKind
import org.fcitx.fcitx5.android.plugin.quicksend.voice.RecognitionEvent
import org.fcitx.fcitx5.android.plugin.quicksend.voice.RemoteAsrException
import org.fcitx.fcitx5.android.plugin.quicksend.voice.VoiceLog
import org.fcitx.fcitx5.android.plugin.quicksend.voice.remote.BaseWsStreamingRecognizer
import org.fcitx.fcitx5.android.plugin.quicksend.voice.remote.BaiduAsrBackend
import org.json.JSONObject
import java.util.UUID

/**
 * 百度智能云实时语音识别（WebSocket，短语音实时转写）。
 *
 * 官方文档：
 *   https://cloud.baidu.com/doc/SPEECH/s/jlbxejt2i  （WebSocket API 协议）
 *   https://cloud.baidu.com/doc/SPEECH/s/cm8sn2bii  （鉴权认证）
 *   https://cloud.baidu.com/doc/SPEECH/s/Zlbxew2qk  （错误码汇总）
 *
 * 协议要点（截至 2026-07，以文档为准，若出问题优先查文档更新）：
 * - 连接：`wss://vop.baidu.com/realtime_asr?sn=<UUID>`，sn 仅日志追踪用。
 * - 鉴权：`appid`（int）+ `appkey`（string）在 START 帧的 data 中直传，无需额外 token。
 * - 采样率固定 **16000 Hz**，格式固定 `pcm`。
 * - 音频帧：二进制 Opcode，每帧 20-200ms，建议 **160ms（5120 bytes）**。**5s 无音频则断开（-3101）**。
 * - 服务端每 5s 下发 HEARTBEAT 帧，客户端可忽略。
 * - 结束：客户端发 `{"type":"FINISH"}` → 服务端处理后返回各类结果帧 → **服务端自行关闭连接**。
 *   因此以 [onServerClose] 作为最终结果触发点，而非等待显式 "final" 消息。
 *
 * 响应帧类型：
 * - `MID_TEXT`：一句话的临时识别结果（`result` 字段，无 start_time/end_time）。
 * - `FIN_TEXT`：一句话的最终识别结果（`result` + `start_time` + `end_time`）。
 *   多句场景会按句返回多个 FIN_TEXT，全部累积在 [appendStable] 中。
 * - `HEARTBEAT`：服务端心跳，忽略即可。
 * - 错误：`err_no != 0` 表示异常，`err_msg` 含详情。部分错误只影响当前句不关连接
 *   （-3003/-3005），部分错误会导致服务端关闭连接（-3004 鉴权/-3008 参数/-3101 超时/-3014 取消）。
 *
 * 断网补发：服务端自行关闭连接，客户端不做补发（补发需维护 end_time 缓存 + resend 逻辑，
 * 且本插件暂停/恢复已覆盖网络抖动场景；若后续需完整补发支持可参考文档中的"断网补发数据"一节）。
 */
class BaiduAsrRecognizer(private val config: BaiduAsrBackend) :
    BaseWsStreamingRecognizer(config.proxy) {

    override val tag: String = "BaiduASR"
    override val requiresListeningState: Boolean = false

    /** 客户端唯一标识（统计 UV 用，自动生成 UUID 去横线）。 */
    private val cuid: String = UUID.randomUUID().toString().replace("-", "")

    override fun buildRequest(): Request {
        require(config.url.isNotBlank()) { "baidu url is empty" }
        require(config.appId.isNotBlank()) { "baidu appId is empty" }
        require(config.appKey.isNotBlank()) { "baidu appKey is empty" }
        val sn = cuid // sn 用于日志追踪，UUID 即可
        val wsUrl = "${config.url}?sn=$sn"
        VoiceLog.i(tag, "wss to ${config.url.takeMasked()}... pid=${config.devPid} appId=${config.appId.takeMasked()}")
        return Request.Builder().url(wsUrl).build()
    }

    override fun sendStart(webSocket: WebSocket) {
        val startMsg = JSONObject().apply {
            put("type", "START")
            put("data", JSONObject().apply {
                put("appid", config.appId.toIntOrNull() ?: throw IllegalStateException("baidu appId must be integer"))
                put("appkey", config.appKey)
                put("dev_pid", config.devPid)
                put("cuid", cuid)
                put("format", "pcm")
                put("sample", 16000)
            })
        }
        webSocket.send(startMsg.toString())
        // 百度无显式 "ready"/"listening" 事件 —— START 帧发出后即可开始推流
        markReady()
    }

    override fun sendFinish(webSocket: WebSocket) {
        webSocket.send("""{"type":"FINISH"}""")
    }

    override fun handleMessage(webSocket: WebSocket, obj: JSONObject) {
        val type = obj.optString("type", "")
        val errNo = obj.optInt("err_no", 0)

        if (errNo != 0) {
            val errMsg = obj.optString("err_msg", "unknown error")
            VoiceLog.w(tag, "baidu error err_no=$errNo: $errMsg")
            val kind = classifyErrorNo(errNo)
            // -3003/-3005 只影响当前句，服务端不关连接，只记日志不 fail
            if (errNo != -3003 && errNo != -3005) {
                failSession(
                    RemoteAsrException("baidu asr $errNo: $errMsg", kind),
                    kind
                )
            }
            return
        }

        when (type) {
            "MID_TEXT" -> {
                val result = obj.optString("result", "")
                if (result.isNotEmpty()) {
                    val display = setPartial(result)
                    eventChannel.trySend(RecognitionEvent.Partial(display))
                }
            }
            "FIN_TEXT" -> {
                val result = obj.optString("result", "")
                if (result.isNotEmpty()) {
                    val display = appendStable(result)
                    eventChannel.trySend(RecognitionEvent.Partial(display))
                }
            }
            "HEARTBEAT" -> {} // 服务端保活心跳，忽略
        }
    }

    /**
     * 服务端主动关闭连接 = 全部识别完成（已收到 FINISH 或超时自然结束）。
     * 将多句累积的 [lastPartialText] 作为 Final 提交。
     */
    override fun onServerClose(code: Int, reason: String) {
        markFinal(RecognitionEvent.Final(lastPartialText))
    }

    /** WS 升级失败：401 → 鉴权；5xx → 过载。 */
    override fun classifyFailure(t: Throwable, response: Response?): ErrorKind {
        val code = response?.code ?: 0
        return when {
            code == 401 || code == 403 -> ErrorKind.RemoteAuth
            code in 500..599 -> ErrorKind.RemoteOverload
            else -> ErrorKind.Generic
        }
    }

    private fun classifyErrorNo(errNo: Int): ErrorKind = when (errNo) {
        -3004 -> ErrorKind.RemoteAuth       // 鉴权失败（appid/appkey/devpid 有误或 QPS 超限）
        -3008 -> ErrorKind.Generic           // START 帧参数错误
        -3101 -> ErrorKind.Generic           // 一段时间无音频导致整体超时
        else -> ErrorKind.Generic
    }

    private fun String.takeMasked(): String =
        if (length <= 8) "***" else take(8) + "***"
}
