/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.quicksend.voice

data class RecognitionConfig(
    val decodingMethod: String = DEFAULT_DECODING_METHOD,
    val maxActivePaths: Int = DEFAULT_MAX_ACTIVE_PATHS,
    val blankPenalty: Float = DEFAULT_BLANK_PENALTY,
    val endpointSilence: Float = DEFAULT_ENDPOINT_SILENCE,
    val endpointMaxUtterance: Float = DEFAULT_ENDPOINT_MAX_UTTERANCE,
    val numThreads: Int = DEFAULT_NUM_THREADS,
    val provider: String = DEFAULT_PROVIDER
) {
    fun toSignature(): String =
        "$decodingMethod|$maxActivePaths|$blankPenalty|$endpointSilence|$endpointMaxUtterance|$numThreads|$provider"

    companion object {
        const val DEFAULT_DECODING_METHOD = "greedy_search"
        const val DEFAULT_MAX_ACTIVE_PATHS = 4
        const val DEFAULT_BLANK_PENALTY = 0.0f
        const val DEFAULT_ENDPOINT_SILENCE = 1.2f
        const val DEFAULT_ENDPOINT_MAX_UTTERANCE = 20.0f
        const val DEFAULT_NUM_THREADS = 2
        const val DEFAULT_PROVIDER = "cpu"
    }
}

data class RecognitionConfigHelp(
    val key: String,
    val title: String,
    val description: String,
    val default: String,
    val recommended: String
)

val recognitionParamHelps = listOf(
    RecognitionConfigHelp(
        key = "decodingMethod",
        title = "解码方式",
        description = "greedy_search 速度最快；modified_beam_search 开启多路径搜索，准确度更高且支持热词。",
        default = RecognitionConfig.DEFAULT_DECODING_METHOD,
        recommended = "modified_beam_search（推荐）"
    ),
    RecognitionConfigHelp(
        key = "maxActivePaths",
        title = "最大活跃路径",
        description = "仅 modified_beam_search 生效。值越大搜索越充分、准确度越高，但速度越慢。",
        default = RecognitionConfig.DEFAULT_MAX_ACTIVE_PATHS.toString(),
        recommended = "4（移动端平衡值）"
    ),
    RecognitionConfigHelp(
        key = "blankPenalty",
        title = "空白惩罚",
        description = "对 transducer 解码中 blank 符号的惩罚系数。>0 降低漏字（更激进输出），<0 减少多字。",
        default = RecognitionConfig.DEFAULT_BLANK_PENALTY.toString(),
        recommended = "0.0～0.5（微调范围）"
    ),
    RecognitionConfigHelp(
        key = "endpointSilence",
        title = "端点静音阈值（秒）",
        description = "识别到语音后，连续静音超过此秒数则自动结束本句。值越小结束越快，值越大等待越长。",
        default = RecognitionConfig.DEFAULT_ENDPOINT_SILENCE.toString(),
        recommended = "1.2（日常对话）/ 2.0（长句输入）"
    ),
    RecognitionConfigHelp(
        key = "endpointMaxUtterance",
        title = "单句最长时长（秒）",
        description = "无论是否静音，单次识别超过此秒数将强制结束。防止长时间未检测到静音时不结束。",
        default = RecognitionConfig.DEFAULT_ENDPOINT_MAX_UTTERANCE.toString(),
        recommended = "20（默认即可）"
    ),
    RecognitionConfigHelp(
        key = "numThreads",
        title = "ONNX 推理线程数",
        description = "线程数越多解码越快，但 CPU 占用越高。建议不超过手机大核数。",
        default = RecognitionConfig.DEFAULT_NUM_THREADS.toString(),
        recommended = "2～4"
    ),
    RecognitionConfigHelp(
        key = "provider",
        title = "推理后端",
        description = "cpu 通用兼容性最好；xnnpack 在部分 SoC 上更快但可能不稳定。",
        default = RecognitionConfig.DEFAULT_PROVIDER,
        recommended = "cpu（稳定优先）"
    )
)
