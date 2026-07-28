/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.quicksend.voice

import kotlinx.coroutines.flow.Flow

interface SpeechRecognizer {

    suspend fun start()

    suspend fun stop()

    suspend fun cancel()

    val events: Flow<RecognitionEvent>

    fun pauseRecording() {}
    fun resumeRecording() {}

    fun releaseNow() {}
}
