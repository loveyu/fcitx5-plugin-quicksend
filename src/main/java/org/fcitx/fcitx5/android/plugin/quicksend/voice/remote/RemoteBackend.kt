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

    /** 返回带更新 tested 标记的副本（保持各自类型），用于测试回写。 */
    fun withTested(tested: Boolean): RemoteBackend

    /** 返回带更新 enable 标记的副本（保持各自类型），用于设置页开关。 */
    fun withEnable(enable: Boolean): RemoteBackend
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
    val appId: String = "",
    val secretId: String = "",
    val secretKey: String = "",
    val engineModelType: String = DEFAULT_ENGINE_MODEL_TYPE,
    val voiceFormat: Int = DEFAULT_VOICE_FORMAT,
    val needVad: Int = DEFAULT_NEED_VAD,
    val filterDirty: Int = DEFAULT_FILTER_DIRTY,
    val filterModal: Int = DEFAULT_FILTER_MODAL,
    val convertNumMode: Int = DEFAULT_CONVERT_NUM_MODE,
    val hotwordList: String = "",
) : RemoteBackend {
    override fun withTested(tested: Boolean): RemoteBackend = copy(tested = tested)
    override fun withEnable(enable: Boolean): RemoteBackend = copy(enable = enable)

    private companion object {
        // 引擎模型：中英粤 + 31 方言（大模型 2.0，无说话人分离）
        const val DEFAULT_ENGINE_MODEL_TYPE = "16k_zh_en_2.0"
        // 1 = PCM（客户端直采，无需编码）
        const val DEFAULT_VOICE_FORMAT = 1
        const val DEFAULT_NEED_VAD = 1
        const val DEFAULT_FILTER_DIRTY = 0
        const val DEFAULT_FILTER_MODAL = 0
        const val DEFAULT_CONVERT_NUM_MODE = 1
    }
}
