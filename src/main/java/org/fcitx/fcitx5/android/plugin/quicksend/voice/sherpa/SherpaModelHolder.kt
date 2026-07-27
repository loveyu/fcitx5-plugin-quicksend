/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.quicksend.voice.sherpa

import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.plugin.quicksend.voice.VoiceLog
import java.io.File

/**
 * 全局单例：进程内保持 Sherpa [OnlineRecognizer] 加载状态。
 *
 * 模型文件到原生对象的加载一次性完成，后续所有 [SherpaRecognizer] 共享同一实例。
 * 插件不销毁则模型常驻内存，避免每次语音会话重新加载（~1s-5s）。
 */
object SherpaModelHolder {

    @Volatile
    private var recognizer: OnlineRecognizer? = null

    @Volatile
    private var loadedModelDir: File? = null

    private val lock = Any()

    /**
     * 获取或加载模型。首次调用时在 [Dispatchers.IO] 上加载（数秒），后续调用零开销返回。
     * 若 [modelDir] 或 [names] 与已加载模型不同，不会热切换（调用方应自行管理）。
     */
    suspend fun getOrLoad(modelDir: File, names: SherpaModelNames = SherpaModelNames()): OnlineRecognizer {
        val cached = recognizer
        if (cached != null && loadedModelDir == modelDir) return cached

        return withContext(Dispatchers.IO) {
            synchronized(lock) {
                recognizer?.let { return@synchronized it }
                VoiceLog.i(TAG, "loading model from $modelDir")
                val files = SherpaModelFiles.resolve(modelDir, names)
                val config = OnlineRecognizerConfig(
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            encoder = files.encoder,
                            decoder = files.decoder,
                            joiner = files.joiner
                        ),
                        tokens = files.tokens,
                        numThreads = 2,
                        debug = false
                    ),
                    enableEndpoint = true
                )
                val rec = OnlineRecognizer(config = config)
                recognizer = rec
                loadedModelDir = modelDir
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
            VoiceLog.i(TAG, "model released")
        }
    }

    private const val TAG = "ModelHolder"
}
