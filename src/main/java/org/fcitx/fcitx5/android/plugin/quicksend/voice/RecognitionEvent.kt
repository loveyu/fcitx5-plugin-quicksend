/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.quicksend.voice

/**
 * 识别过程中产生的事件。对应 PRD §6 RecognitionEvent。
 */
sealed interface RecognitionEvent {

    /** 流式部分结果，持续更新（对应输入框组合区文本）。 */
    data class Partial(val text: String) : RecognitionEvent

    /** 句末/最终结果。收到后应提交并结束本轮识别。 */
    data class Final(val text: String) : RecognitionEvent

    /** 识别过程出错。 */
    data class Error(val throwable: Throwable) : RecognitionEvent
}
