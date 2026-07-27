/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.quicksend.voice.sherpa

import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.plugin.quicksend.voice.RecognitionConfig
import org.fcitx.fcitx5.android.plugin.quicksend.voice.VoiceLog
import java.io.File

/**
 * 全局单例：进程内保持 Sherpa [OnlineRecognizer] 加载状态。
 *
 * 模型文件到原生对象的加载一次性完成，后续所有 [SherpaRecognizer] 共享同一实例。
 * 插件不销毁则模型常驻内存，避免每次语音会话重新加载（~1s-5s）。
 * [RecognitionConfig] 变更时自动释放旧模型并重载。
 */
object SherpaModelHolder {

    @Volatile
    private var recognizer: OnlineRecognizer? = null

    @Volatile
    private var loadedModelDir: File? = null

    @Volatile
    private var loadedConfigSig: String? = null

    private val lock = Any()

    /**
     * 获取或加载模型。首次调用时在 [Dispatchers.IO] 上加载（数秒），后续调用零开销返回。
     * 若 [modelDir]、[names] 或 [config] 与已加载实例不同，会自动释放旧模型并重载。
     */
    suspend fun getOrLoad(
        modelDir: File,
        names: SherpaModelNames = SherpaModelNames(),
        config: RecognitionConfig = RecognitionConfig()
    ): OnlineRecognizer {
        val sig = config.toSignature()
        val cached = recognizer
        if (cached != null && loadedModelDir == modelDir && loadedConfigSig == sig) return cached

        return withContext(Dispatchers.IO) {
            synchronized(lock) {
                val sig2 = config.toSignature()
                if (recognizer != null && loadedModelDir == modelDir && loadedConfigSig == sig2) {
                    return@synchronized recognizer!!
                }
                // 释放旧实例
                recognizer?.release()
                recognizer = null
                loadedConfigSig = null
                loadedModelDir = null

                VoiceLog.i(TAG, "loading model from $modelDir (config=$sig2)")
                val files = SherpaModelFiles.resolve(modelDir, names)
                val recognizerConfig = OnlineRecognizerConfig(
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            encoder = files.encoder,
                            decoder = files.decoder,
                            joiner = files.joiner
                        ),
                        tokens = files.tokens,
                        numThreads = config.numThreads,
                        debug = false,
                        provider = config.provider
                    ),
                    enableEndpoint = true,
                    decodingMethod = config.decodingMethod,
                    maxActivePaths = config.maxActivePaths,
                    blankPenalty = config.blankPenalty,
                    endpointConfig = EndpointConfig(
                        rule1 = EndpointRule(false, 2.4f, 0.0f),
                        rule2 = EndpointRule(true, config.endpointSilence, 0.0f),
                        rule3 = EndpointRule(false, 0.0f, config.endpointMaxUtterance)
                    )
                )
                val rec = OnlineRecognizer(config = recognizerConfig)
                recognizer = rec
                loadedModelDir = modelDir
                loadedConfigSig = sig2
                VoiceLog.i(TAG, "model loaded into memory")
                rec
            }
        }
    }

    /**
     * 强制释放已加载模型（同步）。仅应在插件进程销毁时调用。
     */
    fun release() {
        synchronized(lock) {
            recognizer?.release()
            recognizer = null
            loadedModelDir = null
            loadedConfigSig = null
            VoiceLog.i(TAG, "model released")
        }
    }

    private const val TAG = "ModelHolder"
}
