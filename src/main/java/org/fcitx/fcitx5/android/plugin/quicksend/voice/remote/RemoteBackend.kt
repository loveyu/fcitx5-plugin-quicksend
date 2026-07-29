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
