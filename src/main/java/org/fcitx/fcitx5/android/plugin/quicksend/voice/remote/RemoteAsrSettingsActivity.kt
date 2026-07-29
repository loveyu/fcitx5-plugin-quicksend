/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.quicksend.voice.remote

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import org.fcitx.fcitx5.android.plugin.quicksend.ui.theme.QuickSendTheme
import org.fcitx.fcitx5.android.plugin.quicksend.voice.remote.ui.RemoteAsrSettingsScreen

/**
 * 远端 ASR 多后端设置页（Compose + Material3）：列表（启用在前、可拖拽排序）+ 底部抽屉编辑
 * （按类型填参 + 单后端语音测试）。替代旧的 XML 版 RemoteVoiceSettingsActivity。
 */
class RemoteAsrSettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 测试需要录音权限；预先请求，避免测试时被拦截
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            @Suppress("DEPRECATION")
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), RC_AUDIO)
        }
        setContent {
            QuickSendTheme {
                RemoteAsrSettingsScreen(onBack = { finish() })
            }
        }
    }

    private companion object {
        const val RC_AUDIO = 0x7e03
    }
}
