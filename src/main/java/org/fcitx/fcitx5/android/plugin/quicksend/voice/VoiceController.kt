/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.quicksend.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.common.ipc.IQuickSendService

sealed interface VoiceUiState {
    object Idle : VoiceUiState
    object Initializing : VoiceUiState
    object Listening : VoiceUiState
    data class Partial(val text: String) : VoiceUiState
    data class Paused(val text: String) : VoiceUiState
    object Finishing : VoiceUiState
    data class Error(val message: String, val kind: ErrorKind = ErrorKind.Generic) : VoiceUiState
    object NotReady : VoiceUiState
}

class VoiceController(
    private val recognizerFactory: suspend () -> SpeechRecognizer,
    private val remote: () -> IQuickSendService?,
    private val refiner: TextRefiner = NoOpRefiner,
    private val onSessionEnd: () -> Unit = {}
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow<VoiceUiState>(VoiceUiState.Idle)
    val state: StateFlow<VoiceUiState> = _state.asStateFlow()

    private var recognizer: SpeechRecognizer? = null
    private var collectJob: Job? = null
    private var committedVoiceCharCount = 0
    private var partialBackspaceOffset = 0
    private var rawPartialText = ""

    fun start() {
        val current = _state.value
        if (current is VoiceUiState.Initializing ||
            current is VoiceUiState.Listening ||
            current is VoiceUiState.Partial
        ) return
        if (current is VoiceUiState.Paused) {
            VoiceLog.i(TAG, "resume from paused")
            recognizer?.resumeRecording()
            _state.value = VoiceUiState.Listening
            return
        }
        VoiceLog.i(TAG, "start: creating recognizer")
        _state.value = VoiceUiState.Initializing
        committedVoiceCharCount = 0
        partialBackspaceOffset = 0
        rawPartialText = ""
        scope.launch(Dispatchers.IO) {
            // 注意：recognizerFactory()/start() 都是 suspend；不能用 runCatching 包裹——
            // 它会吞掉 CancellationException，导致 destroy() 取消作用域时误报
            // "recognizer creation failed" / "recognizer start failed"。这里显式重抛取消。
            val rec = try {
                recognizerFactory()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                VoiceLog.e(TAG, "recognizer creation failed", t)
                _state.value = VoiceUiState.Error(t.message ?: "创建识别器失败", t.errorKind())
                return@launch
            }
            VoiceLog.i(TAG, "recognizer created")
            recognizer = rec
            collectJob?.cancel()
            collectJob = scope.launch {
                rec.events.collect { handle(it) }
            }
            try {
                rec.start()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                VoiceLog.e(TAG, "recognizer start failed", t)
                // start() 因 WS 升级失败抛 RemoteAsrException 时，透传其分类（auth/overload）。
                _state.value = VoiceUiState.Error(t.message ?: "启动识别失败", t.errorKind())
                return@launch
            }
            VoiceLog.i(TAG, "recognizer started")
            if (_state.value !is VoiceUiState.Error && _state.value !is VoiceUiState.Paused) {
                _state.value = VoiceUiState.Listening
            }
        }
    }

    fun pause() {
        val current = _state.value
        if (current !is VoiceUiState.Initializing &&
            current !is VoiceUiState.Listening &&
            current !is VoiceUiState.Partial
        ) return
        VoiceLog.i(TAG, "pause requested")
        recognizer?.pauseRecording()
        val text = (current as? VoiceUiState.Partial)?.text.orEmpty()
        _state.value = VoiceUiState.Paused(text)
    }

    fun backspace() {
        val current = _state.value
        if (current is VoiceUiState.Partial || current is VoiceUiState.Paused) {
            if (rawPartialText.isEmpty()) return
            partialBackspaceOffset++
            val adjusted = rawPartialText.dropLast(
                minOf(partialBackspaceOffset, rawPartialText.length)
            )
            VoiceLog.d(TAG, "backspace during stream: offset=$partialBackspaceOffset adjusted=\"$adjusted\"")
            scope.launch {
                withContext(Dispatchers.IO) {
                    val r = remote()
                    if (r != null) runCatching {
                        r.setComposingText(adjusted)
                    }.onFailure { VoiceLog.w(TAG, "backspace composing update failed", it) }
                }
                val now = _state.value
                _state.value = if (now is VoiceUiState.Paused) {
                    VoiceUiState.Paused(adjusted)
                } else {
                    VoiceUiState.Partial(adjusted)
                }
            }
            return
        }
        if (committedVoiceCharCount <= 0) return
        VoiceLog.d(TAG, "backspace ($committedVoiceCharCount remaining)")
        scope.launch {
            withContext(Dispatchers.IO) {
                val r = remote()
                if (r != null) runCatching {
                    r.sendKeyDownUpKey(android.view.KeyEvent.KEYCODE_DEL, 0)
                }.onFailure { VoiceLog.w(TAG, "backspace failed", it) }
            }
            committedVoiceCharCount--
        }
    }

    private suspend fun handle(event: RecognitionEvent) {
        when (event) {
            is RecognitionEvent.Partial -> {
                rawPartialText = event.text
                val adjusted = if (partialBackspaceOffset > 0) {
                    rawPartialText.dropLast(minOf(partialBackspaceOffset, rawPartialText.length))
                } else {
                    rawPartialText
                }
                _state.value = VoiceUiState.Partial(adjusted)
                withContext(Dispatchers.IO) {
                    val r = remote()
                    if (r == null) {
                        VoiceLog.w(TAG, "partial ignored (no remote): \"$adjusted\"")
                    } else {
                        val res = runCatching { r.setComposingText(adjusted) }
                        VoiceLog.d(
                            TAG,
                            "partial \"${event.text}\" → setComposingText \"$adjusted\" " +
                                if (res.isSuccess) "ok" else "fail: ${res.exceptionOrNull()}"
                        )
                    }
                }
            }
            is RecognitionEvent.Final -> {
                val adjustedFinal = if (partialBackspaceOffset > 0) {
                    event.text.dropLast(minOf(partialBackspaceOffset, event.text.length))
                } else {
                    event.text
                }
                val refined = withContext(Dispatchers.Default) {
                    runCatching { refiner.refine(adjustedFinal) }.getOrDefault(adjustedFinal)
                }
                _state.value = VoiceUiState.Finishing
                withContext(Dispatchers.IO) {
                    val r = remote()
                    if (r == null) {
                        VoiceLog.w(TAG, "final ignored (no remote): \"$refined\"")
                    } else {
                        val res = runCatching { r.commitText(refined, -1) }
                        VoiceLog.i(
                            TAG,
                            "final \"${event.text}\" → commit \"$refined\" " +
                                if (res.isSuccess) "ok" else "fail: ${res.exceptionOrNull()}"
                        )
                        if (res.isSuccess) committedVoiceCharCount += refined.length
                    }
                }
                endSession()
            }
            is RecognitionEvent.Error -> {
                VoiceLog.e(TAG, "recognizer error: ${event.throwable.message}", event.throwable)
                _state.value = VoiceUiState.Error(event.throwable.message ?: "识别错误", event.kind)
            }
        }
    }

    fun finish() {
        VoiceLog.i(TAG, "finish requested")
        scope.launch {
            // stop() 是 suspend；正常运行时，final 结果会通过 events 回调经 handle(Final)
            // 触发 endSession()。这里只在 stop() 抛出「真实异常」时兜底结束会话；
            // 若是作用域被取消（destroy()），直接重抛，避免误报 "stop failed" 与二次 endSession。
            try {
                recognizer?.stop()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                VoiceLog.w(TAG, "stop failed, ending session", t)
                endSession()
            }
        }
    }

    fun close() {
        VoiceLog.i(TAG, "close requested")
        scope.launch {
            try {
                recognizer?.cancel()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                VoiceLog.w(TAG, "cancel recognizer failed", t)
            }
            withContext(Dispatchers.IO) {
                val r = remote()
                if (r != null) runCatching { r.setComposingText("") }
                    .onFailure { VoiceLog.w(TAG, "clear composing failed", it) }
            }
            endSession()
        }
    }

    fun cancel() = close()

    private fun endSession() {
        VoiceLog.i(TAG, "session end")
        _state.value = VoiceUiState.Idle
        onSessionEnd()
    }

    fun destroy() {
        VoiceLog.i(TAG, "destroy")
        collectJob?.cancel()
        recognizer?.releaseNow()
        runCatching { scope.cancel() }
    }

    private companion object {
        const val TAG = "VoiceCtrl"
    }
}
