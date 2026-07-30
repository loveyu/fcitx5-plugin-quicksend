/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.quicksend.voice.remote

import org.fcitx.fcitx5.android.plugin.quicksend.voice.SpeechRecognizer
import org.fcitx.fcitx5.android.plugin.quicksend.voice.remote.alibaba.AlibabaCloudAsrRecognizer
import org.fcitx.fcitx5.android.plugin.quicksend.voice.remote.glm.GlmAsrRecognizer
import org.fcitx.fcitx5.android.plugin.quicksend.voice.remote.streaming.StreamingAsrServerRecognizer
import org.fcitx.fcitx5.android.plugin.quicksend.voice.remote.tencent.TencentAsrV1Recognizer
import org.fcitx.fcitx5.android.plugin.quicksend.voice.remote.tencent.TencentAsrV2Recognizer

/**
 * 把一个 [RemoteBackend] 配置造出对应的 [SpeechRecognizer]。新增后端类型时在此加一个分支即可。
 */
fun RemoteBackend.recognizer(): SpeechRecognizer = when (this) {
    is StreamingAsrServerBackend -> StreamingAsrServerRecognizer(url, token.ifBlank { null }, proxy)
    is TencentAsrV1Backend -> TencentAsrV1Recognizer(this)
    is TencentAsrV2Backend -> TencentAsrV2Recognizer(this)
    is AlibabaCloudAsrBackend -> AlibabaCloudAsrRecognizer(this)
    is GlmAsrBackend -> GlmAsrRecognizer(this)
}
