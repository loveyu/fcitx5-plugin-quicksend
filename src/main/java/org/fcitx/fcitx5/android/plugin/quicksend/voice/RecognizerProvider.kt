/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.quicksend.voice

import android.content.Context

/**
 * 识别器提供方。对应 PRD §3.7 Provider 架构。
 *
 * Phase 1 仅本地 Sherpa；在线 Provider（OpenAI / Deepgram / Google / Azure /
 * 阿里云 / 腾讯云 / 自定义 WebSocket）留待后续实现。
 */
interface RecognizerProvider {

    /** 唯一标识，用于持久化默认 Provider 选择。 */
    val id: String

    /** 展示名。 */
    val displayName: String

    /** 是否需要联网（用于 PRD §3.10 网络/离线自动切换）。 */
    val requiresNetwork: Boolean

    /**
     * 创建识别器实例。模型/凭据未就绪时抛出异常，由调用方提示用户。
     */
    fun create(context: Context): SpeechRecognizer
}
