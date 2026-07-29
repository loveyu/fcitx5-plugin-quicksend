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

    /**
     * 识别过程出错。[kind] 供 UI 决定回退策略——远端模式下鉴权/满载不静默回退本地，
     * 仅 [ErrorKind.Generic] 自动回退本地。
     */
    data class Error(
        val throwable: Throwable,
        val kind: ErrorKind = ErrorKind.Generic
    ) : RecognitionEvent
}

/**
 * 识别错误的分类。远端模式下 [Generic] 仍自动回退本地；[RemoteAuth]/[RemoteOverload]
 * 属于需要用户感知的问题，不静默回退。
 */
enum class ErrorKind {
    /** 远端鉴权失败（Token 错误/过期）：配置问题，提示用户检查 Token，不静默回退本地。 */
    RemoteAuth,

    /** 远端满载（HTTP 503）：瞬时，提示稍后重试，不静默回退本地。 */
    RemoteOverload,

    /** 其它错误。远端模式下维持现行自动回退本地的行为。 */
    Generic
}

/**
 * 远端 ASR 失败异常，携带 [kind] 以便上层（VoiceController / Overlay）按分类决定回退与
 * 提示。同一实例既被远端识别器的 start() 抛出、又通过事件通道下发，确保两条
 * 路径分类一致（避免 start() 抛通用异常与事件携带分类互相覆盖的竞态）。
 */
class RemoteAsrException(
    message: String,
    val kind: ErrorKind,
    cause: Throwable? = null
) : Exception(message, cause)

/** 取出 [RemoteAsrException] 携带的分类，否则视为 [ErrorKind.Generic]。 */
fun Throwable.errorKind(): ErrorKind = (this as? RemoteAsrException)?.kind ?: ErrorKind.Generic
