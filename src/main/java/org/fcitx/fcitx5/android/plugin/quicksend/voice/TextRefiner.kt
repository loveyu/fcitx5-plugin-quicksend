/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.quicksend.voice

/**
 * 文本润色。对应 PRD §6 TextRefiner（自动标点 / 去口头禅 / 修正重复 / 数字转换 /
 * 中英文空格 / 专有名词修正等）。
 *
 * Phase 1 仅 [NoOpRefiner] 占位；大模型润色（OpenAI / Gemini / Qwen / DeepSeek）留待后续。
 */
interface TextRefiner {

    suspend fun refine(text: String): String
}

/** 默认实现：原样返回。 */
object NoOpRefiner : TextRefiner {
    override suspend fun refine(text: String): String = text
}
