/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.quicksend.voice

import kotlinx.coroutines.flow.Flow

/**
 * 语音识别器抽象。对应 PRD §6 SpeechRecognizer。
 *
 * Phase 1 唯一实现为 [org.fcitx.fcitx5.android.plugin.quicksend.voice.sherpa.SherpaRecognizer]
 * （Sherpa-ONNX 本地流式识别）；在线 Provider（OpenAI / Deepgram / 阿里云等）实现留待后续。
 */
interface SpeechRecognizer {

    /** 开始识别：建立录音与识别循环，事件通过 [events] 流出。 */
    suspend fun start()

    /** 正常停止并产出最终结果（[RecognitionEvent.Final]）。 */
    suspend fun stop()

    /** 取消识别，不产出最终结果。 */
    suspend fun cancel()

    /** 识别事件流：[RecognitionEvent.Partial] / [RecognitionEvent.Final] / [RecognitionEvent.Error]。 */
    val events: Flow<RecognitionEvent>
}
