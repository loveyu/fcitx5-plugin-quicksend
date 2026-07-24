/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.quicksend.voice

import android.content.Context
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
import org.fcitx.fcitx5.android.plugin.quicksend.voice.sherpa.SherpaModelNames
import org.fcitx.fcitx5.android.plugin.quicksend.voice.sherpa.SherpaRecognizer
import java.io.File

/**
 * 浮层语音状态。
 */
sealed interface VoiceUiState {
    object Idle : VoiceUiState
    object Listening : VoiceUiState
    data class Partial(val text: String) : VoiceUiState
    object Finishing : VoiceUiState
    data class Error(val message: String) : VoiceUiState
    /** 模型未就绪。 */
    object NotReady : VoiceUiState
}

/**
 * 编排一次语音会话：驱动 [SpeechRecognizer]，把 Partial 流式写进输入框组合区、
 * Final 经 [TextRefiner] 后提交；cancel 清空组合区。
 *
 * 文本注入经 [remote]（主项目 [IQuickSendService]）跨进程完成。
 */
class VoiceController(
    private val context: Context,
    private val modelDir: File,
    private val remote: () -> IQuickSendService?,
    private val names: SherpaModelNames = SherpaModelNames(),
    private val refiner: TextRefiner = NoOpRefiner,
    private val onSessionEnd: () -> Unit = {}
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow<VoiceUiState>(VoiceUiState.Idle)
    val state: StateFlow<VoiceUiState> = _state.asStateFlow()

    private var recognizer: SherpaRecognizer? = null
    private var collectJob: Job? = null

    fun start() {
        val current = _state.value
        if (current is VoiceUiState.Listening || current is VoiceUiState.Partial) return
        if (!VoiceModelManager.isReady(context, names)) {
            VoiceLog.w(TAG, "start aborted: model not ready at $modelDir")
            _state.value = VoiceUiState.NotReady
            return
        }
        VoiceLog.i(TAG, "start: creating recognizer, modelDir=$modelDir")
        val rec = SherpaRecognizer(context, modelDir, names)
        recognizer = rec
        _state.value = VoiceUiState.Listening
        collectJob = scope.launch {
            rec.events.collect { handle(it) }
        }
        // 识别器初始化（加载模型/AudioRecord）较重，放 IO 线程
        scope.launch(Dispatchers.IO) {
            runCatching { rec.start() }
                .onSuccess { VoiceLog.i(TAG, "recognizer started") }
                .onFailure {
                    VoiceLog.e(TAG, "recognizer start failed", it)
                    _state.value = VoiceUiState.Error(it.message ?: "启动识别失败")
                }
        }
    }

    private suspend fun handle(event: RecognitionEvent) {
        when (event) {
            is RecognitionEvent.Partial -> {
                _state.value = VoiceUiState.Partial(event.text)
                // IPC 可能阻塞（主项目 setComposingText 派发到 IMS 主线程），切 IO
                withContext(Dispatchers.IO) {
                    val r = remote()
                    if (r == null) {
                        VoiceLog.w(TAG, "partial ignored (no remote): \"${event.text}\"")
                    } else {
                        val res = runCatching { r.setComposingText(event.text) }
                        VoiceLog.d(
                            TAG,
                            "partial \"${event.text}\" → setComposingText " +
                                if (res.isSuccess) "ok" else "fail: ${res.exceptionOrNull()}"
                        )
                    }
                }
            }
            is RecognitionEvent.Final -> {
                val refined = withContext(Dispatchers.Default) {
                    runCatching { refiner.refine(event.text) }.getOrDefault(event.text)
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
                    }
                }
                endSession()
            }
            is RecognitionEvent.Error -> {
                VoiceLog.e(TAG, "recognizer error: ${event.throwable.message}", event.throwable)
                _state.value = VoiceUiState.Error(event.throwable.message ?: "识别错误")
            }
        }
    }

    /** 完成：停止识别并提交最终结果。 */
    fun finish() {
        VoiceLog.i(TAG, "finish requested")
        scope.launch {
            runCatching { recognizer?.stop() }.onFailure {
                VoiceLog.w(TAG, "stop failed, ending session", it)
                endSession()
            }
        }
    }

    /** 取消：丢弃结果并清空输入框组合区。 */
    fun cancel() {
        VoiceLog.i(TAG, "cancel requested")
        scope.launch {
            runCatching { recognizer?.cancel() }
            withContext(Dispatchers.IO) {
                val r = remote()
                if (r != null) runCatching { r.setComposingText("") }
                    .onFailure { VoiceLog.w(TAG, "clear composing failed", it) }
            }
            endSession()
        }
    }

    private fun endSession() {
        VoiceLog.i(TAG, "session end")
        _state.value = VoiceUiState.Idle
        onSessionEnd()
    }

    /** 服务销毁时强制释放（同步、幂等）。 */
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
