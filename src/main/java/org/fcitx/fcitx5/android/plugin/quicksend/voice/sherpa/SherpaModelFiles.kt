/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.quicksend.voice.sherpa

import java.io.File

/**
 * Sherpa 流式 Zipformer-transducer 模型的 4 个必需文件名。
 *
 * 默认对应 `sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23`（中文，14M）的 int8 量化版，
 * 体积更小、更适合移动端。文件名可在设置页"高级"中覆盖以适配其它流式模型。
 */
data class SherpaModelNames(
    val encoder: String = DEFAULT_ENCODER,
    val decoder: String = DEFAULT_DECODER,
    val joiner: String = DEFAULT_JOINER,
    val tokens: String = DEFAULT_TOKENS
) {
    /** 用于下载/校验的有序文件列表。 */
    fun all(): List<String> = listOf(encoder, decoder, joiner, tokens)

    companion object {
        const val DEFAULT_ENCODER = "encoder-epoch-99-avg-1.int8.onnx"
        const val DEFAULT_DECODER = "decoder-epoch-99-avg-1.int8.onnx"
        const val DEFAULT_JOINER = "joiner-epoch-99-avg-1.int8.onnx"
        const val DEFAULT_TOKENS = "tokens.txt"
    }
}

/**
 * 解析模型目录，返回 4 个文件的绝对路径并校验存在。
 */
data class SherpaModelFiles(
    val encoder: String,
    val decoder: String,
    val joiner: String,
    val tokens: String
) {
    companion object {
        fun resolve(modelDir: File, names: SherpaModelNames = SherpaModelNames()): SherpaModelFiles {
            fun path(name: String): String {
                val f = File(modelDir, name)
                require(f.exists() && f.length() > 0) { "Sherpa model file missing or empty: ${f.absolutePath}" }
                return f.absolutePath
            }
            return SherpaModelFiles(
                encoder = path(names.encoder),
                decoder = path(names.decoder),
                joiner = path(names.joiner),
                tokens = path(names.tokens)
            )
        }
    }
}
