/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.quicksend.voice.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 远端语音识别后端的统一配置模型（可插拔多后端）。新增接入只需再添一个 [RemoteBackend] 实现 +
 * 对应 `data class` 与识别器，序列化自动带 `type` 判别字段（[SerialName]）。
 *
 * 公共字段：
 * - [id]：稳定标识（新增时生成的 UUID），拖拽排序 / 测试回写靠它定位；
 * - [name]：用户自定义名称，外部展示用；
 * - [enable]：是否启用；
 * - [tested]：是否通过单后端语音自测（说「测试」、识别结果含「测试」即通过）。
 *   运行时链 [RemoteBackendStore.activeBackends] 只保留 `enable && tested`。
 *
 * 列表顺序即优先级（拖拽改顺序），不单独存 sort 字段。
 */
@Serializable
sealed interface RemoteBackend {
    val id: String
    val name: String
    val enable: Boolean
    val tested: Boolean

    /** 代理 URI（http://host:port 或 socks5://user:pass@host:port；空=不使用）。 */
    val proxy: String

    /** 返回带更新 tested 标记的副本（保持各自类型），用于测试回写。 */
    fun withTested(tested: Boolean): RemoteBackend

    /** 返回带更新 enable 标记的副本（保持各自类型），用于设置页开关。 */
    fun withEnable(enable: Boolean): RemoteBackend
}

/**
 * 腾讯 ASR 后端（V1/V2）的公共字段契约。两者握手地址、签名算法、音频格式与多数参数同构，
 * 抽出来供设置页表单与「复制默认地址」等逻辑统一读取，避免在 UI 里对两个类型各写一份。
 * V1 额外有 `filterPunc`（标点过滤），不在此接口，按类型单独处理。
 */
interface TencentAsrBackend {
    val baseUrl: String
    val appId: String
    val secretId: String
    val secretKey: String
    val engineModelType: String
    val voiceFormat: Int
    val needVad: Int
    val filterDirty: Int
    val filterModal: Int
    val convertNumMode: Int
    val hotwordList: String
}

/**
 * 自建 streaming-asr-server（https://github.com/loveyu/streaming-asr-server）。
 * WebSocket 文本协议 + 二进制 PCM；可选 Bearer Token 鉴权。
 */
@Serializable
@SerialName("streaming-asr-server")
data class StreamingAsrServerBackend(
    override val id: String,
    override val name: String,
    override val enable: Boolean = false,
    override val tested: Boolean = false,
    override val proxy: String = "",
    val url: String = "",
    val token: String = "",
) : RemoteBackend {
    override fun withTested(tested: Boolean): RemoteBackend = copy(tested = tested)
    override fun withEnable(enable: Boolean): RemoteBackend = copy(enable = enable)
}

/**
 * 阿里云智能语音交互实时语音识别（实时转写，SpeechTranscriber）。
 * WebSocket 文本协议（header/payload/context）+ 二进制 PCM。
 * Token 鉴权（通过 URL query 参数），在阿里云控制台获取 AppKey，Token 通过 AK/SK 获取。
 *
 * 服务地址默认上海外网：wss://nls-gateway-cn-shanghai.aliyuncs.com/ws/v1
 * 支持北京/深圳地域及内网地址。
 */
@Serializable
@SerialName("alibaba-asr")
data class AlibabaCloudAsrBackend(
    override val id: String,
    override val name: String,
    override val enable: Boolean = false,
    override val tested: Boolean = false,
    override val proxy: String = "",
    /** 服务地址（含 scheme 和路径，不含 token query）。留空=未配置，必填。 */
    val url: String = "",
    /** 阿里云控制台项目 AppKey，必填。 */
    val appKey: String = "",
    /** 鉴权 Token（手动填入或用 AK/SK 自动获取）。与 accessKeyId+accessKeySecret 二选一。 */
    val token: String = "",
    /** AccessKey ID（可选，用于自动获取 Token）。提供后优先用 token（若已填），否则自动获取。 */
    val accessKeyId: String = "",
    /** AccessKey Secret（可选，配合 accessKeyId 自动获取 Token）。 */
    val accessKeySecret: String = "",
    /** 是否返回中间识别结果，默认 true。 */
    val enableIntermediateResult: Boolean = true,
    /** 是否添加标点，默认 true。 */
    val enablePunctuationPrediction: Boolean = true,
    /** ITN 中文数字转阿拉伯数字，默认 true。 */
    val enableInverseTextNormalization: Boolean = true,
    /** 音频采样率（8000/16000），默认 16000。 */
    val sampleRate: Int = DEFAULT_SAMPLE_RATE,
) : RemoteBackend {
    override fun withTested(tested: Boolean): RemoteBackend = copy(tested = tested)
    override fun withEnable(enable: Boolean): RemoteBackend = copy(enable = enable)
    /** 是否有 AK/SK 凭据可用于自动获取 Token（token 字段为空时生效）。 */
    val canAutoFetchToken: Boolean get() = accessKeyId.isNotBlank() && accessKeySecret.isNotBlank()

    companion object {
        /** 阿里云实时语音识别默认服务地址（上海外网）。 */
        const val DEFAULT_URL = "wss://nls-gateway-cn-shanghai.aliyuncs.com/ws/v1"
        internal const val DEFAULT_SAMPLE_RATE = 16000
    }
}

/**
 * 腾讯云实时语音识别 V2（WebSocket，wss://asr.cloud.tencent.com/asr/v2/<appid>）。
 * 全部由客户端直连：签名（HMAC-SHA1）在客户端计算。所需参数在此填写，其余用默认。
 *
 * voiceFormat 默认 1（PCM）—— 客户端直采 16k PCM16 单声道，直接以二进制帧发送，无需编码。
 */
@Serializable
@SerialName("tencent-asr-v2")
data class TencentAsrV2Backend(
    override val id: String,
    override val name: String,
    override val enable: Boolean = false,
    override val tested: Boolean = false,
    override val proxy: String = "",
    /** 服务地址（含 scheme，不含 appid/参数）。留空=未配置，必填。默认见 [DEFAULT_BASE_URL]。 */
    override val baseUrl: String = "",
    override val appId: String = "",
    override val secretId: String = "",
    override val secretKey: String = "",
    override val engineModelType: String = DEFAULT_ENGINE_MODEL_TYPE,
    override val voiceFormat: Int = DEFAULT_VOICE_FORMAT,
    override val needVad: Int = DEFAULT_NEED_VAD,
    override val filterDirty: Int = DEFAULT_FILTER_DIRTY,
    override val filterModal: Int = DEFAULT_FILTER_MODAL,
    override val convertNumMode: Int = DEFAULT_CONVERT_NUM_MODE,
    override val hotwordList: String = "",
) : RemoteBackend, TencentAsrBackend {
    override fun withTested(tested: Boolean): RemoteBackend = copy(tested = tested)
    override fun withEnable(enable: Boolean): RemoteBackend = copy(enable = enable)

    companion object {
        /** 腾讯实时语音识别 V2 默认服务地址（用户可改，设置页有「复制默认」按钮）。 */
        const val DEFAULT_BASE_URL = "wss://asr.cloud.tencent.com/asr/v2"

        // 引擎模型：中英粤 + 31 方言（大模型 2.0，无说话人分离）
        internal const val DEFAULT_ENGINE_MODEL_TYPE = "16k_zh_en_2.0"
        // 1 = PCM（客户端直采，无需编码）
        internal const val DEFAULT_VOICE_FORMAT = 1
        internal const val DEFAULT_NEED_VAD = 1
        internal const val DEFAULT_FILTER_DIRTY = 0
        internal const val DEFAULT_FILTER_MODAL = 0
        internal const val DEFAULT_CONVERT_NUM_MODE = 1
    }
}

/**
 * 腾讯云实时语音识别 V1（WebSocket，wss://asr.cloud.tencent.com/asr/v2/<appid>）。
 * 文档：https://cloud.tencent.com/document/product/1093/48982
 *
 * 与 V2 共用同一握手地址与同一签名算法（HMAC-SHA1，客户端直连）；区别在于：
 * - 支持**通用引擎**（16k_zh / 16k_zh-TW / 16k_yue / 16k_en 等，对应通用计费资源包），
 *   而 V2 仅支持大模型引擎——选 V1 通常是因为只买了通用资源包。
 * - 响应按句返回 `result.slice_type`（0 开始/1 进行中/2 稳态）+ `voice_text_str`。
 *
 * voiceFormat 默认 1（PCM）—— 客户端直采 16k PCM16 单声道，直接以二进制帧发送。
 */
@Serializable
@SerialName("tencent-asr-v1")
data class TencentAsrV1Backend(
    override val id: String,
    override val name: String,
    override val enable: Boolean = false,
    override val tested: Boolean = false,
    override val proxy: String = "",
    /** 服务地址（含 scheme，不含 appid/参数）。留空=未配置，必填。默认见 [DEFAULT_BASE_URL]。 */
    override val baseUrl: String = "",
    override val appId: String = "",
    override val secretId: String = "",
    override val secretKey: String = "",
    override val engineModelType: String = DEFAULT_ENGINE_MODEL_TYPE,
    override val voiceFormat: Int = DEFAULT_VOICE_FORMAT,
    override val needVad: Int = DEFAULT_NEED_VAD,
    override val filterDirty: Int = DEFAULT_FILTER_DIRTY,
    override val filterModal: Int = DEFAULT_FILTER_MODAL,
    val filterPunc: Int = DEFAULT_FILTER_PUNC,
    override val convertNumMode: Int = DEFAULT_CONVERT_NUM_MODE,
    override val hotwordList: String = "",
) : RemoteBackend, TencentAsrBackend {
    override fun withTested(tested: Boolean): RemoteBackend = copy(tested = tested)
    override fun withEnable(enable: Boolean): RemoteBackend = copy(enable = enable)

    companion object {
        /** 腾讯实时语音识别 V1 默认服务地址（与 V2 同址，用户可改）。 */
        const val DEFAULT_BASE_URL = "wss://asr.cloud.tencent.com/asr/v2"

        // 引擎模型：中文通用（通用计费方案，对应可单独购买的通用资源包）
        internal const val DEFAULT_ENGINE_MODEL_TYPE = "16k_zh"
        // 1 = PCM（客户端直采，无需编码）
        internal const val DEFAULT_VOICE_FORMAT = 1
        internal const val DEFAULT_NEED_VAD = 1
        internal const val DEFAULT_FILTER_DIRTY = 0
        internal const val DEFAULT_FILTER_MODAL = 0
        internal const val DEFAULT_FILTER_PUNC = 0
        internal const val DEFAULT_CONVERT_NUM_MODE = 1
    }
}

/**
 * 百度智能云实时语音识别（WebSocket，短语音实时转写）。
 *
 * 官方文档：
 *   https://cloud.baidu.com/doc/SPEECH/s/jlbxejt2i  （WebSocket API 协议）
 *   https://cloud.baidu.com/doc/SPEECH/s/cm8sn2bii  （鉴权认证）
 *   https://cloud.baidu.com/doc/SPEECH/s/Zlbxew2qk  （错误码汇总）
 *
 * 协议要点（截至 2026-07）：
 * - 连接地址：`wss://vop.baidu.com/realtime_asr?sn=<UUID>`，sn 仅用于日志追踪
 * - 鉴权：appid + appkey 在 START 帧中直传，无需额外 token
 * - 采样率固定 16000 Hz、格式固定 PCM
 * - 音频帧：二进制，每帧 20-200ms，建议 160ms（5120 bytes），5s 无数据则断开
 * - 结束：客户端发 FINISH 后服务端返回最终 FIN_TEXT 并关闭连接
 * - 服务端自行关闭连接（不依赖客户端 close），onClosed 即表示全部识别完成
 */
@Serializable
@SerialName("baidu-asr")
data class BaiduAsrBackend(
    override val id: String,
    override val name: String,
    override val enable: Boolean = false,
    override val tested: Boolean = false,
    override val proxy: String = "",
    /** 服务地址（含 scheme，不含 sn）。默认 [DEFAULT_URL]。 */
    val url: String = DEFAULT_URL,
    /** 百度控制台应用 AppID（必填）。 */
    val appId: String = "",
    /** 百度控制台应用 API Key（必填）。 */
    val appKey: String = "",
    /** 识别模型 PID。
     *  1537=中文普通话（弱标点）、15372=中文普通话（加强标点*）、
     *  15376=中文多方言、1737=英语、17372=英语（加强标点）。
     *  默认 15372。 */
    val devPid: Int = DEFAULT_DEV_PID,
) : RemoteBackend {
    override fun withTested(tested: Boolean): RemoteBackend = copy(tested = tested)
    override fun withEnable(enable: Boolean): RemoteBackend = copy(enable = enable)

    companion object {
        /** 百度实时语音识别默认服务地址。 */
        const val DEFAULT_URL = "wss://vop.baidu.com/realtime_asr"
        /** 推荐模型：中文普通话 + 加强标点（逗号、句号、问号、感叹号）。 */
        const val DEFAULT_DEV_PID = 15372
    }
}

/**
 * 智谱 GLM-ASR-2512 语音转文本（HTTP REST，multipart/form-data 上传 WAV）。
 * 文档：https://docs.bigmodel.cn/api-reference/%E6%A8%A1%E5%9E%8B-api/%E8%AF%AD%E9%9F%B3%E8%BD%AC%E6%96%87%E6%9C%AC
 *
 * 非 WebSocket 实时流：录音→停止→一次性上传完整音频→服务端通过 SSE（stream=true）
 * 下发 `transcript.text.delta`（Partial）/ `transcript.text.done`（Final）。
 * 限制：音频 ≤ 30s / ≤ 25MB，格式 .wav 或 .mp3。
 */
@Serializable
@SerialName("glm-asr")
data class GlmAsrBackend(
    override val id: String,
    override val name: String,
    override val enable: Boolean = false,
    override val tested: Boolean = false,
    override val proxy: String = "",
    /** API Key（智谱开放平台 → API Keys 页面获取），必填。 */
    val apiKey: String = "",
    /** API 地址，默认官方。 */
    val baseUrl: String = DEFAULT_BASE_URL,
    /** 热词表，逗号分隔，提升特定领域词汇识别率，建议不超过 100 个。 */
    val hotwords: String = "",
) : RemoteBackend {
    override fun withTested(tested: Boolean): RemoteBackend = copy(tested = tested)
    override fun withEnable(enable: Boolean): RemoteBackend = copy(enable = enable)

    companion object {
        const val DEFAULT_BASE_URL = "https://open.bigmodel.cn/api"
    }
}
